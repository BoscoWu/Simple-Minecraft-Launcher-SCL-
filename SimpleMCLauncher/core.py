# core.py
import threading
import os
import subprocess
import shutil
import zipfile
import tempfile
import time
import platform
import json
import traceback
import webbrowser
import re
import queue
from typing import Callable, Optional

import requests
import minecraft_launcher_lib
from minecraft_launcher_lib import fabric, forge, quilt, microsoft_account

from utils import *

# ---------- 补丁 ----------
def safe_fabric_get_latest_installer_version():
    import xml.etree.ElementTree as ET
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
            raise ValueError("无法获取 Fabric 安装器版本")
    except Exception as e:
        raise RuntimeError(f"获取 Fabric 安装器版本失败: {e}")

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
            print(f"下载失败，重试 ({attempt+1}/{max_retries}): {url}")
            time.sleep(2 ** attempt)
            continue
        except Exception as e:
            if attempt == max_retries - 1:
                raise
            print(f"下载异常，重试 ({attempt+1}/{max_retries}): {url}")
            time.sleep(2)
            continue

minecraft_launcher_lib._helper.download_file = patched_download_file

# ---------- 核心类 ----------
class LauncherCore:
    def __init__(self, log_callback: Optional[Callable[[str], None]] = None):
        self.log_callback = log_callback or print
        self.config = load_config()
        self.game_process = None
        self.frp_process = None
        self.server_process = None
        self.server_console_mode = False
        self.deepseek_api_key = ""
        self.conversation_history = []
        self.tools = []
        self._ensure_directories()
        self._load_deepseek_config()
        self._load_conversation_history()

        # 如果配置了 API Key 且对话历史为空，添加系统提示
        if self.deepseek_api_key and not self.conversation_history:
            self.conversation_history.append({
                "role": "system",
                "content": "你是一个 Minecraft 启动器助手。当用户说“启动”“打开”“开始游戏”时，必须调用 launch_game 函数。只有用户明确询问模组列表时才调用 list_mods。"
            })

    def _ensure_directories(self):
        os.makedirs(PYLAUNCHER_DIR, exist_ok=True)
        os.makedirs(MC_DIR, exist_ok=True)
        os.makedirs(SERVER_DIR, exist_ok=True)
        os.makedirs(JAVA_DIR, exist_ok=True)

    def log(self, message: str):
        self.log_callback(message)

    def save_config(self):
        save_config(self.config)

    # ---------- DeepSeek 相关 ----------
    def set_api_key(self, key):
        self.deepseek_api_key = key
        config_path = os.path.join(PYLAUNCHER_DIR, "deepseek_config.json")
        config = self._load_deepseek_config()
        config["api_key"] = key
        with open(config_path, 'w', encoding='utf-8') as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
        self.log("DeepSeek API Key 已保存。")
        self.conversation_history = []
        if key:
            self.conversation_history.append({
                "role": "system",
                "content": "你是一个 Minecraft 启动器助手。当用户说“启动”“打开”“开始游戏”时，必须调用 launch_game 函数。只有用户明确询问模组列表时才调用 list_mods。"
            })
            self._save_conversation_history()

    def show_history(self):
        log_file = os.path.join(PYLAUNCHER_DIR, "chat_history.txt")
        if not os.path.exists(log_file):
            self.log("暂无历史记录")
            return
        with open(log_file, "r", encoding="utf-8") as f:
            lines = f.read().splitlines()
        for line in lines[-20:]:
            self.log(line)

    def clear_history(self):
        self.conversation_history = []
        if self.deepseek_api_key:
            self.conversation_history.append({
                "role": "system",
                "content": "你是一个 Minecraft 启动器助手。当用户说“启动”“打开”“开始游戏”时，必须调用 launch_game 函数。只有用户明确询问模组列表时才调用 list_mods。"
            })
        self._save_conversation_history()
        log_file = os.path.join(PYLAUNCHER_DIR, "chat_history.txt")
        if os.path.exists(log_file):
            os.remove(log_file)
        self.log("对话历史已清空（内存和文件）")

    def _load_deepseek_config(self):
        config_path = os.path.join(PYLAUNCHER_DIR, "deepseek_config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except Exception as e:
                self.log(f"加载 DeepSeek 配置失败: {e}")
        default_config = {
            "api_key": "",
            "tools": [
                {"type": "function", "function": {"name": "install_minecraft", "description": "下载指定版本的 Minecraft 原版游戏", "parameters": {"type": "object", "properties": {"version": {"type": "string"}}, "required": ["version"]}}},
                {"type": "function", "function": {"name": "install_loader", "description": "安装模组加载器", "parameters": {"type": "object", "properties": {"loader_type": {"type": "string", "enum": ["fabric","forge","neoforge","quilt"]}, "mc_version": {"type": "string"}}, "required": ["loader_type","mc_version"]}}},
                {"type": "function", "function": {"name": "install_server", "description": "下载 Minecraft 服务器", "parameters": {"type": "object", "properties": {"server_type": {"type": "string"}, "version": {"type": "string"}, "max_mem": {"type": "string"}, "min_mem": {"type": "string"}}, "required": ["server_type","version","max_mem","min_mem"]}}},
                {"type": "function", "function": {"name": "install_java", "description": "下载 Java", "parameters": {"type": "object", "properties": {"version": {"type": "string"}}, "required": ["version"]}}},
                {"type": "function", "function": {"name": "install_mods", "description": "批量安装模组", "parameters": {"type": "object", "properties": {"mod_list": {"type": "string"}}, "required": ["mod_list"]}}},
                {"type": "function", "function": {"name": "install_shaderpack", "description": "安装光影包", "parameters": {"type": "object", "properties": {"shader_name": {"type": "string"}}, "required": ["shader_name"]}}},
                {"type": "function", "function": {"name": "import_modpack", "description": "导入整合包", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}}, "required": ["file_path"]}}},
                {"type": "function", "function": {"name": "launch_game", "description": "启动游戏", "parameters": {"type": "object", "properties": {"version": {"type": "string"}}}}},
                {"type": "function", "function": {"name": "microsoft_login", "description": "微软登录", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "microsoft_logout", "description": "微软登出", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "set_player_name", "description": "设置离线玩家名", "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
                {"type": "function", "function": {"name": "list_loaders", "description": "列出支持的加载器", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "frp_manage", "description": "frp 管理", "parameters": {"type": "object", "properties": {"action": {"type": "string", "enum": ["config","start","stop","status"]}}, "required": ["action"]}}},
                {"type": "function", "function": {"name": "clean_console", "description": "清空控制台", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "close_launcher", "description": "关闭启动器", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "list_mods", "description": "列出模组", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "mod_update_check", "description": "检查模组更新", "parameters": {"type": "object", "properties": {}}}},
                {"type": "function", "function": {"name": "mod_disable", "description": "禁用模组", "parameters": {"type": "object", "properties": {"mod_name": {"type": "string"}}, "required": ["mod_name"]}}},
                {"type": "function", "function": {"name": "mod_enable", "description": "启用模组", "parameters": {"type": "object", "properties": {"mod_name": {"type": "string"}}, "required": ["mod_name"]}}},
                {"type": "function", "function": {"name": "server_console", "description": "启动服务器控制台", "parameters": {"type": "object", "properties": {"server_id": {"type": "string"}, "nogui": {"type": "boolean"}}, "required": ["server_id"]}}},
                {"type": "function", "function": {"name": "server_config", "description": "编辑服务器配置", "parameters": {"type": "object", "properties": {"server_type": {"type": "string"}}, "required": ["server_type"]}}},
                {"type": "function", "function": {"name": "list_java", "description": "列出 Java 安装", "parameters": {"type": "object", "properties": {}}}}
            ]
        }
        try:
            with open(config_path, 'w', encoding='utf-8') as f:
                json.dump(default_config, f, ensure_ascii=False, indent=2)
            self.log("已自动生成 DeepSeek 配置文件。")
        except Exception as e:
            self.log(f"创建 DeepSeek 配置文件失败: {e}")
        return default_config

    def _load_conversation_history(self):
        history_path = os.path.join(PYLAUNCHER_DIR, "conversation_history.json")
        if os.path.exists(history_path):
            try:
                with open(history_path, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except Exception:
                return []
        return []

    def _save_conversation_history(self):
        history_path = os.path.join(PYLAUNCHER_DIR, "conversation_history.json")
        try:
            with open(history_path, 'w', encoding='utf-8') as f:
                to_save = self.conversation_history[-50:] if len(self.conversation_history) > 50 else self.conversation_history
                json.dump(to_save, f, ensure_ascii=False, indent=2)
        except Exception as e:
            self.log(f"保存对话历史失败: {e}")

    def _call_deepseek_api(self, messages, tools=None):
        headers = {
            "Authorization": f"Bearer {self.deepseek_api_key}",
            "Content-Type": "application/json"
        }
        payload = {
            "model": "deepseek-chat",
            "messages": messages,
            "stream": False
        }
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        response = requests.post(
            "https://api.deepseek.com/v1/chat/completions",
            headers=headers,
            json=payload,
            timeout=60
        )
        response.raise_for_status()
        return response.json()

    def chat_with_deepseek(self, user_message):
        """与 DeepSeek AI 对话，支持工具调用"""
        api_key = self.deepseek_api_key
        if not api_key:
            self.log("错误: 未配置 DeepSeek API Key，请使用 'set-api-key <key>' 命令设置。")
            return

        # 添加用户消息到历史
        self.conversation_history.append({"role": "user", "content": user_message})
        if len(self.conversation_history) > 21:
            self.conversation_history = [self.conversation_history[0]] + self.conversation_history[-20:]

        log_file = os.path.join(PYLAUNCHER_DIR, "chat_history.txt")
        try:
            with open(log_file, "a", encoding="utf-8") as f:
                f.write(f"[用户] {user_message}\n")
        except Exception:
            pass

        try:
            response_data = self._call_deepseek_api(self.conversation_history, self.tools)
            message = response_data["choices"][0]["message"]

            # 处理工具调用循环
            while message.get("tool_calls"):
                self.conversation_history.append(message)

                for tool_call in message["tool_calls"]:
                    func_name = tool_call["function"]["name"]
                    func_args = json.loads(tool_call["function"]["arguments"])
                    self.log(f"[DeepSeek] 请求执行操作: {func_name}({func_args})")
                    result = self._execute_tool(func_name, func_args)
                    self.conversation_history.append({
                        "role": "tool",
                        "tool_call_id": tool_call["id"],
                        "content": result
                    })

                response_data = self._call_deepseek_api(self.conversation_history, self.tools)
                message = response_data["choices"][0]["message"]

            reply = message.get("content", "")
            if reply:
                self.conversation_history.append({"role": "assistant", "content": reply})
                with open(log_file, "a", encoding="utf-8") as f:
                    f.write(f"[DeepSeek] {reply}\n")
                    f.write("-" * 40 + "\n")
                self.log(f"[DeepSeek] {reply}")
            else:
                self.log("[DeepSeek] 没有返回内容")

            self._save_conversation_history()

        except Exception as e:
            self.log(f"与 DeepSeek 对话失败: {e}")
            self.log(traceback.format_exc())

    def _execute_tool(self, func_name, args):
        """执行 DeepSeek 请求的工具调用，返回结果字符串"""
        try:
            # 1. 安装原版 Minecraft
            if func_name == "install_minecraft":
                version = args.get("version")
                if not version:
                    return "错误: 未指定 Minecraft 版本"
                threading.Thread(target=self.install_vanilla, args=(version,), daemon=True).start()
                return f"正在后台安装 Minecraft {version}，请稍后查看进度"

            # 2. 安装加载器
            elif func_name == "install_loader":
                loader_type = args.get("loader_type")
                mc_version = args.get("mc_version")
                if not loader_type or not mc_version:
                    return "错误: 缺少加载器类型或 Minecraft 版本"
                mapping = {
                    "fabric": self.install_fabric,
                    "forge": self.install_forge,
                    "neoforge": self.install_neoforge,
                    "quilt": self.install_quilt
                }
                if loader_type not in mapping:
                    return f"不支持的加载器类型: {loader_type}，支持: fabric, forge, neoforge, quilt"
                threading.Thread(target=mapping[loader_type], args=(mc_version,), daemon=True).start()
                return f"正在后台安装 {loader_type} {mc_version}，请稍后查看进度"

            # 3. 安装服务器
            elif func_name == "install_server":
                server_type = args.get("server_type")
                version = args.get("version")
                max_mem = args.get("max_mem")
                min_mem = args.get("min_mem")
                if not all([server_type, version, max_mem, min_mem]):
                    return "错误: 缺少服务器类型、版本、最大内存或最小内存"
                threading.Thread(target=self.install_server, args=(server_type, version, max_mem, min_mem), daemon=True).start()
                return f"正在后台安装 {server_type} 服务器 {version}，内存 {min_mem}-{max_mem}"

            # 4. 下载 Java
            elif func_name == "install_java":
                version = args.get("version")
                if not version:
                    return "错误: 未指定 Java 版本"
                threading.Thread(target=self.install_java, args=(version,), daemon=True).start()
                return f"正在后台下载 Java {version}，请稍后"

            # 5. 批量安装模组
            elif func_name == "install_mods":
                mod_list_str = args.get("mod_list")
                if not mod_list_str:
                    return "错误: 未指定模组名称"
                mod_list = [m.strip() for m in mod_list_str.split(',') if m.strip()]
                threading.Thread(target=self.install_mods_batch, args=(mod_list,), daemon=True).start()
                return f"正在后台批量安装模组: {', '.join(mod_list)}"

            # 6. 安装光影包
            elif func_name == "install_shaderpack":
                shader_name = args.get("shader_name")
                if not shader_name:
                    return "错误: 未指定光影包名称"
                threading.Thread(target=self.install_shaderpack, args=(shader_name,), daemon=True).start()
                return f"正在搜索并安装光影包: {shader_name}"

            # 7. 导入整合包
            elif func_name == "import_modpack":
                file_path = args.get("file_path")
                if not file_path:
                    return "错误: 未指定文件路径"
                if not os.path.exists(file_path):
                    return f"错误: 文件不存在 - {file_path}"
                threading.Thread(target=self.import_modpack, args=(file_path,), daemon=True).start()
                return f"正在导入整合包: {os.path.basename(file_path)}"

            # 8. 启动游戏
            elif func_name == "launch_game":
                version = args.get("version")
                if not version:
                    version = self.config.get("current_version")
                    if not version:
                        return "错误: 未指定版本且当前无默认版本，请先安装一个版本"
                threading.Thread(target=self.launch_game, args=(version,), daemon=True).start()
                return f"正在启动游戏版本: {version}"

            # 9. 登录微软账号
            elif func_name == "microsoft_login":
                threading.Thread(target=self.microsoft_login, daemon=True).start()
                return "正在启动微软设备码登录流程，请按照控制台提示操作"

            # 10. 登出微软账号
            elif func_name == "microsoft_logout":
                self.microsoft_logout()
                return "已退出微软账号登录"

            # 11. 设置离线玩家名
            elif func_name == "set_player_name":
                name = args.get("name")
                if not name:
                    return "错误: 未指定玩家名称"
                self.config["player_name"] = name
                self.save_config()
                return f"离线玩家名称已设置为: {name}"

            # 12. 列出支持的加载器
            elif func_name == "list_loaders":
                self.log("支持的加载器: fabric, forge, neoforge, quilt")
                return "已列出支持的加载器，请查看控制台输出"

            # 13. frp 管理
            elif func_name == "frp_manage":
                action = args.get("action")
                if action == "config":
                    threading.Thread(target=self.frp_config, daemon=True).start()
                    return "正在启动 frp 配置向导"
                elif action == "start":
                    threading.Thread(target=self.frp_start, daemon=True).start()
                    return "正在启动 frp 客户端"
                elif action == "stop":
                    threading.Thread(target=self.frp_stop, daemon=True).start()
                    return "正在停止 frp 客户端"
                elif action == "status":
                    self.frp_status()
                    return "已查询 frp 状态，请查看控制台输出"
                else:
                    return f"未知的 frp 操作: {action}，支持 config/start/stop/status"

            # 14. 清空控制台
            elif func_name == "clean_console":
                # 通过回调通知 GUI 清理
                if self.log_callback:
                    self.log_callback("__CLEAN_CONSOLE__")
                return "控制台已清空"

            # 15. 关闭启动器
            elif func_name == "close_launcher":
                self.close()
                return "正在关闭启动器..."

            # 16. 列出当前模组
            elif func_name == "list_mods":
                self.list_mods()
                return "已列出当前版本模组列表，请查看控制台输出"

            # 17. 检查模组更新
            elif func_name == "mod_update_check":
                self.mod_update()
                return "已检查模组更新，请查看控制台输出"

            # 18. 禁用模组
            elif func_name == "mod_disable":
                mod_name = args.get("mod_name")
                if not mod_name:
                    return "错误: 未指定模组名称"
                self.mod_disable(mod_name)
                return f"已禁用模组: {mod_name}"

            # 19. 启用模组
            elif func_name == "mod_enable":
                mod_name = args.get("mod_name")
                if not mod_name:
                    return "错误: 未指定模组名称"
                self.mod_enable(mod_name)
                return f"已启用模组: {mod_name}"

            # 20. 服务器控制台
            elif func_name == "server_console":
                server_id = args.get("server_id")
                nogui = args.get("nogui", False)
                if not server_id:
                    return "错误: 未指定服务器标识"
                threading.Thread(target=self.server_console, args=(server_id, nogui), daemon=True).start()
                return f"正在启动服务器 {server_id} 控制台（{'无窗口' if nogui else '有新窗口'}）"

            # 21. 编辑服务器配置文件
            elif func_name == "server_config":
                server_type = args.get("server_type")
                if not server_type:
                    return "错误: 未指定服务器类型"
                self.server_config(server_type)
                return f"已打开服务器 {server_type} 的配置文件编辑器"

            # 22. 查询电脑上的 Java 安装
            elif func_name == "list_java":
                self.list_all_java()
                return "已扫描系统中的 Java 安装，请查看控制台输出"

            else:
                return f"未知的工具函数: {func_name}"

        except Exception as e:
            return f"执行 {func_name} 时发生异常: {e}"

    # ---------- 账户相关 ----------
    def get_player_display(self):
        if self.config.get("ms_refresh_token"):
            return self.config.get("ms_username", "已登录用户")
        else:
            return self.config.get("player_name", "Player")

    def microsoft_login(self):
        threading.Thread(target=self._microsoft_device_login, daemon=True).start()

    def _microsoft_device_login(self):
        self.log("正在启动微软设备代码登录流程...")
        try:
            device_resp = requests.post(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                data={"client_id": CLIENT_ID, "scope": "XboxLive.signin offline_access"},
                timeout=20
            )
            if device_resp.status_code != 200:
                self.log(f"设备码请求失败: {device_resp.status_code}")
                return
            device_data = device_resp.json()
            user_code = device_data["user_code"]
            device_code = device_data["device_code"]
            verify_url = device_data.get("verification_uri", "https://www.microsoft.com/link")
            expires_in = int(device_data.get("expires_in", 900))
            interval = int(device_data.get("interval", 5))

            # 复制设备码到剪贴板
            try:
                import pyperclip
                pyperclip.copy(user_code)
                self.log(f"设备代码已复制到剪贴板: {user_code}")
            except ImportError:
                self.log(f"请手动复制设备代码: {user_code} (提示: pip install pyperclip 可启用自动复制)")
            except Exception as e:
                self.log(f"复制到剪贴板失败: {e}，请手动复制: {user_code}")

            self.log(f"请打开浏览器访问: {verify_url}")
            self.log(f"输入设备代码: {user_code}")
            webbrowser.open(verify_url)

            start_time = time.time()
            ms_token_data = None
            while time.time() - start_time < expires_in:
                time.sleep(interval)
                token_resp = requests.post(
                    "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    data={
                        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id": CLIENT_ID,
                        "device_code": device_code
                    },
                    timeout=20
                )
                token_data = token_resp.json()
                if "access_token" in token_data:
                    ms_token_data = token_data
                    break
                err = token_data.get("error")
                if err == "authorization_pending":
                    self.log("等待用户完成登录...")
                elif err == "slow_down":
                    interval += 5
                elif err == "authorization_declined":
                    self.log("登录被用户取消。")
                    return
                elif err == "expired_token":
                    self.log("设备代码已过期，请重新执行 login。")
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
                    "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": f"d={ms_access_token}"},
                    "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT"
                },
                headers={"Content-Type": "application/json"},
                timeout=20
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
                    "RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT"
                },
                headers={"Content-Type": "application/json"},
                timeout=20
            )
            if xsts_resp.status_code != 200:
                try:
                    err_data = xsts_resp.json()
                    xerr = err_data.get("XErr")
                    if xerr == 2148916233:
                        self.log("此微软账号没有 Xbox 账号，请先创建。")
                    elif xerr == 2148916238:
                        self.log("此账号是儿童账号，需要家长授权。")
                    else:
                        self.log(f"XSTS 验证失败: {err_data}")
                except:
                    self.log(f"XSTS 验证失败，状态码: {xsts_resp.status_code}")
                return
            xsts_data = xsts_resp.json()
            xsts_token = xsts_data["Token"]

            # Minecraft 登录
            mc_auth_resp = requests.post(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                json={"identityToken": f"XBL3.0 x={uhs};{xsts_token}"},
                headers={"Content-Type": "application/json"},
                timeout=20
            )
            mc_auth_resp.raise_for_status()
            mc_auth_data = mc_auth_resp.json()
            mc_access_token = mc_auth_data["access_token"]

            # 获取 profile
            profile_resp = requests.get(
                "https://api.minecraftservices.com/minecraft/profile",
                headers={"Authorization": f"Bearer {mc_access_token}"},
                timeout=20
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
            self.log(f"✅ 登录成功！欢迎 {profile['name']}")
        except Exception as e:
            self.log(f"登录失败: {e}")
            self.log(traceback.format_exc())

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

    # ---------- 游戏安装 ----------
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
            loader_version = fabric.get_stable_loader_version(version)
            minecraft_launcher_lib.fabric.install_fabric(version, MC_DIR, callback=callbacks)
            src = os.path.join(MC_DIR, "versions", f"fabric-loader-{loader_version}-{version}")
            dst = os.path.join(MC_DIR, "versions", f"{version}-fabric")
            self._rename_loader_folder(src, dst, version, "fabric")
            self.config["current_version"] = f"{version}-fabric"
            self.config["current_loader"] = "fabric"
            self.config["original_version"] = version
            self.save_config()
            self._install_fabric_api(version, f"{version}-fabric")
            self.log(f"✅ Fabric {version} 安装完成！")
        except Exception as e:
            self.log(f"Fabric 安装失败: {e}")

    def _install_forge_loader(self, version, callbacks):
        self.log("正在安装 Forge 加载器...")
        try:
            forge_versions = forge.list_forge_versions()
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
            # 获取最新版本号（从 Maven 仓库目录列表）
            maven_url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
            resp = requests.get(maven_url, timeout=30)
            resp.raise_for_status()
            version_pattern = re.compile(r'<a href="(\d+\.\d+(?:\.\d+)?)/">')
            versions = version_pattern.findall(resp.text)
            matching = [v for v in versions if v.startswith(version.replace("1.", "")) or v.startswith(version)]
            if not matching:
                self.log(f"错误: 未找到适用于 Minecraft {version} 的 NeoForge 版本")
                return
            matching.sort(key=lambda s: [int(x) for x in s.split('.')])
            neoforge_version = matching[-1]
            self.log(f"找到 NeoForge 版本: {neoforge_version}")

            installer_url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{neoforge_version}/neoforge-{neoforge_version}-installer.jar"
            installer_path = os.path.join(tempfile.gettempdir(), f"neoforge-{neoforge_version}-installer.jar")
            self.log(f"下载安装器: {installer_url}")
            with requests.get(installer_url, stream=True, timeout=60) as r:
                r.raise_for_status()
                with open(installer_path, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        f.write(chunk)

            target_dir = os.path.join(MC_DIR, "versions", f"{version}-neoforge")
            os.makedirs(target_dir, exist_ok=True)

            self.log("正在安装 NeoForge，请稍候...")
            cmd = ["java", "-jar", installer_path, "--install-client", "--target-dir", target_dir]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
            if result.returncode != 0:
                raise Exception(f"安装器执行失败: {result.stderr}")

            os.remove(installer_path)

            # 确保版本 json 文件正确
            possible_json = os.path.join(target_dir, f"neoforge-{neoforge_version}.json")
            if os.path.exists(possible_json):
                shutil.move(possible_json, os.path.join(target_dir, f"{version}-neoforge.json"))

            self.config["current_version"] = f"{version}-neoforge"
            self.config["current_loader"] = "neoforge"
            self.config["original_version"] = version
            self.save_config()
            self.log(f"✅ NeoForge {version} 安装完成！")
        except Exception as e:
            self.log(f"NeoForge 安装失败: {e}")
            self.log(traceback.format_exc())

    def _install_quilt_loader(self, version, callbacks):
        self.log("正在安装 Quilt 加载器...")
        try:
            all_versions = quilt.list_quilt_versions()
            matching = [v for v in all_versions if v.startswith(version)]
            if not matching:
                self.log(f"错误: 未找到适用于 Minecraft {version} 的 Quilt 版本")
                return
            matching.sort(key=lambda v: [int(x) for x in v.split('.')])
            quilt_version = matching[-1]
            self.log(f"找到 Quilt 版本: {quilt_version}")
            quilt.install_quilt_version(quilt_version, MC_DIR, callback=callbacks)
            src = os.path.join(MC_DIR, "versions", quilt_version)
            dst = os.path.join(MC_DIR, "versions", f"{version}-quilt")
            self._rename_loader_folder(src, dst, version, "quilt")
            self.config["current_version"] = f"{version}-quilt"
            self.config["current_loader"] = "quilt"
            self.config["original_version"] = version
            self.save_config()
            self.log(f"✅ Quilt {version} 安装完成！")
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
                    if 'mainClass' not in json_data and loader_name == 'fabric':
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
            self.log(f"已将文件夹重命名为 {version}-{loader_name}")

    def _install_fabric_api(self, mc_version, version_folder):
        self.log("正在自动下载 Fabric API...")
        try:
            headers = {'User-Agent': 'SimpleMCLauncher/1.0'}
            project_id = "fabric-api"
            versions_url = f"https://api.modrinth.com/v2/project/{project_id}/version"
            vers_res = requests.get(versions_url, headers=headers).json()
            compatible = []
            for v in vers_res:
                if mc_version in v.get("game_versions", []) and "fabric" in v.get("loaders", []):
                    compatible.append(v)
            if not compatible:
                self.log("未找到适用于当前版本的 Fabric API")
                return
            compatible.sort(key=lambda x: x.get("date_published", ""), reverse=True)
            latest = compatible[0]
            download_url = latest["files"][0]["url"]
            filename = latest["files"][0]["filename"]
            mods_dir = os.path.join(MC_DIR, "versions", version_folder, "mods")
            os.makedirs(mods_dir, exist_ok=True)
            file_path = os.path.join(mods_dir, filename)
            if os.path.exists(file_path):
                self.log(f"Fabric API 已存在: {filename}")
                return
            self.log(f"正在下载 Fabric API: {filename}")
            with requests.get(download_url, headers=headers, stream=True) as r:
                r.raise_for_status()
                with open(file_path, 'wb') as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        f.write(chunk)
            self.log("✅ Fabric API 安装完成")
        except Exception as e:
            self.log(f"自动下载 Fabric API 失败: {e}")

    # ---------- 启动游戏 ----------
    def launch_game(self, version):
        version_dir = os.path.join(MC_DIR, "versions", version)
        if not os.path.isdir(version_dir):
            self.log(f"错误: 版本文件夹 {version} 不存在")
            return
        json_path = os.path.join(version_dir, f"{version}.json")
        if not os.path.exists(json_path):
            self.log(f"错误: 版本 JSON 文件不存在")
            return
        try:
            with open(json_path, 'r', encoding='utf-8') as f:
                version_data = json.load(f)
            inherits_from = version_data.get("inheritsFrom")
            if inherits_from:
                original_version = inherits_from
                if "-fabric" in version:
                    loader_type = "fabric"
                elif "-forge" in version:
                    loader_type = "forge"
                elif "-quilt" in version:
                    loader_type = "quilt"
                else:
                    loader_type = "vanilla"
            else:
                original_version = version
                loader_type = "vanilla"
            self.config["current_version"] = version
            self.config["original_version"] = original_version
            self.config["current_loader"] = loader_type
            self.save_config()
            self.log(f"当前版本已切换至: {version}")
        except Exception as e:
            self.log(f"警告: 无法解析版本信息: {e}")

        login_options = self.get_login_options()
        if login_options is None:
            self.log("登录已失效，请重新登录")
            return

        isolated_game_dir = version_dir
        os.makedirs(isolated_game_dir, exist_ok=True)
        self._set_game_language_to_chinese(isolated_game_dir)

        options = {
            "username": login_options["username"],
            "uuid": login_options["uuid"],
            "token": login_options["token"],
            "gameDirectory": isolated_game_dir
        }
        try:
            command = minecraft_launcher_lib.command.get_minecraft_command(version, MC_DIR, options)
            self.log("正在启动游戏...")
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            process = subprocess.Popen(
                command, cwd=isolated_game_dir,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding='utf-8', errors='ignore', bufsize=1,
                creationflags=creation_flags
            )
            self.game_process = process
            threading.Thread(target=self._read_game_log, args=(process,), daemon=True).start()
        except Exception as e:
            self.log(f"启动失败: {e}")
            self.log(traceback.format_exc())

    def _read_game_log(self, process):
        for line in iter(process.stdout.readline, ''):
            if line.strip():
                self.log(f"[Game] {line.strip()}")
        process.stdout.close()
        process.wait()
        self.log(f"\n[系统] 游戏已退出，退出码: {process.returncode}")

    def _set_game_language_to_chinese(self, game_dir):
        options_path = os.path.join(game_dir, "options.txt")
        options_data = {}
        if os.path.exists(options_path):
            try:
                with open(options_path, 'r', encoding='utf-8') as f:
                    for line in f:
                        if ':' in line:
                            key, val = line.strip().split(':', 1)
                            options_data[key] = val
            except:
                pass
        options_data['lang'] = 'zh_cn'
        try:
            with open(options_path, 'w', encoding='utf-8') as f:
                for key, val in options_data.items():
                    f.write(f"{key}:{val}\n")
        except:
            pass

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

    # ---------- 模组管理 ----------
    def install_mod(self, mod_name, version=None, auto_select=False):
        # 需要实现，参考原始代码
        self.log(f"安装模组功能待实现: {mod_name}")
        return True

    def install_mods_batch(self, mod_list):
        for mod in mod_list:
            self.install_mod(mod, auto_select=True)

    def install_shaderpack(self, shader_name):
        self.log(f"安装光影包功能待实现: {shader_name}")

    def import_modpack(self, file_path):
        self.log(f"导入整合包功能待实现: {file_path}")

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
            status = "[禁用]" if is_disabled else "[启用]"
            self.log(f"  {status} {mod_name}")

    def mod_update(self):
        self.log("检查模组更新...（暂未实现自动更新）")

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

    # ---------- 服务器 ----------
    def install_server(self, server_type, version, max_mem, min_mem):
        self.log(f"安装服务器 {server_type} {version} 功能待实现")

    def launch_server(self, server_id, nogui=False):
        self.log(f"启动服务器 {server_id} 功能待实现")

    def server_console(self, server_id, nogui=False):
        self.launch_server(server_id, nogui)

    def server_config(self, server_type):
        self.log(f"编辑服务器 {server_type} 配置功能待实现")

    # ---------- Java ----------
    def install_java(self, version):
        self.log(f"安装 Java {version} 功能待实现")

    def list_all_java(self, extra_paths=None):
        self.log("列出 Java 安装功能待实现")

    # ---------- frp ----------
    def frp_config(self):
        self.log("frp 配置功能待实现")

    def frp_start(self):
        self.log("frp 启动功能待实现")

    def frp_stop(self):
        self.log("frp 停止功能待实现")

    def frp_status(self):
        self.log("frp 状态查询功能待实现")

    # ---------- 更新检查 ----------
    def check_for_updates(self, silent=False):
        if not getattr(sys, 'frozen', False):
            if not silent:
                self.log("开发模式运行，跳过更新检查")
            return False
        try:
            api_url = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest"
            resp = requests.get(api_url, timeout=15)
            resp.raise_for_status()
            latest = resp.json()
            latest_tag = latest.get("tag_name", "")
            if not latest_tag:
                return False
            current = VERSION_OF_LAUNCHER.lstrip('v')
            latest_clean = latest_tag.lstrip('v')
            if self._compare_versions(latest_clean, current) > 0:
                if not silent:
                    self.log(f"发现新版本: {latest_tag}")
                return True
        except Exception as e:
            if not silent:
                self.log(f"检查更新失败: {e}")
        return False

    def _compare_versions(self, v1, v2):
        def norm(v):
            return [int(x) for x in v.split('.')]
        try:
            v1_parts = norm(v1)
            v2_parts = norm(v2)
            for a, b in zip(v1_parts, v2_parts):
                if a != b:
                    return 1 if a > b else -1
            return 0 if len(v1_parts) == len(v2_parts) else (1 if len(v1_parts) > len(v2_parts) else -1)
        except:
            return 0

    def close(self):
        """退出程序（由 GUI 层调用 sys.exit）"""
        if self.log_callback:
            self.log_callback("__EXIT__")