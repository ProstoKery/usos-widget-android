"""Tiny UI helper over adb + uiautomator.

usage:
  ui.py tap  <regex> [--timeout S] [--scroll]   tap the first element whose text matches
  ui.py wait <regex> [--timeout S]              wait until such an element is on screen
  ui.py dump                                    print all visible texts
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    raw = adb("exec-out", "cat", "/sdcard/ui.xml").decode("utf-8", "replace")
    start = raw.find("<?xml")
    if start < 0:
        return []
    try:
        root = ET.fromstring(raw[start:])
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        label = (n.get("text") or "") or (n.get("content-desc") or "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if label and m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def find(pattern, timeout, scroll):
    rx = re.compile(pattern, re.IGNORECASE)
    deadline = time.time() + timeout
    while time.time() < deadline:
        for label, x, y in nodes():
            if rx.search(label.strip()):
                return label, x, y
        if scroll:
            adb("shell", "input", "swipe", "540", "1700", "540", "700", "300")
        time.sleep(1)
    return None


def main():
    cmd = sys.argv[1]
    if cmd == "dump":
        for label, x, y in nodes():
            print(f"{x:5d},{y:5d}  {label}")
        return 0
    pattern = sys.argv[2]
    timeout = float(sys.argv[sys.argv.index("--timeout") + 1]) if "--timeout" in sys.argv else 15
    hit = find(pattern, timeout, "--scroll" in sys.argv)
    if not hit:
        print(f"not found: /{pattern}/  — on screen:", [n[0] for n in nodes()])
        return 1
    label, x, y = hit
    if cmd == "tap":
        adb("shell", "input", "tap", str(x), str(y))
        print(f"tapped '{label}' at {x},{y}")
    else:
        print(f"found '{label}'")
    return 0


sys.exit(main())
