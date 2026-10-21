#!/usr/bin/env python3
"""生成商店功能按钮图标（阶段1：解决"用物品凑功能、难辨别"）。

每个功能一个专属图标，画在 48x48 PNG 上，靠 CustomModelData 挂到纸上显示：
- 砍价按钮 = 金币印 "-N"（金色=往下砍）、抬价按钮 = 绿币印 "+N"（绿色=往上抬）
- 入口/动作 = 印汉字的牌子（砍/抬/买/卖/价/货/收）、勾、叉、箭头、锁、宝石
插件侧的 CMD 编号与 gui/ShopIcons.java 必须一致：
  cut 1001-1010 / raise 1011-1020 / tag_cut 1021 / tag_raise 1022 / buy 1023 /
  sell 1024 / accept 1025 / reject 1026 / back 1027 / close 1028 / price 1029 /
  lock 1030 / balance 1031 / prev 1032 / next 1033 / mode_buy 1034 / mode_sell 1035

用法: python3 make_icons.py   (依赖 Pillow)
产物: MCAI-Shop/assets/mcai/textures/icon/*.png + models + minecraft paper.json overrides
"""
from PIL import Image, ImageDraw, ImageFont
import json
import os

S = 48
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "MCAI-Shop")
TEX_DIR = os.path.join(ROOT, "assets", "mcai", "textures", "icon")
MODEL_DIR = os.path.join(ROOT, "assets", "mcai", "models", "icon")
os.makedirs(TEX_DIR, exist_ok=True)
os.makedirs(MODEL_DIR, exist_ok=True)

DEJAVU_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
CJK_TTC = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"


def cjk_font(size):
    """NotoSansCJK 的 .ttc 里有多个地区面，探一个能画出简体字的（以'买'为准）"""
    for idx in range(10):
        try:
            f = ImageFont.truetype(CJK_TTC, size, index=idx)
        except Exception:
            break
        probe = Image.new("L", (size * 2, size * 2), 0)
        d = ImageDraw.Draw(probe)
        d.text((2, 2), "买", font=f, fill=255)
        if probe.getbbox():
            return f
    raise RuntimeError("no CJK face with 买")


def centered(d, text, font, fill, cy=S // 2):
    box = d.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((S - w) / 2 - box[0], cy - h / 2 - box[1]), text, font=font, fill=fill)


def coin(rim, face, hi, text):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([3, 3, S - 4, S - 4], fill=rim)
    d.ellipse([7, 7, S - 8, S - 8], fill=face)
    d.arc([7, 7, S - 8, S - 8], start=200, end=320, fill=hi, width=3)
    size = 21 if len(text) >= 3 else 26
    centered(d, text, ImageFont.truetype(DEJAVU_BOLD, size), (40, 26, 8, 255))
    return img


def plate(bg, edge, draw_fn):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([3, 3, S - 4, S - 4], radius=9, fill=bg, outline=edge, width=2)
    draw_fn(d)
    return img


def char_plate(bg, edge, ch):
    f = cjk_font(27)
    return plate(bg, edge, lambda d: centered(d, ch, f, (255, 255, 255, 255)))


def tag(bg, edge, ch):
    """价签：圆角牌 + 左上穿孔 + 绳圈"""
    f = cjk_font(25)
    def draw(d):
        d.ellipse([7, 7, 15, 15], fill=(0, 0, 0, 0), outline=(255, 255, 255, 255), width=2)
        centered(d, ch, f, (255, 255, 255, 255), cy=S // 2 + 3)
    return plate(bg, edge, draw)


def arrow_plate(bg, edge, left=True):
    def draw(d):
        cy = S // 2
        if left:
            d.polygon([(33, 13), (33, 35), (19, 24)], fill=(255, 255, 255, 255))
            d.rectangle([15, 21, 34, 27], fill=(255, 255, 255, 255))
        else:
            d.polygon([(15, 13), (15, 35), (29, 24)], fill=(255, 255, 255, 255))
            d.rectangle([14, 21, 33, 27], fill=(255, 255, 255, 255))
    return plate(bg, edge, draw)


ICONS = {}  # name -> (cmd, image)


def reg(name, cmd, img):
    ICONS[name] = (cmd, img)


# 砍/抬数字币
for n in range(1, 11):
    reg(f"cut_{n}", 1000 + n, coin((122, 79, 10, 255), (242, 194, 48, 255), (255, 230, 140, 255), f"-{n}"))
    reg(f"raise_{n}", 1010 + n, coin((16, 84, 40, 255), (46, 204, 113, 255), (150, 255, 190, 255), f"+{n}"))

reg("tag_cut", 1021, tag((196, 110, 20, 255), (120, 62, 8, 255), "砍"))
reg("tag_raise", 1022, tag((22, 140, 70, 255), (10, 84, 40, 255), "抬"))
reg("buy", 1023, char_plate((21, 128, 61, 255), (10, 70, 34, 255), "买"))
reg("sell", 1024, char_plate((217, 119, 6, 255), (130, 66, 0, 255), "卖"))
reg("price", 1029, tag((121, 85, 72, 255), (70, 46, 38, 255), "价"))
reg("mode_buy", 1034, char_plate((13, 148, 136, 255), (8, 80, 74, 255), "货"))
reg("mode_sell", 1035, char_plate((147, 51, 234, 255), (88, 28, 135, 255), "收"))


def check_img():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([3, 3, S - 4, S - 4], fill=(22, 163, 74, 255))
    d.line([(13, 25), (21, 33), (36, 15)], fill=(255, 255, 255, 255), width=6, joint="curve")
    return img


def cross_img(bg=(220, 38, 38, 255), plate_mode=False):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if plate_mode:
        d.rounded_rectangle([3, 3, S - 4, S - 4], radius=9, fill=bg, outline=(110, 16, 16, 255), width=2)
    else:
        d.ellipse([3, 3, S - 4, S - 4], fill=bg)
    d.line([(16, 16), (32, 32)], fill=(255, 255, 255, 255), width=6)
    d.line([(32, 16), (16, 32)], fill=(255, 255, 255, 255), width=6)
    return img


reg("accept", 1025, check_img())
reg("reject", 1026, cross_img())
reg("close", 1028, cross_img(bg=(153, 27, 27, 255), plate_mode=True))
reg("back", 1027, arrow_plate((100, 116, 139, 255), (51, 65, 85, 255), left=True))
reg("prev", 1032, arrow_plate((100, 116, 139, 255), (51, 65, 85, 255), left=True))
reg("next", 1033, arrow_plate((100, 116, 139, 255), (51, 65, 85, 255), left=False))


def lock_img():
    def draw(d):
        d.rounded_rectangle([13, 22, 35, 40], radius=4, fill=(255, 255, 255, 255))
        d.arc([16, 8, 32, 28], start=180, end=360, fill=(255, 255, 255, 255), width=4)
        d.ellipse([21, 28, 27, 34], fill=(90, 90, 90, 255))
    return plate((107, 114, 128, 255), (55, 60, 70, 255), draw)


def gem_img():
    def draw(d):
        d.polygon([(24, 8), (38, 18), (24, 41), (10, 18)], fill=(52, 235, 140, 255))
        d.polygon([(24, 8), (31, 18), (24, 41), (17, 18)], fill=(110, 245, 175, 255))
        d.line([(10, 18), (38, 18)], fill=(16, 90, 50, 255), width=2)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dd = ImageDraw.Draw(img)
    dd.rounded_rectangle([3, 3, S - 4, S - 4], radius=9, fill=(30, 41, 59, 255), outline=(12, 20, 32, 255), width=2)
    draw(dd)
    return img


reg("lock", 1030, lock_img())
reg("balance", 1031, gem_img())

# 写纹理 + 模型 + paper overrides
overrides = []
for name, (cmd, img) in sorted(ICONS.items(), key=lambda kv: kv[1][0]):
    img.save(os.path.join(TEX_DIR, f"{name}.png"))
    model = {"parent": "item/generated", "textures": {"layer0": f"mcai:icon/{name}"}}
    with open(os.path.join(MODEL_DIR, f"{name}.json"), "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, indent=2)
    overrides.append({"predicate": {"custom_model_data": cmd}, "model": f"mcai:icon/{name}"})

paper = {"parent": "item/generated", "textures": {"layer0": "item/paper"}, "overrides": overrides}
paper_path = os.path.join(ROOT, "assets", "minecraft", "models", "item")
os.makedirs(paper_path, exist_ok=True)
with open(os.path.join(paper_path, "paper.json"), "w", encoding="utf-8") as f:
    json.dump(paper, f, ensure_ascii=False, indent=2)

print(f"icons: {len(ICONS)} -> {TEX_DIR}")

# 总览图（人工检查用）：拼一张网格图
names = sorted(ICONS.items(), key=lambda kv: kv[1][0])
cols = 6
rows = (len(names) + cols - 1) // cols
sheet = Image.new("RGBA", (cols * S, rows * S), (40, 40, 40, 255))
for i, (_, (_, img)) in enumerate(names):
    sheet.paste(img, ((i % cols) * S, (i // cols) * S), img)
sheet = sheet.resize((cols * S * 2, rows * S * 2), Image.NEAREST)
sheet.save("/tmp/icon-sheet.png")
print("sheet -> /tmp/icon-sheet.png")
