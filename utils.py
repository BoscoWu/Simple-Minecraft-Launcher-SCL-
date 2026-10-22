# utils.py
# Simple MC Launcher (https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-)
# Copyright (C) 2026 BoscoWu
#
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.

import os
import sys
import json
import time
import shutil
import hashlib
import logging
import platform
import subprocess
from logging.handlers import RotatingFileHandler
from typing import Any, Callable, Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# ---------------------------------------------------------------------------
# 路径常量
# ---------------------------------------------------------------------------
if getattr(sys, "frozen", False):
    # 打包成 exe 后，脚本位于临时解包目录，但数据应放在 exe 同级目录
    SCRIPT_DIR = os.path.dirname(sys.executable)
else:
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

PYLAUNCHER_DIR = os.path.join(SCRIPT_DIR, "pyLauncher")
MC_DIR = os.path.join(PYLAUNCHER_DIR, ".minecraft")
SERVER_DIR = os.path.join(PYLAUNCHER_DIR, "servers")
JAVA_DIR = os.path.join(PYLAUNCHER_DIR, "java")
CONFIG_FILE = os.path.join(PYLAUNCHER_DIR, "launcher_config.json")
LOG_FILE = os.path.join(PYLAUNCHER_DIR, "launcher.log")

VERSION_OF_LAUNCHER = "v3.1.0"
REPO_OWNER = "BoscoWu"
REPO_NAME = "Simple-Minecraft-Launcher-SCL-"

USER_AGENT = f"SimpleMCLauncher/{VERSION_OF_LAUNCHER}"

CLIENT_ID = "8d7c60d0-fddf-464c-a9f4-d190f1daa576"
REDIRECT_URL = "https://login.microsoftonline.com/common/oauth2/nativeclient"

# ---------------------------------------------------------------------------
# frp 相关常量
# ---------------------------------------------------------------------------
FRP_DIR = os.path.join(PYLAUNCHER_DIR, "frp")
FRPC_CONFIG = os.path.join(FRP_DIR, "frpc.toml")
FRPC_LOG = os.path.join(FRP_DIR, "frpc.log")
FRP_DOWNLOAD_URL = {
    "windows": {
        "amd64": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_windows_amd64.zip",
        "386": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_windows_386.zip",
    },
    "linux": {
        "amd64": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_linux_amd64.tar.gz",
        "arm64": "https://github.com/fatedier/frp/releases/download/v0.61.2/frp_0.61.2_linux_arm64.tar.gz",
    },
}

# ---------------------------------------------------------------------------
# 日志系统
# ---------------------------------------------------------------------------
_logging_configured = False


def setup_logging() -> logging.Logger:
    """配置日志系统，同时输出到控制台和 launcher.log 文件。

    该函数是幂等的：重复调用不会重复添加 handler。
    返回启动器的根 logger。
    """
    global _logging_configured
    logger = logging.getLogger("launcher")
    if _logging_configured:
        return logger

    logger.setLevel(logging.INFO)
    logger.propagate = False

    fmt = logging.Formatter(
        "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # 文件 handler（带轮转，最大 2MB，保留 3 份）
    try:
        os.makedirs(PYLAUNCHER_DIR, exist_ok=True)
        fh = RotatingFileHandler(LOG_FILE, maxBytes=2 * 1024 * 1024,
                                 backupCount=3, encoding="utf-8")
        fh.setLevel(logging.INFO)
        fh.setFormatter(fmt)
        logger.addHandler(fh)
    except Exception:
        # 日志文件创建失败不应阻断启动
        pass

    # 控制台 handler
    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    ch.setFormatter(fmt)
    logger.addHandler(ch)

    _logging_configured = True
    return logger


def get_logger(name: str = "launcher") -> logging.Logger:
    """获取指定名字的子 logger。"""
    return logging.getLogger(name)


# ---------------------------------------------------------------------------
# 网络：带重试的 Session
# ---------------------------------------------------------------------------
def create_session() -> requests.Session:
    """创建一个带重试与超时策略的 requests.Session。"""
    session = requests.Session()
    retry = Retry(
        total=5,
        connect=5,
        read=5,
        backoff_factor=1,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=frozenset(["GET", "HEAD"]),
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=10, pool_maxsize=10)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    session.headers.update({"User-Agent": USER_AGENT})
    return session


_shared_session: Optional[requests.Session] = None


def get_session() -> requests.Session:
    """获取全局共享的 Session（懒加载）。"""
    global _shared_session
    if _shared_session is None:
        _shared_session = create_session()
    return _shared_session


# ---------------------------------------------------------------------------
# 文件与哈希工具
# ---------------------------------------------------------------------------
def file_sha1(path: str) -> str:
    """计算文件 SHA1，用于校验下载完整性。"""
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def human_size(num: float) -> str:
    """将字节数转为人类可读字符串。"""
    num = float(num)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if num < 1024 or unit == "TB":
            return f"{num:.1f} {unit}" if unit != "B" else f"{int(num)} B"
        num /= 1024
    return f"{num:.1f} TB"


# ---------------------------------------------------------------------------
# 下载：断点续传 + 超时重试 + 进度回调
# ---------------------------------------------------------------------------
def download_file(
    url: str,
    dest_path: str,
    callback: Optional[Callable[[int, int], None]] = None,
    sha1: Optional[str] = None,
    session: Optional[requests.Session] = None,
    headers: Optional[dict] = None,
    chunk_size: int = 8192,
    max_retries: int = 5,
    timeout: tuple = (15, 120),
) -> str:
    """下载文件到 dest_path，支持断点续传、超时重试与进度回调。

    :param url: 下载地址
    :param dest_path: 目标文件路径
    :param callback: 进度回调 callback(downloaded_bytes, total_bytes)，
                     当 total 未知时 total 为 0
    :param sha1: 可选的 SHA1 校验值，下载完成后校验
    :param session: 复用的 requests.Session，默认使用全局 Session
    :param headers: 额外的请求头
    :return: dest_path
    :raises: 下载失败或校验失败时抛出异常
    """
    sess = session or get_session()
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)

    # 如果目标文件已存在且校验通过，则直接返回
    if os.path.isfile(dest_path) and sha1:
        try:
            if file_sha1(dest_path) == sha1:
                return dest_path
        except Exception:
            pass

    part_path = dest_path + ".part"
    last_error: Optional[Exception] = None

    for attempt in range(max_retries):
        try:
            existing = 0
            if os.path.isfile(part_path):
                existing = os.path.getsize(part_path)

            req_headers = dict(headers or {})
            if existing > 0:
                req_headers["Range"] = f"bytes={existing}-"

            with sess.get(url, headers=req_headers, stream=True, timeout=timeout) as resp:
                if existing > 0 and resp.status_code == 206:
                    # 服务器支持断点续传，从已有偏移继续写入
                    _stream_to_file(resp, part_path, "ab", existing, callback, chunk_size)
                elif existing > 0:
                    # 服务器忽略 Range（返回 200 全文或 416），从头重新下载
                    resp.close()
                    with sess.get(url, headers=dict(headers or {}), stream=True,
                                  timeout=timeout) as resp2:
                        resp2.raise_for_status()
                        _stream_to_file(resp2, part_path, "wb", 0, callback, chunk_size)
                else:
                    resp.raise_for_status()
                    _stream_to_file(resp, part_path, "wb", 0, callback, chunk_size)

            # 校验
            if sha1:
                actual = file_sha1(part_path)
                if actual.lower() != sha1.lower():
                    raise ValueError(f"SHA1 校验失败: 期望 {sha1}，实际 {actual}")

            # 原子替换
            if os.path.exists(dest_path):
                os.remove(dest_path)
            os.replace(part_path, dest_path)
            return dest_path

        except Exception as e:  # noqa: BLE001
            last_error = e
            if attempt < max_retries - 1:
                time.sleep(1 + attempt * 2)

    raise RuntimeError(f"下载失败（已重试 {max_retries} 次）: {url} -> {last_error}")


def _stream_to_file(resp, path, mode, start_offset, callback, chunk_size):
    """把响应流写入文件，并触发进度回调。"""
    total = int(resp.headers.get("Content-Length", 0) or 0)
    downloaded = start_offset
    if total:
        total += start_offset
    with open(path, mode) as f:
        for chunk in resp.iter_content(chunk_size=chunk_size):
            if not chunk:
                continue
            f.write(chunk)
            downloaded += len(chunk)
            if callback:
                callback(downloaded, total)


def fetch_json(
    url: str,
    session: Optional[requests.Session] = None,
    timeout: tuple = (15, 60),
    **kwargs: Any,
) -> Any:
    """GET 请求并解析 JSON，自动带重试与超时。"""
    sess = session or get_session()
    resp = sess.get(url, timeout=timeout, **kwargs)
    resp.raise_for_status()
    return resp.json()


# ---------------------------------------------------------------------------
# 配置读写（带损坏恢复）
# ---------------------------------------------------------------------------
DEFAULT_CONFIG = {
    "player_name": "Player",
    "current_version": "",
    "current_loader": "vanilla",
    "original_version": "",
    "ms_refresh_token": None,
    "ms_username": None,
    "ms_uuid": None,
    "ms_access_token": None,
    "java_home": {},
    "max_memory": "2G",
    "min_memory": "1G",
}


def load_config() -> dict:
    """读取配置文件。

    如果文件不存在则返回默认配置；
    如果文件损坏（JSON 解析失败）则自动备份损坏文件并重置为默认配置，
    而不会让程序崩溃。
    """
    if not os.path.exists(CONFIG_FILE):
        return dict(DEFAULT_CONFIG)

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            raise ValueError("配置文件不是有效的 JSON 对象")
        # 合并默认值，保证缺失的键有默认值
        config = dict(DEFAULT_CONFIG)
        config.update(data)
        return config
    except json.JSONDecodeError as e:
        _backup_corrupted_config()
        logging.getLogger("launcher").warning(
            "配置文件损坏（JSON 解析失败），已重置为默认配置: %s", e
        )
        return dict(DEFAULT_CONFIG)
    except Exception as e:  # noqa: BLE001
        _backup_corrupted_config()
        logging.getLogger("launcher").warning(
            "读取配置文件失败，已重置为默认配置: %s", e
        )
        return dict(DEFAULT_CONFIG)


def _backup_corrupted_config() -> None:
    """把损坏的配置文件备份成 .bak，避免覆盖用户数据。"""
    try:
        if os.path.exists(CONFIG_FILE):
            shutil.copy2(CONFIG_FILE, CONFIG_FILE + ".corrupt.bak")
    except Exception:
        pass


def save_config(config: dict) -> None:
    """原子化写入配置文件，避免写入中途断电导致文件损坏。"""
    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    tmp_path = CONFIG_FILE + ".tmp"
    try:
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, CONFIG_FILE)
    except Exception as e:  # noqa: BLE001
        logging.getLogger("launcher").error("保存配置失败: %s", e)
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Java 检查
# ---------------------------------------------------------------------------
def check_java() -> tuple[bool, str]:
    """检查系统 PATH 中是否有可用的 Java。

    返回 (是否可用, 描述信息)。
    """
    java_path = shutil.which("java")
    if java_path is None:
        return False, "未在系统 PATH 中找到 Java，请安装 Java 8 或更高版本。"
    try:
        result = subprocess.run(
            ["java", "-version"],
            capture_output=True,
            text=True,
            timeout=15,
            encoding="utf-8",
            errors="replace",
        )
        output = (result.stderr or "").strip() or (result.stdout or "").strip()
        if result.returncode == 0:
            first_line = output.splitlines()[0] if output else "未知版本"
            return True, f"Java 可用（{java_path}）: {first_line}"
        return False, f"Java 命令执行失败，错误码 {result.returncode}"
    except FileNotFoundError:
        return False, "未找到 Java 可执行文件。"
    except Exception as e:  # noqa: BLE001
        return False, f"检查 Java 时出错: {e}"


def find_java_executable() -> Optional[str]:
    """返回系统 Java 可执行文件的完整路径，找不到则返回 None。"""
    path = shutil.which("java")
    return path


# ---------------------------------------------------------------------------
# 版本号比较
# ---------------------------------------------------------------------------
def compare_versions(v1: str, v2: str) -> int:
    """比较两个版本号字符串，返回 1 / 0 / -1。"""

    def norm(v: str):
        parts = []
        for x in re_split_version(v):
            try:
                parts.append(int(x))
            except ValueError:
                parts.append(x)
        return parts

    a, b = norm(v1), norm(v2)
    for i in range(max(len(a), len(b))):
        x = a[i] if i < len(a) else 0
        y = b[i] if i < len(b) else 0
        if isinstance(x, int) and isinstance(y, int):
            if x != y:
                return 1 if x > y else -1
        else:
            xs, ys = str(x), str(y)
            if xs != ys:
                return 1 if xs > ys else -1
    return 0


def re_split_version(v: str):
    import re
    return re.split(r"[.\-+]", str(v))
