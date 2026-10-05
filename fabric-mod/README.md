# CyanCodex Fabric 模组（Java 版）

《我的世界》Java 版指令助手：指令库、生成器（输出 **Java 版语法**）、AI 助手、收藏与历史，游戏内按 **K** 打开。

---

## 一、交付的两个包

| 包 | 适用版本 | 大小 | MD5 |
|---|---|---|---|
| `cyancodex-fabric-1.0.0+1.20.1-1.21.8.jar` | **1.20.1 ~ 1.21.8 通用** | 108,140 B | `2a8cf9ac79b3ee67f42c6dfe245373fb` |
| `cyancodex-fabric-1.0.0+26.2.jar` | **26.2**（也可以改一个数字给 26.1/26.1.x 用，见下） | 107,834 B | `8d0243d3467c12d2151ef47433ba973d` |

**为什么是两个而不是一个**：Minecraft **26.1 起官方取消了代码混淆**（Mojang 2025 年公告「Removing Obfuscation in Java Edition」），Fabric 随之停止维护 Yarn。也就是说 26.1 是一道**映射体系的分水岭**——26.x 的包引用的是官方真名，1.21.11 及以前的包引用的是 Intermediary 中间名，两者不可能合成同一个 jar。这是平台限制，不是实现取舍。

**为什么不做 1.14~1.19**：那几代的 GUI/渲染 API 与 1.20+ 完全不同（1.20 才有 `GuiGraphics`，早期是 `MatrixStack` + `Screen#drawString`；26.2 又换成了 `GuiGraphicsExtractor`），一份源码无法同时编译。要覆盖它们只能再写一套界面实现——需要的话可以按版本再排期。

---

## 二、玩家怎么装

1. **Fabric Loader**：用官方安装器给对应版本装一次 → https://fabricmc.net/use/installer/
2. **Fabric API**：对应版本的那一个（1.20.1 用 `0.92.12+1.20.1`；1.21.8 用 `0.136.1+1.21.8`；26.2 用 `0.160.0+26.2`）→ https://modrinth.com/mod/fabric-api/versions
3. **本模组**：按上面表格选包，丢进 `.minecraft/mods/`，启动。

纯客户端模组（`environment: client`），服务端不需要装；执行指令走原版 `/` 通道，装不装模组都能用。

### 首次配置（生成器 / AI 需要）
游戏内按 **K** → **设置** 页签 → 填接口地址与 API 密钥 → 「保存并测试」，底部显示 `今日剩余 xxxx / 2000` 即通了。
配置写在 `.minecraft/config/cyancodex.json`。**指令库是公开接口，不填密钥也能用**。

---

## 三、操作

| 操作 | 说明 |
|---|---|
| **K** | 打开/关闭界面 |
| 点列表行 | 选中该行（1.20.1~1.21.8 版点行=选中；26.2 版点行=直接复制） |
| 复制 | 复制到剪贴板（切回游戏 Ctrl+V 粘进聊天框） |
| 执行 | 以玩家身份直接执行（**需要 OP**） |
| 收藏 / 刷新 / ◀ ▶ | 收藏当前行 / 重新拉取 / 翻屏 |

页签：**指令库**（可搜索）、**生成器**（T显计分板 / 穷举 / 滚动字幕 / 音乐 / T显JSON）、**AI 助手**、**收藏**、**设置**。

生成器输出的是 **Java 版语法**（后端 `edition=java`）：`/title @a title {"text":...}`、`/playsound minecraft:block.note_block.harp master @s ~ ~ ~ 1 1.0000`、`give @p minecraft:apple 64`，而不是基岩版的 `titleraw` / `note.harp`。

---

## 四、工程结构与构建

```
fabric-mod/
├─ shared/      版本无关代码：CyanApi（HTTP 客户端）、CyanConfig（配置/收藏/历史）、CyanLog
├─ legacy/      1.20.1 ~ 1.21.8 分支：入口 + 界面（GuiGraphics 绘制）
├─ modern/      26.2 分支：入口 + 界面（纯原版控件，无自定义绘制）
└─ dist/        历史单版本包（归档）
```

### 构建命令
```bat
:: 通用包（1.20.1 ~ 1.21.8）—— 编译按 1.20.1，依赖范围放宽
cd legacy
set ORG_GRADLE_PROJECT_minecraft_version=1.20.1
set ORG_GRADLE_PROJECT_mc_range=>=1.20.1 <1.21.9
set ORG_GRADLE_PROJECT_artifact_suffix=1.20.1-1.21.8
gradlew.bat build

:: 单个版本的包（比如只想要 1.21.8）
set ORG_GRADLE_PROJECT_minecraft_version=1.21.8
gradlew.bat build

:: 26.2 包
cd ..\modern
gradlew.bat build
```
构建完会自动把 jar 复制到 `fabric-mod/` 根目录（`publishToRoot` 任务）。

**环境要求**：JDK 21（legacy 分支）/ **JDK 25**（26.2 分支，因为 26.2 的类文件是 class file 69.0）+ Gradle 9.5（仓库里带了 wrapper）。

> 注意：PowerShell 下传 `-Pminecraft_version=1.21.1` 会被截断成 `1`，所以上面用环境变量 `ORG_GRADLE_PROJECT_xxx` 传参。

---

## 五、「1.20.1~1.21.8 通用包」是怎么验证的

不是靠感觉，是拿映射表逐条核对（脚本：`.deploy/migrate/check_crossver3.py`）：

1. 反汇编 1.20.1 编译出的 jar，取出它引用到的**所有 Minecraft Intermediary 中间名**（类 16 个 + 成员 5 个）；
2. 下载目标版本的 `intermediary-<版本>-v2` 映射（Fabric 官方 maven），把映射里的**混淆描述符先转成中间名**；
3. 逐个核对：**1.21.1 命中 21/21，1.21.8 命中 21/21，零缺失**。

另外核对了 Fabric API 侧（不走中间名，必须单独查）：`KeyMappingHelper.registerKeyMapping`、`ClientTickEvents.END_CLIENT_TICK`、`ClientModInitializer`、`FabricLoader` 在三个版本的 Fabric API / Loader 里都存在。

**已知未覆盖项**：静态验证能保证「引用的 API 都存在」，但不能替代实机运行——1.21.x 上的实际表现仍需在游戏里点一遍（见 `测试说明.md`）。

### 26.2 分支的 API 适配（下面这些是真机改动，不是猜的）
| 位置 | 1.21.8 及以前 | 26.2 |
|---|---|---|
| 键位注册 | `keybinding.v1.KeyBindingHelper.registerKeyBinding` | `keymapping.v1.KeyMappingHelper.registerKeyMapping` |
| 键位分类 | `new KeyMapping(name, type, key, "分类字符串")` | `new KeyMapping(name, type, key, KeyMapping.Category.register(Identifier))` |
| 打开界面 | `Minecraft#setScreen` | `Minecraft#setScreenAndShow` |
| 当前屏幕 | `Minecraft#screen` 字段 | 字段已不对外暴露（改为按键时直接 open） |
| 渲染 | `render(GuiGraphics,...)` | `extractRenderState(GuiGraphicsExtractor,...)`；26.2 界面**全部改用原版控件**，不做自定义绘制 |
| Java | 17 / 21 | **25** |

---

## 六、待办 / 建议

1. **1.21.x 与 26.2 需要你实机点一遍**（我这边没有可运行的 MC 客户端）——清单见 `测试说明.md`。
2. AI 上游免费额度会限流（429），模组已做一次自动重试；AI 与生成器**共用同一把密钥的每日配额**，量大建议给 AI 拆独立配额。
3. 若要覆盖 1.14 ~ 1.19，需要第三套界面实现（按版本排期）。
