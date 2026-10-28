from pathlib import Path
import json

root = Path(__file__).resolve().parent
evidence = root / "evidence"

def read(name):
    return json.loads((evidence / name).read_text(encoding="utf-8-sig"))

def nodes(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from nodes(child)

result = {"device": "emulator-5554 API 37", "input": "ADB input keyevent MOVE_END and input text UI42 after tapping the real EditText; native toolbar clicks via accessibility selectors", "limits": "ASCII key injection, not Chinese IME candidate validation. Same operations, cursor positions differ after center tap. Source probes use identical explicit selections.", "flavors": {}}
for flavor in ("cm", "native"):
    stages = {stage: read(f"{flavor}-ui-{stage}.json") for stage in (
        "initial", "typed", "bold", "undo", "redo", "read", "return", "other", "reopen", "theme-draft", "reopen-undo")}
    s = stages
    typed, bold = s["typed"]["text"], s["bold"]["text"]
    index = s["typed"]["head"]
    checks = {
        "typedIntoActualEditor": typed.replace("UI42", "", 1) == s["initial"]["text"],
        "toolbarBoldExact": bold == typed[:index] + "**粗体**" + typed[index:],
        "toolbarUndoExact": s["undo"]["text"] == typed,
        "toolbarRedoExact": s["redo"]["text"] == bold,
        "readAndReturnSourceExact": s["read"]["text"] == s["return"]["text"] == bold,
        "otherNoteIdentity": s["other"]["noteId"] == "inbox",
        "reopenSameDraft": s["reopen"]["noteId"] == "tcp" and s["reopen"]["text"] == bold,
        "themeRetainsDraftAndSelection": all(s["theme-draft"][key] == s["reopen"][key] for key in ("text", "anchor", "head", "canUndo", "canRedo")),
        "undoAfterReopenAndTheme": s["reopen-undo"]["text"] == typed,
        "readerHasNoAccessibleEditText": not any(n.get("class") == "android.widget.EditText" for n in nodes(read(f"{flavor}-ui-read-layout.json"))),
    }
    result["flavors"][flavor] = {"checks": checks, "selectionBeforeRead": [s["redo"]["anchor"],s["redo"]["head"]],
        "selectionAtRead": [s["read"]["anchor"],s["read"]["head"]],
        "snapshots": [f"{flavor}-ui-{stage}.json" for stage in stages]}
(evidence / "ui-journeys.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
