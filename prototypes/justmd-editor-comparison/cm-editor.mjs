import {EditorState, EditorSelection, Transaction} from '@codemirror/state';
import {EditorView, keymap} from '@codemirror/view';
import {history, undo, redo, undoDepth, redoDepth, historyKeymap, defaultKeymap, isolateHistory} from '@codemirror/commands';
import {markdown} from '@codemirror/lang-markdown';
import {syntaxHighlighting, HighlightStyle} from '@codemirror/language';
import {tags} from '@lezer/highlight';

const MAX = 400000;
const notes = new Map();
let current = null, generation = 0, sequence = 0, session = '', revision = 0;
const revisions = new Map();
const roleHighlight = HighlightStyle.define([
  {tag:[tags.heading,tags.strong],color:'var(--primary)',fontWeight:'700'},
  {tag:tags.emphasis,color:'var(--primary)',fontStyle:'italic'},
  {tag:[tags.link,tags.url],color:'var(--primary)',textDecoration:'underline'},
  {tag:[tags.meta,tags.monospace,tags.processingInstruction],color:'var(--muted)'}
]);
const view = new EditorView({parent: document.querySelector('#editor'), state: makeState('')});
function makeState(doc) {
  return EditorState.create({doc, extensions: [history(), markdown(), syntaxHighlighting(roleHighlight),
    EditorState.transactionFilter.of(transaction => transaction.newDoc.length <= MAX ? transaction : []),
    EditorState.allowMultipleSelections.of(false), EditorView.lineWrapping,
    keymap.of([...defaultKeymap, ...historyKeymap]), EditorView.updateListener.of(update => {
      if (current && (update.docChanged || update.selectionSet)) {
        if (update.docChanged) { revision++; revisions.set(current, revision); }
        notes.set(current, update.state); publish();
      }
    })]});
}
function publish(commandId = null, error = null) {
  if (!current) return;
  const s = view.state.selection.main;
  window.EditorBridge.postMessage(JSON.stringify({kind:'snapshot', session, noteId:current, generation,
    sequence:++sequence, text:view.state.doc.toString(), anchor:s.anchor, head:s.head,
    revision, canUndo:undoDepth(view.state)>0, canRedo:redoDepth(view.state)>0, commandId, error}));
}
function endpoint(text, p) {
  return Number.isInteger(p) && p >= 0 && p <= text.length && !(p > 0 && p < text.length && /[\uD800-\uDBFF]/.test(text[p-1]) && /[\uDC00-\uDFFF]/.test(text[p]));
}
async function finishComposition() {
  view.contentDOM.blur();
  const start = performance.now();
  await new Promise(resolve => setTimeout(resolve, 60));
  while (view.composing) {
    if (performance.now()-start > 1000) throw Error('Composition did not finish');
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}
function format(operation) {
  const state=view.state, s=state.selection.main;
  const text=state.doc.sliceString(s.from,s.to) || (operation==='bold'?'粗体':operation==='link'?'链接':'');
  let from=s.from, to=s.to, insert, anchor, head;
  if (operation==='bold') { insert='**'+text+'**'; anchor=from+2; head=anchor+text.length; }
  if (operation==='link') { insert='['+text+'](https://example.com)'; anchor=from+1; head=anchor+text.length; }
  if (operation==='heading') { from=state.doc.lineAt(s.from).from; to=from; insert='## '; anchor=s.anchor+3; head=s.head+3; }
  if (operation!=='heading' && s.anchor>s.head) [anchor,head]=[head,anchor];
  if(state.doc.length-(to-from)+insert.length>MAX) throw Error('Source too large');
  view.dispatch({changes:{from,to,insert}, selection:EditorSelection.single(anchor,head),
    annotations:[isolateHistory.of('full'),Transaction.userEvent.of('input.format')]});
}
window.receive = async message => {
  try {
    if (message.kind==='select') {
      await finishComposition();
      if (current) notes.set(current,view.state);
      if (typeof message.text!=='string' || message.text.length>MAX) throw Error('Source too large');
      session=message.session; current=message.noteId; generation=message.generation;
      revision=revisions.get(current)||0;
      const next=notes.get(current)||makeState(message.text);
      notes.set(current,next); view.setState(next); publish(message.commandId); return;
    }
    if (message.session!==session || message.noteId!==current || message.generation!==generation) return;
    if (message.kind==='flush') { await finishComposition(); publish(message.commandId); return; }
    if (message.kind==='command') {
      const op=message.operation;
      if (op==='undo') undo(view); else if (op==='redo') redo(view);
      else if (op==='select') {
        const text=view.state.doc.toString();
        if(!endpoint(text,message.anchor)||!endpoint(text,message.head)) throw Error('Invalid UTF16 selection');
        view.dispatch({selection:EditorSelection.single(message.anchor,message.head)});
      } else if (op==='insert') {
        if(typeof message.text!=='string'||view.state.doc.length-view.state.selection.main.to+view.state.selection.main.from+message.text.length>MAX) throw Error('Source too large');
        view.dispatch({...view.state.replaceSelection(message.text),annotations:[isolateHistory.of('full'),Transaction.userEvent.of('input.insert')]});
      } else { await finishComposition(); format(op); }
      view.focus(); publish(message.commandId);
    }
  } catch(error) { publish(message.commandId,String(error.message)); }
};
window.setAppearance = roles => { for (const [key,value] of Object.entries(roles)) document.documentElement.style.setProperty('--'+key,value); };
window.setViewport = (width,height) => { document.querySelector('#editor').style.height=height+'px'; view.requestMeasure(); };
if (window.EditorBridge) window.EditorBridge.postMessage(JSON.stringify({kind:'ready'}));
else document.body.textContent='CodeMirror unavailable: secure message bridge missing';
