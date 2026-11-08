# utils.py
# Simple MC Launcher (https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-)
# Copyright (C) 2026 BoscoWu
#
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.

# utils.py
import os
import sys
import json
import time
import shutil
import hashlib
import logging
import platform
import threading
import subprocess
from logging.handlers import RotatingFileHandler
from typing import Any, Callable, Optional, Tuple

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

if getattr(sys, "frozen", False):
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

# 更新检查：官网与 GitHub 双源（国内直连 GitHub API 常被阻断，走镜像）
SITE_URL = "https://simplemclauncher.xin"
UPDATE_API_URLS = (
    f"https://gh-proxy.com/https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest",
    f"https://ghproxy.net/https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest",
    f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest",
)
# 下载加速前缀（按序尝试）
UPDATE_DL_PREFIXES = (
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
    "",
)
UPDATE_ASSET_NAME = "SimpleMCLauncher.exe"

USER_AGENT = f"SimpleMCLauncher/{VERSION_OF_LAUNCHER}"

CLIENT_ID = "8d7c60d0-fddf-464c-a9f4-d190f1daa576"
REDIRECT_URL = "https://login.microsoftonline.com/common/oauth2/nativeclient"

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

_logging_configured = False

def setup_logging() -> logging.Logger:
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

    try:
        os.makedirs(PYLAUNCHER_DIR, exist_ok=True)
        fh = RotatingFileHandler(LOG_FILE, maxBytes=2 * 1024 * 1024,
                                 backupCount=3, encoding="utf-8")
        fh.setLevel(logging.INFO)
        fh.setFormatter(fmt)
        logger.addHandler(fh)
    except Exception:
        pass

    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    ch.setFormatter(fmt)
    logger.addHandler(ch)

    _logging_configured = True
    return logger

def get_logger(name: str = "launcher") -> logging.Logger:
    return logging.getLogger(name)

def create_session() -> requests.Session:
    session = requests.Session()
    retry = Retry(
        total=5,
        connect=5,
        read=5,
        backoff_factor=1,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=frozenset(["GET", "HEAD"]),
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=32, pool_maxsize=32)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    session.headers.update({"User-Agent": USER_AGENT})
    return session

_shared_session: Optional[requests.Session] = None

def get_session() -> requests.Session:
    global _shared_session
    if _shared_session is None:
        _shared_session = create_session()
    return _shared_session

def file_sha1(path: str) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

def human_size(num: float) -> str:
    num = float(num)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if num < 1024 or unit == "TB":
            return f"{num:.1f} {unit}" if unit != "B" else f"{int(num)} B"
        num /= 1024
    return f"{num:.1f} TB"

def download_file(
    url: str,
    dest_path: str,
    callback: Optional[Callable[[int, int], None]] = None,
    sha1: Optional[str] = None,
    session: Optional[requests.Session] = None,
    headers: Optional[dict] = None,
    chunk_size: int = 1024 * 1024,
    max_retries: int = 5,
    timeout: tuple = (15, 120),
    connections: int = 16,
) -> str:
    sess = session or get_session()
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)

    if os.path.isfile(dest_path) and sha1:
        try:
            if file_sha1(dest_path) == sha1:
                return dest_path
        except Exception:
            pass

    total_size, supports_range = _probe_download(sess, url, headers, timeout)

    if (supports_range and total_size and total_size > 1024 * 1024
            and connections > 1):
        try:
            return _multi_part_download(
                sess, url, dest_path, total_size, callback, sha1,
                headers=headers, chunk_size=chunk_size,
                connections=connections, timeout=timeout, max_retries=max_retries)
        except Exception:
            pass

    return _single_download(
        sess, url, dest_path, callback, sha1,
        headers=headers, chunk_size=chunk_size,
        max_retries=max_retries, timeout=timeout)

def _probe_download(sess, url, headers, timeout) -> Tuple[int, bool]:
    try:
        req_h = dict(headers or {})
        req_h["Range"] = "bytes=0-0"
        r = sess.get(url, headers=req_h, timeout=timeout, allow_redirects=True, stream=True)
        if r.status_code == 206:
            cr = r.headers.get("Content-Range", "")
            if "/" in cr:
                size = int(cr.split("/")[-1])
                r.close()
                return size, True
        r.close()
        
        r = sess.head(url, headers=dict(headers or {}), timeout=timeout, allow_redirects=True)
        if r.status_code == 200:
            size = int(r.headers.get("Content-Length", 0) or 0)
            accept = r.headers.get("Accept-Ranges", "")
            return size, ("bytes" in accept.lower())
    except Exception:
        pass
    return 0, False

def _multi_part_download(sess, url, dest_path, total_size, callback, sha1,
                         headers, chunk_size, connections, timeout, max_retries) -> str:
    import concurrent.futures as _futures

    part_dir = dest_path + ".parts"
    os.makedirs(part_dir, exist_ok=True)

    # 1MB 分片，并发数支持到 16
    seg_count = min(connections, max(1, total_size // (1024 * 1024)))
    seg_size = total_size // seg_count
    ranges = []
    for i in range(seg_count):
        start = i * seg_size
        end = total_size - 1 if i == seg_count - 1 else (start + seg_size - 1)
        ranges.append((i, start, end))

    done_lock = threading.Lock()
    seg_progress = {i: 0 for i, _, _ in ranges}
    last_report_time = [0.0]

    def report(i, got):
        with done_lock:
            seg_progress[i] = got
            now = time.time()
            if callback and (now - last_report_time[0] > 0.05 or sum(seg_progress.values()) == total_size):
                last_report_time[0] = now
                callback(sum(seg_progress.values()), total_size)

    def fetch_segment(idx, start, end):
        seg_path = os.path.join(part_dir, f"{idx}.part")
        want = end - start + 1
        have = os.path.getsize(seg_path) if os.path.isfile(seg_path) else 0
        if have >= want:
            report(idx, want)
            return seg_path

        for attempt in range(max_retries):
            try:
                req_h = dict(headers or {})
                req_h["Range"] = f"bytes={start + have}-{end}"
                with sess.get(url, headers=req_h, stream=True, timeout=timeout) as resp:
                    if resp.status_code not in (200, 206):
                        raise RuntimeError(f"HTTP {resp.status_code}")
                    mode = "ab" if have else "wb"
                    with open(seg_path, mode) as f:
                        cur = have
                        for data in resp.iter_content(chunk_size=chunk_size):
                            if not data:
                                continue
                            f.write(data)
                            cur += len(data)
                            report(idx, cur)
                            if cur >= want:
                                break
                if os.path.getsize(seg_path) >= want:
                    return seg_path
                have = os.path.getsize(seg_path)
            except Exception:
                have = os.path.getsize(seg_path) if os.path.isfile(seg_path) else 0
                time.sleep(1 + attempt * 2)
        raise RuntimeError(f"分片 {idx} 下载失败")

    with _futures.ThreadPoolExecutor(max_workers=seg_count) as pool:
        futs = [pool.submit(fetch_segment, i, s, e) for i, s, e in ranges]
        for f in futs:
            f.result()

    merged = dest_path + ".merging"
    with open(merged, "wb") as out:
        for i, _, _ in ranges:
            with open(os.path.join(part_dir, f"{i}.part"), "rb") as src:
                shutil.copyfileobj(src, out, length=chunk_size)

    if sha1:
        actual = file_sha1(merged)
        if actual.lower() != sha1.lower():
            shutil.rmtree(part_dir, ignore_errors=True)
            try:
                os.remove(merged)
            except OSError:
                pass
            raise ValueError(f"SHA1 校验失败: 期望 {sha1}，实际 {actual}")

    if os.path.exists(dest_path):
        os.remove(dest_path)
    os.replace(merged, dest_path)
    shutil.rmtree(part_dir, ignore_errors=True)
    return dest_path

def _single_download(sess, url, dest_path, callback, sha1,
                     headers, chunk_size, max_retries, timeout) -> str:
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
                    _stream_to_file(resp, part_path, "ab", existing, callback, chunk_size)
                elif existing > 0:
                    resp.close()
                    with sess.get(url, headers=dict(headers or {}), stream=True,
                                  timeout=timeout) as resp2:
                        resp2.raise_for_status()
                        _stream_to_file(resp2, part_path, "wb", 0, callback, chunk_size)
                else:
                    resp.raise_for_status()
                    _stream_to_file(resp, part_path, "wb", 0, callback, chunk_size)

            if sha1:
                actual = file_sha1(part_path)
                if actual.lower() != sha1.lower():
                    raise ValueError(f"SHA1 校验失败: 期望 {sha1}，实际 {actual}")

            if os.path.exists(dest_path):
                os.remove(dest_path)
            os.replace(part_path, dest_path)
            return dest_path

        except Exception as e:
            last_error = e
            if attempt < max_retries - 1:
                time.sleep(1 + attempt * 2)

    raise RuntimeError(f"下载失败（已重试 {max_retries} 次）: {url} -> {last_error}")

def _stream_to_file(resp, path, mode, start_offset, callback, chunk_size):
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
    sess = session or get_session()
    resp = sess.get(url, timeout=timeout, **kwargs)
    resp.raise_for_status()
    return resp.json()

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
    if not os.path.exists(CONFIG_FILE):
        return dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            raise ValueError("配置文件不是有效的 JSON 对象")
        config = dict(DEFAULT_CONFIG)
        config.update(data)
        return config
    except json.JSONDecodeError as e:
        _backup_corrupted_config()
        logging.getLogger("launcher").warning(
            "配置文件损坏（JSON 解析失败），已重置为默认配置: %s", e
        )
        return dict(DEFAULT_CONFIG)
    except Exception as e:
        _backup_corrupted_config()
        logging.getLogger("launcher").warning(
            "读取配置文件失败，已重置为默认配置: %s", e
        )
        return dict(DEFAULT_CONFIG)

def _backup_corrupted_config() -> None:
    try:
        if os.path.exists(CONFIG_FILE):
            shutil.copy2(CONFIG_FILE, CONFIG_FILE + ".corrupt.bak")
    except Exception:
        pass

def save_config(config: dict) -> None:
    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    tmp_path = CONFIG_FILE + ".tmp"
    try:
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, CONFIG_FILE)
    except Exception as e:
        logging.getLogger("launcher").error("保存配置失败: %s", e)
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except Exception:
            pass

def check_java() -> tuple[bool, str]:
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
    except Exception as e:
        return False, f"检查 Java 时出错: {e}"

def find_java_executable() -> Optional[str]:
    path = shutil.which("java")
    return path

def compare_versions(v1: str, v2: str) -> int:
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