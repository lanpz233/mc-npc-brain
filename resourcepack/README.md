# MCAI-Shop 资源包（商店皮肤）

村民商店界面的木柜台背景图（交易系统路线二的皮肤部分）。

## 里面是什么

- `assets/minecraft/textures/gui/mcai_shop_bg.png`：商店背景（木柜台）
- `assets/minecraft/font/mcai_shop.json`：独立字体，只含两个字形——负空间（对齐用）
  与背景图。插件商店标题引用这个字体把背景画上去；不动游戏默认字体，
  聊天、告示牌等其他文字都不受影响。

## 怎么用

1. 先在**自己的游戏客户端**里试：把 `MCAI-Shop.zip` 放进游戏目录的
   `resourcepacks` 文件夹，进游戏在「选项 → 资源包」里启用它。
2. 服务器 `plugins/MC-AI/config.yml` 里把 `shop.resource-pack-skin` 改成 `true`，
   重启服务器（或 `/ai reload` 后重开商店）。
3. 进游戏跟村民开店：窗口应变成木柜台样子。位置若有偏差（上下左右差几格），
   截图反馈，按图调 `mcai_shop.json` 的 `ascent` 与负空间数值即可，不用改插件。
4. 全服玩家都要这个效果时：把 zip 放到一个直链地址，在服务器 `server.properties`
   里填 `resource-pack=<直链>` 与 `resource-pack-sha1=<zip 的 sha1>`，
   玩家进服时会自动下载；插件那边的开关保持 `true`。

注意：没装这个包的客户端打开商店时标题会显示方框字——所以开关默认关，
确认包到位了再开。
