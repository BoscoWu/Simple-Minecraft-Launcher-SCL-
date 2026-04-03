import requests
import json
import os
import threading
import subprocess
import tkinter as tk
from tkinter import scrolledtext, messagebox, simpledialog, filedialog
import traceback
import xml.etree.ElementTree as ET
import shutil
import webbrowser
from urllib.parse import urlparse, parse_qs
import zipfile
import tempfile
import queue 
import datetime
import time
import re
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import urllib3
import platform
import sys

if getattr(sys, 'frozen', False):
    SCRIPT_DIR = os.path.dirname(sys.executable)
else:
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

PYLAUNCHER_DIR = os.path.join(SCRIPT_DIR, "pyLauncher")
MC_DIR = os.path.join(PYLAUNCHER_DIR, ".minecraft")
SERVER_DIR = os.path.join(PYLAUNCHER_DIR, "servers")
JAVA_DIR = os.path.join(PYLAUNCHER_DIR, "java")
CONFIG_FILE = os.path.join(PYLAUNCHER_DIR, "launcher_config.json")
VERSION_OF_LAUNCHER = "v2.0.1"

os.makedirs(PYLAUNCHER_DIR, exist_ok=True)
os.makedirs(MC_DIR, exist_ok=True)
os.makedirs(SERVER_DIR, exist_ok=True)
os.makedirs(JAVA_DIR, exist_ok=True)
FRP_DIR = os.path.join(PYLAUNCHER_DIR, "frp")
FRPC_CONFIG = os.path.join(FRP_DIR, "frpc.toml")
FRPC_LOG = os.path.join(FRP_DIR, "frpc.log")
FRP_DOWNLOAD_URL = {
    "windows": {
        "amd64": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_windows_amd64.zip",
        "386": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_windows_386.zip"
    },
    "linux": {
        "amd64": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_linux_amd64.tar.gz",
        "arm64": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_linux_arm64.tar.gz"
    }
}

original_get = requests.get

def domestic_get(url, *args, **kwargs):
    try:
        if isinstance(url, str):
            url = url.replace("api.modrinth.com", "api.modrinth.minemacro.com")
            url = url.replace("cdn.modrinth.com", "api.modrinth.minemacro.com")
            url = url.replace("maven.fabricmc.net", "bmclapi2.bangbang93.com/maven")
            url = url.replace("api.adoptium.net", "mirrors.ustc.edu.cn/adoptium")
    except Exception:
        pass
    return original_get(url, *args, **kwargs)

requests.get = domestic_get

session = requests.Session()
retries = Retry(total=5, backoff_factor=1, status_forcelist=[500, 502, 503, 504])
session.mount('http://', HTTPAdapter(max_retries=retries))
session.mount('https://', HTTPAdapter(max_retries=retries))

def get_with_retry(url, *args, **kwargs):
    kwargs.setdefault('timeout', (10, 60))
    return session.get(url, *args, **kwargs)
requests.get = get_with_retry

def check_java():
    java_path = shutil.which("java")
    if java_path is None:
        return False, "未找到 Java，请安装 Java 8 或更高版本，并确保 java 命令可用。"
    try:
        result = subprocess.run(
            ["java", "-version"],
            capture_output=True,
            text=True,
            timeout=10,
            encoding='utf-8',
            errors='replace'
        )
        output = result.stderr.strip() or result.stdout.strip()
        if result.returncode == 0:
            return True, f"Java 可用: {output.splitlines()[0] if output else '版本信息未知'}"
        else:
            return False, f"Java 命令执行失败: 错误码 {result.returncode}"
    except Exception as e:
        return False, f"检查 Java 时发生未知错误: {e}"

import minecraft_launcher_lib
from minecraft_launcher_lib import fabric, forge, quilt, microsoft_account

def safe_fabric_get_latest_installer_version():
    url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml"
    try:
        r = requests.get(url)
        r.raise_for_status()
        root = ET.fromstring(r.text)
        latest_elem = root.find('latest')
        if latest_elem is not None and latest_elem.text:
            return latest_elem.text
        release_elem = root.find('release')
        if release_elem is not None and release_elem.text:
            return release_elem.text
        versions = root.findall('versioning/versions/version')
        if versions:
            return versions[-1].text
        else:
            raise ValueError("无法从 Fabric 安装器元数据中获取版本号")
    except Exception as e:
        raise RuntimeError(f"获取 Fabric 安装器版本失败: {e}") from e

fabric.get_latest_installer_version = safe_fabric_get_latest_installer_version

import minecraft_launcher_lib._helper
from minecraft_launcher_lib._helper import download_file as original_download_file

def patched_download_file(url, path, callback=None, sha1=None, session=None, minecraft_directory=None):
    max_retries = 3
    for attempt in range(max_retries):
        try:
            return original_download_file(url, path, callback, sha1, session, minecraft_directory)
        except (requests.exceptions.ConnectionError,
                urllib3.exceptions.ProtocolError,
                urllib3.exceptions.IncompleteRead) as e:
            if attempt == max_retries - 1:
                raise
            print(f"下载失败，正在重试 ({attempt+1}/{max_retries}): {url}")
            time.sleep(2 ** attempt)
            continue
        except Exception as e:
            if attempt == max_retries - 1:
                raise
            print(f"下载异常，正在重试 ({attempt+1}/{max_retries}): {url} - {e}")
            time.sleep(2)
            continue

minecraft_launcher_lib._helper.download_file = patched_download_file

CLIENT_ID = "8d7c60d0-fddf-464c-a9f4-d190f1daa576"
REDIRECT_URL = "https://login.microsoftonline.com/common/oauth2/nativeclient"

SERVER_SOURCES = {
    "spigot": "https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/paper-{version}-{build}.jar",
    "paper": "https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/paper-{version}-{build}.jar",
    "purpur": "https://api.purpurmc.org/v2/purpur/{version}/latest/download",
    "fabric": "https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/server/jar",
    "forge": "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/{forge}/forge-{forge}-server.jar"
}

JAVA_MIRROR = "https://download.oracle.com/java"

class SimpleMCLauncher:
    def __init__(self, root):
        self.root = root
        self.root.title("极简 MC 启动器 (全能版)")
        self.root.geometry("750x650")
        self.config = self.load_config()
        self.console = scrolledtext.ScrolledText(root, state='disabled', bg='#1e1e1e', fg='#d4d4d4', font=('Consolas', 10))
        self.console.pack(padx=10, pady=10, fill=tk.BOTH, expand=True)
        self.entry = tk.Entry(root, font=('Consolas', 12), bg='#2d2d2d', fg='white', insertbackground='white')
        self.entry.pack(padx=10, pady=(0, 10), fill=tk.X)
        self.entry.bind("<Return>", self.on_enter)
        self.game_process = None
        self.progress_line = None
        self.frp_process = None
        self.server_process = None
        self.server_console_mode = False

        self.log(f"欢迎使用极简 MC 启动器 (全能版)！ 作者BoscoNew")
        self.log(f"启动器版本: {VERSION_OF_LAUNCHER}")
        self.log(f"游戏数据目录: {MC_DIR}")
        self.log(f"服务器目录: {SERVER_DIR}")
        self.log(f"Java 目录: {JAVA_DIR}")
        self.log(f"系统架构：{platform.system().lower()} {platform.machine().lower()}")
        self.log(f"当前玩家: [{self.get_player_display()}] | 当前版本: [{self.config.get('current_version', '未设置')}]\n")

    def show_help(self):
        self.log("📦 可用命令列表:")
        self.log("  install minecraft <version>                 - 下载指定版本游戏")
        self.log("  install <loader> <version>                  - 安装加载器 (fabric/forge/neoforge/quilt)")
        self.log("  install server <type> <version> <max> <min> - 下载服务器 (spigot/paper/purpur/fabric/forge)")
        self.log("  install java-<version>                      - 下载指定版本 Java (如 17,21)")
        self.log("  install <mod1, mod2,...>                    - 批量安装模组（逗号分隔）")
        self.log("  install shaderpack <name>                   - 搜索并下载光影包到当前版本")
        self.log("  import <path>                               - 导入本地整合包 (.zip/.mrpack)")
        self.log("  launch <version>                            - 启动游戏")
        self.log("  login                                       - 登录微软账号")
        self.log("  logout                                      - 退出当前登录")
        self.log("  player-name=<name>                          - 设置离线玩家名")
        self.log("  list loaders                                - 查看支持的加载器")
        self.log("  frp config / start / stop / status          - 管理 frp 内网穿透")
        self.log("  about                                       - 鸣谢人员名单")
        self.log("  clean                                       - 清除控制台信息")
        self.log("  close                                       - 关闭启动器")
        self.log("  list mods                                   - 列出当前版本模组及更新状态")
        self.log("  mod update                                  - 检查模组更新")
        self.log("  mod disable <mod-name>                      - 禁用模组")
        self.log("  mod enable <mod-name>                       - 启用模组")
        self.log("  server console <type> [nogui]               - 启动服务器")
        self.log("  server config <type>                        - 编辑服务器配置文件")
        self.log("  help / h                                    - 显示此列表")
        self.log("-" * 65)

    def get_player_display(self):
        if self.config.get("ms_refresh_token"):
            return self.config.get("ms_username", "已登录用户")
        else:
            return self.config.get("player_name", "Player")

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except Exception:
                pass
        return {"player_name": "Player", "current_version": "", "current_loader": "vanilla",
                "original_version": "", "ms_refresh_token": None, "ms_username": None,
                "java_home": {}}

    def save_config(self):
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            json.dump(self.config, f, indent=2)

    def log(self, message):
        self.root.after(0, self._log_safe, message)

    def _log_safe(self, message):
        self.console.config(state='normal')
        self.console.insert(tk.END, message + "\n")
        self.console.see(tk.END)
        self.console.config(state='disabled')

    def _get_user_choice(self, title, prompt):
        q = queue.Queue()
        def ask():
            choice = simpledialog.askstring(title, prompt, parent=self.root)
            q.put(choice)
        self.root.after(0, ask)
        return q.get()

    def on_enter(self, event):
        cmd = self.entry.get().strip()
        self.entry.delete(0, tk.END)
        if self.server_console_mode and self.server_process and self.server_process.poll() is None:
            if cmd == "exit":
                self.log("退出服务器控制台模式。服务器仍在后台运行。")
                self.server_console_mode = False
            elif cmd == "stop":
                self.log("正在停止服务器...")
                try:
                    self.server_process.stdin.write("stop\n")
                    self.server_process.stdin.flush()
                except Exception as e:
                    self.log(f"发送 stop 命令失败: {e}")
                self.server_console_mode = False
            elif cmd:
                try:
                    self.server_process.stdin.write(cmd + "\n")
                    self.server_process.stdin.flush()
                except Exception as e:
                    self.log(f"发送命令失败: {e}")
            return

        if cmd == "clean":
            self.clean_console()
            return

        if not cmd:
            return
        self.log(f"\n> {cmd}")

        parts = cmd.split()
        if not parts:
            return

        command = parts[0].lower()

        if command == "login":
            threading.Thread(target=self.microsoft_login, daemon=True).start()
        elif command == "logout":
            self.microsoft_logout()

        elif command == "list" and len(parts) > 1:
            if parts[1].lower() == "loaders":
                self.list_loaders()
            elif parts[1].lower() == "mods":
                self.list_mods()
            else:
                self.log("未知列表类型。可用: loaders, mods")

        elif command == "import" and len(parts) > 1:
            file_path = cmd[7:].strip()
            threading.Thread(target=self.import_modpack, args=(file_path,), daemon=True).start()

        elif command.startswith("player-name="):
            name = cmd.split("=", 1)[1].strip()
            if name:
                self.config["player_name"] = name
                self.save_config()
                self.log(f"成功: 离线玩家名称已更新为 [{name}]")

        elif command == "install" and len(parts) > 1:
            subcmd = parts[1].lower()
            if subcmd == "minecraft" and len(parts) > 2:
                version = parts[2]
                threading.Thread(target=self.install_vanilla, args=(version,), daemon=True).start()
            elif subcmd == "server" and len(parts) >= 5:
                server_type = parts[2]
                version = parts[3]
                max_mem = parts[4]
                min_mem = parts[5] if len(parts) > 5 else "1G"
                threading.Thread(target=self.install_server, args=(server_type, version, max_mem, min_mem), daemon=True).start()
            elif subcmd == "frp":
                threading.Thread(target=self.install_frp, daemon=True).start()
            elif subcmd.startswith("java-"):
                version = subcmd[5:]
                threading.Thread(target=self.install_java, args=(version,), daemon=True).start()
            elif subcmd == "shaderpack" and len(parts) > 2:
                shader_name = " ".join(parts[2:])
                threading.Thread(target=self.install_shaderpack, args=(shader_name,), daemon=True).start()
            else:
                loader_map = {
                    "fabric": self.install_fabric,
                    "forge": self.install_forge,
                    "neoforge": self.install_neoforge,
                    "quilt": self.install_quilt
                }
                if subcmd in loader_map and len(parts) > 2:
                    loader = subcmd
                    version = parts[2]
                    threading.Thread(target=loader_map[loader], args=(version,), daemon=True).start()
                else:
                    mod_names = cmd[8:].strip()
                    if ',' in mod_names:
                        mod_list = [m.strip() for m in mod_names.split(',')]
                        threading.Thread(target=self.install_mods_batch, args=(mod_list,), daemon=True).start()
                    else:
                        threading.Thread(target=self.install_mod, args=(mod_names,), daemon=True).start()

        elif command == "mod" and len(parts) > 1:
            subcmd = parts[1].lower()
            if subcmd == "update":
                self.mod_update()
            elif subcmd == "disable" and len(parts) > 2:
                mod_name = parts[2]
                self.mod_disable(mod_name)
            elif subcmd == "enable" and len(parts) > 2:
                mod_name = parts[2]
                self.mod_enable(mod_name)
            else:
                self.log("用法: mod update / disable <mod-name> / enable <mod-name>")

        elif command == "server" and len(parts) > 1:
            subcmd = parts[1].lower()
            if subcmd == "console" and len(parts) > 2:
                server_type = parts[2]
                nogui = len(parts) > 3 and parts[3].lower() == "nogui"
                threading.Thread(target=self.server_console, args=(server_type, nogui), daemon=True).start()
            elif subcmd == "config" and len(parts) > 2:
                server_type = parts[2]
                self.server_config(server_type)
            else:
                self.log("用法: server console <type> [nogui] | server config <type>")

        elif command == "launch" and len(parts) > 1:
            if parts[1].lower() == "server" and len(parts) > 2:
                server_type = parts[2]
                nogui = len(parts) > 3 and parts[3].lower() == "nogui"
                threading.Thread(target=self.launch_server, args=(server_type, nogui), daemon=True).start()
            else:
                version = parts[1]
                threading.Thread(target=self.launch_game, args=(version,), daemon=True).start()

        elif command == "frp" and len(parts) > 1:
            subcmd = parts[1].lower()
            if subcmd == "config":
                threading.Thread(target=self.frp_config, daemon=True).start()
            elif subcmd == "start":
                threading.Thread(target=self.frp_start, daemon=True).start()
            elif subcmd == "stop":
                threading.Thread(target=self.frp_stop, daemon=True).start()
            elif subcmd == "status":
                self.frp_status()
            else:
                self.log("未知 frp 子命令，可用: config / start / stop / status")

        elif command == "help" or command == "h":
            self.show_help()

        elif command == "stop":
            self.stop_game()

        elif command == "about":
            self.about()

        elif command == "close":
            self.close()

        else:
            self.log("未知命令。输入 'help' 查看可用命令。")

    def list_loaders(self):
        self.log("支持的模组加载器:")
        self.log("  • Fabric    - 轻量级、快速更新")
        self.log("  • Forge     - 经典、兼容性广")
        self.log("  • NeoForge  - Forge 的现代分支")
        self.log("  • Quilt     - Fabric 的分支，注重模块化")
        self.log("  • Vanilla   - 原版无加载器")
        self.log("\n安装示例: install fabric 1.21.4")

    def install_vanilla(self, version):
        self.log(f"开始安装原版 Minecraft {version} ...")
        self._install_minecraft_version(version, "vanilla")

    def install_fabric(self, version):
        self.log(f"开始安装 Fabric + Minecraft {version} ...")
        self._install_minecraft_version(version, "fabric")

    def install_forge(self, version):
        self.log(f"开始安装 Forge + Minecraft {version} ...")
        self._install_minecraft_version(version, "forge")

    def install_neoforge(self, version):
        self.log(f"开始安装 NeoForge + Minecraft {version} ...")
        self._install_minecraft_version(version, "neoforge")

    def install_quilt(self, version):
        self.log(f"开始安装 Quilt + Minecraft {version} ...")
        self._install_minecraft_version(version, "quilt")

    def _install_minecraft_version(self, version, loader):
        last_status = {"status": ""}
        def set_status(status):
            if status != last_status["status"]:
                self.log(f"[下载进度] {status}")
                last_status["status"] = status
        callbacks = {
            "setStatus": set_status,
            "setProgress": lambda p: None,
            "setMax": lambda m: None
        }
        try:
            self.log(f"正在下载 Minecraft {version}...")
            minecraft_launcher_lib.install.install_minecraft_version(version, MC_DIR, callback=callbacks)
            if loader != "vanilla":
                java_ok, java_msg = check_java()
                if not java_ok:
                    self.log(f"错误: {java_msg}")
                    self.log("请安装 Java 17 或更高版本")
                    return
                self.log(java_msg)
            if loader == "fabric":
                self._install_fabric_loader(version, callbacks)
            elif loader == "forge":
                self._install_forge_loader(version, callbacks)
            elif loader == "neoforge":
                self._install_neoforge_loader(version, callbacks)
            elif loader == "quilt":
                self._install_quilt_loader(version, callbacks)
            elif loader == "vanilla":
                self.config["current_version"] = version
                self.config["current_loader"] = "vanilla"
                self.config["original_version"] = version
                self.save_config()
                self.log(f"✅ Minecraft {version} 安装完成！")
        except Exception as e:
            self.log(f"安装失败: {e}")
            self.log(traceback.format_exc())

    def _install_fabric_loader(self, version, callbacks):
        self.log("正在安装 Fabric 加载器...")
        try:
            loader_version = minecraft_launcher_lib.fabric.get_stable_loader_version(version)
        except AttributeError:
            loader_version = minecraft_launcher_lib.fabric.get_latest_loader_version()
        expected_folder = f"fabric-loader-{loader_version}-{version}"
        minecraft_launcher_lib.fabric.install_fabric(version, MC_DIR, callback=callbacks)
        src = os.path.join(MC_DIR, "versions", expected_folder)
        dst = os.path.join(MC_DIR, "versions", f"{version}-fabric")
        self._rename_loader_folder(src, dst, version, "fabric")
        self.config["current_version"] = f"{version}-fabric"
        self.config["current_loader"] = "fabric"
        self.config["original_version"] = version
        self.save_config()
        self.log(f"✅ Fabric {version} 安装完成！")

    def _install_forge_loader(self, version, callbacks):
        self.log("正在安装 Forge 加载器...")
        try:
            forge_versions = minecraft_launcher_lib.forge.list_forge_versions()
            forge_version = None
            for fv in forge_versions:
                if fv.startswith(version):
                    forge_version = fv
                    break
            if not forge_version:
                self.log(f"错误: 未找到适用于 Minecraft {version} 的 Forge 版本")
                return
            self.log(f"找到 Forge 版本: {forge_version}")
            minecraft_launcher_lib.forge.install_forge_version(forge_version, MC_DIR, callback=callbacks)
            src = os.path.join(MC_DIR, "versions", forge_version)
            dst = os.path.join(MC_DIR, "versions", f"{version}-forge")
            self._rename_loader_folder(src, dst, version, "forge")
            self.config["current_version"] = f"{version}-forge"
            self.config["current_loader"] = "forge"
            self.config["original_version"] = version
            self.save_config()
            self.log(f"✅ Forge {version} 安装完成！")
        except Exception as e:
            self.log(f"Forge 安装失败: {e}")

    def _install_neoforge_loader(self, version, callbacks):
        self.log("正在安装 NeoForge 加载器...")
        try:
            try:
                neoforge_versions = minecraft_launcher_lib.forge.list_forge_versions()
                neoforge_version = None
                for nf in neoforge_versions:
                    if nf.startswith(version) and "neoforge" in nf.lower():
                        neoforge_version = nf
                        break
                if neoforge_version:
                    minecraft_launcher_lib.forge.install_forge_version(neoforge_version, MC_DIR, callback=callbacks)
                    src = os.path.join(MC_DIR, "versions", neoforge_version)
                    dst = os.path.join(MC_DIR, "versions", f"{version}-neoforge")
                    self._rename_loader_folder(src, dst, version, "neoforge")
                    self.config["current_version"] = f"{version}-neoforge"
                    self.config["current_loader"] = "neoforge"
                    self.config["original_version"] = version
                    self.save_config()
                    self.log(f"✅ NeoForge {version} 安装完成！")
                else:
                    self.log("未找到自动安装的 NeoForge 版本，请手动安装。")
            except:
                self.log("自动安装失败，请手动安装 NeoForge。")
        except Exception as e:
            self.log(f"NeoForge 安装失败: {e}")

    def _install_quilt_loader(self, version, callbacks):
        self.log("正在安装 Quilt 加载器...")
        try:
            try:
                loader_version = minecraft_launcher_lib.quilt.get_latest_loader_version(version)
            except:
                loader_version = "latest"
            minecraft_launcher_lib.quilt.install_quilt(version, MC_DIR, callback=callbacks)
            src = os.path.join(MC_DIR, "versions", f"fabric-loader-{loader_version}-{version}")
            dst = os.path.join(MC_DIR, "versions", f"{version}-quilt")
            if os.path.exists(src):
                self._rename_loader_folder(src, dst, version, "quilt")
                self.config["current_version"] = f"{version}-quilt"
                self.config["current_loader"] = "quilt"
                self.config["original_version"] = version
                self.save_config()
                self.log(f"✅ Quilt {version} 安装完成（使用 Fabric 兼容层）！")
            else:
                self.log("Quilt 自动安装失败，请访问 https://quiltmc.org/ 手动安装。")
        except Exception as e:
            self.log(f"Quilt 安装失败: {e}")

    def _rename_loader_folder(self, src, dst, version, loader_name):
        if os.path.exists(src) and not os.path.exists(dst):
            json_files = [f for f in os.listdir(src) if f.endswith('.json')]
            for json_file in json_files:
                json_path = os.path.join(src, json_file)
                try:
                    with open(json_path, 'r', encoding='utf-8') as f:
                        json_data = json.load(f)
                    new_id = f"{version}-{loader_name}"
                    json_data['id'] = new_id
                    json_data['inheritsFrom'] = version
                    json_data.pop('jar', None)
                    if 'mainClass' not in json_data:
                        json_data['mainClass'] = 'net.fabricmc.loader.impl.launch.knot.KnotClient'
                    new_json_path = os.path.join(src, f"{new_id}.json")
                    with open(new_json_path, 'w', encoding='utf-8') as f:
                        json.dump(json_data, f, indent=2)
                    os.remove(json_path)
                except:
                    pass
            os.rename(src, dst)
            src_jar = os.path.join(MC_DIR, "versions", version, f"{version}.jar")
            dst_jar = os.path.join(dst, f"{version}-{loader_name}.jar")
            if os.path.exists(src_jar) and not os.path.exists(dst_jar):
                shutil.copy2(src_jar, dst_jar)
                self.log(f"已复制原版 JAR 到 {version}-{loader_name}.jar")
            self.log(f"已将文件夹重命名为 {version}-{loader_name}")

    def install_server(self, server_type, version, max_mem, min_mem):
        server_type = server_type.lower()
        if server_type not in SERVER_SOURCES:
            self.log(f"错误: 不支持的服务器类型 {server_type}，可用: {', '.join(SERVER_SOURCES.keys())}")
            return
        self.log(f"开始安装 {server_type} {version} 服务器 (内存: {min_mem} - {max_mem})...")
        server_dir = os.path.join(SERVER_DIR, server_type)
        os.makedirs(server_dir, exist_ok=True)
        url = None
        jar_name = f"{server_type}-server.jar"
        if server_type in ["spigot", "paper"]:
            build = self._get_paper_build(version)
            if not build:
                self.log(f"无法获取 Paper {version} 的构建号")
                return
            url = SERVER_SOURCES["paper"].format(version=version, build=build)
        elif server_type == "purpur":
            url = SERVER_SOURCES["purpur"].format(version=version)
        elif server_type == "fabric":
            loader = self._get_fabric_loader_version(version)
            if not loader:
                self.log(f"无法获取 Fabric 加载器版本")
                return
            url = f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{loader}/fabric-loader-{loader}.jar"
        elif server_type == "forge":
            forge_ver = self._get_forge_version(version)
            if not forge_ver:
                self.log(f"无法获取 Forge 版本")
                return
            url = SERVER_SOURCES["forge"].format(version=version, forge=forge_ver)
        else:
            self.log(f"未知的服务器类型 {server_type}")
            return
        if not url:
            self.log("无法生成下载链接")
            return
        jar_path = os.path.join(server_dir, jar_name)
        self.log(f"正在下载 {server_type} 服务器核心...")
        try:
            r = requests.get(url, stream=True)
            r.raise_for_status()
            with open(jar_path, 'wb') as f:
                for chunk in r.iter_content(chunk_size=8192):
                    f.write(chunk)
            self.log("下载完成")
        except Exception as e:
            self.log(f"下载失败: {e}")
            return
        eula_path = os.path.join(server_dir, "eula.txt")
        with open(eula_path, 'w') as f:
            f.write("eula=true\n")
        self.log("已设置 eula=true")
        server_info = {
            "type": server_type,
            "version": version,
            "max_mem": max_mem,
            "min_mem": min_mem,
            "jar": jar_name,
            "path": server_dir
        }
        info_path = os.path.join(server_dir, "server_info.json")
        with open(info_path, 'w') as f:
            json.dump(server_info, f)
        self.log(f"✅ {server_type} {version} 服务器安装完成！")
        self.log(f"启动命令: server console {server_type} [nogui]")
 
    def _get_paper_build(self, version):
        url = f"https://api.papermc.io/v2/projects/paper/versions/{version}"
        try:
            r = requests.get(url)
            r.raise_for_status()
            data = r.json()
            builds = data.get("builds", [])
            if builds:
                return max(builds)
        except:
            pass
        return None

    def _get_fabric_loader_version(self, version):
        try:
            url = f"https://meta.fabricmc.net/v2/versions/loader/{version}"
            self.log(f"正在从 Fabric Meta API 获取加载器版本: {url}")
            r = requests.get(url, timeout=10)
            r.raise_for_status()
            data = r.json()
            if data and isinstance(data, list) and len(data) > 0:
                loader_version = data[0]["loader"]["version"]
                self.log(f"从 API 获取到 Fabric 加载器版本: {loader_version}")
                return loader_version
            else:
                self.log(f"API 返回空数据")
        except Exception as e:
            self.log(f"从 Fabric Meta API 获取版本失败: {e}")
        self.log(f"错误: 无法获取 Minecraft {version} 的 Fabric 加载器版本")
        return None

    def _get_forge_version(self, version):
        try:
            versions = forge.list_forge_versions()
            for v in versions:
                if v.startswith(version):
                    return v
        except:
            pass
        return None
    
    def _read_server_log(self, process):
        for line in iter(process.stdout.readline, ''):
            if line.strip():
                # 添加前缀 [Server] 以区分游戏日志
                self.log(f"[Server] {line.strip()}")
        process.stdout.close()
        process.wait()
        self.log("[Server] 服务器进程已退出")
        self.server_process = None

    def launch_server(self, server_type, nogui=False):
        server_dir = os.path.join(SERVER_DIR, server_type)
        info_path = os.path.join(server_dir, "server_info.json")
        if not os.path.exists(info_path):
            self.log(f"错误: 服务器 {server_type} 未安装或信息文件丢失")
            return
        with open(info_path, 'r') as f:
            info = json.load(f)
        jar_path = os.path.join(server_dir, info["jar"])
        max_mem = info["max_mem"]
        min_mem = info["min_mem"]
        java_cmd = "java"
        if self.config.get("java_home", {}).get("current"):
            java_cmd = os.path.join(self.config["java_home"]["current"], "bin", "java")
        cmd = [java_cmd, f"-Xms{min_mem}", f"-Xmx{max_mem}", "-jar", jar_path]
        if nogui:
            cmd.append("-nogui")
        self.log(f"启动服务器: {' '.join(cmd)}")
        try:
            # 启动服务器进程，捕获输出并允许输入
            process = subprocess.Popen(
                cmd,
                cwd=server_dir,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                encoding='utf-8',
                errors='ignore'
            )
            self.server_process = process
            # 启动线程读取服务器输出
            threading.Thread(target=self._read_server_log, args=(process,), daemon=True).start()
            self.log(f"服务器已启动，工作目录: {server_dir}")
        except Exception as e:
            self.log(f"启动服务器失败: {e}")

    def install_java(self, version):
        system = platform.system().lower()
        if system == "windows":
            os_type = "windows"
            ext = ".zip"
        elif system == "linux":
            os_type = "linux"
            ext = ".tar.gz"
        elif system == "darwin":
            os_type = "mac"
            ext = ".tar.gz"
        else:
            self.log(f"不支持的操作系统: {system}")
            return
        machine = platform.machine().lower()
        if machine in ("amd64", "x86_64"):
            arch = "x64"
        elif machine in ("arm64", "aarch64"):
            arch = "aarch64"
        elif machine in ("armv7l", "arm"):
            arch = "arm"
        else:
            self.log(f"不支持的架构: {machine}")
            return
        java_target_dir = os.path.join(JAVA_DIR, f"jdk-{version}")
        if os.path.exists(java_target_dir):
            java_exe = os.path.join(java_target_dir, "bin", "java.exe" if os_type == "windows" else "java")
            if os.path.exists(java_exe):
                self.log(f"Java {version} 已安装: {java_target_dir}")
                self.config.setdefault("java_home", {})
                self.config["java_home"][version] = java_target_dir
                self.config["java_home"]["current"] = java_target_dir
                self.save_config()
                return
        api_url = f"https://api.adoptium.net/v3/assets/latest/{version}/hotspot"
        self.log(f"正在从 Adoptium API 获取 Java {version} 下载信息...")
        try:
            r = requests.get(api_url, timeout=10)
            r.raise_for_status()
            assets = r.json()
            if not assets:
                self.log("未获取到任何下载信息")
                return
        except Exception as e:
            self.log(f"获取版本信息失败: {e}")
            return
        download_url = None
        filename = None
        version_tag = None
        for asset in assets:
            binary = asset.get("binary", {})
            if binary.get("os") == os_type and binary.get("architecture") == arch:
                download_url = binary.get("package", {}).get("link")
                filename = download_url.split("/")[-1] if download_url else None
                version_tag = binary.get("version")
                break
        if not download_url:
            self.log(f"未找到适合 {os_type} {arch} 的下载链接")
            return
        if version_tag:
            tag_for_url = version_tag.replace('+', '_')
            mirror_url = f"https://mirrors.huaweicloud.com/adoptium/{version}/{tag_for_url}/{filename}"
            self.log(f"使用华为云镜像下载: {mirror_url}")
            download_url = mirror_url
        else:
            self.log("无法获取版本标签，尝试原始链接（可能很慢）...")
        zip_path = os.path.join(java_target_dir, filename)
        os.makedirs(java_target_dir, exist_ok=True)
        if os.path.exists(zip_path):
            self.log(f"压缩包已存在: {zip_path}，跳过下载")
        else:
            self.log(f"正在下载 Java {version} ({arch})...")
            try:
                with requests.get(download_url, stream=True, timeout=60) as r:
                    r.raise_for_status()
                    with open(zip_path, 'wb') as f:
                        for chunk in r.iter_content(chunk_size=8192):
                            f.write(chunk)
                self.log("下载完成")
            except Exception as e:
                self.log(f"下载失败: {e}")
                self.log("提示: 可能是网络问题，请手动下载后放入该目录")
                self.log(f"下载链接: {download_url}")
                return
        self.log("正在解压...")
        try:
            if ext == ".zip":
                with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                    zip_ref.extractall(java_target_dir)
            else:
                import tarfile
                with tarfile.open(zip_path, 'r:gz') as tar_ref:
                    tar_ref.extractall(java_target_dir)
            os.remove(zip_path)
        except Exception as e:
            self.log(f"解压失败: {e}")
            return
        items = os.listdir(java_target_dir)
        if len(items) == 1 and items[0].startswith("jdk-"):
            subdir = os.path.join(java_target_dir, items[0])
            for item in os.listdir(subdir):
                shutil.move(os.path.join(subdir, item), java_target_dir)
            os.rmdir(subdir)
        self.config.setdefault("java_home", {})
        self.config["java_home"][version] = java_target_dir
        self.config["java_home"]["current"] = java_target_dir
        self.save_config()
        self.log(f"✅ Java {version} 安装完成，路径: {java_target_dir}")

    def install_mod(self, mod_name, auto_select=False):
        version_for_search = self.config.get("original_version")
        version_folder = self.config.get("current_version")
        loader = self.config.get("current_loader", "vanilla")
        if not version_for_search or loader == "vanilla":
            self.log("错误: 请先安装一个带加载器的版本！")
            return False
        self.log(f"正在搜索模组: {mod_name} ...")
        try:
            headers = {'User-Agent': 'SimpleMCLauncher/1.0'}
            search_url = f"https://api.modrinth.com/v2/search?query={mod_name}&limit=5"
            res = requests.get(search_url, headers=headers).json()
            if not res.get("hits"):
                self.log(f"错误: 未找到名为 {mod_name} 的模组。")
                return False
            self.log("找到以下模组:")
            for i, hit in enumerate(res["hits"][:5]):
                self.log(f"  {i+1}. {hit['title']} - {hit.get('description', '')[:60]}")
            if auto_select:
                selected = res["hits"][0]
                self.log(f"自动选择: {selected['title']}")
            else:
                choice = self._get_user_choice("选择模组", f"请输入编号 (1-{len(res['hits'][:5])}，直接回车选第一个):")
                selected = None
                if choice and choice.strip():
                    try:
                        idx = int(choice) - 1
                        if 0 <= idx < len(res["hits"][:5]):
                            selected = res["hits"][:5][idx]
                    except ValueError:
                        pass
                if not selected:
                    selected = res["hits"][0]
                    self.log(f"使用第一个: {selected['title']}")
            project_id = selected["project_id"]
            project_title = selected["title"]
            versions_url = f"https://api.modrinth.com/v2/project/{project_id}/version"
            vers_res = requests.get(versions_url, headers=headers).json()
            download_url = None
            filename = None
            for v in vers_res:
                if version_for_search in v["game_versions"] and loader in v["loaders"]:
                    download_url = v["files"][0]["url"]
                    filename = v["files"][0]["filename"]
                    break
            if not download_url:
                self.log(f"该模组没有适用于 {version_for_search} {loader} 的版本。")
                return False
            self.console.tag_delete("progress")
            mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
            os.makedirs(mods_dir, exist_ok=True)
            file_path = os.path.join(mods_dir, filename)
            self.log(f"正在下载 {filename} ...")
            with requests.get(download_url, headers=headers, stream=True) as r:
                r.raise_for_status()
                with open(file_path, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        f.write(chunk)
            self.log("下载完成！")
            self.log(f"✅ {project_title} 已安装到 {version_folder}/mods")
            return True
        except Exception as e:
            self.log(f"模组下载失败: {e}")
            self.log(traceback.format_exc())
            return False

    def install_mods_batch(self, mod_list):
        success_count = 0
        fail_count = 0
        for mod_name in mod_list:
            mod_name = mod_name.strip()
            if not mod_name:
                continue
            self.log(f"正在安装 {mod_name}...")
            if self.install_mod(mod_name, auto_select=True):
                success_count += 1
            else:
                fail_count += 1
        self.log(f"批量安装完成：成功 {success_count} 个，失败 {fail_count} 个")

    def microsoft_login(self):
        self.log("正在启动微软登录流程...")
        try:
            login_url, state, code_verifier = microsoft_account.get_secure_login_data(
                CLIENT_ID,
                REDIRECT_URL
            )
            self.log("请在打开的浏览器中登录您的微软账号。")
            self.log("登录后请复制地址栏的完整URL并粘贴到弹出的输入框中。")
            webbrowser.open(login_url)
            self.root.after(0, lambda: self._prompt_for_redirect_url(state, code_verifier))
        except Exception as e:
            self.log(f"登录初始化失败: {e}")

    def _prompt_for_redirect_url(self, state, code_verifier):
        redirected_url = simpledialog.askstring("微软登录",
            "请粘贴浏览器地址栏的完整URL:",
            parent=self.root)
        if not redirected_url:
            self.log("登录已取消。")
            return
        threading.Thread(target=self._complete_login, args=(redirected_url, state, code_verifier), daemon=True).start()

    def _complete_login(self, redirected_url, state, code_verifier):
        try:
            self.log(f"接收到URL，正在处理...")
            if "error" in redirected_url:
                self.log(f"登录失败: URL包含错误信息")
                return
            auth_code = microsoft_account.parse_auth_code_url(redirected_url, state)
            login_data = microsoft_account.complete_login(
                CLIENT_ID,
                None,
                REDIRECT_URL,
                auth_code,
                code_verifier
            )
            if "refresh_token" in login_data:
                self.config["ms_refresh_token"] = login_data["refresh_token"]
            self.config["ms_username"] = login_data["name"]
            self.config["ms_uuid"] = login_data["id"]
            self.config["ms_access_token"] = login_data["access_token"]
            self.save_config()
            self.log(f"✅ 登录成功！欢迎 {login_data['name']}")
        except Exception as e:
            self.log(f"登录完成失败: {e}")

    def microsoft_logout(self):
        self.config["ms_refresh_token"] = None
        self.config["ms_username"] = None
        self.config["ms_uuid"] = None
        self.config["ms_access_token"] = None
        self.save_config()
        self.log("已退出登录")

    def refresh_microsoft_token(self):
        refresh_token = self.config.get("ms_refresh_token")
        if not refresh_token:
            return False
        try:
            new_data = microsoft_account.complete_refresh(
                client_id=CLIENT_ID,
                refresh_token=refresh_token,
                redirect_uri=REDIRECT_URL,
                client_secret=None
            )
            if "refresh_token" in new_data:
                self.config["ms_refresh_token"] = new_data["refresh_token"]
            self.config["ms_username"] = new_data["name"]
            self.config["ms_uuid"] = new_data["id"]
            self.config["ms_access_token"] = new_data["access_token"]
            self.save_config()
            return True
        except Exception:
            return False

    def get_login_options(self):
        if self.config.get("ms_refresh_token"):
            if not self.refresh_microsoft_token():
                return None
            return {
                "username": self.config["ms_username"],
                "uuid": self.config["ms_uuid"],
                "token": self.config["ms_access_token"]
            }
        else:
            return {
                "username": self.config.get("player_name", "Player"),
                "uuid": "",
                "token": ""
            }

    def import_modpack(self, file_path):
        file_path = file_path.strip('"').strip("'")
        if not os.path.exists(file_path):
            self.log(f"错误: 文件不存在 - {file_path}")
            return
        if not (file_path.lower().endswith('.zip') or file_path.lower().endswith('.mrpack')):
            self.log("错误: 仅支持 .zip 或 .mrpack 格式的整合包")
            return
        self.log(f"正在导入整合包: {os.path.basename(file_path)}")
        threading.Thread(target=self._install_modpack_from_zip, args=(file_path,), daemon=True).start()

    def _install_modpack_from_zip(self, zip_path):
        try:
            extract_dir = tempfile.mkdtemp()
            self.log(f"正在解压整合包...")
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_dir)
            manifest_path = None
            modrinth_index_path = None
            for root, dirs, files in os.walk(extract_dir):
                if 'manifest.json' in files:
                    manifest_path = os.path.join(root, 'manifest.json')
                    break
                if 'modrinth.index.json' in files:
                    modrinth_index_path = os.path.join(root, 'modrinth.index.json')
                    break
            if manifest_path:
                self.log("检测到 CurseForge 格式整合包")
                self._install_curseforge_modpack(manifest_path, extract_dir)
            elif modrinth_index_path:
                self.log("检测到 Modrinth 格式整合包")
                try:
                    with open(modrinth_index_path, 'r', encoding='utf-8') as f:
                        index_data = json.load(f)
                    deps = index_data.get('dependencies', {})
                    game_version = deps.get('minecraft')
                    if not game_version:
                        self.log("错误: 无法从整合包中获取 Minecraft 版本")
                        return
                    self.log(f"整合包目标游戏版本: {game_version}")
                except Exception as e:
                    self.log(f"解析整合包索引失败: {e}")
                    return
                self._install_modrinth_modpack(modrinth_index_path, extract_dir, game_version)
            else:
                self.log("检测到直接覆盖式整合包")
                self._install_direct_modpack(extract_dir)
            shutil.rmtree(extract_dir, ignore_errors=True)
            os.remove(zip_path)
            self.log("临时文件已清理")
        except Exception as e:
            self.log(f"整合包安装失败: {e}")
            self.log(traceback.format_exc())

    def _install_curseforge_modpack(self, manifest_path, extract_dir):
        self.log("CurseForge 整合包安装功能需补全")

    def _install_modrinth_modpack(self, index_path, extract_dir, game_version):
        try:
            with open(index_path, 'r', encoding='utf-8') as f:
                index = json.load(f)
            pack_name = index.get('name', 'modpack').strip()
            pack_name = "".join(c for c in pack_name if c.isalnum() or c in (' ', '-', '_')).rstrip()
            pack_name = pack_name.replace(' ', '_')
            if not pack_name:
                pack_name = "modpack"
            deps = index.get('dependencies', {})
            minecraft_version = deps.get('minecraft', game_version)
            loader_type = None
            for l in ['fabric-loader', 'forge', 'quilt-loader']:
                if l in deps:
                    loader_type = l.replace('-loader', '')
                    break
            self.log(f"整合包信息: Minecraft {minecraft_version}, 加载器: {loader_type}")
            vanilla_dir = os.path.join(MC_DIR, "versions", minecraft_version)
            if not os.path.isdir(vanilla_dir):
                self.log(f"原版 Minecraft {minecraft_version} 未安装，正在安装...")
                self._install_minecraft_version(minecraft_version, "vanilla")
            inherits_from = minecraft_version
            if loader_type:
                if loader_type in ['fabric', 'forge', 'quilt']:
                    self.log(f"正在安装 {loader_type} 加载器...")
                    self._install_minecraft_version(minecraft_version, loader_type)
                    inherits_from = f"{minecraft_version}-{loader_type}"
                else:
                    self.log(f"未知加载器 {loader_type}，将使用原版")
            version_folder = os.path.join(MC_DIR, "versions", pack_name)
            os.makedirs(version_folder, exist_ok=True)
            self.log(f"创建整合包版本文件夹: {pack_name}")
            src_jar = os.path.join(MC_DIR, "versions", minecraft_version, f"{minecraft_version}.jar")
            dst_jar = os.path.join(version_folder, f"{pack_name}.jar")
            if os.path.exists(src_jar) and not os.path.exists(dst_jar):
                shutil.copy2(src_jar, dst_jar)
                self.log(f"已复制原版 JAR 到 {pack_name}.jar")
            overrides_dir = os.path.join(extract_dir, 'overrides')
            if os.path.exists(overrides_dir):
                for item in os.listdir(overrides_dir):
                    src = os.path.join(overrides_dir, item)
                    dst = os.path.join(version_folder, item)
                    if os.path.isdir(src):
                        shutil.copytree(src, dst, dirs_exist_ok=True)
                    else:
                        shutil.copy2(src, dst)
                self.log("已应用整合包覆盖文件")
            files = index.get('files', [])
            if files:
                mods_dir = os.path.join(version_folder, "mods")
                os.makedirs(mods_dir, exist_ok=True)
                self.log(f"需要下载 {len(files)} 个模组...")
                headers = {'User-Agent': 'SimpleMCLauncher/1.0'}
                for file_info in files:
                    downloads = file_info.get('downloads', [])
                    if not downloads:
                        continue
                    download_url = downloads[0]
                    target_path = file_info.get('path', '').replace('\\', '/')
                    if not target_path:
                        target_path = file_info.get('filename', '')
                    if '/' in target_path:
                        subdir = os.path.dirname(target_path)
                        target_dir = os.path.join(mods_dir, subdir)
                        os.makedirs(target_dir, exist_ok=True)
                        local_file = os.path.join(target_dir, os.path.basename(target_path))
                    else:
                        local_file = os.path.join(mods_dir, target_path or os.path.basename(download_url))
                    if os.path.exists(local_file):
                        continue
                    self.log(f"  下载: {os.path.basename(local_file)}")
                    try:
                        with requests.get(download_url, headers=headers, stream=True) as r:
                            r.raise_for_status()
                            with open(local_file, 'wb') as f:
                                for chunk in r.iter_content(chunk_size=8192):
                                    f.write(chunk)
                    except Exception as e:
                        self.log(f"  下载失败: {e}")
                self.log("模组下载完成")
            version_json = {
                "id": pack_name,
                "inheritsFrom": inherits_from,
                "time": datetime.datetime.now().isoformat(),
                "releaseTime": datetime.datetime.now().isoformat(),
                "type": "release",
                "jar": f"{pack_name}.jar"
            }
            json_path = os.path.join(version_folder, f"{pack_name}.json")
            with open(json_path, 'w', encoding='utf-8') as f:
                json.dump(version_json, f, indent=2)
            self.config["current_version"] = pack_name
            self.config["current_loader"] = loader_type if loader_type else "vanilla"
            self.config["original_version"] = minecraft_version
            self.save_config()
            self.log(f"✅ 整合包安装完成！")
            self.log(f"启动命令: launch {pack_name}")
        except Exception as e:
            self.log(f"安装 Modrinth 整合包失败: {e}")
            self.log(traceback.format_exc())

    def _install_direct_modpack(self, extract_dir):
        self.log("直接覆盖式整合包安装功能需补全")

    def set_game_language_to_chinese(self, game_dir):
        options_path = os.path.join(game_dir, "options.txt")
        options_data = {}
        if os.path.exists(options_path):
            try:
                with open(options_path, 'r', encoding='utf-8') as f:
                    for line in f:
                        if ':' in line:
                            key, val = line.strip().split(':', 1)
                            options_data[key] = val
            except Exception:
                pass
        options_data['lang'] = 'zh_cn'
        try:
            with open(options_path, 'w', encoding='utf-8') as f:
                for key, val in options_data.items():
                    f.write(f"{key}:{val}\n")
        except Exception:
            pass

    def launch_game(self, version):
        version_dir = os.path.join(MC_DIR, "versions", version)
        if not os.path.isdir(version_dir):
            self.log(f"错误: 版本文件夹 {version} 不存在。")
            return
        json_path = os.path.join(version_dir, f"{version}.json")
        if not os.path.exists(json_path):
            self.log(f"错误: 版本 JSON 文件不存在。")
            return
        login_options = self.get_login_options()
        if login_options is None:
            self.log("登录已失效，请重新执行 login 命令。")
            return
        isolated_game_dir = version_dir
        os.makedirs(isolated_game_dir, exist_ok=True)
        self.set_game_language_to_chinese(isolated_game_dir)
        options = {
            "username": login_options["username"],
            "uuid": login_options["uuid"],
            "token": login_options["token"],
            "gameDirectory": isolated_game_dir
        }
        try:
            command = minecraft_launcher_lib.command.get_minecraft_command(version, MC_DIR, options)
            self.log("正在启动游戏...")
            if os.name == 'nt':
                creation_flags = subprocess.CREATE_NO_WINDOW
            else:
                creation_flags = 0
            process = subprocess.Popen(
                command, cwd=isolated_game_dir,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding='utf-8', errors='ignore', bufsize=1,
                creationflags=creation_flags
            )
            self.game_process = process
            threading.Thread(target=self.read_game_log, args=(process,), daemon=True).start()
        except Exception as e:
            self.log(f"启动失败: {e}")
            self.log(traceback.format_exc())

    def read_game_log(self, process):
        for line in iter(process.stdout.readline, ''):
            if line.strip():
                self.log(f"[Game] {line.strip()}")
        process.stdout.close()
        process.wait()
        self.log(f"\n[系统] 游戏已退出，退出码: {process.returncode}")

    def install_frp(self):
        self.log("开始安装 frp 内网穿透工具...")
        os.makedirs(FRP_DIR, exist_ok=True)
        system = platform.system().lower()
        arch = platform.machine().lower()
        self.log(f"检测到系统: {system}, 架构: {arch}")
        if system not in FRP_DOWNLOAD_URL:
            self.log(f"不支持的操作系统: {system}，目前支持: {list(FRP_DOWNLOAD_URL.keys())}")
            return
        arch_key = None
        if "amd64" in arch or "x86_64" in arch:
            arch_key = "amd64"
        elif "86" in arch or "i386" in arch or "i686" in arch:
            arch_key = "386"
        elif "arm64" in arch or "aarch64" in arch:
            arch_key = "arm64"
        else:
            self.log(f"不支持的架构: {arch}")
            return
        if arch_key not in FRP_DOWNLOAD_URL[system]:
            self.log(f"不支持的架构: {arch_key}，支持的架构: {list(FRP_DOWNLOAD_URL[system].keys())}")
            return
        download_url = FRP_DOWNLOAD_URL[system][arch_key]
        zip_filename = os.path.basename(download_url)
        zip_path = os.path.join(FRP_DIR, zip_filename)
        if os.path.exists(zip_path):
            self.log(f"压缩包已存在: {zip_path}，跳过下载，直接解压...")
        else:
            self.log(f"原始下载链接: {download_url}")
            proxy_url = f"https://ghproxy.com/{download_url}"
            self.log(f"通过代理下载: {proxy_url}")
            try:
                r = requests.get(proxy_url, stream=True, timeout=30)
                r.raise_for_status()
                with open(zip_path, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        f.write(chunk)
                self.log("下载完成")
            except Exception as e:
                self.log(f"下载失败: {e}")
                return
        try:
            if zip_path.endswith('.zip'):
                with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                    zip_ref.extractall(FRP_DIR)
            else:
                import tarfile
                with tarfile.open(zip_path, 'r:gz') as tar_ref:
                    tar_ref.extractall(FRP_DIR)
            os.remove(zip_path)
            self.log("解压完成")
        except Exception as e:
            self.log(f"解压失败: {e}")
            return
        frpc_exe = None
        for root, dirs, files in os.walk(FRP_DIR):
            for file in files:
                if file == 'frpc' or file == 'frpc.exe':
                    frpc_exe = os.path.join(root, file)
                    break
            if frpc_exe:
                break
        if not frpc_exe:
            self.log("未找到 frpc 可执行文件")
            return
        target = os.path.join(FRP_DIR, 'frpc.exe' if system == 'windows' else 'frpc')
        if frpc_exe != target:
            shutil.move(frpc_exe, target)
        self.log(f"✅ frp 安装完成，可执行文件: {target}")
        self.log("接下来请使用 'frp config' 命令进行配置。")

    def frp_config(self):
        self.log("开始配置 frp 客户端（TOML 格式）...")
        frpc_path = os.path.join(FRP_DIR, 'frpc.exe' if platform.system().lower() == 'windows' else 'frpc')
        if not os.path.exists(frpc_path):
            self.log("未找到 frpc，请先执行 'install frp'")
            return
        q = queue.Queue()
        def ask(question, default=""):
            result = simpledialog.askstring("frp 配置", question, initialvalue=default, parent=self.root)
            q.put(result)
        self.root.after(0, lambda: ask("请输入 frp 服务器地址 (如 frp.example.com)"))
        server_addr = q.get()
        if not server_addr:
            self.log("配置取消")
            return
        self.root.after(0, lambda: ask("请输入 frp 服务器端口", "7000"))
        server_port = q.get()
        if not server_port:
            server_port = "7000"
        self.root.after(0, lambda: ask("请输入认证令牌 (token，若无则留空)"))
        token = q.get()
        self.root.after(0, lambda: ask("请输入本地 Minecraft 服务器端口", "25565"))
        local_port = q.get()
        if not local_port:
            local_port = "25565"
        self.root.after(0, lambda: ask("请输入远程端口 (分配给您的公网端口)"))
        remote_port = q.get()
        if not remote_port:
            self.log("远程端口不能为空")
            return
        config_content = f'''# frpc.toml 自动生成
    serverAddr = "{server_addr}"
    serverPort = {server_port}
    '''
        if token:
            config_content += f'token = "{token}"\n'
        config_content += f'''
    [[proxies]]
    name = "minecraft-server"
    type = "tcp"
    localIP = "127.0.0.1"
    localPort = {local_port}
    remotePort = {remote_port}
    '''
        try:
            with open(FRPC_CONFIG, 'w', encoding='utf-8') as f:
                f.write(config_content)
            self.log(f"✅ 配置文件已生成: {FRPC_CONFIG}")
            self.log("你可以使用 'frp start' 启动穿透。")
        except Exception as e:
            self.log(f"写入配置文件失败: {e}")

    def frp_start(self):
        if hasattr(self, 'frp_process') and self.frp_process and self.frp_process.poll() is None:
            self.log("frp 已在运行中")
            return
        frpc_path = os.path.join(FRP_DIR, 'frpc.exe' if platform.system().lower() == 'windows' else 'frpc')
        if not os.path.exists(frpc_path):
            self.log("未找到 frpc，请先执行 'install frp'")
            return
        if not os.path.exists(FRPC_CONFIG):
            self.log("未找到配置文件，请先执行 'frp config'")
            return
        self.log("正在启动 frp 客户端...")
        try:
            cmd = [frpc_path, '-c', FRPC_CONFIG]
            if platform.system().lower() == 'windows':
                self.frp_process = subprocess.Popen(
                    ['start', 'cmd', '/k'] + cmd,
                    shell=True,
                    creationflags=subprocess.CREATE_NEW_CONSOLE
                )
            else:
                self.frp_process = subprocess.Popen(
                    cmd,
                    stdout=open(FRPC_LOG, 'a'),
                    stderr=subprocess.STDOUT,
                    start_new_session=True
                )
            self.log("✅ frp 客户端已启动，请在新窗口中查看日志。")
        except Exception as e:
            self.log(f"启动失败: {e}")

    def frp_stop(self):
        if not hasattr(self, 'frp_process') or self.frp_process is None:
            self.log("frp 未运行")
            return
        try:
            self.frp_process.terminate()
            self.frp_process.wait(timeout=5)
            self.log("✅ frp 已停止")
        except Exception as e:
            self.log(f"停止失败: {e}")

    def frp_status(self):
        if not hasattr(self, 'frp_process') or self.frp_process is None:
            self.log("frp 未启动")
            return
        poll = self.frp_process.poll()
        if poll is None:
            self.log("frp 正在运行中")
        else:
            self.log(f"frp 已停止，退出码: {poll}")

    def install_shaderpack(self, shader_name):
        version_for_search = self.config.get("original_version")
        version_folder = self.config.get("current_version")
        loader = self.config.get("current_loader", "vanilla")
        if not version_for_search or loader == "vanilla":
            self.log("错误: 请先安装一个带加载器的版本（Fabric+Iris 或 Forge+OptiFine）！")
            self.log("提示: 光影需要 Iris (Fabric) 或 OptiFine (Forge) 支持")
            return
        self.log(f"正在搜索光影包: {shader_name} 适用于 Minecraft {version_for_search} {loader}...")
        try:
            headers = {'User-Agent': 'SimpleMCLauncher/1.0'}
            search_url = f"https://api.modrinth.com/v2/search?query={shader_name}&limit=20&project_type=shader"
            res = requests.get(search_url, headers=headers).json()
            if not res.get("hits"):
                self.log(f"错误: 未找到名为 {shader_name} 的光影包。")
                self.log("提示: 可以试试 BSL, Complementary, SEUS, Sildur's 等知名光影")
                return
            shader_hits = [hit for hit in res["hits"] if hit.get("project_type") == "shader"]
            if not shader_hits:
                self.log("未找到光影包，请尝试其他关键词")
                return
            self.log("找到以下光影包:")
            shader_list = []
            for i, hit in enumerate(shader_hits[:10]):
                self.log(f"  {i+1}. {hit['title']} - {hit.get('description', '')[:60]}")
                shader_list.append(hit)
            choice = self._get_user_choice("选择光影包", f"请输入编号 (1-{len(shader_list)}，直接回车选第一个):")
            selected = None
            if choice and choice.strip():
                try:
                    idx = int(choice) - 1
                    if 0 <= idx < len(shader_list):
                        selected = shader_list[idx]
                except ValueError:
                    pass
            if not selected:
                selected = shader_list[0]
            project_id = selected["project_id"]
            project_title = selected["title"]
            self.log(f"选择: {project_title}")
            versions_url = f"https://api.modrinth.com/v2/project/{project_id}/version"
            vers_res = requests.get(versions_url, headers=headers).json()
            compatible_versions = []
            for v in vers_res:
                if version_for_search in v.get("game_versions", []):
                    compatible_versions.append(v)
            if not compatible_versions:
                self.log(f"该光影包没有适用于 Minecraft {version_for_search} 的版本。")
                return
            compatible_versions.sort(key=lambda x: x.get("date_published", ""), reverse=True)
            self.log("找到以下可用版本:")
            version_list = []
            for i, v in enumerate(compatible_versions[:5]):
                date = v.get("date_published", "")[:10]
                self.log(f"  {i+1}. {v['version_number']} - {date}")
                version_list.append(v)
            version_choice = self._get_user_choice("选择版本", "请输入版本编号 (直接回车选最新):")
            selected_version = None
            if version_choice and version_choice.strip():
                try:
                    idx = int(version_choice) - 1
                    if 0 <= idx < len(version_list):
                        selected_version = version_list[idx]
                except ValueError:
                    pass
            if not selected_version:
                selected_version = compatible_versions[0]
            if selected_version['files']:
                file_info = selected_version['files'][0]
                download_url = file_info['url']
                filename = file_info['filename']
                version_shaderpacks_dir = os.path.join(MC_DIR, "versions", version_folder, "shaderpacks")
                os.makedirs(version_shaderpacks_dir, exist_ok=True)
                file_path = os.path.join(version_shaderpacks_dir, filename)
                self.log(f"正在下载 {filename} 到当前版本的 shaderpacks 文件夹...")
                with requests.get(download_url, headers=headers, stream=True) as r:
                    r.raise_for_status()
                    with open(file_path, 'wb') as f:
                        for chunk in r.iter_content(chunk_size=8192):
                            f.write(chunk)
                self.log(f"✅ {project_title} 已安装到 {version_folder}/shaderpacks 文件夹！")
                self.log("提示: 启动该版本时，游戏会自动读取此处的光影包")
                if loader == "fabric":
                    self.log("📝 当前使用 Fabric，请确保已安装 Iris 模组")
                    self.log("   Iris 官网: https://irisshaders.net/")
                elif loader == "forge":
                    self.log("📝 当前使用 Forge，请确保已安装 OptiFine 模组")
                    self.log("   OptiFine 官网: https://optifine.net/")
                else:
                    self.log("⚠️ 当前版本未安装加载器，光影无法运行")
                    self.log("   请先安装 Fabric+Iris 或 Forge+OptiFine")
                self.log("🎮 在游戏中启用光影: 选项 → 视频设置 → 光影")
            else:
                self.log("该光影包无可用文件。")
        except Exception as e:
            self.log(f"光影包下载失败: {e}")
            self.log(traceback.format_exc())

    def stop_game(self):
        if self.game_process is None:
            self.log("没有正在运行的游戏进程")
            return
        if self.game_process.poll() is not None:
            self.log("游戏进程已结束")
            self.game_process = None
            return
        try:
            self.log("正在终止游戏进程...")
            self.game_process.terminate()
            self.game_process.wait(timeout=3)
            self.log("游戏已终止")
        except subprocess.TimeoutExpired:
            self.log("游戏进程未响应，强制结束...")
            self.game_process.kill()
            self.game_process.wait()
            self.log("游戏已强制终止")
        except Exception as e:
            self.log(f"终止游戏时出错: {e}")
        finally:
            self.game_process = None

    def about(self):
        self.log("-"*65)
        self.log("鸣谢人员")
        self.log("BoscoNew                       - 作者")
        self.log("DeepSeek https://deepseek.com  - 软件开发")
        self.log("ts                             - 建议提出人")
        self.log("yeshen97                       - 建议提出人")
        self.log("-"*65)

    def clean_console(self):
        self.console.config(state='normal')
        self.console.delete('1.0', tk.END)
        self.console.config(state='disabled')
        self.progress_line = None
        self.log(f"欢迎使用极简 MC 启动器 (全能版)！ 作者BoscoNew")
        self.log(f"启动器版本: {VERSION_OF_LAUNCHER}")
        self.log(f"游戏数据目录: {MC_DIR}")
        self.log(f"服务器目录: {SERVER_DIR}")
        self.log(f"Java 目录: {JAVA_DIR}")
        self.log(f"系统架构：{platform.system().lower()} {platform.machine().lower()}")
        self.log(f"当前玩家: [{self.get_player_display()}] | 当前版本: [{self.config.get('current_version', '未设置')}]\n")

    def close(self):
        sys.exit()
    
    def list_mods(self):
        version_folder = self.config.get("current_version")
        if not version_folder:
            self.log("错误: 未选择任何版本")
            return
        mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
        if not os.path.isdir(mods_dir):
            self.log("mods 文件夹不存在")
            return

        self.log(f"当前版本 [{version_folder}] 已安装模组:")
        mod_files = [f for f in os.listdir(mods_dir) if f.endswith('.jar') or f.endswith('.disabled')]
        if not mod_files:
            self.log("  无模组")
            return

        for mod_file in sorted(mod_files):
            is_disabled = mod_file.endswith('.disabled')
            mod_name = mod_file.replace('.disabled', '') if is_disabled else mod_file
            # 尝试从 Modrinth 获取最新版本
            update_status = self._check_mod_update(mod_name, version_folder)
            status = "[禁用]" if is_disabled else "[启用]"
            update_str = f" (可更新至 {update_status})" if update_status else ""
            self.log(f"  {status} {mod_name}{update_str}")
    
    def _check_mod_update(self, mod_filename, mc_version):
        try:
            # 去掉版本号后缀，保留基本名称（简单处理，可能不准）
            # 例如 sodium-fabric-0.5.3+mc1.21.1.jar -> sodium
            base_name = re.sub(r'-\d+\.\d+(\.\d+)?.*?\.jar$', '', mod_filename).lower()
            headers = {'User-Agent': 'SimpleMCLauncher/1.0'}
            search_url = f"https://api.modrinth.com/v2/search?query={base_name}&limit=1"
            res = requests.get(search_url, headers=headers).json()
            if not res.get("hits"):
                return None
            project_id = res["hits"][0]["project_id"]
            versions_url = f"https://api.modrinth.com/v2/project/{project_id}/version"
            vers = requests.get(versions_url, headers=headers).json()
            # 筛选出适用于当前 Minecraft 版本的稳定版本
            compatible = [v for v in vers if mc_version in v["game_versions"] and v["version_type"] == "release"]
            if not compatible:
                compatible = [v for v in vers if mc_version in v["game_versions"]]
            if compatible:
                # 取最新（按发布日期）
                latest = max(compatible, key=lambda x: x["date_published"])
                return latest["version_number"]
        except Exception:
            pass
        return None
    
    def mod_update(self):
        version_folder = self.config.get("current_version")
        if not version_folder:
            self.log("错误: 未选择任何版本")
            return
        mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
        if not os.path.isdir(mods_dir):
            self.log("mods 文件夹不存在")
            return

        mod_files = [f for f in os.listdir(mods_dir) if f.endswith('.jar') and not f.endswith('.disabled')]
        if not mod_files:
            self.log("没有已启用的模组")
            return

        self.log("检查模组更新...")
        updates = []
        for mod_file in mod_files:
            latest = self._check_mod_update(mod_file, version_folder)
            if latest:
                updates.append((mod_file, latest))
        if updates:
            self.log("以下模组有更新:")
            for mod_file, latest in updates:
                self.log(f"  {mod_file} -> {latest}")
            self.log("使用 'mod update [模组名]' 可尝试下载更新（暂未实现）")
        else:
            self.log("所有模组均为最新")
    
    def mod_disable(self, mod_name):
        version_folder = self.config.get("current_version")
        if not version_folder:
            self.log("错误: 未选择任何版本")
            return
        mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
        target = None
        for f in os.listdir(mods_dir):
            if f == mod_name or f == mod_name + ".jar":
                target = f
                break
        if not target:
            self.log(f"未找到模组 {mod_name}")
            return
        src = os.path.join(mods_dir, target)
        dst = os.path.join(mods_dir, target + ".disabled")
        try:
            os.rename(src, dst)
            self.log(f"模组 {target} 已禁用")
        except Exception as e:
            self.log(f"禁用失败: {e}")

    def mod_enable(self, mod_name):
        version_folder = self.config.get("current_version")
        if not version_folder:
            self.log("错误: 未选择任何版本")
            return
        mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
        target = None
        for f in os.listdir(mods_dir):
            if f == mod_name + ".disabled":
                target = f
                break
        if not target:
            self.log(f"未找到禁用模组 {mod_name}")
            return
        src = os.path.join(mods_dir, target)
        dst = os.path.join(mods_dir, target.replace('.disabled', ''))
        try:
            os.rename(src, dst)
            self.log(f"模组 {mod_name} 已启用")
        except Exception as e:
            self.log(f"启用失败: {e}")
    
    def server_config(self, server_type):
        server_dir = os.path.join(SERVER_DIR, server_type)
        if not os.path.isdir(server_dir):
            self.log(f"服务器 {server_type} 未安装")
            return
        eula = os.path.join(server_dir, "eula.txt")
        props = os.path.join(server_dir, "server.properties")
        # 使用系统默认编辑器打开
        if os.name == 'nt':
            editor = "notepad.exe"
        else:
            editor = "gedit"  # 可改为 xdg-open 或用户设置
        for file in (eula, props):
            if os.path.exists(file):
                subprocess.Popen([editor, file])
                self.log(f"已打开 {file}")
            else:
                self.log(f"文件不存在: {file}")
    
    def server_command(self, cmd):
        if not hasattr(self, 'server_process') or self.server_process is None:
            self.log("服务器未运行")
            return
        try:
            self.server_process.stdin.write(cmd + "\n")
            self.server_process.stdin.flush()
            self.log(f"已发送命令: {cmd}")
        except Exception as e:
            self.log(f"发送命令失败: {e}")
    
    def server_console(self, server_type, nogui=False):
        server_dir = os.path.join(SERVER_DIR, server_type)
        info_path = os.path.join(server_dir, "server_info.json")
        if not os.path.exists(info_path):
            self.log(f"错误: 服务器 {server_type} 未安装或信息文件丢失")
            return

        with open(info_path, 'r') as f:
            info = json.load(f)

        jar_path = os.path.join(server_dir, info["jar"])
        max_mem = info["max_mem"]
        min_mem = info["min_mem"]

        java_cmd = "java"
        if self.config.get("java_home", {}).get("current"):
            java_cmd = os.path.join(self.config["java_home"]["current"], "bin", "java")

        cmd = [java_cmd, f"-Xms{min_mem}", f"-Xmx{max_mem}", "-jar", jar_path]

        if nogui:
            cmd.append("-nogui")
            self.log(f"启动服务器控制台 (无窗口): {' '.join(cmd)}")
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            self.server_process = subprocess.Popen(
                cmd,
                cwd=server_dir,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                encoding='utf-8',
                errors='replace',
                creationflags=creation_flags
            )
            self.server_console_mode = True
            self.log("服务器控制台已启动。输入 'exit' 退出控制台模式（服务器继续运行），或输入 'stop' 停止服务器并退出。")
            self.log("可直接输入 Minecraft 服务器命令，如 'list'、'save-all'。")
            threading.Thread(target=self._read_server_output, daemon=True).start()
        else:
            self.log(f"启动服务器控制台 (新窗口): {' '.join(cmd)}")
            if os.name == 'nt':
                subprocess.Popen(
                    ['start', 'cmd', '/k'] + cmd,
                    shell=True,
                    cwd=server_dir,
                    creationflags=subprocess.CREATE_NEW_CONSOLE
                )
            else:
                subprocess.Popen(['x-terminal-emulator', '-e'] + cmd, cwd=server_dir)
            self.log("服务器已在独立窗口中启动。")

    def _read_server_output(self):
        for line in iter(self.server_process.stdout.readline, ''):
            if not line:
                break
            self.log(f"[Server] {line.strip()}")
        self.log("服务器进程已退出")
        self.server_console_mode = False
        self.server_process = None

if __name__ == "__main__":
    root = tk.Tk()
    app = SimpleMCLauncher(root)
    app.entry.focus_set()
    root.mainloop()