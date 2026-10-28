# 【ZHBO】plugin_BagTest_1.6.4 · 个人仓库插件
> Minecraft **1.12.2**（Spigot / Paper）服务端个人仓库插件 —— 多页仓库、VIP 权限组、拓展器逐页解锁、开锁器解锁格子、体验券限时升级、YAML / MySQL 双存储、全配置化。
![version](https://img.shields.io/badge/version-1.6.4-2ea44f)
![platform](https://img.shields.io/badge/platform-Spigot%20%7C%20Paper%201.12.2-orange)
![java](https://img.shields.io/badge/Java-8-blue)
![config](https://img.shields.io/badge/%E5%85%A8%E9%85%8D%E7%BD%AE%E5%8C%96-YAML-informational)
[![download](https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD-ZHBO--plugin__BagTest__1.6.4.jar-2ea44f?logo=github)](https://github.com/zhaobuobuo114/ZHBO_plugin_BagTest_1.6.4/releases/latest)
> 👤 **作者**：zhaobuobuo ｜ 📧 **联系方式**：1071576270@QQ.COM
---
## 目录
- [简介](#简介)
- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装](#安装)
- [指令与权限](#指令与权限)
- [界面说明](#界面说明)
- [配置文件详解](#配置文件详解)
- [数据存储](#数据存储)
- [编译构建](#编译构建)
- [常见问题](#常见问题)
- [更新日志](#更新日志)
- [插件定制](#插件定制)
---
## 简介
`【ZHBO】plugin_BagTest_1.6.4` 是一套开箱即用的个人仓库系统。玩家输入 `/bag` 打开聊天选项菜单（可直接点击页码按钮进入指定页），或 `/bag open [页码]` 打开仓库界面，把物品存进自己的多页仓库。
仓库支持**权限组**与**逐页解锁**：VIP 组只决定页数上限，实际页数需要消耗**仓库拓展器**一页一页开启；每个仓库页还有**锁定格**，需要**仓库开锁器**逐个解锁。**仓库体验券**可限时体验更高一级的权限组，到期后超出权限的页自动进入只读模式，只能取出不能存入。
所有存储方式、页数、行数、标题、锁定格位置、特殊物品材质、音效粒子、提示文本均可在 `config.yml` 中修改，**不需要改代码**。
## 功能特性
| 功能 | 说明 |
| --- | --- |
| 多页仓库 | 每位玩家独立仓库，页码 `1-100`，每页 `1-6` 行（9~54 格），页数据懒加载 |
| 权限组 | `default / vip1 / vip2 / svip` 全部配置化：页数上限、每页行数、统一标题或逐页标题 |
| 组判定 | 指令绑定优先于权限节点，拥有多个组权限时自动取页数最多的组 |
| 逐页解锁 | 已解锁页数 = 基础 1 页 + 已用拓展器数量，最多到所在组页数上限 |
| 锁定格 | 默认界面下半部分（或自定义槽位），开锁器右键自动解锁下一格，也可在界面内手持开锁器点击解锁 |
| 仓库拓展器 | 右键消耗一个开启新仓库页，默认绑定领取者，每人累计获得上限默认 10 个 |
| 仓库体验券 | 3 天 / 7 天两种，右键临时升级到"基础组的上一级"，可叠加时间；体验期内页面全开、锁定格全解锁 |
| 到期只读 | 体验到期后，超出权限但有物品的页可打开为只读页：只能取出，取空后自动关闭 |
| 防丢防护 | 特殊物品不可丢弃、不可熔炉烧炼、不可被漏斗转移或吸取，死亡保留并自动归还 |
| 来源日志 | 发放/消耗记录写入 `itemlog.txt`，物品 lore 自动追加"来源"与"绑定"行 |
| 视听反馈 | 打开、翻页、开锁、拓展、失败、体验券均有可配置的音效与粒子 |
| 双重存储 | YAML（每位玩家独立文件）或 MySQL（单表 REPLACE INTO），MySQL 失败自动回退 YAML |
| 热重载 | `/bag reload` 手动重载 + 配置文件变更自动检测，存储配置变更时自动切换存储 |
| 实时保存 | 界面每次变动后 2 tick 合并落盘，关服/切换存储时同步保存，防异常回档复制 |
| 管理员功能 | 查看在线/离线玩家仓库、发放特殊物品、VIP 绑定、分组管理、保存数据 |
## 环境要求
- 服务端：**Spigot / Paper 1.12.2**（材质名、音效名、粒子名均为 1.12.2 规范）
- Java：**8** 及以上
- 可选：MySQL 5.x / 8.x（仅在 `storage.type: MYSQL` 时需要，1.12.2 服务端自带驱动）
> 无任何硬前置插件，权限组使用插件自带的 `groups.yml` 绑定或权限节点，不依赖 LuckPerms 等权限插件。
## 安装
1. 到 [Releases](https://github.com/zhaobuobuo114/ZHBO_plugin_BagTest_1.6.4/releases/latest) 下载 `ZHBO-plugin_BagTest_1.6.4.jar`（源工程本地构建产物为 `【ZHBO】plugin_BagTest_1.6.4.jar`，GitHub 附件不支持书名号会自动改写），放入服务端 `plugins/` 目录。
2. 启动服务器，插件会自动生成 `plugins/ZHBO_plugin_BagTest_1.6.4/config.yml`。
3. 按需修改 `config.yml`（存储、权限组、特殊物品、音效粒子、消息等）。
4. 执行 `/bag reload` 重载，或重启服务器。
5. 玩家执行 `/bag` 即可打开选项菜单，点击页码进入仓库。
> **升级提示**：新版本可能新增配置项。升级后请先备份并删除旧 `config.yml`，让插件重新生成，再把自定义内容迁移回去。
> **目录变更**：插件已更名为 `【ZHBO】plugin_BagTest_1.6.4`（插件内部名 `ZHBO_plugin_BagTest_1.6.4`），数据目录由 `plugins/BagTest/` 变为 `plugins/ZHBO_plugin_BagTest_1.6.4/`。升级时请把旧的 `config.yml`、`groups.yml`、`playerdata/`、`itemlog.txt` 整体迁移到新目录。
## 指令与权限
主命令 `/bag`。
| 指令 | 说明 | 权限 |
| --- | --- | --- |
| `/bag` | 显示选项菜单（页码按钮可点击直接进入对应页） | `bagtest.use` |
| `/bag open [页码]` / `/bag <页码>` | 打开自己仓库的指定页，默认第 1 页 | `bagtest.use` |
| `/bag open <玩家> [页码]` | 打开指定玩家仓库，支持离线玩家（按最高组加载） | `bagtest.admin` |
| `/bag vip <玩家> <编号\|组名\|remove>` | 快捷设置玩家 VIP 组，如 `/bag vip Steve 1` = `vip1` 组 | `bagtest.admin` |
| `/bag give <玩家> <unlocker\|expander\|ticket3\|ticket7> [数量]` | 发放开锁器 / 拓展器 / 3 天券 / 7 天券（支持中文别名） | `bagtest.admin` |
| `/bag group add <玩家> <组名>` | 绑定玩家权限组（离线可用，存于 `groups.yml`） | `bagtest.admin` |
| `/bag group remove <玩家>` | 移除玩家自定义分组，恢复权限节点判定 | `bagtest.admin` |
| `/bag group info <玩家>` | 查看玩家当前生效组与来源 | `bagtest.admin` |
| `/bag group list` | 列出全部权限组与页数 | `bagtest.admin` |
| `/bag reload` | 手动热重载配置文件 | `bagtest.admin` |
| `/bag save` | 立即保存全部在线玩家仓库数据 | `bagtest.admin` |
| `/bag info` | 查看插件版本、存储方式、自己的权限组 / 已解锁页数 / 体验券状态 | `bagtest.use` |
| `/bag help` / `/bag menu` | 显示选项菜单 | `bagtest.use` |
| 权限节点 | 说明 | 默认 |
| --- | --- | --- |
| `bagtest.use` | 使用个人仓库基础功能 | 所有人 |
| `bagtest.admin` | 管理指令（重载 / 保存 / 查看他人 / 发物品 / 分组） | OP |
> 权限组节点默认示例：`bagtest.vip1`、`bagtest.vip2`、`bagtest.svip`，可在配置中任意增加。
命令支持 Tab 补全：子命令按权限补全，玩家名、页码、组名、物品类型、发卡数量均可补全。
## 界面说明
**仓库界面（默认 54 格）**
- 普通格：存放物品，每次点击/拖拽后自动同步并实时保存
- 最后一行两端：`上一页` / `下一页` 按钮
  - 可翻页：箭头按钮
  - 下一页在权限内但未解锁：屏障"未解锁"，提示使用仓库拓展器
  - 下一页超出权限：屏障"权限不足"，标注需要的权限组
  - 已到边界：屏障"已是第一页 / 已是最后一页"
- 锁定格：默认界面下半部分（自动避开翻页按钮），显示红色玻璃板占位
  - 手持开锁器右键：自动解锁当前已解锁页中的下一个锁定格
  - 打开界面后手持开锁器点击锁定格：解锁该格
  - 每个开锁器仅解锁一个格子，解锁记录按页保存
- 只读页：体验券到期后，超出当前权限但仍有物品的页标题追加 `[只读]`，只能取出不能存入，取空后自动关闭
- 体验激活中：页标题追加 `权限组剩余: X天X时X分`，全部权限内页面与锁定格临时解锁
**选项菜单（聊天栏）**
- 页码按钮：按已解锁页数动态生成，点击直接执行 `/bag open <页码>`
- 固定条目：来自 `menu.entries`，`admin` 条目仅管理员可见，`suggest` 决定点击填入还是直接执行
## 配置文件详解
配置文件位于 `plugins/ZHBO_plugin_BagTest_1.6.4/config.yml`，全部支持 `&` 颜色代码。
### storage · 存储设置
```yaml
storage:
  type: YAML                 # YAML | MYSQL
  yaml:
    data-folder: playerdata  # 每位玩家一个 <uuid>.yml
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    username: root
    password: "123456"
    table-prefix: bag_       # 实际表名 = 前缀 + player
    use-ssl: false
```
MySQL 连接失败时插件自动回退 YAML 存储，不会导致服务器启动失败。切换存储配置后 `/bag reload` 会保存全部数据再切换连接，**数据不会自动迁移**，请自行导入。
### groups · 权限组（多页仓库核心）
```yaml
groups:
  default:
    pages: 1                       # 页数上限
    rows: 6                        # 每页行数 1-6
    titles:
      1: "&8&l✦ 我的仓库"          # 逐页标题，优先于 title 模板
  vip1:
    permission: "bagtest.vip1"     # 权限节点
    pages: 3
    rows: 6
    title: "&6&l✦ VIP1仓库 &7- &e第{page}页"
```
- `pages`：该组的页数上限（`1-100`），拓展器最多解锁到此数
- `rows`：该组每页行数（`1-6`），每页格数 = 行数 × 9
- `title`：统一标题模板，占位符 `{page}`、`{player}`
- `titles`：按页码单独定义标题，优先级高于 `title`
- 判定优先级：`/bag vip` 指令绑定 → 权限节点 → `default` 兜底
- 玩家降级后超出页数的数据不会删除，保留在存档中，升级后恢复
### bag · 通用设置
```yaml
bag:
  open-cooldown: 0     # 打开仓库冷却秒数，0 = 不限制
  max-pages: 20        # 全服页数硬上限
```
### locked-slots · 锁定格
```yaml
locked-slots:
  enabled: true
  mode: BOTTOM_HALF        # BOTTOM_HALF=界面下半部分 | CUSTOM=手动槽位
  slots: [46, 47, 48, 49, 50, 51, 52]   # CUSTOM 模式生效
  material: STAINED_GLASS_PANE
  data: 14
  name: "&c🔒 锁定格子"
  lore:
    - "&7手持开锁器点击此格解锁"
    - "&8每个开锁器仅能解锁一个格子"
```
> 翻页按钮所在的两格永远不会被锁定格占用。
### items · 特殊物品
```yaml
items:
  unlocker:
    material: IRON_INGOT
    enchanted: true
    bind-owner: false        # 开锁器不绑定，可自由交易
    name: "&e🔑 仓库开锁器"
    lore: ["&7手持右键自动消耗", "&8可解锁一个锁定格子"]
  expander:
    material: EMERALD
    enchanted: true
    bind-owner: true         # 默认绑定领取者，防转移
    name: "&a📦 仓库拓展器"
    lore: ["&7手持右键消耗", "&8开启一个新的仓库页"]
    gain-limit: 10           # 每人累计获得上限，0 = 不限量
  ticket3:
    material: PAPER
    days: 3
    bind-owner: false
    name: "&b🎟 仓库体验券 &7(3天)"
  ticket7:
    material: PAPER
    days: 7
    bind-owner: false
    name: "&d🎟 仓库体验券 &7(7天)"
```
- 物品识别按 **材质 + data + 显示名 + 描述前缀** 精确比对，玩家改名或别的插件物品不会误判
- `give` 发放时自动追加 `&8来源: <发放者> <时间>`，绑定物品额外追加 `&8绑定: <玩家名>`
- 体验券升级路径按页数升序：`default → vip1 → vip2 → svip`，已是最高组时使用不消耗
### effects · 音效与粒子
```yaml
effects:
  sounds:
    enabled: true
    open: "BLOCK_CHEST_OPEN;1.0;1.0"
    page-turn: "ITEM_BOOK_PAGE_TURN;1.0;1.2"
    unlock: "ENTITY_PLAYER_LEVELUP;1.0;1.5"
    expand: "ITEM_TOTEM_USE;1.0;1.0"
    fail: "ENTITY_VILLAGER_NO;1.0;0.8"
    ticket: "ENTITY_ELDER_GUARDIAN_CURSE;1.0;1.5"
  particles:
    enabled: true
    unlock: "TOTEM;40;0.5;0.9;0.5;0.2"
    expand: "FIREWORKS_SPARK;50;0.5;1.0;0.5;0.05"
    ticket: "SPELL_WITCH;50;0.5;1.0;0.5;0.1"
```
- 音效格式：`音效名;音量;音调`，音效名为 1.12.2 `Sound` 枚举名
- 粒子格式：`粒子名;数量;偏移X;偏移Y;偏移Z;速度`
- 留空 `""` 即关闭该场景特效；配置错误自动跳过并在后台警告，不影响运行
### gui · 翻页按钮
```yaml
gui:
  prev-name: "&e◀ 上一页"
  prev-lore: "&7点击翻到第 {page} 页"
  next-name: "&e下一页 ▶"
  next-lore: "&7点击翻到第 {page} 页"
  locked-name: "&c未解锁"
  locked-lore: "&7手持 &e仓库拓展器 &7右键解锁"
  vip-locked-name: "&c权限不足"
  vip-locked-lore: "&7该页需要 &e{group} &7组权限"
```
### menu · 选项菜单
```yaml
menu:
  title: "&7===== &6个人仓库 选项菜单 &7====="
  pages-label: "&7我的仓库页: "
  page-button: "&e[&6{page}&e]"
  hint: "&7提示: &8点击上方选项即可执行"
  entries:
    - text: "&e/bag open [页码] &7- 打开自己的仓库"
      command: "/bag open "
      suggest: true
    - text: "&e/bag reload &7- 手动热重载配置文件"
      command: "/bag reload"
      suggest: false
      admin: true
    - text: "&7作者: &ezhaobuobuo &7| 联系: &e1071576270@QQ.COM"
```
- `command` 为空则不可点击；`suggest: true` 点击填入聊天框，`false` 直接执行
- `admin: true` 的条目仅 `bagtest.admin` 权限者可见；`/bag` 与 `/bag help` 显示同一菜单
### settings · 功能开关
```yaml
settings:
  auto-save-minutes: 10          # 自动保存间隔（分钟），0 = 关闭
  auto-reload: true              # 配置文件变更自动热重载
  check-interval-seconds: 5      # 变更检测间隔（秒）
```
### messages · 消息文本
所有提示集中在 `messages` 下，支持 `&` 颜色代码与占位符：
| 占位符 | 含义 |
| --- | --- |
| `{player}` / `{page}` / `{max}` | 玩家名 / 页码 / 最大已解锁页 |
| `{count}` / `{amount}` / `{item}` | 数量 / 数量 / 物品显示名 |
| `{group}` / `{groups}` / `{source}` | 权限组名 / 全部组名 / 组来源 |
| `{time}` / `{seconds}` / `{rows}` / `{pages}` | 耗时或剩余时间 / 冷却秒数 / 行数 / 页数 |
| `{limit}` / `{current}` | 拓展器上限 / 当前已获得数 |
| `{old}` / `{new}` | 切换存储前后的类型 |
## 数据存储
- 主配置：`plugins/ZHBO_plugin_BagTest_1.6.4/config.yml`
- 分组绑定：`plugins/ZHBO_plugin_BagTest_1.6.4/groups.yml`（玩家 UUID → 组名，原子写入）
- 物品日志：`plugins/ZHBO_plugin_BagTest_1.6.4/itemlog.txt`（发放 / 消耗 / 来源追溯）
- YAML 仓库数据：`plugins/ZHBO_plugin_BagTest_1.6.4/playerdata/<uuid>.yml`
  - 结构：`version`、`name`、`bonus-pages`、`ticket`、`gained-expanders`、`unlocked`、`pages.<页码>.items.<槽位>`
  - 空页不落盘、空槽位不序列化、物品使用 Bukkit 原生对象流 Base64 序列化
  - 自动兼容旧版 v1 单页格式（自动迁移为第 1 页）
- MySQL 仓库数据：表 `前缀 + player`，字段 `uuid / name / data / updated_at`，`data` 存 YAML 字符串
- 保存策略：界面变动后 2 tick 合并实时保存；定时异步保存；关服时同步保存；切换存储前先全量保存
> 备份 / 迁移服务器时，复制 `config.yml`、`groups.yml`、`playerdata/` 与 `itemlog.txt` 即可。
## 编译构建
```bash
git clone <本仓库地址>
cd ZHBO_plugin_BagTest_1.6.4
mvn clean package
```
产物：`target/【ZHBO】plugin_BagTest_1.6.4.jar`
构建说明：
- 使用 **Maven**，JDK 8，目标字节码 1.8。
- 依赖 `spigot-api 1.12.2-R0.1-SNAPSHOT`（`provided`，不打包进 jar），仓库使用 Aikar 聚合仓库。
- `plugin.yml` 开启资源过滤，`version` 自动读取 `pom.xml` 中的 `1.6.4`。
## 常见问题
**Q：升级后玩家仓库数据 / 配置不见了？**
A：插件已更名，数据目录由 `plugins/BagTest/` 变为 `plugins/ZHBO_plugin_BagTest_1.6.4/`。把旧目录下的 `config.yml`、`groups.yml`、`playerdata/`、`itemlog.txt` 整体复制到新目录即可。
**Q：`/bag` 菜单点页码没反应？**
A：页码按钮使用聊天栏点击组件，个别服务端核心不支持时会自动降级为纯文本，改用 `/bag open <页码>` 打开即可。
**Q：拓展器右键提示已达上限？**
A：拓展器受两层限制：所在权限组的 `pages` 页数上限，以及 `items.expander.gain-limit` 每人累计获得上限（默认 10，消耗不减）。
**Q：体验券到期后高权限页的物品拿不出来？**
A：超出当前权限但仍有物品的页会以只读页打开（标题带 `[只读]`），可以从下一页按钮进入并取出物品，取空后该页自动关闭、无法再打开。
**Q：开锁器/拓展器被漏斗吸走或卡住？**
A：插件已拦截丢弃、熔炉烧炼、漏斗转移与漏斗吸取；若地上仍有掉落物，请管理员手动清理并核对 `itemlog.txt`。
**Q：改了配置没生效？**
A：执行 `/bag reload`，或确认 `settings.auto-reload: true`（默认每 5 秒检测）。存储相关配置变更会自动保存全量数据并切换存储。
**Q：MySQL 连不上会不会丢数据？**
A：不会。插件会自动回退到 YAML 存储并在控制台警告，修复数据库配置后执行 `/bag reload` 会切回 MySQL（不会自动迁移已有数据）。
**Q：支持 1.13+ 吗？**
A：本版本面向 1.12.2 编写，材质名、音效名、粒子名均为 1.12.2 规范。如需其他版本适配，见下方定制服务。
## 更新日志
### v1.6.4
- 插件与仓库更名为 `【ZHBO】plugin_BagTest_1.6.4`（插件内部名 `ZHBO_plugin_BagTest_1.6.4`）
- 插件作者信息统一为 `zhaobuobuo`
- 新增联系方式 `1071576270@QQ.COM`（插件元数据、启动日志、`/bag` 菜单、`/bag info`、README）
- 修复死亡保留的特殊物品在"重生后秒退 / 死亡界面直接退出 / 关服残留"场景下丢失的问题
- 修复体验到期后超出权限的只读页无法通过翻页按钮到达的问题
- 修复热重载缩小行数后，历史大页中的锁定格无法用开锁器解锁的问题
- 新增漏斗吸取特殊物品拦截（此前掉落物会被吸入漏斗且无法取出）
- 新增 1.8 旧音效名兼容映射（`CHEST_OPEN` 等自动转换为 1.12.2 枚举）
### v1.6.3
- 防复制：仓库变动时同步保存玩家 `.dat`，关服时两类数据同批落盘
- `give` 发放与死亡归还原物品后立即保存玩家数据
### v1.6.2
- 体验券体验组改为"基础组上一级"动态解析，基础组升降级时体验自动跟随
- 只读页 / 只读格取空状态修复，翻页按钮支持步进跳过空只读页
### v1.6.1
- 开锁器与体验券默认不绑定，可自由交易；拓展器保持绑定
- 体验券使用中可叠加时长，体验期间权限内页面与锁定格全部解锁
### v1.6.0
- 新增仓库体验券（3 天 / 7 天），到期后超权限页进入只读模式
- 新增拓展器每人累计获得上限 `gain-limit`
### v1.5.0
- 新增特殊物品来源日志与 lore 绑定/来源行
- 新增防丢弃、防熔炉、防漏斗转移、死亡保留
- 新增可配置音效与粒子特效
### v1.4.0
- VIP 组改为页数上限，页面需拓展器逐页解锁
- 特殊物品附魔光效，界面翻页按钮状态细化
### v1.3.0
- 新增锁定格、开锁器、拓展器、实时保存
### v1.2.0
- 新增界面翻页按钮、`/bag vip` 快捷分组指令
### v1.1.0
- 新增权限组与 `groups.yml` 指令绑定（`/bag group`）
## 插件定制
> **承接所有版本 Minecraft 服务端插件定制**
> 仓库 / 签到 / 抽奖 / 副本 / 经济 / GUI / 数据统计等均可定制，支持 1.7.10 ~ 最新版本。
>
> **联系方式**
> 作者：`zhaobuobuo`
> 邮箱：`1071576270@QQ.COM`
如果这个插件对你有帮助，欢迎 Star ⭐ 支持一下。
