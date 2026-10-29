// Markdown 渲染管线：源码快照 → marked(GFM) → 图片协议改写 → DOMPurify 消毒 →
// 标签胶囊与检索高亮的 DOM 后处理。
//
// 安全边界（NFR-SEC-02/03 的 Windows 适配）：
// - 全部 HTML 经 DOMPurify 消毒，脚本/事件属性/iframe 一律剔除；
// - 远程图片不自动加载，只渲染为链接文本；
// - 本地图片仅允许解析到工作区内部（attach:// 协议后端再做一次校验）。
import { Marked, type Tokens } from "marked";
import DOMPurify from "dompurify";
import type { LinkView } from "../mygo";

export interface RenderOptions {
  noteId: string;
  /** 笔记相对 notes/ 的目录（"" 为根目录），用于解析相对图片路径 */
  noteDir: string;
  /** 与正文顺序一致的链接解析视图 */
  links?: LinkView[];
  /** 需要在阅读页高亮的检索词 */
  highlightTerms?: string[];
}

const marked = new Marked({ gfm: true, breaks: false });

// 与后端 extractTags 一致的标签字符与边界规则。
const TAG_RE = /#([A-Za-z0-9_\-\u4e00-\u9fa5]+(?:\/[A-Za-z0-9_\-\u4e00-\u9fa5]+)*)/g;
const isTagRune = (r: string) => /[A-Za-z0-9_\-一-鿿]/.test(r);

// 双链内联扩展：[[目标]] 或 [[目标|别名]]。
const wikilink = {
  name: "wikilink",
  level: "inline" as const,
  start(src: string) {
    const i = src.indexOf("[[");
    return i < 0 ? undefined : i;
  },
  tokenizer(src: string) {
    const m = /^\[\[([^\[\]\n]+?)\]\]/.exec(src);
    if (m) {
      return { type: "wikilink", raw: m[0], inner: m[1], tokens: [] } as unknown as Tokens.Generic;
    }
    return undefined;
  },
  renderer(token: Tokens.Generic) {
    const inner = String(token.inner);
    const bar = inner.indexOf("|");
    const target = (bar >= 0 ? inner.slice(0, bar) : inner).trim();
    const alias = bar >= 0 ? inner.slice(bar + 1).trim() : "";
    return `<a href="javascript:void(0)" class="wikilink" data-target="${escapeAttr(target)}">${escapeHtml(alias || target)}</a>`;
  },
};
marked.use({ extensions: [wikilink] });

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
function escapeAttr(s: string): string {
  return escapeHtml(s);
}

// 把相对图片路径改写为 attach 协议地址；远程/越界图片降级为文本。
function resolveImageSrc(src: string, opts: RenderOptions): string | null {
  const s = src.trim();
  if (/^(https?:|data:|javascript:)/i.test(s)) return null;
  if (/^attach:\/\//i.test(s)) return s;
  const base = opts.noteDir ? opts.noteDir + "/" : "";
  const rel = (base + s).replace(/\\/g, "/");
  return `attach://localhost/note/${encodeURIComponent(opts.noteId)}/${rel.split("/").map(encodeURIComponent).join("/")}`;
}

function fold(s: string): string {
  return s.toLowerCase();
}

export function renderMarkdown(source: string, opts: RenderOptions): string {
  const rawHtml = marked.parse(source, { async: false }) as string;
  const clean = DOMPurify.sanitize(rawHtml, {
    FORBID_TAGS: ["style", "form", "input", "button", "textarea"],
    ADD_ATTR: ["target"],
  });

  const host = document.createElement("template");
  host.innerHTML = clean;

  // 1. 图片改写。
  host.content.querySelectorAll("img").forEach((img) => {
    const resolved = resolveImageSrc(img.getAttribute("src") ?? "", opts);
    if (resolved) {
      img.setAttribute("src", resolved);
      img.setAttribute("loading", "lazy");
    } else {
      const span = document.createElement("span");
      span.className = "img-blocked";
      span.textContent = `[外部图片：${img.getAttribute("alt") || img.getAttribute("src") || ""}]`;
      img.replaceWith(span);
    }
  });

  // 2. 双链解析状态：按出现顺序消费后端 LinkView，避免顺序漂移时回退按目标匹配。
  const links = [...(opts.links ?? [])];
  let cursor = 0;
  const consume = (target: string): LinkView | undefined => {
    const t = fold(target);
    for (let i = cursor; i < links.length; i++) {
      const link = links[i];
      if (link && fold(link.target) === t) {
        cursor = i + 1;
        return link;
      }
    }
    return links.find((l) => fold(l.target) === t);
  };
  host.content.querySelectorAll<HTMLAnchorElement>("a.wikilink").forEach((a) => {
    const view = consume(a.dataset.target ?? "");
    if (view && view.state === "RESOLVED" && view.resolvedNoteId) {
      a.classList.add("wl-resolved");
      a.dataset.noteId = view.resolvedNoteId;
    } else if (view && view.state === "AMBIGUOUS") {
      a.classList.add("wl-ambiguous");
    } else {
      a.classList.add("wl-unresolved");
    }
  });

  // 3. 标签胶囊（跳过代码区；带与后端一致的边界规则）。
  const walker = document.createTreeWalker(host.content, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = (node as Text).parentElement;
      if (parent && (parent.closest("pre, code, a.wikilink") || parent.classList.contains("img-blocked"))) {
        return NodeFilter.FILTER_REJECT;
      }
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  const textNodes: Text[] = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode as Text);
  for (const node of textNodes) {
    const text = node.textContent ?? "";
    TAG_RE.lastIndex = 0;
    if (!TAG_RE.test(text)) continue;
    TAG_RE.lastIndex = 0;
    const frag = document.createDocumentFragment();
    let last = 0;
    let m: RegExpExecArray | null;
    let prevChar = "";
    while ((m = TAG_RE.exec(text))) {
      const before = text.slice(0, m.index);
      const prev = before.length > 0 ? (before[before.length - 1] ?? "") : prevChar;
      const boundaryOk = prev === "" || !isTagRune(prev) || prev === "#";
      // 正则本身贪婪吃满标签字符，且已排除 "# " 标题形态，无需再检查右侧。
      if (m[0].startsWith("# ") || !boundaryOk) continue;
      if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
      const chip = document.createElement("span");
      chip.className = "tag-chip";
      chip.textContent = m[0];
      frag.appendChild(chip);
      last = m.index + m[0].length;
    }
    if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
    node.replaceWith(frag);
  }

  // 4. 检索词高亮。
  const terms = (opts.highlightTerms ?? []).map(fold).filter((t) => t.length > 0);
  if (terms.length > 0) {
    highlightTextNodes(host.content, terms);
  }
  return host.innerHTML;
}

function highlightTextNodes(root: DocumentFragment | HTMLElement, terms: string[]) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = (node as Text).parentElement;
      if (parent && (parent.closest("mark") || parent.classList.contains("img-blocked"))) {
        return NodeFilter.FILTER_REJECT;
      }
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  const nodes: Text[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode as Text);
  for (const node of nodes) {
    const text = node.textContent ?? "";
    const lower = text.toLowerCase();
    let first = -1;
    for (const t of terms) {
      const i = lower.indexOf(t);
      if (i >= 0 && (first < 0 || i < first)) first = i;
    }
    if (first < 0) continue;
    // 找到首个命中词后在所在行内完成全部高亮。
    const frag = document.createDocumentFragment();
    let pos = 0;
    while (pos < text.length) {
      let hit = -1;
      let hitLen = 0;
      for (const t of terms) {
        const i = lower.indexOf(t, pos);
        if (i >= 0 && (hit < 0 || i < hit)) {
          hit = i;
          hitLen = t.length;
        }
      }
      if (hit < 0) break;
      if (hit > pos) frag.appendChild(document.createTextNode(text.slice(pos, hit)));
      const mark = document.createElement("mark");
      mark.className = "search-hit";
      mark.textContent = text.slice(hit, hit + hitLen);
      frag.appendChild(mark);
      pos = hit + hitLen;
    }
    if (pos < text.length) frag.appendChild(document.createTextNode(text.slice(pos)));
    node.replaceWith(frag);
  }
}

// 编辑器快捷插入辅助 ----------------------------------------------------------

export interface SelectionEdit {
  text: string;
  start: number;
  end: number;
}

// wrapSelection 在选区两侧包裹标记；无选区时插入占位符。
export function wrapSelection(sel: SelectionEdit, before: string, after: string, placeholder: string): string {
  const inner = sel.text || placeholder;
  return sel.text.slice(0, sel.start) + before + inner + after + sel.text.slice(sel.end);
}

// prefixLines 给选中行加前缀（列表/引用/任务）。
export function prefixLines(text: string, start: number, end: number, prefix: string): string {
  const lineStart = text.lastIndexOf("\n", start - 1) + 1;
  let lineEnd = text.indexOf("\n", end);
  if (lineEnd < 0) lineEnd = text.length;
  const block = text.slice(lineStart, lineEnd);
  const prefixed = block
    .split("\n")
    .map((l) => (l.startsWith(prefix) ? l : prefix + l))
    .join("\n");
  return text.slice(0, lineStart) + prefixed + text.slice(lineEnd);
}
