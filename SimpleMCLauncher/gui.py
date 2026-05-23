# gui.py
import tkinter as tk
from tkinter import scrolledtext, messagebox, simpledialog
import threading
import platform
from core import LauncherCore
from utils import VERSION_OF_LAUNCHER, MC_DIR, SERVER_DIR, JAVA_DIR

class SimpleMCLauncherGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("极简 MC 启动器 (全能版)")
        self.root.geometry("750x650")
        
        # 先创建所有 UI 控件
        self.console = scrolledtext.ScrolledText(root, state='disabled', bg='#1e1e1e', fg='#d4d4d4', font=('Consolas', 10))
        self.console.pack(padx=10, pady=10, fill=tk.BOTH, expand=True)
        
        self.entry = tk.Entry(root, font=('Consolas', 12), bg='#2d2d2d', fg='white', insertbackground='white')
        self.entry.pack(padx=10, pady=(0, 10), fill=tk.X)
        self.entry.bind("<Return>", self.on_enter)
        
        # 命令历史
        self.command_history = []
        self.history_index = -1
        self.entry.bind("<Up>", self.history_up)
        self.entry.bind("<Down>", self.history_down)
        
        # 服务器控制台模式标志
        self.server_console_mode = False
        
        # 现在初始化核心（此时 self.console 已存在）
        self.core = LauncherCore(log_callback=self.log)
        
        # 显示欢迎信息
        self.show_welcome()

    def show_welcome(self):
        self.log(f"欢迎使用极简 MC 启动器 (全能版)！ 作者BoscoNew")
        self.log(f"启动器版本: {VERSION_OF_LAUNCHER}")
        self.log(f"游戏数据目录: {MC_DIR}")
        self.log(f"服务器目录: {SERVER_DIR}")
        self.log(f"Java 目录: {JAVA_DIR}")
        self.log(f"系统架构：{platform.system().lower()} {platform.machine().lower()}")
        self.log(f"当前玩家: [{self.core.get_player_display()}] | 当前版本: [{self.core.config.get('current_version', '未设置')}]")
        
    def log(self, message):
        """GUI 日志输出，同时处理特殊指令"""
        if message == "__CLEAN_CONSOLE__":
            self.clean_console()
            return
        if message == "__EXIT__":
            self.root.quit()
            return
        self.console.config(state='normal')
        self.console.insert(tk.END, message + "\n")
        self.console.see(tk.END)
        self.console.config(state='disabled')

    def on_enter(self, event):
        cmd = self.entry.get().strip()
        if not cmd:
            return
        if not self.command_history or self.command_history[-1] != cmd:
            self.command_history.append(cmd)
        self.history_index = -1
        self.entry.delete(0, tk.END)
        self.log(f"\n> {cmd}")
        self._dispatch_command(cmd)

    def _dispatch_command(self, cmd):
        parts = cmd.split()
        if not parts:
            return
        command = parts[0].lower()
        
        # 帮助
        if command in ("help", "h"):
            self.show_help()
        elif command == "clean":
            self.clean_console()
        elif command == "close":
            self.root.quit()
        elif command == "login":
            threading.Thread(target=self.core.microsoft_login, daemon=True).start()
        elif command == "logout":
            self.core.microsoft_logout()
        elif command.startswith("player-name="):
            name = cmd.split("=", 1)[1].strip()
            if name:
                self.core.config["player_name"] = name
                self.core.save_config()
                self.log(f"离线玩家名称已设置为 {name}")
        elif command == "install" and len(parts) > 1:
            sub = parts[1].lower()
            if sub == "minecraft" and len(parts) > 2:
                threading.Thread(target=self.core.install_vanilla, args=(parts[2],), daemon=True).start()
            elif sub == "fabric" and len(parts) > 2:
                threading.Thread(target=self.core.install_fabric, args=(parts[2],), daemon=True).start()
            elif sub == "forge" and len(parts) > 2:
                threading.Thread(target=self.core.install_forge, args=(parts[2],), daemon=True).start()
            elif sub == "neoforge" and len(parts) > 2:
                threading.Thread(target=self.core.install_neoforge, args=(parts[2],), daemon=True).start()
            elif sub == "quilt" and len(parts) > 2:
                threading.Thread(target=self.core.install_quilt, args=(parts[2],), daemon=True).start()
            elif sub == "server" and len(parts) >= 5:
                server_type = parts[2]
                version = parts[3]
                max_mem = parts[4]
                min_mem = parts[5] if len(parts) > 5 else "1G"
                threading.Thread(target=self.core.install_server, args=(server_type, version, max_mem, min_mem), daemon=True).start()
            elif sub.startswith("java-"):
                ver = sub[5:]
                threading.Thread(target=self.core.install_java, args=(ver,), daemon=True).start()
            else:
                self.log("未知安装命令，输入 help 查看用法")
        elif command == "launch" and len(parts) > 1:
            version = parts[1]
            threading.Thread(target=self.core.launch_game, args=(version,), daemon=True).start()
        elif command == "stop":
            self.core.stop_game()
        elif command == "list" and len(parts) > 1:
            if parts[1] == "mods":
                self.core.list_mods()
            elif parts[1] == "loaders":
                self.show_loaders()
            else:
                self.log("未知列表类型")
        elif command == "mod" and len(parts) > 1:
            sub = parts[1].lower()
            if sub == "update":
                self.core.mod_update()
            elif sub == "disable" and len(parts) > 2:
                self.core.mod_disable(parts[2])
            elif sub == "enable" and len(parts) > 2:
                self.core.mod_enable(parts[2])
            else:
                self.log("用法: mod update / disable <name> / enable <name>")
        elif command == "server" and len(parts) > 2:
            if parts[1] == "console":
                server_id = parts[2]
                nogui = len(parts) > 3 and parts[3].lower() == "nogui"
                threading.Thread(target=self.core.server_console, args=(server_id, nogui), daemon=True).start()
            elif parts[1] == "config":
                self.core.server_config(parts[2])
        elif command == "set-api-key" and len(parts) > 1:
            self.core.set_api_key(parts[1])
        elif command == "history":
            self.core.show_history()
        elif command == "clear-history":
            self.core.clear_history()
        else:
            # 未识别命令，尝试 DeepSeek
            threading.Thread(target=self.core.chat_with_deepseek, args=(cmd,), daemon=True).start()

    def show_help(self):
        help_text = """📦 可用命令列表:
        install minecraft <version>                 - 下载指定版本游戏
        install fabric <version>                    - 安装 Fabric
        install forge <version>                     - 安装 Forge
        install neoforge <version>                  - 安装 NeoForge
        install quilt <version>                     - 安装 Quilt
        install server <type> <version> <max> <min> - 下载服务器 (paper/purpur/fabric/forge)
        install java-<version>                      - 下载 Java (如 17,21)
        launch <version>                            - 启动游戏
        login / logout                              - 微软账号登录/登出
        player-name=<name>                          - 设置离线玩家名
        list mods                                   - 列出当前版本模组
        mod update / disable / enable               - 模组管理
        server console <id> [nogui]                 - 启动服务器控制台
        server config <type>                        - 编辑服务器配置
        set-api-key <key>                           - 设置 DeepSeek API Key
        history / clear-history                     - 查看/清空 DeepSeek 对话历史
        clean / close / stop / help                 - 其他
        any                                         - 与 DeepSeek 对话（需设置 API Key）
        """
        self.log(help_text)

    def show_loaders(self):
        self.log("支持的加载器: fabric, forge, neoforge, quilt")

    def clean_console(self):
        self.console.config(state='normal')
        self.console.delete('1.0', tk.END)
        self.console.config(state='disabled')
        self.show_welcome()

    def history_up(self, event):
        if not self.command_history:
            return
        if self.history_index == -1:
            self.current_input = self.entry.get()
            self.history_index = len(self.command_history) - 1
        elif self.history_index > 0:
            self.history_index -= 1
        else:
            return
        self.entry.delete(0, tk.END)
        self.entry.insert(0, self.command_history[self.history_index])

    def history_down(self, event):
        if not self.command_history or self.history_index == -1:
            return
        if self.history_index < len(self.command_history) - 1:
            self.history_index += 1
            self.entry.delete(0, tk.END)
            self.entry.insert(0, self.command_history[self.history_index])
        else:
            self.history_index = -1
            self.entry.delete(0, tk.END)
            if hasattr(self, 'current_input'):
                self.entry.insert(0, self.current_input)
                del self.current_input