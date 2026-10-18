# ⚡ 极简 MC 启动器 (Simple Minecraft Launcher)

一个功能全面的 Minecraft 启动器，支持游戏下载、模组管理、服务器开服、内网穿透、光影安装、微软登录等。界面基于 CustomTkinter，现代深色风格，开箱即用。

## ✨ 功能特性

- 🎮 **游戏管理**：一键下载原版、Fabric、Forge、NeoForge、Quilt 等版本，自动配置 Java 运行环境
- 📦 **模组管理**：从 Modrinth 搜索并安装模组，支持禁用/启用、检查更新
- 🖥️ **服务器开服**：支持 Paper / Purpur / 原版(Vanilla) / Fabric / Forge 服务端一键下载与启动，内置控制台
- 🌐 **内网穿透**：集成 frp 客户端，一键配置并启动穿透
- 🎨 **光影包下载**：搜索并下载光影包到当前版本
- 🔐 **微软登录**：支持正版微软账号设备代码流登录，自动刷新令牌
- 🧩 **整合包导入**：支持导入 .mrpack 整合包（Modrinth 格式，自动下载模组与加载器）
- ☕ **Java 管理**：自动下载所需 Java 运行时，或检测系统已安装的 Java
- 🤖 **AI 助手（可选）**：调用 DeepSeek API（需自行配置 API Key，https://platform.deepseek.com/usage ）
- 🪵 **日志系统**：自动写入 `pyLauncher/launcher.log`，便于排查问题
- 💾 **便携式**：所有数据存储在启动器同目录的 `pyLauncher` 文件夹，可随意移动

## 🚀 快速开始

1. 下载并运行 `SimpleMCLauncher.exe`（无需安装 Python）。
2. 在「版本」页选择加载器与 MC 版本，点击「安装」。
3. 回到「启动」页选择版本，点击「🚀 启动游戏」。

> 源码运行：`pip install -r requirements.txt` 后执行 `python run.py`。

## 📁 目录结构

```
SimpleMCLauncher/
├── SimpleMCLauncher.exe   # 打包好的启动器
├── run.py                 # 程序入口
├── core.py                # 核心逻辑
├── gui.py                 # 图形界面
├── utils.py               # 工具函数（日志/下载/配置）
└── pyLauncher/            # 运行时数据（.minecraft、服务器、Java、配置等）
```

---

### 2026/10/18 更新内容

- Paper 服务器国内网络适配（镜像加速，解决下载超时）

### 2026/10/17 更新内容

- 界面全面重写为 **CustomTkinter** 现代深色界面，移除命令行入口
- 修复 Fabric / Forge / NeoForge / Quilt 模组加载器的安装兼容性问题
- 补全此前未完成的功能：模组安装、光影包、整合包导入、服务器开服、Java 下载、frp 管理
- 新增日志系统（`launcher.log`）
- 下载支持断点续传、超时重试与进度显示
- 配置文件损坏时自动备份并重置，避免程序崩溃

### 2026/5/23 更新内容

修复了已知 bug

### 2026/5/8 更新内容

修复了已知 bug

### 2026/5/5 更新内容

将微软登录部分改为设备代码流

### 2026/5/1 更新内容

允许 DeepSeek 辅助用户进行启动器内置功能
