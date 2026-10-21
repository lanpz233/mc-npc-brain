#!/usr/bin/env python3
"""生成商店背景图 mcai_shop_bg.png（木柜台）。

画布 256x256；插件标题字形以 height=222 引用本图，面板在屏幕上对应 176x222，
故面板在图内宽 = 176 * 256/222 ≈ 203px、高占满 256。
改配色/布局后运行本脚本，再把 MCAI-Shop 文件夹压成 zip 即是资源包。

用法: python3 make_bg.py   (依赖 Pillow: pip install Pillow)
"""
from PIL import Image, ImageDraw
import os

IMG = 256
PANEL_W = 203  # image px

BASE = (107, 74, 44, 255)       # 木板底
BASE_ALT = (117, 83, 52, 255)   # 木板交替
SEAM = (78, 54, 32, 255)        # 板缝
FRAME = (65, 48, 27, 255)       # 外框深木
FRAME_HI = (138, 104, 68, 255)  # 框内高光
FIELD = (99, 69, 42, 255)       # 货架区
SIGN = (217, 179, 128, 255)     # 招牌羊皮纸
SIGN_EDGE = (138, 104, 68, 255)

img = Image.new("RGBA", (IMG, IMG), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 整块面板
d.rectangle([0, 0, PANEL_W - 1, IMG - 1], fill=BASE)
# 木板横纹（每 32 image px 一块板）
for y in range(0, IMG, 32):
    if (y // 32) % 2 == 1:
        d.rectangle([0, y, PANEL_W - 1, min(y + 31, IMG - 1)], fill=BASE_ALT)
    d.line([0, y, PANEL_W - 1, y], fill=SEAM, width=2)
# 外框
for i in range(4):
    d.rectangle([i, i, PANEL_W - 1 - i, IMG - 1 - i], outline=FRAME)
d.rectangle([5, 5, PANEL_W - 6, IMG - 6], outline=FRAME_HI)

# 货架区（商店格子区：screen y 18..126 -> image 21..145；x 8..168 -> 9..194）
d.rectangle([9, 21, 194, 145], fill=FIELD)
d.rectangle([9, 21, 194, 145], outline=SEAM)
# 玩家背包区（screen y 139..219 -> image 160..252）
d.rectangle([9, 160, 194, 252], fill=FIELD)
d.rectangle([9, 160, 194, 252], outline=SEAM)
# 柜台横梁（两区分隔：screen y ~130 -> image 150）
d.rectangle([0, 147, PANEL_W - 1, 154], fill=FRAME)
d.line([0, 147, PANEL_W - 1, 147], fill=FRAME_HI)

# 顶部招牌（screen y 4..16 -> image 5..18，居中 x 38..138 -> 44..159）
d.rectangle([44, 5, 159, 19], fill=SIGN)
d.rectangle([44, 5, 159, 19], outline=SIGN_EDGE)

# 四角钉子
for cx, cy in [(10, 10), (PANEL_W - 11, 10), (10, IMG - 11), (PANEL_W - 11, IMG - 11)]:
    d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(40, 28, 14, 255))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "MCAI-Shop", "assets", "minecraft", "textures", "gui", "mcai_shop_bg.png")
img.save(out)
print("written:", out)
