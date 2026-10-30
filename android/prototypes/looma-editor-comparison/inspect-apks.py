from pathlib import Path
import hashlib
import json
import struct
import zipfile

root = Path(__file__).resolve().parent
results = {}
for flavor in ("native", "cm"):
    apk = root / f"android/app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
    strings = set()
    with zipfile.ZipFile(apk) as bundle:
        names = bundle.namelist()
        for name in names:
            if name.startswith("classes") and name.endswith(".dex"):
                dex = bundle.read(name)
                count, offset = struct.unpack_from("<II", dex, 56)
                for index in range(count):
                    position = struct.unpack_from("<I", dex, offset + index * 4)[0]
                    while dex[position] & 0x80:
                        position += 1
                    position += 1
                    end = dex.index(b"\0", position)
                    strings.add(dex[position:end].decode("utf-8", errors="replace"))
        results[flavor] = {
            "file": str(apk.relative_to(root)),
            "bytes": apk.stat().st_size,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "editorAssets": [name for name in names if name.startswith("assets/editor/")],
            "javascriptAssets": [name for name in names if name.endswith((".js", ".mjs"))],
            "containsCodeMirrorPort": "Ledu/hust/looma/comparison/CodeMirrorPort;" in strings,
            "containsNativeEditorPort": "Ledu/hust/looma/comparison/NativeEditorPort;" in strings,
            "containsAndroidXWebKitDescriptors": any(value.startswith("Landroidx/webkit/") for value in strings),
            "scope": "Debug APK inspection. The OS WebView engine is not bundled into either APK."
        }
report = root / "evidence/apk-inspection.json"
report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(results, ensure_ascii=False, indent=2))
