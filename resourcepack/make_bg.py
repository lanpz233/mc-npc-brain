#!/usr/bin/env python3
"""生成商店背景图：通用木柜台 + 按职业换装的 14 个变体（素材阶段 3）。

画布 256x256；插件标题字形以 height=222 引用本图，面板在屏幕上对应 176x222，
故面板在图内宽 = 176 * 256/222 ≈ 203px、高占满 256。
所有变体与通用版**几何完全一致**（对齐参数是实测调好的，绝不能动），
只换三样：木色微调、顶部招牌的职业色、招牌中央的小徽记。

插件侧字形（font/mcai_shop.json 与 ShopGui.title 对应）：
  通用 \ue100（无业 + 兜底） / 农夫 101 / 铁匠 102 / 武器匠 103 / 盔甲匠 104 /
  牧师 105 / 图书管理员 106 / 制图师 107 / 制箭师 108 / 渔夫 109 / 屠夫 10a /
  皮匠 10b / 石匠 10c / 牧羊人 10d / 傻子 10e

用法: python3 make_bg.py   (依赖 Pillow)
"""
from PIL import Image, ImageDraw
import os

IMG = 256
PANEL_W = 203  # image px
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "MCAI-Shop", "assets", "minecraft", "textures", "gui")

CREAM = (245, 232, 200, 255)   # 徽记主色（奶油白，在深色招牌上醒目）
DARK = (52, 40, 26, 255)       # 浅色招牌上的徽记色


def palette(base, base_alt, seam, frame, frame_hi, field, sign, sign_edge):
    return dict(BASE=base, BASE_ALT=base_alt, SEAM=seam, FRAME=frame,
                FRAME_HI=frame_hi, FIELD=field, SIGN=sign, SIGN_EDGE=sign_edge)


GENERIC = palette((107, 74, 44), (117, 83, 52), (78, 54, 32), (65, 48, 27),
                  (138, 104, 68), (99, 69, 42), (217, 179, 128), (138, 104, 68))


def tinted(dr, dg, db, sign, sign_edge):
    """以通用木色为底整体偏移 RGB，得到某职业的木色"""
    def sh(c):
        return tuple(max(0, min(255, v + dv)) for v, dv in zip(c, (dr, dg, db)))
    return palette(sh(GENERIC["BASE"]), sh(GENERIC["BASE_ALT"]), sh(GENERIC["SEAM"]),
                   sh(GENERIC["FRAME"]), sh(GENERIC["FRAME_HI"]), sh(GENERIC["FIELD"]),
                   sign, sign_edge)


# ===== 徽记（画在招牌中央，约 x 92..110、y 5..19 的小区域，cx=101, cy=12） =====

def em_wheat(d, c):
    d.line([101, 18, 101, 7], fill=c, width=2)
    for yy in (8, 11, 14):
        d.line([101, yy, 96, yy - 3], fill=c, width=2)
        d.line([101, yy, 106, yy - 3], fill=c, width=2)


def em_hammer(d, c):
    d.rectangle([97, 5, 108, 10], fill=c)          # 锤头
    d.line([103, 9, 97, 18], fill=c, width=3)       # 锤柄


def em_sword(d, c):
    d.polygon([(101, 5), (103, 8), (102, 14), (100, 14), (99, 8)], fill=c)  # 剑身
    d.line([96, 15, 106, 15], fill=c, width=2)       # 护手
    d.line([101, 15, 101, 18], fill=c, width=2)     # 柄


def em_shield(d, c):
    d.polygon([(94, 6), (108, 6), (108, 12), (101, 19), (94, 12)], outline=c, width=2)
    d.line([101, 6, 101, 17], fill=c, width=1)


def em_flask(d, c):
    d.ellipse([95, 9, 107, 19], outline=c, width=2)  # 瓶身
    d.rectangle([99, 5, 103, 9], outline=c, width=2)  # 瓶颈
    d.ellipse([99, 13, 103, 17], fill=c)             # 药液芯


def em_book(d, c):
    d.rectangle([94, 6, 101, 18], outline=c, width=2)
    d.rectangle([101, 6, 108, 18], outline=c, width=2)
    d.line([97, 10, 99, 10], fill=c, width=1)
    d.line([103, 10, 105, 10], fill=c, width=1)


def em_compass(d, c):
    d.ellipse([93, 5, 109, 19], outline=c, width=2)
    d.polygon([(101, 7), (103, 12), (101, 17), (99, 12)], fill=c)  # 指针


def em_arrow(d, c):
    d.line([93, 12, 106, 12], fill=c, width=2)
    d.polygon([(106, 8), (110, 12), (106, 16)], fill=c)            # 箭头
    d.line([93, 12, 97, 8], fill=c, width=2)                       # 羽
    d.line([93, 12, 97, 16], fill=c, width=2)


def em_fish(d, c):
    d.polygon([(93, 12), (99, 7), (106, 9), (106, 15), (99, 17)], fill=c)  # 身
    d.polygon([(106, 9), (110, 6), (110, 18), (106, 15)], fill=c)          # 尾
    d.ellipse([96, 10, 98, 12], fill=(30, 30, 30, 255))                    # 眼


def em_cleaver(d, c):
    d.rectangle([94, 6, 104, 14], fill=c)          # 刀身
    d.line([104, 8, 109, 8], fill=c, width=3)      # 刀柄
    d.ellipse([96, 8, 99, 11], fill=(30, 30, 30, 255))  # 挂孔


def em_hide(d, c):
    d.rounded_rectangle([94, 6, 108, 18], radius=4, outline=c, width=2)
    for xx in (97, 101, 105):
        d.line([xx, 8, xx, 10], fill=c, width=1)   # 缝线
        d.line([xx, 14, xx, 16], fill=c, width=1)


def em_bricks(d, c):
    d.rectangle([93, 6, 109, 18], outline=c, width=2)
    d.line([93, 12, 109, 12], fill=c, width=2)
    d.line([101, 6, 101, 12], fill=c, width=2)
    d.line([97, 12, 97, 18], fill=c, width=2)
    d.line([105, 12, 105, 18], fill=c, width=2)


def em_wool(d, c):
    for cx, cy, r in [(96, 10, 4), (102, 8, 5), (107, 11, 4), (99, 14, 4), (105, 15, 4)]:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)


def em_flower(d, c):
    for dx, dy in [(0, -4), (4, 0), (0, 4), (-4, 0)]:
        d.ellipse([101 + dx - 2, 10 + dy - 2, 101 + dx + 2, 10 + dy + 2], fill=c)
    d.ellipse([99, 8, 103, 12], fill=(240, 190, 60, 255))
    d.line([101, 12, 101, 18], fill=(90, 150, 70, 255), width=2)


# key -> (木色偏移, 招牌色, 招牌边, 徽记函数, 徽记色)
VARIANTS = {
    "farmer":       (tinted(10, 6, -4, (96, 140, 52), (58, 88, 28)), em_wheat, CREAM),
    "toolsmith":    (tinted(-16, -14, -12, (84, 96, 110), (50, 58, 68)), em_hammer, CREAM),
    "weaponsmith":  (tinted(-16, -12, -10, (150, 52, 44), (96, 32, 26)), em_sword, CREAM),
    "armorer":      (tinted(-10, -8, -4, (70, 100, 140), (42, 62, 88)), em_shield, CREAM),
    "cleric":       (tinted(16, 14, 10, (128, 84, 168), (80, 50, 108)), em_flask, CREAM),
    "librarian":    (tinted(14, -4, -8, (140, 44, 52), (88, 26, 32)), em_book, CREAM),
    "cartographer": (tinted(18, 14, 6, (206, 186, 128), (140, 122, 78)), em_compass, DARK),
    "fletcher":     (tinted(2, 4, -6, (110, 120, 50), (66, 74, 28)), em_arrow, CREAM),
    "fisherman":    (tinted(2, 2, 6, (44, 120, 128), (24, 72, 78)), em_fish, CREAM),
    "butcher":      (tinted(8, 0, -4, (168, 60, 52), (106, 36, 30)), em_cleaver, CREAM),
    "leatherworker": (tinted(10, 2, -6, (170, 120, 70), (110, 76, 42)), em_hide, DARK),
    "mason":        (tinted(-6, -4, 0, (110, 110, 110), (70, 70, 70)), em_bricks, CREAM),
    "shepherd":     (tinted(12, 10, 4, (228, 222, 206), (160, 152, 132)), em_wool, (120, 116, 104, 255)),
    "nitwit":       (GENERIC, em_flower, DARK),
}


def draw_bg(pal, emblem=None, emblem_color=CREAM):
    img = Image.new("RGBA", (IMG, IMG), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rgb = {k: v + (255,) if len(v) == 3 else v for k, v in pal.items()}
    # 整块面板
    d.rectangle([0, 0, PANEL_W - 1, IMG - 1], fill=rgb["BASE"])
    # 木板横纹（每 32 image px 一块板）
    for y in range(0, IMG, 32):
        if (y // 32) % 2 == 1:
            d.rectangle([0, y, PANEL_W - 1, min(y + 31, IMG - 1)], fill=rgb["BASE_ALT"])
        d.line([0, y, PANEL_W - 1, y], fill=rgb["SEAM"], width=2)
    # 外框
    for i in range(4):
        d.rectangle([i, i, PANEL_W - 1 - i, IMG - 1 - i], outline=rgb["FRAME"])
    d.rectangle([5, 5, PANEL_W - 6, IMG - 6], outline=rgb["FRAME_HI"])
    # 货架区（商店格子区：screen y 18..126 -> image 21..145；x 8..168 -> 9..194）
    d.rectangle([9, 21, 194, 145], fill=rgb["FIELD"])
    d.rectangle([9, 21, 194, 145], outline=rgb["SEAM"])
    # 玩家背包区（screen y 139..219 -> image 160..252）
    d.rectangle([9, 160, 194, 252], fill=rgb["FIELD"])
    d.rectangle([9, 160, 194, 252], outline=rgb["SEAM"])
    # 柜台横梁（两区分隔：screen y ~130 -> image 150）
    d.rectangle([0, 147, PANEL_W - 1, 154], fill=rgb["FRAME"])
    d.line([0, 147, PANEL_W - 1, 147], fill=rgb["FRAME_HI"])
    # 顶部招牌（screen y 4..16 -> image 5..18，居中 x 38..138 -> 44..159）：职业色 + 徽记
    d.rectangle([44, 5, 159, 19], fill=rgb["SIGN"])
    d.rectangle([44, 5, 159, 19], outline=rgb["SIGN_EDGE"])
    if emblem is not None:
        emblem(d, emblem_color + (255,) if len(emblem_color) == 3 else emblem_color)
    # 四角钉子
    for cx, cy in [(10, 10), (PANEL_W - 11, 10), (10, IMG - 11), (PANEL_W - 11, IMG - 11)]:
        d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(40, 28, 14, 255))
    return img


def main():
    draw_bg(GENERIC).save(os.path.join(OUT_DIR, "mcai_shop_bg.png"))
    for key, (pal, emblem, color) in VARIANTS.items():
        draw_bg(pal, emblem, color).save(os.path.join(OUT_DIR, f"mcai_shop_bg_{key}.png"))
    print(f"backgrounds: 1 generic + {len(VARIANTS)} variants -> {OUT_DIR}")
    # 总览图（人工检查用）
    keys = ["generic"] + list(VARIANTS.keys())
    imgs = [Image.open(os.path.join(OUT_DIR, "mcai_shop_bg.png" if k == "generic"
                                    else f"mcai_shop_bg_{k}.png")).resize((128, 128))
            for k in keys]
    cols = 5
    rows = (len(imgs) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 128, rows * 128), (30, 30, 30, 255))
    for i, im in enumerate(imgs):
        sheet.paste(im, ((i % cols) * 128, (i // cols) * 128))
    sheet.save("/tmp/bg-sheet.png")
    print("sheet -> /tmp/bg-sheet.png (order: generic, " + ", ".join(VARIANTS.keys()) + ")")


if __name__ == "__main__":
    main()
