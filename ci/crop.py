# 登録画像を作る：テストゲームの怪物を、余白つきで切り取る（左上は 3 の倍数にそろえる）
import re, sys
from PIL import Image
src, line, dst = sys.argv[1], sys.argv[2], sys.argv[3]
m = re.search(r"cx=(\d+) cy=(\d+)", line)
cx, cy = int(m.group(1)), int(m.group(2))
im = Image.open(src).convert("RGB")
x0, y0 = cx - 75 - 25, cy - 105 - 20
x0 -= x0 % 3
y0 -= y0 % 3
box = (x0, y0, x0 + 201, y0 + 222)
im.crop(box).save(dst)
print("screen", im.size, "crop", box)
