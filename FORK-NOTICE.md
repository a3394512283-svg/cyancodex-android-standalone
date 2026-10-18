# 关于本仓库（二改版说明）

本仓库是 **CyanCodex 2.0.10 二改版**的源码，基于官方开源包（v1.0.90 快照，
`https://git.cyancodex.cn/`）继续开发。

本仓库由**沧蓝工坊成员**维护，基于官方开源包继续开发。
**非官方发布**，不代表沧蓝工坊官方立场或背书。原项目著作权归原作者
（沧蓝工坊工作室）所有，二改部分同样以 GPLv3 发布。

## 二改做了什么

### 桌面端（新增，不在上游范围内）

`cyancodex-desktop/` 是本仓库新增的 Windows 客户端，上游没有这个端：

- Electron 壳：本地 HTTP 服务 + contextBridge 白名单（14 个 IPC 通道）
- 本地数据：`%APPDATA%\CyanCodex\data\*.json`，原子写 + 可备份可恢复
- 桌面集成：托盘、原生通知、开机自启、关闭最小化、拖拽导入、Ctrl+S 导出
- CLI：`cyancodex gen|lib|ai`，同一 exe 当 node 用
- 界面按 App 的 6 个导航重做（首页 / 指令库 / 广场 / 工具箱 / AI / 我的），
  图标取 Material Icons 官方 SVG，与 App 的 `Icons.Default.*` 同名同形

### 安卓端（在上游基础上修改）

- **版本**：`2.0.0 → 2.0.10`，`versionCode` 每次发版 +1
- **应用内更新页**（`ui/screens/UpdateScreen.kt`）：入口「关于 → 检查更新」，
  下载进度用 `MutableStateFlow` 驱动，胶囊按钮本身当进度条
- **免费 AI 通道**：接入自建服务端的 `/api/v1/ai/*`（流式 SSE），
  未登录时引导登录，另给「配置自定义 AI」的出路
- **免费搜索**：两个 provider（tinyfish / deepseek），可在服务端切换
- **指令库**：内置 125 条基岩版指令，支持搜索 / 分类 / 收藏
- **云同步**：`/api/v1/convs` 增量同步，冲突策略 last-write-wins by `updated_at`
- **网页版**（`web/`）：与桌面版共用一套前端，`desktop.js` 只在桌面壳注入
- 若干 UI 细节：去掉更新页多余的 `statusBarsPadding`、底部白底板、
  首页模块卡片配色对齐 `homeModules` 的 tint

### 移除的内容

- 原包的「官方高防服务器 IP」在上游开源时已移除，本仓库同样不含
- 本仓库**不含**：签名密钥（`cyancodex.keystore` / `keystore.properties`）、
  服务端后端代码、管理端 / 审核端 / 压测工具

## 自己构建

### 安卓端

```bash
cd android
# 1. 复制模板并填入你自己的签名信息
cp keystore.properties.example keystore.properties   # 若不存在则自建
# storeFile=../your.keystore
# storePassword=***
# keyAlias=***
# keyPassword=***

# 2. 构建
./gradlew assembleRelease
```

**不要**使用本仓库原作者的任何签名文件 —— 本仓库不含它们，你也应该用自己的。

### 桌面端

```bash
cd cyancodex-desktop
npm install
npm start
```

## 许可与义务

GPLv3。分发本项目的修改版本时，必须同样开源并提供完整源码。
应用名称、图标、界面设计等标识归沧蓝工坊工作室所有，二改版不应暗示官方背书。

## 免责声明

本项目由沧蓝工坊成员维护，但**不是官方发布**。使用本软件产生的任何问题（账号、积分、游戏内效果等）
由使用者自行承担，与原作者及本项目维护者无关。
