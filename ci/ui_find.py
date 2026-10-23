# uiautomator の画面情報からボタンの中心座標を探す
#   start : 画面共有の「開始」ボタン
#   anr   : 「応答していません」などのシステムのダイアログを閉じるボタン
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
mode = sys.argv[2] if len(sys.argv) > 2 else "start"
if mode == "start":
    words = ["Start now", "Start", "Share screen", "Share", "Next", "今すぐ開始", "開始", "共有"]
else:
    words = ["Wait", "Close app", "OK", "Got it", "Close", "待機", "アプリを終了"]
nodes = re.findall(r'<node [^>]*>', xml)
def center(n):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    x0, y0, x1, y1 = map(int, b.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2
for w in words:
    for n in nodes:
        t = re.search(r' text="([^"]*)"', n)
        if t and t.group(1).strip().lower() == w.lower():
            print("%d %d" % center(n)); sys.exit(0)
if mode == "start":
    for n in nodes:
        if 'resource-id="android:id/button1"' in n:
            print("%d %d" % center(n)); sys.exit(0)
