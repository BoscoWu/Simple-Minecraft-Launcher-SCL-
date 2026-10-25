# 🚀 极简 MC 启动器 (Simple Minecraft Launcher - Java版)

一个基于 **Java 21 + JavaFX** 编写的轻量级、现代化 Minecraft 启动器。

> 注：本项目的前身为 Python 版本，现已全面重构为 Java 版本，拥有更好的跨平台兼容性和更快的下载速度。

## ✨ 核心特性

- **极速下载**：采用基于 CPU 核心数的多线程并发下载，支持 **BMCLAPI 镜像源** 和 **Mojang 官方源** 无缝切换。
- **多版本支持**：支持原版（Vanilla）以及 Forge、NeoForge、Fabric、Quilt、OptiFine 等主流模组加载器的安装。
- **账号管理**：支持微软正版（设备代码流）登录，以及自定义离线用户名登录（自动匹配皮肤站头像）。
- **资源中心**：集成 Modrinth API，一键搜索并下载热门模组、整合包、光影包、资源包、数据包和世界存档。
- **服务端管理**：支持 Paper、Purpur、Vanilla、Fabric、Forge 服务端的一键下载与控制台命令交互。
- **界面友好**：现代化的左右分栏设计，左侧状态栏直观显示当前状态，右侧功能区域清晰明了。

## 🛠️ 技术栈

- **语言**: Java 21
- **UI 框架**: JavaFX 21
- **网络**: Java 11+ `HttpClient` (支持高并发)
- **JSON 处理**: Jackson
- **构建工具**: Maven
- **CI/CD**: GitHub Actions (自动打包发布)

## 📦 如何运行

### 方式一：直接下载（普通玩家推荐）
前往本仓库的 [Releases](https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-/releases) 页面，根据你的操作系统下载对应的文件：
- **Windows**: 下载 `SimpleMCL-Windows.zip`，解压后双击 `SimpleMCL.exe` 运行。
- **macOS**: 下载 `SimpleMCL-3.1.0.dmg`，打开后将 `SimpleMCL.app` 拖入 Applications 文件夹（由于未签名，首次打开可能需在系统设置中允许运行）。
- **Linux**: 下载 `simplemcl_3.1.0_amd64.deb`，使用 `sudo dpkg -i` 安装即可运行。

*以上包均已自带 Java 运行环境，无需单独安装 Java。*

### 方式二：从源码运行（开发者）
确保你已安装 **JDK 21** 和 **Maven**，然后在项目根目录执行：

```bash
mvn clean javafx:run