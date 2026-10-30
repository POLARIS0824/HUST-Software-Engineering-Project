from pathlib import Path
import json
import subprocess
import sys

root = Path(__file__).resolve().parent
flavor, stage = sys.argv[1:3]
if flavor not in ("cm", "native") or not stage.replace("-", "").isalnum():
    raise SystemExit("Usage: observe.py cm|native stage-name [filename]")
filename = sys.argv[3] if len(sys.argv) > 3 else "active-draft.json"
if filename not in ("active-draft.json", "palette.json", "editor-probes.json"):
    raise SystemExit("Unsupported observation file")
raw = subprocess.check_output(["adb", "-s", "emulator-5554", "shell", "run-as",
                               f"edu.hust.looma.comparison.{flavor}", "cat", f"files/{filename}"])
value = json.loads(raw)
destination = root / "evidence" / f"{flavor}-{stage}.json"
destination.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
if "text" in value:
    print(json.dumps({"file": str(destination), "noteId": value["noteId"], "length": len(value["text"]),
                      "selection": [value["anchor"], value["head"]],
                      "canUndo": value["canUndo"], "canRedo": value["canRedo"]}, ensure_ascii=False))
elif "cases" in value:
    print(json.dumps({"engine": value["engine"], "cases": [
        {"id": case["id"], "checks": {key: item for key, item in case.items() if isinstance(item, bool)},
         "error": case.get("error")} for case in value["cases"]]}, ensure_ascii=False))
else:
    print(json.dumps(value, ensure_ascii=False))
