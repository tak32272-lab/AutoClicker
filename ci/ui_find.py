# uiautomator の画面情報から、画面共有の「開始」ボタンの中心座標を探す
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
words = ["Start now", "Start", "Share screen", "Share", "Next", "今すぐ開始", "開始", "共有"]
nodes = re.findall(r'<node [^>]*>', xml)
def center(n):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    x0, y0, x1, y1 = map(int, b.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2
for w in words:
    for n in nodes:
        t = re.search(r' text="([^"]*)"', n)
        if t and t.group(1).strip().lower() == w.lower() and 'clickable="true"' in n:
            print("%d %d" % center(n)); sys.exit(0)
for n in nodes:
    if 'resource-id="android:id/button1"' in n:
        print("%d %d" % center(n)); sys.exit(0)
