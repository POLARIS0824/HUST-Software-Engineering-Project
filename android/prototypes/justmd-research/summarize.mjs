import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {createHash} from 'node:crypto';
const root = new URL('./', import.meta.url);
const read = name => JSON.parse(readFileSync(new URL(`evidence/${name}`,root),'utf8').replace(/^\uFEFF/,''));
const native = read('native-probes.json');
const vditor = read('vditor-probes.json');
const cm = read('codemirror-probes.json');
if (vditor.version !== '3.9.9' || cm.engine !== 'CodeMirror 6') throw new Error('Wrong engine evidence');
if (vditor.cases.length !== 8 || cm.cases.length !== 8) throw new Error('Incomplete corpus');
const sourceFiles = ['android/app/src/main/assets/editor/vditor/dist/index.min.js','android/app/src/main/assets/editor/codemirror.bundle.js','android/app/build/outputs/apk/debug/app-debug.apk'];
const screenshots = ['a-library','b-library','c-library','a-editor','b-editor','c-editor','c-search-empty'];
const summary = {
  scope:'Small fixed corpus on one Android emulator. No speed, reliability, full IR, or product-readiness claim.',
  device:native.android,
  room:{status:native.search.status,version:native.search.evidence?.sqliteVersion,schema:native.search.evidence?.schema,
    literalCases:native.search.evidence?.cases.map(c=>({query:c.query,expected:c.expectedLiteralScanIds,
      like:c.like.evidence?.returnedIds,likeMatches:c.like.evidence?.equalsFixtureScan,
      match:c.rawMatch.evidence?.returnedIds,route:c.applicationRoute}))},
  storage:native.storage,
  vditor:{version:vditor.version,domStorageEnabled:vditor.domStorageEnabled,
    cases:vditor.cases.map(c=>({id:c.id,exactUntouched:c.exactUntouched,originalStillPresentAfterEdit:c.originalStillPresentAfterEdit,undoError:c.undoError,error:c.error})),
    referenceTitleCase:vditor.cases.find(c=>c.id==='reference')},
  codemirror:{engine:cm.engine,domStorageEnabled:cm.domStorageEnabled,
    cases:cm.cases.map(c=>({id:c.id,exactUntouched:c.exactUntouched,originalStillPresentAfterEdit:c.originalStillPresentAfterEdit,exactUndo:c.exactUndo,error:c.error}))},
  hashes:Object.fromEntries(sourceFiles.map(file=>[file,createHash('sha256').update(readFileSync(new URL(file,root))).digest('hex')])),
  screenshots:screenshots.map(name=>{const file=`screenshots/${name}.png`; if(!existsSync(new URL(file,root)))throw new Error(`Missing ${file}`);return file;})
};
writeFileSync(new URL('evidence/summary.json',root),JSON.stringify(summary,null,2));
console.log(JSON.stringify({room:summary.room.status,sqlite:summary.room.version,like:summary.room.literalCases.filter(c=>c.likeMatches).length,
  likeTotal:summary.room.literalCases.length,vditorExact:vditor.cases.filter(c=>c.exactUntouched).length,
  vditorErrors:vditor.cases.filter(c=>c.error).length,cmExact:cm.cases.filter(c=>c.exactUntouched&&c.exactUndo&&c.originalStillPresentAfterEdit).length,
  cmDomStorage:cm.domStorageEnabled,screenshots:summary.screenshots.length},null,2));
