# 极简 MC 启动器 · Simple MC Launcher

免费、开源、无广告的 Minecraft 启动器（JavaFX 版）。

一键安装原版与 Fabric / Forge / Quilt / NeoForge，从 Modrinth 查找模组与整合包，
拖拽即可导入，内置服务器面板，在一个地方完成下载、更新与备份。

## 下载

每个平台**一个安装文件**，装好后从菜单/启动台运行，不用管内部目录。
**已内置 Java 21 运行时，无需另外安装 Java。**

| 平台 | 文件 | 安装方式 |
|------|------|----------|
| Windows | `SimpleMCLauncher-*-windows.exe` | 双击安装 |
| macOS | `SimpleMCLauncher-*-macos.dmg` | 打开后拖进 Applications |
| Linux | `SimpleMCLauncher-*-linux.deb` | `sudo dpkg -i` |

到 [Releases](https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-/releases) 页面下载对应版本。

### 安装

- **Windows**：双击 `.exe`，按向导安装
  - 若提示「Windows 已保护你的电脑」，点「更多信息」→「仍要运行」
- **macOS**：打开 `.dmg`，把启动器拖进 Applications
  - 首次打开若提示「无法验证开发者」，右键点击 → 打开
- **Linux**：
  ```bash
  sudo dpkg -i SimpleMCLauncher-*-linux.deb
  # 若提示依赖缺失：
  sudo apt-get install -f
  ```
  装好后可在应用菜单找到，或命令行运行 `SimpleMCLauncher`

### 数据目录

配置和游戏数据都在用户目录下：

| 平台 | 位置 |
|------|------|
| Windows | `%APPDATA%\SimpleMCLauncher\pyLauncher` |
| macOS | `~/Library/Application Support/SimpleMCLauncher/pyLauncher` |
| Linux | `~/.local/share/SimpleMCLauncher/pyLauncher` |

```
pyLauncher/
├── launcher_config.json   配置
├── launcher.log           日志
├── .minecraft/            游戏目录（版本、模组、存档）
├── servers/               服务端
└── java/                  下载的 Java
```

> **便携用法**：把程序放在可写目录下运行时，数据会存到程序旁边的
> `pyLauncher/`，可以连同程序一起拷到 U 盘。

## 功能

### 版本与加载器
- 923 个官方版本的完整清单，分「正式版 / 预览版 / 远古版 / 愚人节版」四组折叠展示
- 愚人节版按 **4 月 1 日发布日期**自动归集（15w14a、1.RV-Pre1、3D Shareware v1.34、20w14infinite、21w13a、22w13oneblockatatime、23w13a_or_b、24w14potato、25w14craftmine、26w14a）
- Forge / NeoForge / Fabric / Quilt 各自列出真实可用版本
- 装好加载器后实例自动带可读名称，如 `1.21.1 - Fabric 0.20.1`

### 内容管理
- Modrinth 集成：模组、整合包、数据包、资源包、光影、世界、收藏夹
- **版本选择页**：点下载后先选目标版本，当前适配的版本排最前并标「推荐」（原版优先）
- **按类型装到对应目录**（版本隔离）：
  - 模组 → `versions/<版本>/mods/`
  - 资源包 → `versions/<版本>/resourcepacks/`
  - 光影包 → `versions/<版本>/shaderpacks/`
  - 数据包 → 弹窗选世界 → `versions/<版本>/saves/<世界>/datapacks/`
  - 整合包 → 安装为独立实例
- 整合包导入：支持 `.mrpack` 与 `.zip`，**拖到窗口任意位置**即可导入
- 压缩包内自带 Forge/NeoForge 安装器时会自动识别并执行

### 目录自检
- 启动时检查 `versions` / `libraries` / `assets` / `mods` / `resourcepacks` /
  `shaderpacks` / `saves` / `screenshots` / `config` / `logs`
- 缺失的目录自动补建（只建空目录，不动已有内容）
- 关键目录缺失时提示可到回收站还原

### 下载
- 多线程分片下载，并发数可在设置里调整（1-32）
- 官方 CDN 优先，BMCLAPI 镜像自动回退
- HTML 响应识别与拒绝、SHA1 校验、断点续传
- 失败自动重试（TLS 握手失败 / 超时 / 429 限流）
- 支持 HTTP / SOCKS5 代理，会一并传给外部安装器

### 实例管理
- 左侧文件夹列表 + 右侧可搜索列表
- 收藏、打开目录、删除、实例设置

### 服务器
- Java 版服务端下载（原版 / Paper / Fabric）
- 控制台实时日志与命令发送
- EULA 确认、干净停止（含孤儿进程清理）

### 其他
- Microsoft 正版登录（设备代码流）与离线模式
- 离线玩家名直接编辑，回车保存
- Java 自动检测与下载安装（Adoptium）
- 游戏语言自动设为简体中文
- Windows 10 直角界面 / Windows 11、macOS、Linux 圆角界面
- 全文中文界面

### 多语言混编
- Java 负责界面与下载流程
- Python 负责版本分类（愚人节判定）与 WebP 图标解码
- 未安装 Python 时自动降级到纯 Java 实现，功能不受影响

## 从源码运行

需要 JDK 21。

```bash
./mvnw compile
```

Windows 下双击 `启动.cmd`；或手动：

```powershell
.\launch.ps1
```

## 构建安装包

需要 JDK 21（含 `jpackage`）。

```bash
./build-native.sh
```

依赖：

- **Windows** 需要 [WiX Toolset](https://wixtoolset.org/) 3.x（生成 `.exe`）
- **Linux** 需要 `fakeroot`（生成 `.deb`）
- **macOS** 无需额外依赖（生成 `.dmg`）

产物在 `target/dist/`：

| 平台 | 产物 |
|------|------|
| Windows | `SimpleMCLauncher-<ver>-windows.exe` |
| macOS | `SimpleMCLauncher-<ver>-macos.dmg` |
| Linux | `SimpleMCLauncher-<ver>-linux.deb` |

内置的运行时模块包含 `java.net.http`（网络）和 `jdk.crypto.ec`（TLS），
少任一个都会导致启动器无法联网。

版本号自动从 `pom.xml` 读取，不用手动改多处。

推送到 `v*` tag 会触发 GitHub Actions 在三个平台各自构建并发布到 Release。

## 目录结构

```
src/main/java/com/simplemc/launcher/
├── LauncherApp.java          程序入口
├── core/
│   ├── VersionManager.java   版本清单
│   ├── VanillaInstaller.java 原版下载安装
│   ├── LoaderInstaller.java  Fabric/Forge/NeoForge/Quilt
│   ├── GameLauncher.java     启动游戏（合并 inheritsFrom）
│   ├── InstanceManager.java  实例与整合包导入
│   ├── InstancePaths.java    实例内容目录（版本隔离）
│   ├── DirRepair.java        目录完整性自检
│   ├── JavaManager.java      Java 检测与安装
│   ├── AccountManager.java   正版/离线账户
│   ├── Modrinth.java         Modrinth API
│   ├── ServerManager.java    服务端管理
│   └── UpdateManager.java    自动更新
├── ui/
│   ├── MainWindow.java       主窗口与全局拖拽
│   ├── Theme.java            主题与样式
│   ├── SplashScreen.java     启动闪屏
│   └── page/
│       ├── HomePage.java     启动页
│       ├── DownloadPage.java 下载页
│       ├── VersionPicker.java 版本选择页
│       ├── ServerPage.java   服务器页
│       └── SettingsPage.java 设置页
└── util/
    ├── Config.java           配置（损坏自动修复）
    ├── Downloader.java       多线程下载
    ├── Http.java             HTTP 与镜像
    ├── ProxyUtil.java        代理设置
    ├── PyBridge.java         Python 桥（混编）
    ├── Win11Corners.java     Windows 窗口圆角
    ├── Log.java              日志
    └── Constants.java        常量与平台检测

src_lib/com/fasterxml/jackson/databind/
                              轻量 JSON 实现（替代 Jackson，省约 1.9MB）

src/main/resources/py/
    scl_helper.py             Python 侧：版本分类 + WebP 解码
```

数据目录为程序同级的 `pyLauncher/`，游戏目录为 `pyLauncher/.minecraft`。
与 Python 版共享同一数据格式，可直接迁移。

## 许可证

LGPL-2.1
