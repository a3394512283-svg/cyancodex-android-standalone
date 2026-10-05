# CyanCodex 沧蓝工坊 · 开源代码

本目录收录 CyanCodex 的**用户端（安卓客户端 / 网页端）源码**与**指令生成器算法**。
**不含服务端后端代码**（数据库、接口路由、鉴权、验证码、邮件短信、AI 代理），
**也不含管理端 / 审核端 / 压测工具**（网页管理端与安卓管理端、审核端、压测端均不在本包内）。

- 源码快照：App v1.0.90（versionCode 10090）
- 许可：**GNU GPLv3.0**（全文见 `LICENSE`）；应用名称、图标、界面设计等标识归沧蓝工坊工作室所有
- 官网：https://cyancodex.cn/　·　源码站：https://git.cyancodex.cn/
- 已做脱敏：**官方高防服务器 IP 已从源码中移除**（见下面「脱敏说明」）

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `android/` | **安卓用户端工程源码**（只 `:app` 一个模块）：`app/src/`（Kotlin 源码 + 资源 + `AndroidManifest.xml`）、`app/build.gradle.kts`、`app/proguard-rules.pro`、根 `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml` 与 Gradle Wrapper |
| `web/` | 网页版用户端前端：`index.html`、`app.js`、`api.js`、`generators.js`、`icons.js`、`style.css` |
| `web/dev/` | 开发者平台前端：开放 API 文档、在线调试、插件开发教学（`docs.js` / `dev.js` / `dev.css`） |
| `fabric-mod/` | Java 版 Fabric 模组源码：`legacy/`（1.20.1–1.21.8）、`modern/`（26.2）、`shared/`（API 客户端、配置、语言文件、图标） |
| `official-site/` | 官网前端：首页、使用教程、隐私政策、服务条款与静态资源 |
| `plugin-sdk/` | 插件开发 SDK：接口桩 `stub/`、内置背景插件、教科书插件、插件工坊运行时、打包脚本 |
| `server/lib/generators.js`、`generators-java.js`、`server/test-generators*.js` | 生成器算法与回归测试（**服务端只发布这 4 个文件**：纯字符串算法，不连数据库、不含任何接口/鉴权逻辑） |

> 用户端生成器在 `android/app/src/main/java/com/canglan/cyancodex/generators/Generators.kt`，
> 五子棋 AI 在同一个目录的 `GomokuEngine.kt`。

## 不包含

- **服务端**：数据库脚本与表结构、接口路由、中间件、鉴权 / 令牌 / 验证码、邮件短信、AI 代理与模型配置
- **管理端 / 审核端 / 压测端**：网页管理端（`admin-web/`）、安卓管理端（`:admin`）、审核端（`:reviewer`）、压测工具（`:stresstool`）的源码一律不发布
- **签名与口令**：`*.keystore`、`keystore.properties`、`local.properties`
  （要打 Release 包请自己生成签名并在 `android/keystore.properties` 填 `storePassword` / `keyPassword`）
- **官方高防服务器 IP**：已脱敏移除（见下）

## 脱敏说明（重要）

官方 App 采用「域名 + 内置高防 IP 直连」方案，服务器 IP 原本以 **XOR+Base64 混淆**内置在
`android/app/src/main/java/com/canglan/cyancodex/data/Session.kt`：

- 开源版本**已移除官方高防 IP**（`SERVER_IP_ENC = ""`），整份源码里不存在可还原的官方 IP；
- API 域名常量保留（该域名本身是公开信息，App / 模组 / 网页版都在用）；
- 想接入自己的服务器：用文件里新增的 `encode()` 把地址处理成 XOR+Base64，填进
  `SERVER_ENC`（API 基址）与 `SERVER_IP_ENC`（可选；留空 = 不做直连，完全走系统 DNS）；
- `android/settings.gradle.kts` 已同步改为只 `include(":app")`，拿到源码即可直接 `gradle sync`。

## 本地运行网页端

`web/` 是纯静态页面，双击 `web/index.html` 就能打开界面。需要真实数据（指令库、AI、账号）时，
把前端指向你自己的后端 —— 接口封装在 `web/api.js`，默认使用同源相对路径 `''`，
由你自己的反向代理把 `/api` 转到后端即可。

## 生成器覆盖

| 生成器 | 说明 |
| --- | --- |
| T显 / 计分板 | `mode=sidebar` 输出侧边栏计分板（含每位玩家分数）；`mode=tdisplay` 输出 T显字幕（引用计分项实时显示） |
| 穷举生成 | 模板里的 `{变量}` 做笛卡尔积批量展开，一次可生成成百上千条指令 |
| 滚动字幕 | `mode=roll` 轮播公告（命令方块节拍器 + `titleraw`）；`mode=banner` 逐字盔甲架横幅 |
| 音乐 | 音符网格 → `playsound note.<乐器>` 命令方块序列（含循环/链式/条件注解） |
| T显 JSON | `rawtext` 六种元素：文字、翻译、选择器、计分板、顺序拼接、原始 JSON |

**基岩版（Bedrock）要点**：计分项只有 `dummy` 一种；标题必须用 `/titleraw` + `rawtext`（`/title` 的 JSON 写法不生效）；
音效为 `playsound note.<乐器>`，音高 `0.5 * 2^(n/12)`，音效名不带 `minecraft:` 前缀。

**Java 版（1.13+）要点**：标题用 `/title <目标> <title|subtitle|actionbar> <文本组件>`
（单元素直接输出，多元素用 `{"text":"","extra":[...]}`）；音乐用 `playsound minecraft:block.note_block.<乐器> master`，
音高 `2^((note-12)/12)`（note 0–24 → 0.5–2.0）；物品 ID 自动补 `minecraft:` 命名空间；
逐字横幅用 `summon minecraft:armor_stand … {CustomName:'{"text":"字"}',CustomNameVisible:1b}`。

## 构建

- **安卓用户端**：用 Android Studio 打开 `android/`，或命令行 `./gradlew :app:assembleRelease`
  （AGP 9.3.1 / Kotlin 2.2.10 / Gradle 9.5，`compileSdk 36`、`minSdk 26`；Release 需要自己的签名配置）
- **Fabric 模组**：`fabric-mod/legacy`（1.20.1–1.21.8）与 `fabric-mod/modern`（26.2）各自执行 `./gradlew build`
- **插件**：见 `plugin-sdk/`，实现 `com.canglan.cyancodex.plugin.CyanPlugin`
  （可选 `BackgroundProvider` 自定义主页背景），用 `build-plugins.py` / `build-plugins.ps1` 打包成
  JAR（`classes.dex` + `META-INF/services/com.canglan.cyancodex.plugin.CyanPlugin`），
  放进 App 插件目录后在「优化设置 → 插件」重新加载并开启开关

## 许可

本项目源代码以 **GNU General Public License v3.0** 发布，全文见 `LICENSE`。
转载、修改、再分发请保留版权声明与许可文本；应用名称、图标、界面设计等标识归沧蓝工坊工作室所有。
