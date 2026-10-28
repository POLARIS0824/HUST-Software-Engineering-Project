import {EditorState} from '@codemirror/state';
import {EditorView, keymap, Decoration, ViewPlugin} from '@codemirror/view';
import {markdown} from '@codemirror/lang-markdown';
import {history, historyKeymap, undo} from '@codemirror/commands';

function markers(view) {
  const ranges = [];
  const activeLine = view.state.doc.lineAt(view.state.selection.main.head).number;
  for (let number = 1; number <= view.state.doc.lines; number++) {
    const line = view.state.doc.line(number);
    if (line.number === activeLine && view.hasFocus) continue;
    const heading = /^(#{1,6}) /.exec(line.text);
    if (heading) {
      ranges.push(Decoration.replace({}).range(line.from, line.from + heading[0].length));
      ranges.push(Decoration.line({class: heading[1].length === 1 ? 'cm-title' : 'cm-heading'}).range(line.from));
    }
    for (const bold of line.text.matchAll(/\*\*(.+?)\*\*/g)) {
      const start = line.from + bold.index;
      ranges.push(Decoration.replace({}).range(start, start + 2));
      ranges.push(Decoration.mark({class: 'cm-bold'}).range(start + 2, start + bold[0].length - 2));
      ranges.push(Decoration.replace({}).range(start + bold[0].length - 2, start + bold[0].length));
    }
  }
  return Decoration.set(ranges, true);
}
const liveMarkers = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = markers(view); }
  update(update) { if (update.docChanged || update.selectionSet || update.focusChanged) this.decorations = markers(update.view); }
}, {decorations: plugin => plugin.decorations});

window.runCodeMirror = async config => {
  document.querySelector('#editor').replaceChildren();
  const view = new EditorView({parent: document.querySelector('#editor'), state: EditorState.create({
    doc: config.body,
    extensions: [markdown(), history(), keymap.of(historyKeymap), EditorView.lineWrapping, liveMarkers,
      EditorView.updateListener.of(update => {
        if (update.docChanged && !config.probe) window.ResearchBridge?.postMessage(JSON.stringify({kind:'draft',body:update.state.doc.toString()}));
      }),
      EditorView.theme({
        '&': {height:'100%',backgroundColor:'var(--bg)',color:'var(--fg)',fontFamily:'system-ui,sans-serif'},
        '.cm-scroller': {fontFamily:'inherit',lineHeight:'1.85',fontSize:'16px'},
        '.cm-content': {padding:'12px 24px 60px'}, '.cm-line':{padding:'0',minHeight:'1.85em'},
        '.cm-title':{fontSize:'30px',fontWeight:'700',lineHeight:'1.4',padding:'12px 0 20px'},
        '.cm-heading':{fontSize:'21px',fontWeight:'600',padding:'16px 0 8px'},
        '.cm-bold':{fontWeight:'700'}, '.cm-focused':{outline:'none'}
      })]
  })});
  if (!config.probe) return;
  const cases = [];
  for (const [id, input] of window.fidelityCorpus) {
    view.setState(EditorState.create({doc:input,extensions:[history()]}));
    const untouched = view.state.doc.toString();
    view.dispatch({changes:{from:view.state.doc.length,insert:'\n\n相邻编辑。'}});
    const edited = view.state.doc.toString();
    undo(view);
    const undone = view.state.doc.toString();
    cases.push({id,input,untouched,edited,undone,exactUntouched:input===untouched,
      originalStillPresentAfterEdit:edited.startsWith(input),exactUndo:undone===input,
      executedHostileScript:window.__executed===true});
  }
  window.ResearchBridge?.postMessage(JSON.stringify({kind:'evidence',result:{engine:'CodeMirror 6',
    webView:config.webView,domStorageEnabled:config.domStorageEnabled,method:'EditorState doc; dispatch append; undo; raw text comparison',
    limitation:'Source document and a minimal heading/bold decoration sketch. No full IR, WikiLink, IME, or accessibility claim.',cases}}));
  view.setState(EditorState.create({doc:config.body,extensions:[markdown(),EditorView.lineWrapping]}));
};
