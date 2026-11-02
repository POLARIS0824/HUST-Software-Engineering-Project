#!/usr/bin/env python3
"""下载 Material Symbols Rounded 图标并转换为 Android VectorDrawable。

用法：
    python tools/fetch_material_symbols.py            # 生成 ICONS 中的全部图标
    python tools/fetch_material_symbols.py undo table # 只重新生成指定图标

脚本只依赖标准库，重复执行不会产生差异（内容未变则不写文件）。
新增图标只需在 ICONS 中加一行映射。
"""

from __future__ import annotations

import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

BASE_URL = (
    "https://fonts.gstatic.com/s/i/short-term/release/"
    "materialsymbolsrounded/{name}/default/24px.svg"
)

# Material Symbols 名称 -> res/drawable 文件名（不含 .xml）
ICONS = {
    "undo": "ic_editor_undo",
    "redo": "ic_editor_redo",
    "format_bold": "ic_editor_bold",
    "format_italic": "ic_editor_italic",
    "strikethrough_s": "ic_editor_strikethrough",
    "format_ink_highlighter": "ic_editor_highlight",
    "code": "ic_editor_code",
    "format_h1": "ic_editor_h1",
    "format_h2": "ic_editor_h2",
    "format_h3": "ic_editor_h3",
    "format_list_bulleted": "ic_editor_bullet_list",
    "format_list_numbered": "ic_editor_numbered_list",
    "checklist": "ic_editor_checklist",
    "format_quote": "ic_editor_quote",
    "link": "ic_editor_link",
    "code_blocks": "ic_editor_code_block",
    "table": "ic_editor_table",
    "horizontal_rule": "ic_editor_divider",
    "visibility": "ic_editor_preview",
    "edit": "ic_editor_edit",
}

DRAWABLE_DIR = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
SVG_NAMESPACE = "{http://www.w3.org/2000/svg}"
FILL_COLOR = "#FF000000"


class IconError(Exception):
    """单个图标抓取或解析失败。"""


def fetch_svg(name: str) -> bytes:
    url = BASE_URL.format(name=name)
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
            body = response.read()
    except urllib.error.HTTPError as exc:
        raise IconError(f"{url} 返回 HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise IconError(f"{url} 请求失败：{exc.reason}") from exc
    if status != 200:
        raise IconError(f"{url} 返回 HTTP {status}")
    if not body:
        raise IconError(f"{url} 返回空响应")
    return body


def extract_paths(svg: bytes) -> list:
    """按文档顺序取出所有 <path> 的 d 属性。"""
    try:
        root = ET.fromstring(svg)
    except ET.ParseError as exc:
        raise IconError(f"SVG 解析失败：{exc}") from exc

    paths = []
    for element in root.iter():
        if element.tag not in (f"{SVG_NAMESPACE}path", "path"):
            continue
        data = (element.get("d") or "").strip()
        if data:
            paths.append(data)
    if not paths:
        raise IconError("SVG 中没有 <path>")
    return paths


def escape_attr(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def render_vector(paths: list) -> str:
    # Material Symbols 的 viewBox 是 "0 -960 960 960"，用 translateY 把坐标系移回原点
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="960"',
        '    android:viewportHeight="960">',
        '    <group android:translateY="960">',
    ]
    for data in paths:
        lines.append('        <path')
        lines.append(f'            android:fillColor="{FILL_COLOR}"')
        lines.append(f'            android:pathData="{escape_attr(data)}" />')
    lines.extend(['    </group>', '</vector>', ''])
    return "\n".join(lines)


def write_if_changed(path: Path, content: str) -> bool:
    if path.is_file():
        with open(path, encoding="utf-8", newline="") as existing:
            if existing.read() == content:
                return False
    with open(path, "w", encoding="utf-8", newline="\n") as target:
        target.write(content)
    return True


def select_icons(args: list) -> dict:
    """支持按 Material 名称或 drawable 名称筛选，未指定时返回全部。"""
    if not args:
        return dict(ICONS)

    drawable_to_name = {drawable: name for name, drawable in ICONS.items()}
    selected = {}
    unknown = []
    for arg in args:
        if arg in ICONS:
            selected[arg] = ICONS[arg]
        elif arg in drawable_to_name:
            selected[drawable_to_name[arg]] = arg
        else:
            unknown.append(arg)
    if unknown:
        raise SystemExit(f"未知图标：{', '.join(unknown)}（可选：{', '.join(ICONS)}）")
    return selected


def main(argv: list) -> int:
    if any(arg in ("-h", "--help") for arg in argv):
        print(__doc__.strip())
        return 0

    selected = select_icons(argv)
    DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)

    failures = []
    multi_path = []
    written = 0
    unchanged = 0

    for name, drawable in selected.items():
        try:
            paths = extract_paths(fetch_svg(name))
            content = render_vector(paths)
        except IconError as exc:
            failures.append(f"{name} ({drawable}): {exc}")
            print(f"ERROR {name}: {exc}", file=sys.stderr)
            continue

        if len(paths) > 1:
            multi_path.append((name, drawable, len(paths)))

        target = DRAWABLE_DIR / f"{drawable}.xml"
        if write_if_changed(target, content):
            written += 1
            print(f"写入 {target.name}（{name}，{len(paths)} 条 path）")
        else:
            unchanged += 1
            print(f"未变 {target.name}")

    print(
        f"\n完成：共 {len(selected)} 个图标，写入 {written} 个，未变 {unchanged} 个，"
        f"失败 {len(failures)} 个"
    )
    if multi_path:
        print("包含多条 path 的图标：")
        for name, drawable, count in multi_path:
            print(f"  {name} -> {drawable}.xml：{count} 条")
    if failures:
        print("\n失败列表：", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
