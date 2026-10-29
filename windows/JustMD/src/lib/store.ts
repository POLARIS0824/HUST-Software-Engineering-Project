// 前端响应式状态层：全部业务状态与对 Go 后端的调用集中在这里。
// 保存状态机遵循 BR-05：只有收到后端回执且期间没有新输入，才显示"已保存"。
import { reactive } from "vue";
import { Vault, events, type NoteDocument, type NoteSummary, type TagNode, type WorkspaceInfo, type SearchHit, type BacklinkItem, type ImportPlan, type ImportResult, type SaveReceipt } from "../mygo";
import { on, currentWindow } from "mygo-runtime";

export type ViewName = "notes" | "trash" | "settings";
export type SaveState = "saved" | "dirty" | "saving" | "error";
export type EditorMode = "edit" | "read";

export interface Modal {
  kind: "confirm" | "conflict" | "candidates";
  title: string;
  message?: string;
  danger?: boolean;
  plan?: ImportPlan;
  results?: ImportResult[];
  candidates?: NoteSummary[];
  target?: string;
  resolve?: (value: boolean) => void;
  resolveChoice?: (value: string) => void;
}

export interface Snackbar {
  message: string;
  actionLabel?: string;
  durationMs: number;
  onAction?: () => void;
}

interface AppState {
  ready: boolean;
  view: ViewName;
  workspace: WorkspaceInfo;
  notes: NoteSummary[];
  trash: NoteSummary[];
  tags: TagNode[];
  sortOption: string;
  selectedTag: string | null;

  searchQuery: string;
  searchResults: SearchHit[] | null; // null 表示非检索态
  searching: boolean;

  currentId: string | null;
  current: NoteDocument | null;
  mode: EditorMode;
  saveState: SaveState;
  backlinks: BacklinkItem[];
  showBacklinks: boolean;
  highlightTerms: string[];

  modal: Modal | null;
  snackbar: Snackbar | null;
  busy: string | null; // 全局忙碌提示（导出/重建等）
}

export const store = reactive<AppState>({
  ready: false,
  view: "notes",
  workspace: { root: "", noteCount: 0, trashCount: 0, ready: false },
  notes: [],
  trash: [],
  tags: [],
  sortOption: "updated_desc",
  selectedTag: null,

  searchQuery: "",
  searchResults: null,
  searching: false,

  currentId: null,
  current: null,
  mode: "edit",
  saveState: "saved",
  backlinks: [],
  showBacklinks: false,
  highlightTerms: [],

  modal: null,
  snackbar: null,
  busy: null,
});

// 自动保存的防抖句柄与保存序号（BR-05：旧回执不清除新脏状态）。
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let saveSeq = 0;
let searchTimer: ReturnType<typeof setTimeout> | null = null;
let snackbarTimer: ReturnType<typeof setTimeout> | null = null;
let editSeq = 0;

export async function initApp() {
  await refreshWorkspace();
  store.ready = true;
  if (store.workspace.ready) {
    await refreshLists();
  }
  events.notesChanged.on(() => {
    void refreshLists();
  });
  events.menuAction.on((action) => {
    void handleMenuAction(action);
  });
}

export async function refreshWorkspace() {
  store.workspace = await Vault.getWorkspace();
}

export async function refreshLists() {
  const [notes, tags, trash] = await Promise.all([
    Vault.listNotes(store.sortOption),
    Vault.listTags(),
    Vault.listTrash(),
  ]);
  store.notes = notes;
  store.tags = tags;
  store.trash = trash;
  store.workspace = { ...store.workspace, noteCount: notes.length, trashCount: trash.length, ready: true };
}

export async function chooseWorkspace() {
  const info = await Vault.chooseWorkspace();
  if (info.ready) {
    store.workspace = info;
    store.current = null;
    store.currentId = null;
    store.searchResults = null;
    store.searchQuery = "";
    store.view = "notes";
    await refreshLists();
  }
}

export async function createNote() {
  const doc = await Vault.createNote("");
  store.view = "notes";
  store.searchQuery = "";
  store.searchResults = null;
  store.selectedTag = null;
  await refreshLists();
  await openNote(doc.id);
  store.mode = "edit";
}

export async function openNote(id: string, terms: string[] = []) {
  await flushSave();
  const doc = await Vault.openNote(id);
  if (doc.trashed) {
    showSnackbar("该笔记在回收站中，先恢复才能编辑");
    return;
  }
  store.current = doc;
  store.currentId = doc.id;
  store.mode = terms.length > 0 ? "read" : "edit";
  store.saveState = "saved";
  store.highlightTerms = terms;
  editSeq++;
  saveSeq = editSeq;
  await loadBacklinks(id);
}

export async function loadBacklinks(id: string) {
  store.backlinks = await Vault.getBacklinks(id);
}

// onEditorInput 由编辑器在每次输入时调用（FR-EDIT-04：停止输入 2 秒后自动保存）。
export function onEditorInput(markdown: string) {
  if (!store.current) return;
  store.current.markdown = markdown;
  store.current.stats = recomputeStatsLocal(markdown);
  store.saveState = "dirty";
  editSeq++;
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => void saveNow(), 2000);
}

// saveNow 立即保存（Ctrl+S / 离开编辑 / 防抖触发）。
export async function saveNow(): Promise<void> {
  if (!store.current || store.saveState === "saving" || store.current.trashed) return;
  if (store.saveState !== "dirty" && store.saveState !== "error") return;
  const seqAtStart = editSeq;
  store.saveState = "saving";
  try {
    const receipt: SaveReceipt = await Vault.saveContent(store.current.id, store.current.markdown);
    if (!store.current) return;
    store.current.revision = receipt.revision;
    store.current.stats = receipt.stats;
    store.current.tags = receipt.tags;
    store.current.links = receipt.links;
    // 只有期间没有新输入才进入"已保存"（旧回执不清除新脏状态）。
    if (seqAtStart === editSeq) {
      store.saveState = "saved";
    } else {
      store.saveState = "dirty";
      if (saveTimer) clearTimeout(saveTimer);
      saveTimer = setTimeout(() => void saveNow(), 1200);
    }
    void loadBacklinks(store.current.id);
  } catch {
    store.saveState = "error"; // FR-EDIT-06：失败保留编辑态，禁止显示已保存
  }
}

export async function flushSave() {
  if (store.saveState === "dirty" || store.saveState === "error") {
    if (saveTimer) clearTimeout(saveTimer);
    await saveNow();
  }
}

// 简化的前端统计（展示用；权威统计以后端回执为准）。
function recomputeStatsLocal(md: string) {
  let cjk = 0;
  let en = 0;
  let inWord = false;
  for (const ch of md) {
    if (/[\u4e00-\u9fff]/.test(ch)) {
      cjk++;
      inWord = false;
    } else if (/[a-zA-Z]/.test(ch)) {
      if (!inWord) {
        en++;
        inWord = true;
      }
    } else {
      inWord = false;
    }
  }
  const total = cjk + en;
  return {
    cjkCharCount: cjk,
    enWordCount: en,
    charCount: [...md].length,
    readingTimeMinute: Math.max(md.length > 0 ? 1 : 0, Math.ceil(total / 300)),
  };
}

export async function renameNote(newTitle: string) {
  if (!store.current) return;
  const trimmed = newTitle.trim();
  if (trimmed === store.current.title) return;
  try {
    const doc = await Vault.renameNote(store.current.id, trimmed);
    store.current = doc;
    await refreshLists();
    showSnackbar(`已重命名，并级联更新了相关引用`);
  } catch {
    showSnackbar("重命名失败：标题为空或包含非法字符");
  }
}

export async function trashCurrentNote() {
  if (!store.current) return;
  const id = store.current.id;
  const title = store.current.title;
  await Vault.trashNote(id);
  store.current = null;
  store.currentId = null;
  store.backlinks = [];
  await refreshLists();
  // 5 秒撤销窗口（BR-11 / AC-NOTE-02）。
  showUndo(`已将「${title}」移入回收站`, 5000, async () => {
    await Vault.restoreFromTrash(id);
    await refreshLists();
    showSnackbar("已撤销删除");
  });
}

export async function restoreNote(id: string) {
  await Vault.restoreFromTrash(id);
  await refreshLists();
  showSnackbar("已恢复笔记");
}

export async function deleteForever(id: string) {
  const ok = await confirmModal("彻底删除", "该笔记将被永久粉碎，无法恢复。确定继续吗？", true);
  if (!ok) return;
  await Vault.deleteForever(id);
  await refreshLists();
  showSnackbar("已彻底删除");
}

export async function emptyTrash() {
  const ok = await confirmModal("清空回收站", "回收站中的全部笔记将被永久粉碎，无法恢复。确定继续吗？", true);
  if (!ok) return;
  await Vault.emptyTrash();
  await refreshLists();
  showSnackbar("回收站已清空");
}

export function setSearchQuery(q: string) {
  store.searchQuery = q;
  if (searchTimer) clearTimeout(searchTimer);
  if (q.trim() === "") {
    store.searchResults = null;
    store.searching = false;
    return;
  }
  store.searching = true;
  searchTimer = setTimeout(async () => {
    try {
      store.searchResults = await Vault.search(q);
    } finally {
      store.searching = false;
    }
  }, 250);
}

export function selectTag(path: string | null) {
  store.selectedTag = store.selectedTag === path ? null : path;
}

export function setSort(option: string) {
  store.sortOption = option;
  void refreshLists();
}

// 点击阅读页中的双链（FR-LINK-03/04/05）。
export async function followWikilink(target: string, resolvedId?: string) {
  if (resolvedId) {
    await openNote(resolvedId);
    return;
  }
  const candidates = await Vault.resolveLinkTargets(target);
  if (candidates.length > 1) {
    store.modal = {
      kind: "candidates",
      title: `「${target}」存在多个同名笔记`,
      candidates,
    };
    return;
  }
  const ok = await confirmModal("创建笔记", `笔记「${target}」尚未创建，是否立即创建？`, false);
  if (!ok) return;
  const doc = await Vault.createNoteFromLink(target);
  await refreshLists();
  await openNote(doc.id);
}

// 导入流程：先准备计划，有冲突则弹逐项决策（FR-DATA-01 / AC-DATA-02）。
export async function startImport() {
  const plan = await Vault.prepareImport();
  if (plan.files.length === 0) return;
  const hasConflict = plan.files.some((f) => f.status === "conflict");
  if (!hasConflict) {
    await applyImport(plan, {});
    return;
  }
  const choice = await new Promise<string>((resolve) => {
    store.modal = { kind: "conflict", title: "发现同名文件", plan, resolveChoice: resolve };
  });
  if (choice === "__cancel__") return;
  await applyImport(plan, choice === "__per_file__" ? {} : Object.fromEntries(plan.files.map((f) => [f.sourcePath, choice])));
}

async function applyImport(plan: ImportPlan, actions: Record<string, string>) {
  store.busy = "正在导入…";
  try {
    const results = await Vault.applyImport(plan, actions);
    await refreshLists();
    store.modal = {
      kind: "confirm",
      title: "导入完成",
      message: summarizeImport(results),
    };
  } finally {
    store.busy = null;
  }
}

function summarizeImport(results: ImportResult[]): string {
  const ok = results.filter((r) => r.status === "imported" || r.status === "renamed" || r.status === "overwritten").length;
  const skipped = results.filter((r) => r.status === "skipped");
  const failed = results.filter((r) => r.status === "failed");
  const parts = [`成功导入 ${ok} 篇`];
  if (skipped.length > 0 && skipped[0]) parts.push(`跳过 ${skipped.length} 篇（${skipped[0].reason ?? "同名"}）`);
  if (failed.length > 0 && failed[0]) parts.push(`失败 ${failed.length} 篇（${failed[0].reason ?? "未知"}）`);
  return parts.join("；");
}

export async function exportVault() {
  store.busy = "正在打包知识库…";
  try {
    const path = await Vault.exportVault();
    if (path) showSnackbar(`已导出到 ${path}`);
  } finally {
    store.busy = null;
  }
}

export async function exportCurrentNote() {
  if (!store.current) return;
  const path = await Vault.exportNote(store.current.id);
  if (path) showSnackbar(`已导出到 ${path}`);
}

export async function rebuildIndex() {
  store.busy = "正在重建索引…";
  try {
    const r = await Vault.rebuildIndex();
    await refreshLists();
    showSnackbar(`索引重建完成：${r.noteCount} 篇笔记，耗时 ${r.durationMs} ms`);
  } finally {
    store.busy = null;
  }
}

function handleMenuAction(action: string) {
  switch (action) {
    case "new-note":
      void createNote();
      break;
    case "import":
      void startImport();
      break;
    case "export-vault":
      void exportVault();
      break;
    case "rebuild-index":
      void rebuildIndex();
      break;
  }
}

// 拖放导入（桌面端增强）：md 入库、图片进 attachments。
export async function importDroppedFiles(paths: string[]) {
  const results = await Vault.importDroppedFiles(paths);
  await refreshLists();
  const images = results.filter((r) => r.status === "copied");
  const notes = results.filter((r) => r.status === "imported");
  if (notes.length > 0) showSnackbar(`已导入 ${notes.length} 篇笔记`);
  if (images.length > 0) showSnackbar(`已复制 ${images.length} 个附件到 attachments/`);
  return results;
}

export function confirmModal(title: string, message: string, danger: boolean): Promise<boolean> {
  return new Promise((resolve) => {
    store.modal = { kind: "confirm", title, message, danger, resolve };
  });
}

export function closeModal(result: boolean | string | null) {
  const modal = store.modal;
  store.modal = null;
  if (!modal) return;
  if (modal.kind === "confirm" && modal.resolve) modal.resolve(Boolean(result));
  if (modal.kind === "conflict" && modal.resolveChoice) modal.resolveChoice(String(result));
}

export function showSnackbar(message: string) {
  showUndo(message, 2600, undefined);
}

export function showUndo(message: string, durationMs: number, onAction?: () => void) {
  if (snackbarTimer) clearTimeout(snackbarTimer);
  store.snackbar = { message, durationMs, actionLabel: onAction ? "撤销" : undefined, onAction };
  snackbarTimer = setTimeout(() => {
    store.snackbar = null;
  }, durationMs);
}

export function dismissSnackbar() {
  if (snackbarTimer) clearTimeout(snackbarTimer);
  store.snackbar = null;
}

// 窗口控制（自定义标题栏）。
export const windowControls = {
  minimize: () => currentWindow.minimize(),
  toggleMaximize: () => currentWindow.toggleMaximize(),
  close: () => currentWindow.close(),
};
