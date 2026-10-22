# core.py
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
import re
import shutil
import zipfile
import tempfile
import threading
import subprocess
import platform
import traceback
import webbrowser
from typing import Any, Callable, Dict, List, Optional, Tuple

import requests
import minecraft_launcher_lib
from minecraft_launcher_lib import microsoft_account
from minecraft_launcher_lib import mod_loader as _mod_loader
from minecraft_launcher_lib import mrpack as _mrpack

from utils import (
    PYLAUNCHER_DIR, MC_DIR, SERVER_DIR, JAVA_DIR, CONFIG_FILE,
    CLIENT_ID, REDIRECT_URL, USER_AGENT, VERSION_OF_LAUNCHER,
    REPO_OWNER, REPO_NAME, FRP_DIR, FRPC_CONFIG, FRPC_LOG, FRP_DOWNLOAD_URL,
    load_config, save_config, check_java, find_java_executable,
    download_file, fetch_json, get_session, file_sha1, human_size,
    setup_logging, get_logger, compare_versions,
)

log = get_logger("launcher.core")

# Modrinth API 地址
MODRINTH_API = "https://api.modrinth.com/v2"

# 支持的加载器及其 Modrinth loader 标识
LOADER_NAMES = {
    "vanilla": "原版",
    "fabric": "Fabric",
    "forge": "Forge",
    "neoforge": "NeoForge",
    "quilt": "Quilt",
}


# ---------------------------------------------------------------------------
# 网络补丁：默认超时 + NeoForge 国内镜像
# ---------------------------------------------------------------------------
def _patch_request_timeout() -> None:
    """给 requests 的所有请求加默认超时，避免网络故障时无限卡死。

    minecraft-launcher-lib 内部大量使用裸 ``requests.get(...)`` 且不带超时，
    遇到被墙/丢包会挂很久。这里统一补上默认超时（连接 15s / 读取 60s）。
    """
    import requests as _requests

    if getattr(_requests.sessions.Session, "_sml_timeout_patched", False):
        return
    _orig_request = _requests.sessions.Session.request

    def _request(self, method, url, **kwargs):
        if "timeout" not in kwargs:
            kwargs["timeout"] = (15, 60)
        return _orig_request(self, method, url, **kwargs)

    _requests.sessions.Session.request = _request
    _requests.sessions.Session._sml_timeout_patched = True  # type: ignore[attr-defined]


def _patch_neoforge_mirror() -> None:
    """把 NeoForge 的下载源切换到国内镜像。

    ``maven.neoforged.net`` 在国内无法直连（连接超时），而其版本列表 API 又没有
    现成镜像。这里改用 bmclapi2 镜像的 maven-metadata.xml 来获取版本，并让安装器
    也从镜像下载，从而完整地绕开被墙的官方源。
    """
    import xml.etree.ElementTree as ET
    import minecraft_launcher_lib.mod_loader._neoforge as _nf

    if getattr(_nf.Neoforge, "_sml_mirror_patched", False):
        return

    NEOFORGE_METADATA = "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml"
    NEOFORGE_MAVEN = "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge"
    _VER_RE = re.compile(r"^(\d+)\.(\d+)")
    _cache: Dict[str, Optional[List[str]]] = {"versions": None}

    def _unstable(v: str) -> bool:
        low = v.lower()
        return any(m in low for m in ("alpha", "beta", "snapshot", "pre", "rc", "nightly", "craftmine"))

    def _fetch_versions() -> List[str]:
        if _cache["versions"] is None:
            resp = get_session().get(NEOFORGE_METADATA, timeout=(15, 60))
            resp.raise_for_status()
            root = ET.fromstring(resp.text)
            _cache["versions"] = [v.text for v in root.iter() if v.tag.endswith("version") and v.text]
        return _cache["versions"]

    def _num_key(v: str):
        return tuple(int(n) for n in re.findall(r"\d+", v))

    def get_minecraft_versions(self, stable_only: bool) -> List[str]:
        result: Dict[str, bool] = {}
        for v in _fetch_versions():
            m = _VER_RE.match(v)
            if not m or m.group(1) == "0":
                continue
            if stable_only and _unstable(v):
                continue
            result[self._normalize_minecraft_version(m.group(0))] = True
        return list(result.keys())

    def get_loader_versions(self, minecraft_version: str, stable_only: bool) -> List[str]:
        out: List[str] = []
        for v in _fetch_versions():
            m = _VER_RE.match(v)
            if not m or m.group(1) == "0":
                continue
            if self._normalize_minecraft_version(m.group(0)) != minecraft_version:
                continue
            if stable_only and _unstable(v):
                continue
            out.append(v)
        out.sort(key=_num_key, reverse=True)
        return out

    def get_installer_url(self, minecraft_version: str, loader_version: str) -> str:
        return f"{NEOFORGE_MAVEN}/{loader_version}/neoforge-{loader_version}-installer.jar"

    # 安装器运行时会从 maven.neoforged.net 下载 NeoForge 依赖，同样被墙，
    # 因此给安装器传 --mirror 指向国内镜像。
    NEOFORGE_MIRROR_BASE = "https://bmclapi2.bangbang93.com/maven"

    def install(self, minecraft_version: str, minecraft_directory: str,
                callback: dict, java: str, loader_version: str) -> None:
        from minecraft_launcher_lib.vanilla_launcher import (
            do_vanilla_launcher_profiles_exists,
            create_empty_vanilla_launcher_profiles_file,
        )
        from minecraft_launcher_lib._helper import (
            download_file as _lib_download_file,
            empty,
            SUBPROCESS_STARTUP_INFO,
        )
        import tempfile as _tempfile

        if not do_vanilla_launcher_profiles_exists(minecraft_directory):
            create_empty_vanilla_launcher_profiles_file(minecraft_directory)

        with _tempfile.TemporaryDirectory(prefix="minecraft-launcher-lib-") as tempdir:
            installer_path = os.path.join(tempdir, "neoforge-installer.jar")
            _lib_download_file(self.get_installer_url(minecraft_version, loader_version), installer_path)

            callback.get("setStatus", empty)("Running installer")
            command = [
                java, "-jar", installer_path, "--install-client", str(minecraft_directory),
                "--mirror", NEOFORGE_MIRROR_BASE,
            ]
            result = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=tempdir,
                startupinfo=SUBPROCESS_STARTUP_INFO,
            )
            if result.returncode != 0:
                out = (result.stdout or b"").decode("utf-8", "replace").strip()
                err = (result.stderr or b"").decode("utf-8", "replace").strip()
                detail = (err or out)[-2000:]
                raise RuntimeError(f"NeoForge 安装器执行失败（退出码 {result.returncode}）:\n{detail}")

    _nf.Neoforge.get_minecraft_versions = get_minecraft_versions
    _nf.Neoforge.get_loader_versions = get_loader_versions
    _nf.Neoforge.get_installer_url = get_installer_url
    _nf.Neoforge.install = install
    _nf.Neoforge._sml_mirror_patched = True  # type: ignore[attr-defined]


# 模块导入时应用网络补丁
_patch_request_timeout()
_patch_neoforge_mirror()


class LauncherCore:
    """启动器核心，封装了账号、安装、启动、模组、服务器、Java、frp 等全部功能。"""

    def __init__(
        self,
        log_callback: Optional[Callable[[str], None]] = None,
        status_callback: Optional[Callable[[str], None]] = None,
        progress_callback: Optional[Callable[[int, int], None]] = None,
    ):
        setup_logging()
        self.log_callback = log_callback or (lambda m: None)
        self.status_callback = status_callback or (lambda m: None)
        self.progress_callback = progress_callback or (lambda c, t: None)

        self.config = load_config()
        self.game_process: Optional[subprocess.Popen] = None
        self.server_process: Optional[subprocess.Popen] = None
        self.frp_process: Optional[subprocess.Popen] = None
        self.deepseek_api_key = ""
        self.conversation_history: List[Dict[str, str]] = []

        self._ensure_directories()
        self._load_deepseek_config()

    # ------------------------------------------------------------------
    # 基础工具
    # ------------------------------------------------------------------
    def _ensure_directories(self) -> None:
        for d in (PYLAUNCHER_DIR, MC_DIR, SERVER_DIR, JAVA_DIR):
            try:
                os.makedirs(d, exist_ok=True)
            except Exception as e:  # noqa: BLE001
                log.error("创建目录失败 %s: %s", d, e)

    def log(self, message: str) -> None:
        log.info(message)
        try:
            self.log_callback(message)
        except Exception:  # noqa: BLE001
            pass

    def status(self, message: str) -> None:
        try:
            self.status_callback(message)
        except Exception:  # noqa: BLE001
            pass

    def progress(self, current: int, total: int) -> None:
        try:
            self.progress_callback(current, total)
        except Exception:  # noqa: BLE001
            pass

    def save_config(self) -> None:
        save_config(self.config)

    def _make_callbacks(self) -> Dict[str, Any]:
        """构造 minecraft_launcher_lib 需要的回调字典。"""
        return {
            "setStatus": self.status,
            "setProgress": lambda p: self.progress(p, 0),
            "setMax": lambda m: self.progress(0, m),
        }

    # ------------------------------------------------------------------
    # 账号
    # ------------------------------------------------------------------
    def get_player_display(self) -> str:
        if self.config.get("ms_refresh_token"):
            return self.config.get("ms_username", "已登录用户")
        return self.config.get("player_name", "Player")

    def is_microsoft_logged_in(self) -> bool:
        return bool(self.config.get("ms_refresh_token"))

    def set_player_name(self, name: str) -> None:
        name = (name or "").strip()
        if not name:
            self.log("玩家名不能为空")
            return
        self.config["player_name"] = name
        self.save_config()
        self.log(f"离线玩家名已设置为: {name}")

    def microsoft_login(self) -> None:
        threading.Thread(target=self._microsoft_device_login, daemon=True).start()

    def _microsoft_device_login(self) -> None:
        self.log("正在启动微软设备代码登录流程...")
        try:
            device_resp = requests.post(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                data={"client_id": CLIENT_ID, "scope": "XboxLive.signin offline_access"},
                timeout=30,
            )
            if device_resp.status_code != 200:
                self.log(f"设备码请求失败: HTTP {device_resp.status_code}")
                return
            device_data = device_resp.json()
            user_code = device_data["user_code"]
            device_code = device_data["device_code"]
            verify_url = device_data.get("verification_uri", "https://www.microsoft.com/link")
            expires_in = int(device_data.get("expires_in", 900))
            interval = int(device_data.get("interval", 5))

            try:
                import pyperclip  # 可选依赖
                pyperclip.copy(user_code)
                self.log(f"设备代码已复制到剪贴板: {user_code}")
            except Exception:  # noqa: BLE001
                self.log(f"请手动复制设备代码: {user_code}")

            self.log(f"请打开浏览器访问: {verify_url}")
            self.log(f"输入设备代码: {user_code}")
            try:
                webbrowser.open(verify_url)
            except Exception:  # noqa: BLE001
                pass

            start_time = time.time()
            ms_token_data = None
            while time.time() - start_time < expires_in:
                time.sleep(interval)
                token_resp = requests.post(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    data={
                        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id": CLIENT_ID,
                        "device_code": device_code,
                    },
                    timeout=30,
                )
                token_data = token_resp.json()
                if "access_token" in token_data:
                    ms_token_data = token_data
                    break
                err = token_data.get("error")
                if err == "authorization_pending":
                    self.status("等待完成登录...")
                elif err == "slow_down":
                    interval += 5
                elif err == "authorization_declined":
                    self.log("登录被用户取消。")
                    return
                elif err == "expired_token":
                    self.log("设备代码已过期，请重新登录。")
                    return
                else:
                    self.log(f"微软登录失败: {token_data}")
                    return

            if not ms_token_data:
                self.log("登录超时")
                return

            ms_access_token = ms_token_data["access_token"]
            ms_refresh_token = ms_token_data.get("refresh_token")

            # Xbox Live
            xbl_resp = requests.post(
                "https://user.auth.xboxlive.com/user/authenticate",
                json={
                    "Properties": {
                        "AuthMethod": "RPS",
                        "SiteName": "user.auth.xboxlive.com",
                        "RpsTicket": f"d={ms_access_token}",
                    },
                    "RelyingParty": "http://auth.xboxlive.com",
                    "TokenType": "JWT",
                },
                headers={"Content-Type": "application/json"},
                timeout=30,
            )
            xbl_resp.raise_for_status()
            xbl_data = xbl_resp.json()
            xbl_token = xbl_data["Token"]
            uhs = xbl_data["DisplayClaims"]["xui"][0]["uhs"]

            # XSTS
            xsts_resp = requests.post(
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                json={
                    "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
                    "RelyingParty": "rp://api.minecraftservices.com/",
                    "TokenType": "JWT",
                },
                headers={"Content-Type": "application/json"},
                timeout=30,
            )
            if xsts_resp.status_code != 200:
                self._handle_xsts_error(xsts_resp)
                return
            xsts_data = xsts_resp.json()
            xsts_token = xsts_data["Token"]

            # Minecraft 登录
            mc_auth_resp = requests.post(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                json={"identityToken": f"XBL3.0 x={uhs};{xsts_token}"},
                headers={"Content-Type": "application/json"},
                timeout=30,
            )
            mc_auth_resp.raise_for_status()
            mc_auth_data = mc_auth_resp.json()
            mc_access_token = mc_auth_data["access_token"]

            profile_resp = requests.get(
                "https://api.minecraftservices.com/minecraft/profile",
                headers={"Authorization": f"Bearer {mc_access_token}"},
                timeout=30,
            )
            if profile_resp.status_code != 200:
                self.log("此账号可能没有正版 Minecraft Java Edition。")
                return
            profile = profile_resp.json()

            self.config["ms_refresh_token"] = ms_refresh_token
            self.config["ms_username"] = profile["name"]
            self.config["ms_uuid"] = profile["id"]
            self.config["ms_access_token"] = mc_access_token
            self.save_config()
            self.log(f"登录成功！欢迎 {profile['name']}")
        except Exception as e:  # noqa: BLE001
            self.log(f"登录失败: {e}")
            log.exception("微软登录异常")

    def _handle_xsts_error(self, xsts_resp) -> None:
        try:
            err_data = xsts_resp.json()
            xerr = err_data.get("XErr")
            if xerr == 2148916233:
                self.log("此微软账号没有 Xbox 账号，请先创建。")
            elif xerr == 2148916238:
                self.log("此账号是儿童账号，需要家长授权。")
            else:
                self.log(f"XSTS 验证失败: {err_data}")
        except Exception:  # noqa: BLE001
            self.log(f"XSTS 验证失败，状态码: {xsts_resp.status_code}")

    def microsoft_logout(self) -> None:
        self.config["ms_refresh_token"] = None
        self.config["ms_username"] = None
        self.config["ms_uuid"] = None
        self.config["ms_access_token"] = None
        self.save_config()
        self.log("已退出登录")

    def refresh_microsoft_token(self) -> bool:
        refresh_token = self.config.get("ms_refresh_token")
        if not refresh_token:
            return False
        try:
            new_data = microsoft_account.complete_refresh(
                client_id=CLIENT_ID,
                refresh_token=refresh_token,
                redirect_uri=REDIRECT_URL,
                client_secret=None,
            )
            if "refresh_token" in new_data:
                self.config["ms_refresh_token"] = new_data["refresh_token"]
            self.config["ms_username"] = new_data["name"]
            self.config["ms_uuid"] = new_data["id"]
            self.config["ms_access_token"] = new_data["access_token"]
            self.save_config()
            return True
        except Exception as e:  # noqa: BLE001
            log.warning("刷新微软令牌失败: %s", e)
            return False

    def get_login_options(self) -> Optional[Dict[str, str]]:
        if self.config.get("ms_refresh_token"):
            if not self.refresh_microsoft_token():
                return None
            return {
                "username": self.config["ms_username"],
                "uuid": self.config["ms_uuid"],
                "token": self.config["ms_access_token"],
            }
        return {
            "username": self.config.get("player_name", "Player"),
            "uuid": "",
            "token": "",
        }

    # ------------------------------------------------------------------
    # 版本信息
    # ------------------------------------------------------------------
    def get_installed_versions(self) -> List[str]:
        """返回已安装的版本 id 列表（不含隐藏/无 json 的目录）。"""
        versions_dir = os.path.join(MC_DIR, "versions")
        if not os.path.isdir(versions_dir):
            return []
        result = []
        for name in os.listdir(versions_dir):
            if name.startswith("."):
                continue
            json_path = os.path.join(versions_dir, name, f"{name}.json")
            if os.path.isfile(json_path):
                result.append(name)
        return sorted(result)

    def get_available_vanilla_versions(self) -> List[str]:
        """返回 Mojang 可下载的原版版本 id 列表（含快照）。"""
        try:
            infos = minecraft_launcher_lib.utils.get_version_list()
            return [i["id"] for i in infos if i.get("type") == "release"]
        except Exception as e:  # noqa: BLE001
            log.warning("获取可用版本失败: %s", e)
            return []

    @staticmethod
    def detect_loader(version_id: str) -> str:
        """根据版本 id 推断加载器类型（兼容新旧两种命名）。"""
        # 新命名：<version>-<loader>（如 1.21.1-neoforge）
        if version_id.endswith("-neoforge"):
            return "neoforge"
        if version_id.endswith("-fabric"):
            return "fabric"
        if version_id.endswith("-quilt"):
            return "quilt"
        if version_id.endswith("-forge"):
            return "forge"
        # 旧的自然命名（兼容已安装的旧版本）
        if version_id.startswith("fabric-loader"):
            return "fabric"
        if version_id.startswith("quilt-loader"):
            return "quilt"
        if version_id.startswith("neoforge"):
            return "neoforge"
        if "-forge-" in version_id:
            return "forge"
        return "vanilla"

    def _resolve_original_version(self, version_id: str) -> str:
        """推断某版本对应的原版 MC 版本号，用于 Modrinth 查询等。"""
        # 优先用配置中的 original_version（当版本等于 current_version 时）
        if self.config.get("current_version") == version_id and self.config.get("original_version"):
            return self.config["original_version"]
        # 最可靠：读取版本 json 的 inheritsFrom（所有加载器都有该字段）
        try:
            json_path = os.path.join(MC_DIR, "versions", version_id, f"{version_id}.json")
            with open(json_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if data.get("inheritsFrom"):
                return data["inheritsFrom"]
        except Exception:  # noqa: BLE001
            pass
        # 兜底：按命名规则解析
        loader = self.detect_loader(version_id)
        if loader == "vanilla":
            return version_id
        if version_id.endswith("-" + loader):
            # 新命名 <version>-<loader>
            return version_id[: -(len(loader) + 1)]
        if loader in ("fabric", "quilt"):
            # 旧命名 fabric-loader-0.16.9-1.21.1 -> 1.21.1
            parts = version_id.rsplit("-", 1)
            return parts[1] if len(parts) == 2 else version_id
        if loader == "forge":
            # 旧命名 1.21.1-forge-52.1.2 -> 1.21.1
            return version_id.split("-forge-", 1)[0]
        return version_id

    # ------------------------------------------------------------------
    # 游戏安装
    # ------------------------------------------------------------------
    def install_vanilla(self, version: str) -> None:
        self._install_minecraft_version(version, "vanilla")

    def install_loader(self, loader_type: str, version: str) -> None:
        if loader_type not in ("fabric", "forge", "neoforge", "quilt"):
            self.log(f"不支持的加载器: {loader_type}")
            return
        self._install_minecraft_version(version, loader_type)

    def _install_minecraft_version(self, version: str, loader: str) -> None:
        """安装原版（或带加载器）的 Minecraft 版本。

        :param version: 原版 MC 版本号，如 "1.21.1"
        :param loader: vanilla / fabric / forge / neoforge / quilt
        """
        version = (version or "").strip()
        if not version:
            self.log("错误: 未指定 Minecraft 版本")
            return

        callbacks = self._make_callbacks()
        try:
            self.status(f"正在下载 Minecraft {version}...")
            self.log(f"开始安装 {LOADER_NAMES.get(loader, loader)} + Minecraft {version}")

            # 安装原版
            minecraft_launcher_lib.install.install_minecraft_version(
                version, MC_DIR, callback=callbacks
            )

            if loader == "vanilla":
                self.config["current_version"] = version
                self.config["current_loader"] = "vanilla"
                self.config["original_version"] = version
                self.save_config()
                self.log(f"原版 Minecraft {version} 安装完成！")
                self.status("安装完成")
                return

            # 检查 Java（加载器安装需要）
            java_ok, java_msg = check_java()
            if not java_ok:
                self.log(f"错误: {java_msg}")
                self.log("请先在「设置」中安装 Java 17+")
                self.status("安装失败: 缺少 Java")
                return
            self.log(java_msg)

            # 使用 mod_loader 统一安装各加载器（修复了旧版的兼容性问题）
            ml = _mod_loader.get_mod_loader(loader)
            installed_id = ml.install(version, MC_DIR, callback=callbacks)

            # 统一命名：<version>-<loader>（如 1.21.1-fabric）
            final_id = f"{version}-{loader}"
            self._rename_version(installed_id, final_id)

            self.config["current_version"] = final_id
            self.config["current_loader"] = loader
            self.config["original_version"] = version
            self.save_config()

            if loader == "fabric":
                self._install_fabric_api(version, final_id)

            self.log(f"{LOADER_NAMES[loader]} + Minecraft {version} 安装完成！")
            self.log(f"版本已命名为: {final_id}")
            self.status("安装完成")
        except Exception as e:  # noqa: BLE001
            self.log(f"安装失败: {e}")
            self.status("安装失败")
            log.exception("安装 Minecraft 版本失败")

    def _rename_version(self, old_id: str, new_id: str) -> bool:
        """把已安装的版本目录重命名为新 id（如 fabric-loader-x-1.21.1 -> 1.21.1-fabric）。

        同时重写版本 json 里的 ``id`` 字段，并重命名 .jar 文件，保证游戏能正常启动。
        """
        if old_id == new_id:
            return True
        old_dir = os.path.join(MC_DIR, "versions", old_id)
        new_dir = os.path.join(MC_DIR, "versions", new_id)
        if not os.path.isdir(old_dir):
            return False
        if os.path.isdir(new_dir):
            # 目标已存在，视为已重命名过
            return True

        try:
            # 1. 重写 json 里的 id（保留 inheritsFrom 指向原版版本）
            old_json = os.path.join(old_dir, f"{old_id}.json")
            new_json = os.path.join(old_dir, f"{new_id}.json")
            if os.path.isfile(old_json):
                with open(old_json, "r", encoding="utf-8") as f:
                    data = json.load(f)
                data["id"] = new_id
                with open(new_json, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                if new_json != old_json:
                    os.remove(old_json)

            # 2. 重命名 .jar（如果存在）
            old_jar = os.path.join(old_dir, f"{old_id}.jar")
            new_jar = os.path.join(old_dir, f"{new_id}.jar")
            if os.path.isfile(old_jar) and not os.path.exists(new_jar):
                os.rename(old_jar, new_jar)

            # 3. 重命名整个目录
            os.rename(old_dir, new_dir)
            return True
        except Exception as e:  # noqa: BLE001
            log.warning("重命名版本目录失败 %s -> %s: %s", old_id, new_id, e)
            return False

    def _install_fabric_api(self, mc_version: str, version_folder: str) -> None:
        """Fabric 安装后自动下载 Fabric API 模组。"""
        self.log("正在自动下载 Fabric API...")
        try:
            headers = {"User-Agent": USER_AGENT}
            versions_url = f"{MODRINTH_API}/project/fabric-api/version"
            vers_res = get_session().get(versions_url, headers=headers, timeout=(15, 60))
            vers_res.raise_for_status()
            vers_list = vers_res.json()

            compatible = [
                v for v in vers_list
                if mc_version in v.get("game_versions", [])
                and "fabric" in v.get("loaders", [])
            ]
            if not compatible:
                self.log("未找到适用于当前版本的 Fabric API")
                return
            compatible.sort(key=lambda x: x.get("date_published", ""), reverse=True)
            latest = compatible[0]
            download_url = latest["files"][0]["url"]
            filename = latest["files"][0]["filename"]

            mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
            os.makedirs(mods_dir, exist_ok=True)
            dest = os.path.join(mods_dir, filename)
            if os.path.exists(dest):
                self.log(f"Fabric API 已存在: {filename}")
                return
            self.status(f"下载 {filename}...")
            download_file(download_url, dest, callback=self.progress, headers=headers)
            self.log("Fabric API 安装完成")
        except Exception as e:  # noqa: BLE001
            self.log(f"自动下载 Fabric API 失败: {e}")

    # ------------------------------------------------------------------
    # 启动游戏
    # ------------------------------------------------------------------
    def _get_java_executable(self) -> str:
        """优先返回配置/内置的 Java 可执行文件，否则回退到系统 java。"""
        java_home = self.config.get("java_home") or {}
        if isinstance(java_home, dict) and java_home.get("path"):
            exe = os.path.join(java_home["path"], "bin", "javaw.exe")
            if os.path.isfile(exe):
                return exe
            exe = os.path.join(java_home["path"], "bin", "java.exe")
            if os.path.isfile(exe):
                return exe
        # 内置 Java
        if os.path.isdir(JAVA_DIR):
            for entry in sorted(os.listdir(JAVA_DIR), reverse=True):
                exe = os.path.join(JAVA_DIR, entry, "bin", "javaw.exe")
                if os.path.isfile(exe):
                    return exe
                exe = os.path.join(JAVA_DIR, entry, "bin", "java.exe")
                if os.path.isfile(exe):
                    return exe
        return find_java_executable() or "java"

    def _default_jvm_args(self) -> List[str]:
        mx = self.config.get("max_memory", "2G") or "2G"
        ms = self.config.get("min_memory", "1G") or "1G"
        return [
            f"-Xmx{mx}",
            f"-Xms{ms}",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-Dfile.encoding=UTF-8",
        ]

    def launch_game(self, version: str) -> None:
        """启动指定版本的游戏。"""
        version = (version or "").strip()
        if not version:
            version = self.config.get("current_version", "")
        if not version:
            self.log("错误: 未指定要启动的版本")
            return

        version_dir = os.path.join(MC_DIR, "versions", version)
        json_path = os.path.join(version_dir, f"{version}.json")
        if not os.path.isdir(version_dir) or not os.path.isfile(json_path):
            self.log(f"错误: 版本 {version} 未安装")
            return

        loader_type = self.detect_loader(version)
        self.config["current_version"] = version
        self.config["current_loader"] = loader_type
        self.config["original_version"] = self._resolve_original_version(version)
        self.save_config()

        login_options = self.get_login_options()
        if login_options is None:
            self.log("登录已失效，请重新登录")
            return

        # 检查并修复游戏文件（下载缺失的运行库 .dll、解压 natives、安装 Java 运行时等）
        try:
            self.status("正在检查游戏运行库...")
            minecraft_launcher_lib.install.install_minecraft_version(
                version, MC_DIR, callback=self._make_callbacks()
            )
        except Exception as e:  # noqa: BLE001
            log.warning("运行库检查失败（将尝试直接启动）: %s", e)

        # 隔离的游戏目录：每个版本独立保存存档、模组、配置
        isolated_game_dir = version_dir
        os.makedirs(isolated_game_dir, exist_ok=True)
        self._set_game_language_to_chinese(isolated_game_dir)

        options = {
            "username": login_options["username"],
            "uuid": login_options["uuid"],
            "token": login_options["token"],
            "gameDirectory": isolated_game_dir,
            # 让库根据版本自动选择正确的 Java 运行时；
            # defaultExecutablePath 仅作为旧版本（无 javaVersion 字段）的兜底
            "defaultExecutablePath": self._get_java_executable(),
            "jvmArguments": self._default_jvm_args(),
            "launcherName": "SimpleMCLauncher",
            "launcherVersion": VERSION_OF_LAUNCHER,
        }
        try:
            command = minecraft_launcher_lib.command.get_minecraft_command(
                version, MC_DIR, options
            )
            self.log(f"正在启动游戏: {version} ...")
            self.status("正在启动游戏...")
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            process = subprocess.Popen(
                command,
                cwd=isolated_game_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="ignore",
                bufsize=1,
                creationflags=creation_flags,
            )
            self.game_process = process
            threading.Thread(target=self._read_game_log, args=(process,), daemon=True).start()
        except Exception as e:  # noqa: BLE001
            self.log(f"启动失败: {e}")
            self.status("启动失败")
            log.exception("启动游戏失败")

    def _read_game_log(self, process: subprocess.Popen) -> None:
        try:
            for line in iter(process.stdout.readline, ""):
                if line.strip():
                    self.log(f"[游戏] {line.strip()}")
        except Exception:  # noqa: BLE001
            pass
        finally:
            try:
                process.stdout.close()
            except Exception:  # noqa: BLE001
                pass
            process.wait()
            self.log(f"[系统] 游戏已退出，退出码: {process.returncode}")
            self.status("游戏已退出")
            if self.game_process is process:
                self.game_process = None

    def _set_game_language_to_chinese(self, game_dir: str) -> None:
        options_path = os.path.join(game_dir, "options.txt")
        options_data: Dict[str, str] = {}
        if os.path.exists(options_path):
            try:
                with open(options_path, "r", encoding="utf-8") as f:
                    for line in f:
                        if ":" in line:
                            key, _, val = line.strip().partition(":")
                            options_data[key] = val
            except Exception:  # noqa: BLE001
                options_data = {}
        options_data["lang"] = "zh_cn"
        try:
            with open(options_path, "w", encoding="utf-8") as f:
                for key, val in options_data.items():
                    f.write(f"{key}:{val}\n")
        except Exception:  # noqa: BLE001
            pass

    def is_game_running(self) -> bool:
        return self.game_process is not None and self.game_process.poll() is None

    def stop_game(self) -> None:
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
            try:
                self.game_process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.game_process.kill()
                self.game_process.wait()
            self.log("游戏已终止")
        except Exception as e:  # noqa: BLE001
            self.log(f"终止游戏时出错: {e}")
        finally:
            self.game_process = None

    # ------------------------------------------------------------------
    # 模组管理
    # ------------------------------------------------------------------
    def _mods_dir(self, version: Optional[str] = None) -> str:
        version = version or self.config.get("current_version", "")
        if not version:
            return os.path.join(MC_DIR, "mods")
        return os.path.join(MC_DIR, "versions", version, "mods")

    def _shaderpacks_dir(self, version: Optional[str] = None) -> str:
        version = version or self.config.get("current_version", "")
        if not version:
            return os.path.join(MC_DIR, "shaderpacks")
        return os.path.join(MC_DIR, "versions", version, "shaderpacks")

    def get_mods_list(self, version: Optional[str] = None) -> List[Dict[str, Any]]:
        """返回指定版本模组列表 [{name, file, disabled, size}]。"""
        mods_dir = self._mods_dir(version)
        if not os.path.isdir(mods_dir):
            return []
        result = []
        try:
            for f in sorted(os.listdir(mods_dir)):
                if not f.endswith(".jar") and not f.endswith(".disabled"):
                    continue
                full = os.path.join(mods_dir, f)
                disabled = f.endswith(".disabled")
                display = f[:-9] if disabled else f
                try:
                    size = os.path.getsize(full)
                except OSError:
                    size = 0
                result.append({
                    "name": display,
                    "file": f,
                    "disabled": disabled,
                    "size": size,
                })
        except Exception as e:  # noqa: BLE001
            log.warning("读取模组列表失败: %s", e)
        return result

    def list_mods(self) -> None:
        version = self.config.get("current_version")
        if not version:
            self.log("错误: 未选择任何版本")
            return
        mods = self.get_mods_list(version)
        self.log(f"当前版本 [{version}] 已安装模组 ({len(mods)}):")
        if not mods:
            self.log("  无模组")
            return
        for m in mods:
            status = "[禁用]" if m["disabled"] else "[启用]"
            self.log(f"  {status} {m['name']} ({human_size(m['size'])})")

    def _modrinth_search(self, query: str, project_type: str = "mod") -> Optional[dict]:
        params = {
            "query": query,
            "limit": 5,
            "facets": json.dumps([[f"project_type:{project_type}"]]),
        }
        data = fetch_json(f"{MODRINTH_API}/search", params=params,
                          headers={"User-Agent": USER_AGENT})
        hits = data.get("hits", [])
        if not hits:
            return None
        return hits[0]

    def _modrinth_latest_file(self, project_slug: str, mc_version: str,
                              loader: str) -> Optional[Dict[str, Any]]:
        """获取项目在指定 MC 版本/加载器下的最新版本文件。"""
        params = {
            "game_versions": json.dumps([mc_version]),
            "loaders": json.dumps([loader]) if loader != "vanilla" else json.dumps([]),
        }
        try:
            vers = fetch_json(f"{MODRINTH_API}/project/{project_slug}/version",
                              params=params, headers={"User-Agent": USER_AGENT})
        except Exception:  # noqa: BLE001
            vers = []
        if not vers:
            # 回退：不限定加载器，仅按 MC 版本匹配
            params = {"game_versions": json.dumps([mc_version])}
            try:
                vers = fetch_json(f"{MODRINTH_API}/project/{project_slug}/version",
                                  params=params, headers={"User-Agent": USER_AGENT})
            except Exception:  # noqa: BLE001
                vers = []
        if not vers:
            return None
        vers.sort(key=lambda x: x.get("date_published", ""), reverse=True)
        latest = vers[0]
        if not latest.get("files"):
            return None
        return latest["files"][0]

    def install_mod(self, mod_name: str, version: Optional[str] = None) -> bool:
        """从 Modrinth 搜索并安装单个模组到当前版本。"""
        mod_name = (mod_name or "").strip()
        if not mod_name:
            self.log("错误: 模组名称为空")
            return False

        current = version or self.config.get("current_version", "")
        if not current:
            self.log("错误: 请先选择/安装一个游戏版本")
            return False

        loader = self.detect_loader(current)
        mc_version = self._resolve_original_version(current)

        self.status(f"搜索模组: {mod_name}")
        try:
            hit = self._modrinth_search(mod_name, "mod")
            if not hit:
                self.log(f"未找到模组: {mod_name}")
                self.status("搜索完成: 无结果")
                return False
            slug = hit.get("slug") or hit.get("project_id")
            title = hit.get("title", slug)
            self.log(f"找到模组: {title} (匹配度 {hit.get('relevance', 0):.0f}%)")

            file_info = self._modrinth_latest_file(slug, mc_version, loader)
            if not file_info:
                self.log(f"模组 {title} 没有适用于 Minecraft {mc_version} 的版本")
                self.status("无兼容版本")
                return False

            filename = file_info.get("filename", f"{slug}.jar")
            download_url = file_info.get("url")
            if not download_url:
                self.log(f"模组 {title} 没有可下载的文件")
                return False

            mods_dir = self._mods_dir(current)
            os.makedirs(mods_dir, exist_ok=True)
            dest = os.path.join(mods_dir, filename)
            if os.path.exists(dest):
                self.log(f"模组已存在: {filename}")
                return True

            self.status(f"下载 {filename}...")
            sha1 = (file_info.get("hashes") or {}).get("sha1")
            download_file(download_url, dest, callback=self.progress, sha1=sha1,
                          headers={"User-Agent": USER_AGENT})
            self.log(f"模组安装完成: {filename}")
            self.status("模组安装完成")
            return True
        except Exception as e:  # noqa: BLE001
            self.log(f"安装模组失败: {e}")
            self.status("模组安装失败")
            log.exception("安装模组失败")
            return False

    def install_mods_batch(self, mod_list: List[str]) -> None:
        for mod in mod_list:
            self.install_mod(mod)

    def install_shaderpack(self, shader_name: str) -> bool:
        """从 Modrinth 搜索并安装光影包。"""
        shader_name = (shader_name or "").strip()
        if not shader_name:
            self.log("错误: 光影包名称为空")
            return False
        current = self.config.get("current_version", "")
        if not current:
            self.log("错误: 请先选择/安装一个游戏版本")
            return False
        mc_version = self._resolve_original_version(current)

        self.status(f"搜索光影包: {shader_name}")
        try:
            hit = self._modrinth_search(shader_name, "shader")
            if not hit:
                self.log(f"未找到光影包: {shader_name}")
                self.status("搜索完成: 无结果")
                return False
            slug = hit.get("slug") or hit.get("project_id")
            title = hit.get("title", slug)
            self.log(f"找到光影包: {title}")

            params = {"game_versions": json.dumps([mc_version])}
            vers = fetch_json(f"{MODRINTH_API}/project/{slug}/version", params=params,
                              headers={"User-Agent": USER_AGENT})
            if not vers:
                self.log(f"光影包 {title} 没有适用于 Minecraft {mc_version} 的版本")
                return False
            vers.sort(key=lambda x: x.get("date_published", ""), reverse=True)
            file_info = vers[0].get("files", [None])[0]
            if not file_info:
                self.log("光影包没有可下载的文件")
                return False
            filename = file_info.get("filename", f"{slug}.zip")
            download_url = file_info.get("url")

            shader_dir = self._shaderpacks_dir(current)
            os.makedirs(shader_dir, exist_ok=True)
            dest = os.path.join(shader_dir, filename)
            if os.path.exists(dest):
                self.log(f"光影包已存在: {filename}")
                return True
            self.status(f"下载 {filename}...")
            sha1 = (file_info.get("hashes") or {}).get("sha1")
            download_file(download_url, dest, callback=self.progress, sha1=sha1,
                          headers={"User-Agent": USER_AGENT})
            self.log(f"光影包安装完成: {filename}")
            self.status("光影包安装完成")
            return True
        except Exception as e:  # noqa: BLE001
            self.log(f"安装光影包失败: {e}")
            self.status("光影包安装失败")
            return False

    def import_modpack(self, file_path: str) -> bool:
        """导入 .mrpack 整合包。"""
        file_path = (file_path or "").strip()
        if not file_path or not os.path.isfile(file_path):
            self.log(f"错误: 文件不存在 - {file_path}")
            return False

        if file_path.lower().endswith(".mrpack"):
            return self._import_mrpack(file_path)

        self.log("仅支持 .mrpack 格式的整合包（Modrinth 格式）。")
        self.log("CurseForge .zip 整合包请先在 Modrinth 官网转换为 .mrpack 后再导入。")
        return False

    def _import_mrpack(self, file_path: str) -> bool:
        callbacks = self._make_callbacks()
        try:
            info = _mrpack.get_mrpack_information(file_path)
            self.log(f"整合包: {info.get('name', '未知')} (MC {info.get('minecraftVersion', '?')})")

            launch_version = _mrpack.get_mrpack_launch_version(file_path)
            mc_version = info.get("minecraftVersion", "")
            loader = self.detect_loader(launch_version)
            # 统一命名：<version>-<loader>
            final_id = f"{mc_version}-{loader}" if loader != "vanilla" else mc_version

            target_dir = os.path.join(MC_DIR, "versions", launch_version)
            os.makedirs(target_dir, exist_ok=True)

            self.status("正在安装整合包...")
            _mrpack.install_mrpack(file_path, MC_DIR, modpack_directory=target_dir,
                                   callback=callbacks)

            # 重命名为统一命名规则
            self._rename_version(launch_version, final_id)

            self.config["current_version"] = final_id
            self.config["current_loader"] = loader
            self.config["original_version"] = mc_version
            self.save_config()
            self.log(f"整合包导入完成！启动版本: {final_id}")
            self.status("整合包导入完成")
            return True
        except Exception as e:  # noqa: BLE001
            self.log(f"导入整合包失败: {e}")
            self.status("导入失败")
            log.exception("导入整合包失败")
            return False

    def mod_update(self) -> None:
        """检查当前版本模组更新（尽力而为的 Modrinth 匹配，仅提示不自动下载）。"""
        current = self.config.get("current_version", "")
        if not current:
            self.log("错误: 未选择任何版本")
            return
        mods = self.get_mods_list(current)
        if not mods:
            self.log("当前版本没有安装模组")
            return
        loader = self.detect_loader(current)
        mc_version = self._resolve_original_version(current)
        self.log(f"正在检查 {len(mods)} 个模组的更新（基于 Modrinth，结果仅供参考）...")
        for m in mods:
            if m["disabled"]:
                continue
            base = re.sub(r"[-_](v?\d[\d.]*|release).*$", "", m["name"].rsplit(".", 1)[0])
            base = re.sub(r"[-_.]\d+(\.\d+)*$", "", base)
            try:
                hit = self._modrinth_search(base, "mod")
                if not hit:
                    continue
                slug = hit.get("slug")
                file_info = self._modrinth_latest_file(slug, mc_version, loader)
                if file_info:
                    self.log(f"  {m['name']} -> 最新可用: {file_info.get('filename', '?')}")
            except Exception:  # noqa: BLE001
                continue
        self.log("模组更新检查完成")
        self.status("更新检查完成")

    def mod_disable(self, mod_name: str) -> None:
        self._toggle_mod(mod_name, disable=True)

    def mod_enable(self, mod_name: str) -> None:
        self._toggle_mod(mod_name, disable=False)

    def _toggle_mod(self, mod_name: str, disable: bool) -> None:
        version = self.config.get("current_version")
        if not version:
            self.log("错误: 未选择任何版本")
            return
        mods_dir = self._mods_dir(version)
        if not os.path.isdir(mods_dir):
            self.log("mods 文件夹不存在")
            return
        target = None
        for f in os.listdir(mods_dir):
            if disable:
                if f == mod_name or f == mod_name + ".jar":
                    target = f
                    break
            else:
                if f == mod_name + ".disabled" or f == mod_name:
                    target = f
                    break
        if not target:
            self.log(f"未找到模组 {mod_name}")
            return
        src = os.path.join(mods_dir, target)
        if disable and not target.endswith(".disabled"):
            dst = src + ".disabled"
        elif not disable and target.endswith(".disabled"):
            dst = src[:-9]
        else:
            self.log("模组状态未改变")
            return
        try:
            if os.path.exists(dst):
                os.remove(dst)
            os.rename(src, dst)
            self.log(f"模组 {mod_name} 已{'禁用' if disable else '启用'}")
        except Exception as e:  # noqa: BLE001
            self.log(f"操作失败: {e}")

    # ------------------------------------------------------------------
    # 服务器
    # ------------------------------------------------------------------
    def get_installed_servers(self) -> List[str]:
        if not os.path.isdir(SERVER_DIR):
            return []
        return sorted([
            d for d in os.listdir(SERVER_DIR)
            if os.path.isdir(os.path.join(SERVER_DIR, d))
        ])

    def _find_server_jar(self, server_id: str) -> Optional[str]:
        server_dir = os.path.join(SERVER_DIR, server_id)
        if not os.path.isdir(server_dir):
            return None
        jars = [f for f in os.listdir(server_dir) if f.endswith(".jar")]
        # 优先选择名字中不含 installer 的 jar
        for jar in jars:
            if "installer" not in jar.lower():
                return os.path.join(server_dir, jar)
        return os.path.join(server_dir, jars[0]) if jars else None

    def _find_server_run_script(self, server_dir: str) -> Optional[str]:
        """返回 Forge 等安装器生成的启动脚本路径，没有则返回 None。"""
        if os.name == "nt":
            names = ("run.bat",)
        else:
            names = ("run.sh",)
        for name in names:
            p = os.path.join(server_dir, name)
            if os.path.isfile(p):
                return p
        return None

    def install_server(self, server_type: str, version: str,
                       max_mem: str = "2G", min_mem: str = "1G") -> bool:
        """下载指定类型的服务器 jar。支持 paper / purpur / vanilla / forge / fabric。"""
        server_type = (server_type or "").strip().lower()
        version = (version or "").strip()
        if not server_type or not version:
            self.log("错误: 缺少服务器类型或版本")
            return False
        if server_type not in ("paper", "purpur", "vanilla", "forge", "fabric"):
            self.log(f"不支持的服务器类型: {server_type}")
            return False

        server_dir = os.path.join(SERVER_DIR, f"{server_type}-{version}")
        os.makedirs(server_dir, exist_ok=True)

        try:
            self.status(f"正在下载 {server_type} {version} 服务器...")
            url, jar_name = self._resolve_server_download(server_type, version)
            self.log(f"下载地址: {url}")
            dest = os.path.join(server_dir, jar_name)
            download_file(url, dest, callback=self.progress)

            # Forge 需要先运行安装器生成运行环境
            if server_type == "forge":
                self.status("正在运行 Forge 安装器...")
                self.log("正在安装 Forge 服务器（这可能需要几分钟）...")
                java = self._get_java_executable()
                result = subprocess.run(
                    [java, "-jar", jar_name, "--installServer"],
                    cwd=server_dir,
                    capture_output=True,
                    text=True,
                    timeout=900,
                    encoding="utf-8",
                    errors="replace",
                )
                if result.returncode != 0:
                    tail = (result.stderr or result.stdout or "").strip()[-800:]
                    self.log(f"Forge 服务器安装失败: {tail}")
                    self.status("Forge 安装失败")
                    return False

            # 写入 eula 与启动脚本（Forge 由安装器生成启动脚本，这里跳过）
            if server_type != "forge":
                self._write_server_defaults(server_dir, jar_name, max_mem, min_mem)
            else:
                eula_path = os.path.join(server_dir, "eula.txt")
                if not os.path.exists(eula_path):
                    with open(eula_path, "w", encoding="utf-8") as f:
                        f.write("eula=true\n")

            # 记录配置
            server_info = {
                "type": server_type,
                "version": version,
                "jar": jar_name,
                "max_mem": max_mem,
                "min_mem": min_mem,
            }
            with open(os.path.join(server_dir, "server_info.json"), "w", encoding="utf-8") as f:
                json.dump(server_info, f, ensure_ascii=False, indent=2)

            self.log(f"服务器 {server_type} {version} 下载完成！")
            self.log(f"启动命令: 在「服务器」页选择 {server_type}-{version} 并点击启动")
            self.status("服务器下载完成")
            return True
        except Exception as e:  # noqa: BLE001
            self.log(f"下载服务器失败: {e}")
            self.status("服务器下载失败")
            log.exception("下载服务器失败")
            return False

    def _resolve_server_download(self, server_type: str, version: str) -> Tuple[str, str]:
        """返回 (下载地址, 保存文件名)。"""
        if server_type == "paper":
            # Paper 已迁移到新的 Fill API（旧 v2 已下线）
            builds = fetch_json(
                f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds")
            if not builds:
                raise ValueError(f"Paper 不支持版本 {version}")
            # builds 已按最新在前返回；优先选择 STABLE 构建
            stable = [b for b in builds if b.get("channel") == "STABLE"]
            build = (stable or builds)[0]
            build_id = build["id"]
            dl = build.get("downloads", {}).get("server:default")
            if not dl:
                raise ValueError(f"Paper {version} 构建 {build_id} 没有可下载文件")
            return dl["url"], dl["name"]
        if server_type == "purpur":
            url = f"https://api.purpurmc.org/v2/purpur/{version}/latest/download"
            return url, f"purpur-{version}.jar"
        if server_type == "vanilla":
            manifest = fetch_json("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
            v = next((x for x in manifest["versions"] if x["id"] == version), None)
            if v is None:
                raise ValueError(f"原版服务器版本 {version} 不存在")
            vdata = fetch_json(v["url"])
            return vdata["downloads"]["server"]["url"], f"server-{version}.jar"
        if server_type == "forge":
            forge_version = version
            url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{forge_version}/forge-{forge_version}-installer.jar"
            return url, f"forge-{forge_version}-installer.jar"
        if server_type == "fabric":
            loader_info = fetch_json(f"https://meta.fabricmc.net/v2/versions/loader/{version}")
            loader_version = loader_info[0]["loader"]["version"]
            installer = fetch_json("https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml")
            # 解析 latest
            m = re.search(r"<latest>(.*?)</latest>", installer)
            installer_version = m.group(1) if m else None
            if not installer_version:
                raise ValueError("无法获取 Fabric 安装器版本")
            url = (f"https://meta.fabricmc.net/v2/versions/loader/{version}/"
                   f"{loader_version}/{installer_version}/server/jar")
            return url, f"fabric-server-{version}.jar"
        raise ValueError(f"无法处理服务器类型: {server_type}")

    def _write_server_defaults(self, server_dir: str, jar_name: str,
                               max_mem: str, min_mem: str) -> None:
        # eula.txt（服务器首次启动需要同意）
        eula_path = os.path.join(server_dir, "eula.txt")
        if not os.path.exists(eula_path):
            with open(eula_path, "w", encoding="utf-8") as f:
                f.write("eula=true\n")
        # 启动脚本
        if os.name == "nt":
            bat = os.path.join(server_dir, "start.bat")
            with open(bat, "w", encoding="utf-8") as f:
                f.write("@echo off\n")
                f.write(f"java -Xmx{max_mem} -Xms{min_mem} -jar {jar_name} nogui\n")
                f.write("pause\n")
        else:
            sh = os.path.join(server_dir, "start.sh")
            with open(sh, "w", encoding="utf-8") as f:
                f.write("#!/bin/sh\n")
                f.write(f"java -Xmx{max_mem} -Xms{min_mem} -jar {jar_name} nogui\n")

    def launch_server(self, server_id: str, nogui: bool = False) -> None:
        server_id = (server_id or "").strip()
        if not server_id:
            self.log("错误: 未指定服务器")
            return
        server_dir = os.path.join(SERVER_DIR, server_id)
        if not os.path.isdir(server_dir):
            self.log(f"错误: 服务器 {server_id} 不存在")
            return
        run_script = self._find_server_run_script(server_dir)
        jar = self._find_server_jar(server_id)
        if run_script is None and not jar:
            self.log(f"错误: 服务器 {server_id} 目录中没有可启动的文件")
            return

        max_mem = self.config.get("server_max_mem", "2G") or "2G"
        min_mem = self.config.get("server_min_mem", "1G") or "1G"
        # 尝试读取 server_info.json
        info_path = os.path.join(server_dir, "server_info.json")
        if os.path.exists(info_path):
            try:
                with open(info_path, "r", encoding="utf-8") as f:
                    info = json.load(f)
                max_mem = info.get("max_mem", max_mem)
                min_mem = info.get("min_mem", min_mem)
            except Exception:  # noqa: BLE001
                pass

        java = self._get_java_executable()

        # Forge 服务器使用安装器生成的启动脚本
        if run_script is not None:
            if os.name == "nt":
                command = ["cmd", "/c", run_script]
            else:
                command = ["sh", run_script]
        else:
            command = [java, f"-Xmx{max_mem}", f"-Xms{min_mem}", "-jar", os.path.basename(jar)]
            if nogui or "installer" not in os.path.basename(jar).lower():
                command.append("nogui")

        self.log(f"正在启动服务器 {server_id} ...")
        self.status("正在启动服务器...")
        try:
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            process = subprocess.Popen(
                command,
                cwd=server_dir,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="ignore",
                bufsize=1,
                creationflags=creation_flags,
            )
            self.server_process = process
            threading.Thread(target=self._read_server_log, args=(process,), daemon=True).start()
        except Exception as e:  # noqa: BLE001
            self.log(f"启动服务器失败: {e}")
            self.status("服务器启动失败")
            log.exception("启动服务器失败")

    def _read_server_log(self, process: subprocess.Popen) -> None:
        try:
            for line in iter(process.stdout.readline, ""):
                if line.strip():
                    self.log(f"[服务器] {line.strip()}")
        except Exception:  # noqa: BLE001
            pass
        finally:
            try:
                process.stdout.close()
            except Exception:  # noqa: BLE001
                pass
            process.wait()
            self.log("[系统] 服务器已停止")
            self.status("服务器已停止")
            if self.server_process is process:
                self.server_process = None

    def is_server_running(self) -> bool:
        return self.server_process is not None and self.server_process.poll() is None

    def stop_server(self) -> None:
        if self.server_process is None:
            self.log("没有正在运行的服务器进程")
            return
        if self.server_process.poll() is not None:
            self.server_process = None
            return
        try:
            # 尝试通过 stdin 发送 stop 命令优雅关闭
            try:
                self.server_process.stdin.write("stop\n")
                self.server_process.stdin.flush()
                self.server_process.wait(timeout=10)
            except Exception:  # noqa: BLE001
                self.server_process.terminate()
                try:
                    self.server_process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.server_process.kill()
            self.log("服务器已停止")
        except Exception as e:  # noqa: BLE001
            self.log(f"停止服务器时出错: {e}")
        finally:
            self.server_process = None

    def server_console(self, server_id: str, nogui: bool = False) -> None:
        self.launch_server(server_id, nogui)

    def server_config(self, server_type: str) -> None:
        """打开服务器配置目录/文件。"""
        server_type = (server_type or "").strip()
        candidates = []
        if server_type:
            candidates.append(os.path.join(SERVER_DIR, server_type))
        # 尝试匹配前缀
        for d in self.get_installed_servers():
            if server_type and d.startswith(server_type):
                candidates.append(os.path.join(SERVER_DIR, d))
        target = None
        for c in candidates:
            if os.path.isdir(c):
                target = c
                break
        if not target:
            self.log(f"未找到服务器: {server_type}")
            return
        props = os.path.join(target, "server.properties")
        if os.path.isfile(props):
            self._open_with_editor(props)
        else:
            self._open_folder(target)

    def _open_with_editor(self, path: str) -> None:
        try:
            if os.name == "nt":
                os.startfile(path)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", path])
            else:
                subprocess.Popen(["xdg-open", path])
            self.log(f"已打开: {path}")
        except Exception as e:  # noqa: BLE001
            self.log(f"无法打开文件: {e}")

    def _open_folder(self, path: str) -> None:
        try:
            if os.name == "nt":
                os.startfile(path)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", path])
            else:
                subprocess.Popen(["xdg-open", path])
            self.log(f"已打开文件夹: {path}")
        except Exception as e:  # noqa: BLE001
            self.log(f"无法打开文件夹: {e}")

    # ------------------------------------------------------------------
    # Java
    # ------------------------------------------------------------------
    def install_java(self, version: str) -> bool:
        """通过 Adoptium API 下载指定大版本的 JRE。"""
        version = (version or "").strip()
        if not version:
            self.log("错误: 未指定 Java 版本")
            return False
        try:
            os_name = "windows" if platform.system() == "Windows" else "linux"
            arch = "x64"
            if platform.machine().lower() in ("aarch64", "arm64"):
                arch = "aarch64"
            url = (f"https://api.adoptium.net/v3/assets/latest/{version}/hotspot"
                   f"?architecture={arch}&image_type=jre&os={os_name}&vendor=eclipse")
            data = fetch_json(url)
            if not data:
                self.log(f"未找到 Java {version} 的可用下载")
                return False
            binary = data[0]["binary"]
            package = binary["package"]
            link = package["link"]
            name = package.get("name", f"jdk-{version}")

            target_dir = os.path.join(JAVA_DIR, name)
            if os.path.isdir(target_dir) and os.path.isfile(
                    os.path.join(target_dir, "bin", "java.exe" if os.name == "nt" else "java")):
                self.log(f"Java {version} 已安装: {target_dir}")
                return True

            self.status(f"正在下载 Java {version}...")
            tmp_zip = os.path.join(tempfile.gettempdir(), f"jre-{version}.zip")
            download_file(link, tmp_zip, callback=self.progress)

            self.status("正在解压 Java...")
            os.makedirs(JAVA_DIR, exist_ok=True)
            with zipfile.ZipFile(tmp_zip, "r") as zf:
                zf.extractall(target_dir)
            # Adoptium 的 zip 里通常有一层目录，需要移动
            extracted = os.listdir(target_dir)
            if len(extracted) == 1 and os.path.isdir(os.path.join(target_dir, extracted[0])):
                inner = os.path.join(target_dir, extracted[0])
                for item in os.listdir(inner):
                    shutil.move(os.path.join(inner, item), target_dir)
                os.rmdir(inner)
            try:
                os.remove(tmp_zip)
            except Exception:  # noqa: BLE001
                pass

            self.log(f"Java {version} 安装完成: {target_dir}")
            self.status("Java 安装完成")
            return True
        except Exception as e:  # noqa: BLE001
            self.log(f"安装 Java 失败: {e}")
            self.status("Java 安装失败")
            log.exception("安装 Java 失败")
            return False

    def list_all_java(self) -> List[str]:
        """扫描系统与本启动器内置的 Java 安装。"""
        results: List[str] = []
        # 内置 Java
        if os.path.isdir(JAVA_DIR):
            for entry in os.listdir(JAVA_DIR):
                exe = os.path.join(JAVA_DIR, entry, "bin", "java.exe")
                if os.path.isfile(exe):
                    results.append(f"内置: {exe}")
        # JAVA_HOME
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            results.append(f"JAVA_HOME: {java_home}")
        # PATH
        path_java = find_java_executable()
        if path_java:
            results.append(f"系统 PATH: {path_java}")
        if not results:
            self.log("未检测到任何 Java 安装，请安装 Java 17+")
        else:
            self.log("检测到的 Java 安装:")
            for r in results:
                self.log(f"  {r}")
        return results

    # ------------------------------------------------------------------
    # frp
    # ------------------------------------------------------------------
    def _get_frpc_path(self) -> Optional[str]:
        if not os.path.isdir(FRP_DIR):
            return None
        for name in ("frpc.exe", "frpc"):
            p = os.path.join(FRP_DIR, name)
            if os.path.isfile(p):
                return p
        # 也可能在解压后的子目录里
        for root, dirs, files in os.walk(FRP_DIR):
            for name in files:
                if name in ("frpc.exe", "frpc"):
                    return os.path.join(root, name)
        return None

    def frp_config(self) -> None:
        os.makedirs(FRP_DIR, exist_ok=True)
        if os.path.isfile(FRPC_CONFIG):
            self._open_with_editor(FRPC_CONFIG)
            return
        default = (
            "# frpc 配置文件示例\n"
            "serverAddr = \"x.x.x.x\"\n"
            "serverPort = 7000\n"
            "auth.token = \"your_token\"\n\n"
            "[[proxies]]\n"
            "name = \"mc\"\n"
            "type = \"tcp\"\n"
            "localIP = \"127.0.0.1\"\n"
            "localPort = 25565\n"
            "remotePort = 25565\n"
        )
        try:
            with open(FRPC_CONFIG, "w", encoding="utf-8") as f:
                f.write(default)
            self.log(f"已生成 frpc 配置模板: {FRPC_CONFIG}")
            self._open_with_editor(FRPC_CONFIG)
        except Exception as e:  # noqa: BLE001
            self.log(f"生成 frpc 配置失败: {e}")

    def frp_start(self) -> None:
        frpc = self._get_frpc_path()
        if not frpc:
            self.log("未找到 frpc，请先安装 frp。")
            self.log("可从 https://github.com/fatedier/frp/releases 下载并解压到 frp 目录")
            return
        if not os.path.isfile(FRPC_CONFIG):
            self.log("未找到 frpc.toml 配置，请先执行配置。")
            return
        if self.frp_status():
            self.log("frpc 已经在运行")
            return
        try:
            log_file = open(FRPC_LOG, "a", encoding="utf-8")
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            self.frp_process = subprocess.Popen(
                [frpc, "-c", FRPC_CONFIG],
                stdout=log_file, stderr=subprocess.STDOUT,
                creationflags=creation_flags,
            )
            self.log("frpc 已启动")
        except Exception as e:  # noqa: BLE001
            self.log(f"启动 frpc 失败: {e}")

    def frp_stop(self) -> None:
        if self.frp_process is None or self.frp_process.poll() is not None:
            self.log("frpc 未在运行")
            return
        try:
            self.frp_process.terminate()
            self.frp_process.wait(timeout=3)
            self.log("frpc 已停止")
        except Exception as e:  # noqa: BLE001
            self.log(f"停止 frpc 失败: {e}")
        finally:
            self.frp_process = None

    def frp_status(self) -> bool:
        running = self.frp_process is not None and self.frp_process.poll() is None
        self.log("frpc 运行中" if running else "frpc 未运行")
        return running

    # ------------------------------------------------------------------
    # 更新检查
    # ------------------------------------------------------------------
    def check_for_updates(self, silent: bool = False) -> bool:
        try:
            api_url = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest"
            resp = get_session().get(api_url, timeout=15)
            resp.raise_for_status()
            latest = resp.json()
            latest_tag = latest.get("tag_name", "")
            if not latest_tag:
                return False
            if compare_versions(latest_tag.lstrip("v"), VERSION_OF_LAUNCHER.lstrip("v")) > 0:
                if not silent:
                    self.log(f"发现新版本: {latest_tag}")
                return True
        except Exception as e:  # noqa: BLE001
            if not silent:
                self.log(f"检查更新失败: {e}")
        return False

    # ------------------------------------------------------------------
    # DeepSeek（隐藏的后端 AI 助手，可选）
    # ------------------------------------------------------------------
    def _load_deepseek_config(self) -> None:
        config_path = os.path.join(PYLAUNCHER_DIR, "deepseek_config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                self.deepseek_api_key = (data or {}).get("api_key", "")
            except Exception as e:  # noqa: BLE001
                log.warning("加载 DeepSeek 配置失败: %s", e)
                self.deepseek_api_key = ""

    def set_api_key(self, key: str) -> None:
        key = (key or "").strip()
        self.deepseek_api_key = key
        config_path = os.path.join(PYLAUNCHER_DIR, "deepseek_config.json")
        try:
            data = {}
            if os.path.exists(config_path):
                with open(config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
            data["api_key"] = key
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            self.log("DeepSeek API Key 已保存")
        except Exception as e:  # noqa: BLE001
            self.log(f"保存 API Key 失败: {e}")
        self.conversation_history = []

    def chat_with_deepseek(self, user_message: str) -> str:
        """与 DeepSeek 进行简单对话（无工具调用，隐藏后端）。"""
        if not self.deepseek_api_key:
            return "DeepSeek AI 未启用（未配置 API Key）"
        if not self.conversation_history:
            self.conversation_history.append({
                "role": "system",
                "content": "你是一个 Minecraft 启动器助手，用简体中文简洁地回答用户问题。",
            })
        self.conversation_history.append({"role": "user", "content": user_message})
        if len(self.conversation_history) > 20:
            self.conversation_history = [self.conversation_history[0]] + self.conversation_history[-19:]
        try:
            resp = requests.post(
                "https://api.deepseek.com/v1/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.deepseek_api_key}",
                    "Content-Type": "application/json",
                },
                json={"model": "deepseek-chat", "messages": self.conversation_history,
                      "stream": False},
                timeout=60,
            )
            resp.raise_for_status()
            message = resp.json()["choices"][0]["message"]
            reply = message.get("content", "")
            if reply:
                self.conversation_history.append({"role": "assistant", "content": reply})
            return reply or "（无回复）"
        except Exception as e:  # noqa: BLE001
            log.warning("DeepSeek 对话失败: %s", e)
            return f"与 DeepSeek 对话失败: {e}"

    def clear_history(self) -> None:
        self.conversation_history = []
        self.log("对话历史已清空")

    # ------------------------------------------------------------------
    # 关闭
    # ------------------------------------------------------------------
    def close(self) -> None:
        """关闭前清理：停止游戏与服务器进程。"""
        try:
            if self.is_game_running():
                self.stop_game()
        except Exception:  # noqa: BLE001
            pass
        try:
            if self.is_server_running():
                self.stop_server()
        except Exception:  # noqa: BLE001
            pass
