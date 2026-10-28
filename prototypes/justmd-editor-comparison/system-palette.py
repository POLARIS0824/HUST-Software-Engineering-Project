"""Temporarily change the emulator's system palette, then restore its saved setting."""
from pathlib import Path
import json
import subprocess
import sys

root = Path(__file__).resolve().parent
original = json.loads((root / "evidence/emulator-original-state.json").read_text(encoding="utf-8-sig"))
device = original["device"]
key = "theme_customization_overlay_packages"
if sys.argv[1:] == ["restore"]:
    value = original["themeCustomization"]
    command = f"settings delete secure {key}" if value == "null" else f"settings put secure {key} '{value}'"
else:
    value = json.dumps({
        "android.theme.customization.system_palette": "27845A",
        "android.theme.customization.color_source": "preset",
        "android.theme.customization.theme_style": "TONAL_SPOT",
    }, separators=(",", ":"))
    command = f"settings put secure {key} '{value}'"
subprocess.run(["adb", "-s", device, "shell", command], check=True)
result = subprocess.check_output(["adb", "-s", device, "shell", "settings", "get", "secure", key], text=True).strip()
print(result)
if sys.argv[1:] == ["restore"] and result != original["themeCustomization"]:
    raise SystemExit("System palette setting did not restore exactly")
