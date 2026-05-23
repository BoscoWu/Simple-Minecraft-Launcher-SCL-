# utils.py
import os
import sys
import json
import platform
import requests
import urllib3
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
import time
import subprocess
import shutil

# ---------- 路径常量 ----------
if getattr(sys, 'frozen', False):
    SCRIPT_DIR = os.path.dirname(sys.executable)
else:
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

PYLAUNCHER_DIR = os.path.join(SCRIPT_DIR, "pyLauncher")
MC_DIR = os.path.join(PYLAUNCHER_DIR, ".minecraft")
SERVER_DIR = os.path.join(PYLAUNCHER_DIR, "servers")
JAVA_DIR = os.path.join(PYLAUNCHER_DIR, "java")
CONFIG_FILE = os.path.join(PYLAUNCHER_DIR, "launcher_config.json")
VERSION_OF_LAUNCHER = "v3.0.3"
REPO_OWNER = "BoscoWu"
REPO_NAME = "Simple-Minecraft-Launcher-SCL-"

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

CLIENT_ID = "8d7c60d0-fddf-464c-a9f4-d190f1daa576"
REDIRECT_URL = "https://login.microsoftonline.com/common/oauth2/nativeclient"

SERVER_SOURCES = {
    "spigot": "https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/paper-{version}-{build}.jar",
    "paper": "https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/paper-{version}-{build}.jar",
    "purpur": "https://api.purpurmc.org/v2/purpur/{version}/latest/download",
    "fabric": "https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/server/jar",
    "forge": "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/{forge}/forge-{forge}-server.jar"
}

# ---------- 网络优化 ----------
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

# ---------- 配置管理 ----------
def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            pass
    return {"player_name": "Player", "current_version": "", "current_loader": "vanilla",
            "original_version": "", "ms_refresh_token": None, "ms_username": None,
            "java_home": {}}

def save_config(config):
    with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
        json.dump(config, f, indent=2)

# ---------- Java 检查 ----------
def check_java():
    java_path = shutil.which("java")
    if java_path is None:
        return False, "未找到 Java，请安装 Java 8 或更高版本。"
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
            return True, f"Java 可用: {output.splitlines()[0] if output else '未知'}"
        else:
            return False, f"Java 命令执行失败: 错误码 {result.returncode}"
    except Exception as e:
        return False, f"检查 Java 时出错: {e}"