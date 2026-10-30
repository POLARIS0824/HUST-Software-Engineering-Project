let editor;
let loading = true;
const send = message => window.ResearchBridge?.postMessage(JSON.stringify(message));
window.setAppearance = appearance => {
  document.body.dataset.style = appearance.style;
  for (const [name, value] of Object.entries({bg: appearance.background, fg: appearance.foreground, muted: appearance.muted, accent: appearance.accent, container: appearance.container})) {
    document.documentElement.style.setProperty(`--${name}`, value);
  }
};
const settle = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
window.setViewport = height => document.documentElement.style.setProperty('--editor-height', `${height}px`);
window.launch = config => {
  if (editor) return;
  window.setAppearance(config.appearance);
  window.setViewport(config.viewportHeight);
  if (config.engine === 'codemirror') return window.runCodeMirror(config);
  editor = new Vditor('editor', {
    cdn: 'https://appassets.androidplatform.net/assets/editor/vditor',
    mode: 'ir', lang: 'zh_CN', height: config.viewportHeight, minHeight: 200,
    toolbar: [], cache: {enable: false}, value: config.body, undoDelay: 1,
    outline: {enable: false}, counter: {enable: false},
    hint: {emoji: {}, emojiPath: 'https://appassets.androidplatform.net/assets/editor/vditor/images/emoji'},
    preview: {hljs: {enable: false}, markdown: {sanitize: true}},
    input: body => { if (!loading) send({kind: 'draft', body}); },
    after: async () => {
      loading = false;
      document.activeElement?.blur();
      if (config.probe) await runFidelity(config);
      else send({kind:'diagnostic',result:{nativeHeight:config.viewportHeight,innerHeight:window.innerHeight,nodes:[...document.querySelectorAll('#editor,.vditor-content,.vditor-ir,.vditor-ir pre')].map(node=>({
        class:node.className, inline:node.getAttribute('style'), height:node.getBoundingClientRect().height,
        computedHeight:getComputedStyle(node).height, overflow:getComputedStyle(node).overflow,display:getComputedStyle(node).display
      }))}});
    }
  });
};
async function runFidelity(config) {
  const corpus = window.fidelityCorpus;
  const cases = [];
  for (const [id, input] of corpus) {
    try {
      editor.setValue(input);
      await settle();
      const untouched = editor.getValue();
      const editable = document.querySelector('.vditor-ir [contenteditable="true"]');
      editable.focus();
      const range = document.createRange();
      range.selectNodeContents(editable);
      range.collapse(false);
      const selection = getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      editor.insertValue('\n\n相邻编辑。');
      await settle();
      const edited = editor.getValue();
      await new Promise(resolve => setTimeout(resolve, 20));
      let undoError;
      try { editor.vditor.undo.undo(editor.vditor); } catch (error) { undoError = String(error); }
      await settle();
      const undone = editor.getValue();
      cases.push({id, input, untouched, edited, undone, undoError, exactUntouched: input === untouched,
        originalStillPresentAfterEdit: edited.startsWith(input.trimEnd()), executedHostileScript: window.__executed === true});
    } catch (error) { cases.push({id, input, error: String(error)}); }
  }
  send({kind: 'evidence', result: {version: '3.9.9', bundleSha256: config.bundleSha256,
    domStorageEnabled: config.domStorageEnabled,
    webView: config.webView, method: 'setValue; getValue; insertValue at document end; getValue; pinned internal vditor.undo.undo; getValue',
    limitation: 'Programmatic edit, not a Chinese IME or TalkBack test. Byte comparison includes whitespace normalization.', cases}});
  editor.setValue(config.body);
}
window.fidelityCorpus = [
    ['basic', '# 标题\n\n正文包含 **加粗** 与 `code`。\n'],
    ['wikilink', '# 关联\n\n[[TCP 三次握手|握手]] 与 [[未创建的笔记]]。\n'],
    ['unknown', '# 保真\n\n::: custom-box\n未知扩展  &nbsp;  保留空格\n:::\n\n尾段。\n'],
    ['reference', '# 引用\n\n[说明][ref]\n\n[ref]: https://example.com "标题"\n'],
    ['html', '# HTML\n\n<span data-private="must-retain">原始内容</span>\n\n尾段。\n'],
    ['code-tag', '# 标签\n\n```md\n#不是标签 [[不是链接]]\n```\n\n#真实标签\n'],
    ['math', '# 公式\n\n$$\nE = mc^2\n$$\n'],
    ['hostile-html', '# 不执行\n\n<img src="https://example.com/tracker.png" onerror="window.__executed=true">\n']
];
