#!/usr/bin/env python3
"""从 Minecraft 1.21.1 客户端 jar 提取物品小图标，供网页配置面板的中文选物器使用。

用法: python3 scripts/extract_item_icons.py <client.jar 路径>
输出: src/main/resources/webpanel/icons/<物品路径>.png（与 item-names-zh.tsv 的物品一一对应）

图标是 Mojang 原版贴图（二进制），不进 git 仓库，由打包流程随 jar 交付；
缺图标时面板自动用汉字牌兜底，不影响功能。提取分三轮：直取贴图 → 按模型
textures 引用解析 → 规律启发式（涂蜡铜/墙/栅栏/虫蛀石等用基底贴图）。
覆盖率（1.21.1）：1421/1479。
"""
import json
import os
import sys
import zipfile

ROOT = os.path.join(os.path.dirname(__file__), "..")
TSV = os.path.join(ROOT, "src/main/resources/item-names-zh.tsv")
OUT = os.path.join(ROOT, "src/main/resources/webpanel/icons")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    jar = zipfile.ZipFile(sys.argv[1])
    names = set(jar.namelist())
    os.makedirs(OUT, exist_ok=True)

    items = []
    for line in open(TSV, encoding="utf-8"):
        if line.startswith("#") or "\t" not in line:
            continue
        mat = line.split("\t", 1)[0].strip()
        if mat != "AIR":
            items.append(mat.lower())

    def tex_bytes(ref: str):
        r = ref.replace("minecraft:", "")
        kind, _, p = r.partition("/")
        cand = f"assets/minecraft/textures/{kind}/{p}.png"
        return jar.read(cand) if cand in names else None

    def model_json(kind, path):
        p = f"assets/minecraft/models/{kind}/{path}.json"
        return json.loads(jar.read(p)) if p in names else None

    def via_model(path):
        for kind in ("item", "block"):
            chain, cur, seen = [], path, set()
            while cur and cur not in seen:
                seen.add(cur)
                m = model_json(kind, cur)
                if m is None:
                    break
                chain.append(m)
                parent = m.get("parent", "")
                cur = parent.replace("minecraft:", "").split("/")[-1] if parent else None
            tex = {}
            for m in reversed(chain):
                tex.update(m.get("textures", {}))
            for key in ("layer0", "all", "side", "top", "end", "front", "texture", "back", "particle"):
                if key in tex:
                    b = tex_bytes(tex[key])
                    if b:
                        return b
            for v in tex.values():
                b = tex_bytes(v)
                if b:
                    return b
        return None

    def tex(kind, p):
        cand = f"assets/minecraft/textures/{kind}/{p}.png"
        return jar.read(cand) if cand in names else None

    done = set()
    for path in items:
        data = None
        for cand in (f"assets/minecraft/textures/item/{path}.png",
                     f"assets/minecraft/textures/block/{path}.png"):
            if cand in names:
                data = jar.read(cand)
                break
        if data is None:
            data = via_model(path)
        if data:
            open(os.path.join(OUT, path + ".png"), "wb").write(data)
            done.add(path)

    for path in [p for p in items if p not in done]:
        cands = []
        if path.startswith("waxed_"):
            cands.append(path[6:])
        if path.endswith("_wall"):
            base = path[:-5]
            cands += [base, base + "_side", base + "s"]
        if path.startswith("infested_"):
            cands.append(path[9:])
        if path.endswith("_fence"):
            base = path[:-6]
            cands += [base + "_planks", base + "s", base.replace("brick", "bricks")]
        if path.startswith("waxed_") and ("_slab" in path or "_stairs" in path):
            rest = path[6:]
            cands += [rest.replace("_slab", "").replace("_stairs", "")]
        cands += [path + "_0", path + "_side", path + "_top"]
        data = None
        for c in cands:
            data = tex("block", c) or tex("item", c)
            if data:
                break
        if data:
            open(os.path.join(OUT, path + ".png"), "wb").write(data)
            done.add(path)

    print(f"items: {len(items)}  icons: {len(done)}  missing: {len(items) - len(done)}")


if __name__ == "__main__":
    main()
