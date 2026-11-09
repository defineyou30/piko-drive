#!/usr/bin/env python3
"""
uiautomator 기반 초간단 UI 드라이버 (S4 플로우 QA용).
  python3 tools/ui.py tap "설정"          # 텍스트가 포함된 요소(또는 그 클릭 가능한 조상)를 탭
  python3 tools/ui.py tap "피코 ·" 5      # 5번 연속 탭 (숨은 데모 패널)
  python3 tools/ui.py has "설정"          # 존재 여부 (exit 0/1)
  python3 tools/ui.py shot path.png
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*a, out=False):
    r = subprocess.run(["adb", *a], capture_output=True)
    return r.stdout if out else r.returncode


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml", out=True))


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text):
    """'=텍스트' 는 정확히 일치하는 요소만 ('설정'이 '가족 설정' 버튼에 걸리지 않게),
    '$텍스트' 는 정확히 일치하는 것 중 마지막 (같은 글자가 목록과 칩에 둘 다 있을 때 화면 아래쪽 칩)"""
    last = text.startswith("$")
    exact = text.startswith("=") or last
    if exact:
        text = text[1:]
    if last:
        root = dump()
        parents = {c: p for p in root.iter() for c in p}
        hits = [n for n in root.iter("node") if (n.get("text") or "") == text]
        if not hits:
            return None
        cur = hits[-1]
        while cur is not None and cur.get("clickable") != "true":
            cur = parents.get(cur)
        return cur if cur is not None else hits[-1]
    root = dump()
    parents = {c: p for p in root.iter() for c in p}
    for n in root.iter("node"):
        t = n.get("text") or ""
        if (t == text) if exact else (text in t):
            cur = n
            while cur is not None and cur.get("clickable") != "true":
                cur = parents.get(cur)
            return cur if cur is not None else n
    return None


def main():
    cmd = sys.argv[1]
    if cmd == "shot":
        open(sys.argv[2], "wb").write(adb("exec-out", "screencap", "-p", out=True))
        return
    target = find(sys.argv[2])
    if cmd == "has":
        sys.exit(0 if target is not None else 1)
    if target is None:
        print(f"NOT FOUND: {sys.argv[2]}", file=sys.stderr)
        sys.exit(1)
    x, y = center(target)
    for _ in range(int(sys.argv[3]) if len(sys.argv) > 3 else 1):
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.15)
    print(f"tap '{sys.argv[2]}' @({x},{y})")


if __name__ == "__main__":
    main()
