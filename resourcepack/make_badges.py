#!/usr/bin/env python3
"""职业徽章生成（素材阶段 5）：从各职业柜台背景图上裁下「招牌 + 徽记」，
缩成聊天里用的小徽章（字体字形 \ue120-\ue12e，font/mcai_shop.json 引用）。

为什么裁现成的：徽章和柜台招牌长得一模一样，玩家在店里见过什么招牌，
聊天框里名字前面就是什么徽章，一眼对得上号，不用再学一套新图。

用法: python3 make_badges.py（先跑过 make_bg.py 生成柜台图）
"""
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
GUI_DIR = os.path.join(HERE, "MCAI-Shop", "assets", "minecraft", "textures", "gui")

# 与 ShopGui.bgGlyph / make_bg.VARIANTS 的顺序一致：通用 + 14 职业
KEYS = ["generic", "farmer", "toolsmith", "weaponsmith", "armorer", "cleric",
        "librarian", "cartographer", "fletcher", "fisherman", "butcher",
        "leatherworker", "mason", "shepherd", "nitwit"]

# 招牌在柜台图上的范围：x 44..159、y 5..19，徽记居中 cx=101 —— 裁徽记周围一小块
CROP = (86, 2, 117, 23)  # 31x21


def main():
    for key in KEYS:
        src_name = "mcai_shop_bg.png" if key == "generic" else f"mcai_shop_bg_{key}.png"
        img = Image.open(os.path.join(GUI_DIR, src_name)).convert("RGBA")
        badge = img.crop(CROP).resize((62, 42), Image.LANCZOS)
        canvas = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        canvas.paste(badge, (1, 11), badge)
        out = os.path.join(GUI_DIR, f"badge_{key}.png")
        canvas.save(out)
    print(f"badges: {len(KEYS)} -> {GUI_DIR}")


if __name__ == "__main__":
    main()
