# gui.py
"""极简 MC 启动器图形界面（基于 CustomTkinter）。

界面完全通过按钮/下拉框/输入框交互，不再暴露命令行入口。
所有耗时操作都在后台线程执行，并通过 after() 安全地更新界面。
"""
import os
import threading
import platform
from tkinter import filedialog

import customtkinter as ctk

from core import LauncherCore, LOADER_NAMES
from utils import VERSION_OF_LAUNCHER, MC_DIR, SERVER_DIR, JAVA_DIR, LOG_FILE

ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

APP_TITLE = "极简 MC 启动器"


class SimpleMCLauncherGUI(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("960x680")
        self.minsize(800, 600)

        # 进度状态（跨线程跟踪）
        self._progress_total = 0
        self._progress_current = 0

        # 初始化核心（回调会在线程间安全地调度 UI 更新）
        self.core = LauncherCore(
            log_callback=self._thread_safe_log,
            status_callback=self._thread_safe_status,
            progress_callback=self._thread_safe_progress,
        )

        self._build_ui()
        self._refresh_versions()
        self._refresh_mods()
        self._refresh_servers()
        self._update_player_display()

        # 关闭时清理进程
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ------------------------------------------------------------------
    # 线程安全的 UI 更新
    # ------------------------------------------------------------------
    def _thread_safe_log(self, message: str) -> None:
        self.after(0, self._append_log, message)

    def _thread_safe_status(self, message: str) -> None:
        self.after(0, self._set_status, message)

    def _thread_safe_progress(self, current: int, total: int) -> None:
        self.after(0, self._set_progress, current, total)

    def _append_log(self, message: str) -> None:
        try:
            self.log_textbox.configure(state="normal")
            self.log_textbox.insert("end", str(message) + "\n")
            self.log_textbox.see("end")
            self.log_textbox.configure(state="disabled")
        except Exception:  # noqa: BLE001
            pass

    def _set_status(self, message: str) -> None:
        try:
            self.status_label.configure(text=str(message))
        except Exception:  # noqa: BLE001
            pass

    def _set_progress(self, current: int, total: int) -> None:
        if total and total > 0:
            self._progress_total = total
            self._progress_current = current
        else:
            self._progress_current = current
        if self._progress_total > 0:
            frac = min(1.0, max(0.0, self._progress_current / self._progress_total))
            self.progress_bar.set(frac)
        else:
            self.progress_bar.set(0)

    def _reset_progress(self) -> None:
        self._progress_total = 0
        self._progress_current = 0
        self.progress_bar.set(0)

    # ------------------------------------------------------------------
    # 后台任务
    # ------------------------------------------------------------------
    def _run_bg(self, fn, *args) -> None:
        threading.Thread(target=fn, args=args, daemon=True).start()

    # ------------------------------------------------------------------
    # UI 构建
    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        # 顶栏
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)

        header = ctk.CTkFrame(self, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", padx=16, pady=(12, 6))
        header.grid_columnconfigure(0, weight=1)

        title = ctk.CTkLabel(header, text=APP_TITLE, font=ctk.CTkFont(size=22, weight="bold"))
        title.grid(row=0, column=0, sticky="w")
        self.status_label = ctk.CTkLabel(header, text="就绪", text_color="#9aa0a6")
        self.status_label.grid(row=1, column=0, sticky="w", pady=(2, 0))

        # 标签页
        self.tabview = ctk.CTkTabview(self, command=self._on_tab_changed)
        self.tabview.grid(row=1, column=0, sticky="nsew", padx=16, pady=6)

        self.tab_home = self.tabview.add("启动")
        self.tab_versions = self.tabview.add("版本")
        self.tab_mods = self.tabview.add("模组")
        self.tab_servers = self.tabview.add("服务器")
        self.tab_settings = self.tabview.add("设置")
        self.tab_log = self.tabview.add("日志")

        self._build_home_tab()
        self._build_versions_tab()
        self._build_mods_tab()
        self._build_servers_tab()
        self._build_settings_tab()
        self._build_log_tab()

    # ---------------- 启动页 ----------------
    def _build_home_tab(self) -> None:
        tab = self.tab_home
        tab.grid_columnconfigure(0, weight=1)

        # 账号信息
        account_frame = ctk.CTkFrame(tab)
        account_frame.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 6))
        account_frame.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(account_frame, text="账号", font=ctk.CTkFont(weight="bold")).grid(
            row=0, column=0, padx=12, pady=10, sticky="w")
        self.player_label = ctk.CTkLabel(account_frame, text="未登录", text_color="#9aa0a6")
        self.player_label.grid(row=0, column=1, sticky="w", padx=6)
        self.login_btn = ctk.CTkButton(account_frame, text="微软登录", width=110,
                                       command=lambda: self._run_bg(self.core.microsoft_login))
        self.login_btn.grid(row=0, column=2, padx=6, pady=10)
        self.logout_btn = ctk.CTkButton(account_frame, text="退出登录", width=110,
                                        fg_color="#8b0000", hover_color="#a00000",
                                        command=lambda: self._run_bg(self._do_logout))
        self.logout_btn.grid(row=0, column=3, padx=(6, 12), pady=10)

        # 版本选择 + 启动
        launch_frame = ctk.CTkFrame(tab)
        launch_frame.grid(row=1, column=0, sticky="ew", padx=12, pady=6)
        launch_frame.grid_columnconfigure(1, weight=1)

        ctk.CTkLabel(launch_frame, text="游戏版本", font=ctk.CTkFont(weight="bold")).grid(
            row=0, column=0, padx=12, pady=12, sticky="w")
        self.version_combo = ctk.CTkComboBox(launch_frame, values=["（未安装版本）"],
                                             state="readonly", width=320)
        self.version_combo.grid(row=0, column=1, sticky="w", padx=6, pady=12)
        ctk.CTkButton(launch_frame, text="刷新", width=70,
                      command=self._refresh_versions).grid(row=0, column=2, padx=6)

        self.launch_btn = ctk.CTkButton(launch_frame, text="🚀 启动游戏", height=48,
                                        font=ctk.CTkFont(size=16, weight="bold"),
                                        command=self._on_launch)
        self.launch_btn.grid(row=1, column=0, columnspan=3, padx=12, pady=(6, 12), sticky="ew")
        self.stop_btn = ctk.CTkButton(launch_frame, text="停止游戏", height=34,
                                      fg_color="#8b0000", hover_color="#a00000",
                                      command=self.core.stop_game)
        self.stop_btn.grid(row=2, column=0, columnspan=3, padx=12, pady=(0, 12), sticky="ew")

        # 进度条
        self.progress_bar = ctk.CTkProgressBar(launch_frame)
        self.progress_bar.grid(row=3, column=0, columnspan=3, padx=12, pady=(0, 12), sticky="ew")
        self.progress_bar.set(0)

        # 信息
        info_frame = ctk.CTkFrame(tab)
        info_frame.grid(row=2, column=0, sticky="ew", padx=12, pady=6)
        info_text = (f"版本: {VERSION_OF_LAUNCHER}    作者: BoscoNew\n"
                     f"系统: {platform.system()} {platform.machine()}\n"
                     f"游戏目录: {MC_DIR}")
        ctk.CTkLabel(info_frame, text=info_text, justify="left",
                     text_color="#9aa0a6").grid(row=0, column=0, padx=12, pady=12, sticky="w")

    def _on_launch(self) -> None:
        version = self.version_combo.get()
        if not version or version == "（未安装版本）":
            self._append_log("请先选择要启动的版本（可在「版本」页安装）")
            return
        self.launch_btn.configure(state="disabled")
        self._reset_progress()

        def job():
            try:
                self.core.launch_game(version)
            finally:
                self.after(0, lambda: self.launch_btn.configure(state="normal"))

        self._run_bg(job)

    def _do_logout(self) -> None:
        self.core.microsoft_logout()
        self._update_player_display()

    def _update_player_display(self) -> None:
        display = self.core.get_player_display()
        logged_in = self.core.is_microsoft_logged_in()
        self.player_label.configure(
            text=f"{display}" + ("（正版登录）" if logged_in else "（离线模式）"))
        self.logout_btn.configure(state="normal" if logged_in else "disabled")

    # ---------------- 版本页 ----------------
    def _build_versions_tab(self) -> None:
        tab = self.tab_versions
        tab.grid_columnconfigure(0, weight=1)

        # 安装区域
        install_frame = ctk.CTkFrame(tab)
        install_frame.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 6))
        ctk.CTkLabel(install_frame, text="安装新版本",
                     font=ctk.CTkFont(weight="bold")).grid(row=0, column=0, padx=12, pady=(12, 6), sticky="w")

        form = ctk.CTkFrame(install_frame, fg_color="transparent")
        form.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 12))
        form.grid_columnconfigure(1, weight=1)

        ctk.CTkLabel(form, text="MC 版本").grid(row=0, column=0, padx=(0, 6), pady=6, sticky="w")
        self.install_version_entry = ctk.CTkEntry(form, placeholder_text="如 1.21.1", width=180)
        self.install_version_entry.grid(row=0, column=1, padx=6, pady=6, sticky="w")
        ctk.CTkLabel(form, text="加载器").grid(row=0, column=2, padx=(12, 6), pady=6, sticky="w")
        self.install_loader_menu = ctk.CTkOptionMenu(
            form, values=list(LOADER_NAMES.keys()), width=160,
            command=lambda _: None)
        self.install_loader_menu.set("vanilla")
        self.install_loader_menu.grid(row=0, column=3, padx=6, pady=6, sticky="w")
        self.install_btn = ctk.CTkButton(form, text="安装", width=90,
                                         command=self._on_install)
        self.install_btn.grid(row=0, column=4, padx=12, pady=6)

        self.versions_status = ctk.CTkLabel(install_frame, text="", text_color="#9aa0a6")
        self.versions_status.grid(row=2, column=0, padx=12, pady=(0, 10), sticky="w")

        # 已安装列表
        ctk.CTkLabel(tab, text="已安装版本",
                     font=ctk.CTkFont(weight="bold")).grid(row=1, column=0, padx=12, pady=(6, 4), sticky="w")
        self.versions_scroll = ctk.CTkScrollableFrame(tab, height=280)
        self.versions_scroll.grid(row=2, column=0, sticky="ew", padx=12, pady=(0, 12))
        self.versions_scroll.grid_columnconfigure(0, weight=1)

    def _on_install(self) -> None:
        version = self.install_version_entry.get().strip()
        loader = self.install_loader_menu.get()
        if not version:
            self._append_log("请输入 MC 版本号")
            return
        self.install_btn.configure(state="disabled")
        self._reset_progress()
        self.versions_status.configure(text=f"正在安装 {LOADER_NAMES.get(loader, loader)} {version} ...")

        def job():
            try:
                if loader == "vanilla":
                    self.core.install_vanilla(version)
                else:
                    self.core.install_loader(loader, version)
            finally:
                self.after(0, self._install_done)

        self._run_bg(job)

    def _install_done(self) -> None:
        self.install_btn.configure(state="normal")
        self.versions_status.configure(text="")
        self._refresh_versions()

    def _refresh_versions(self) -> None:
        versions = self.core.get_installed_versions()
        values = versions if versions else ["（未安装版本）"]
        self.version_combo.configure(values=values)
        current = self.core.config.get("current_version", "")
        if current in versions:
            self.version_combo.set(current)
        else:
            self.version_combo.set(values[0])

        # 刷新列表
        for w in self.versions_scroll.winfo_children():
            w.destroy()
        if not versions:
            ctk.CTkLabel(self.versions_scroll, text="暂无已安装版本，请在上方安装",
                         text_color="#9aa0a6").grid(row=0, column=0, padx=8, pady=8)
            return
        for i, v in enumerate(versions):
            loader = self.core.detect_loader(v)
            row = ctk.CTkFrame(self.versions_scroll)
            row.grid(row=i, column=0, sticky="ew", padx=4, pady=3)
            row.grid_columnconfigure(0, weight=1)
            label = f"{v}   [{LOADER_NAMES.get(loader, loader)}]"
            if v == self.core.config.get("current_version", ""):
                label += "   (当前)"
            ctk.CTkLabel(row, text=label, anchor="w").grid(row=0, column=0, sticky="w", padx=8, pady=4)
            ctk.CTkButton(row, text="设为当前", width=80,
                          command=lambda ver=v: self._set_current_version(ver)).grid(
                row=0, column=1, padx=4, pady=4)

    def _set_current_version(self, version: str) -> None:
        self.core.config["current_version"] = version
        self.core.config["current_loader"] = self.core.detect_loader(version)
        self.core.config["original_version"] = self.core._resolve_original_version(version)
        self.core.save_config()
        self._append_log(f"当前版本已切换为: {version}")
        self._refresh_versions()

    # ---------------- 模组页 ----------------
    def _build_mods_tab(self) -> None:
        tab = self.tab_mods
        tab.grid_columnconfigure(0, weight=1)

        self.mods_version_label = ctk.CTkLabel(tab, text="", font=ctk.CTkFont(weight="bold"))
        self.mods_version_label.grid(row=0, column=0, padx=12, pady=(12, 4), sticky="w")

        # 操作区
        actions = ctk.CTkFrame(tab, fg_color="transparent")
        actions.grid(row=1, column=0, sticky="ew", padx=12, pady=4)
        actions.grid_columnconfigure(1, weight=1)

        ctk.CTkLabel(actions, text="安装模组").grid(row=0, column=0, padx=(0, 6), pady=6, sticky="w")
        self.mod_entry = ctk.CTkEntry(actions, placeholder_text="模组名称，如 sodium", width=200)
        self.mod_entry.grid(row=0, column=1, padx=6, pady=6, sticky="ew")
        ctk.CTkButton(actions, text="搜索安装", width=100,
                      command=self._on_install_mod).grid(row=0, column=2, padx=6, pady=6)

        ctk.CTkLabel(actions, text="光影包").grid(row=0, column=3, padx=(12, 6), pady=6, sticky="w")
        self.shader_entry = ctk.CTkEntry(actions, placeholder_text="光影名称，如 complementary", width=180)
        self.shader_entry.grid(row=0, column=4, padx=6, pady=6, sticky="ew")
        ctk.CTkButton(actions, text="安装", width=80,
                      command=self._on_install_shader).grid(row=0, column=5, padx=6, pady=6)

        actions2 = ctk.CTkFrame(tab, fg_color="transparent")
        actions2.grid(row=2, column=0, sticky="ew", padx=12, pady=4)
        ctk.CTkButton(actions2, text="导入整合包 (.mrpack)",
                      command=self._on_import_modpack).grid(row=0, column=0, padx=0, pady=4, sticky="w")
        ctk.CTkButton(actions2, text="检查模组更新", width=120,
                      command=lambda: self._run_bg(self.core.mod_update)).grid(
            row=0, column=1, padx=12, pady=4, sticky="w")
        ctk.CTkButton(actions2, text="刷新列表", width=100,
                      command=self._refresh_mods).grid(row=0, column=2, padx=0, pady=4, sticky="w")

        # 模组列表
        self.mods_scroll = ctk.CTkScrollableFrame(tab, height=320)
        self.mods_scroll.grid(row=3, column=0, sticky="ew", padx=12, pady=(6, 12))
        self.mods_scroll.grid_columnconfigure(0, weight=1)

    def _current_version_or_none(self):
        v = self.core.config.get("current_version", "")
        return v if v else None

    def _on_install_mod(self) -> None:
        name = self.mod_entry.get().strip()
        if not name:
            self._append_log("请输入模组名称")
            return
        self._reset_progress()
        self._run_bg(self._mod_job, lambda: self.core.install_mod(name))

    def _on_install_shader(self) -> None:
        name = self.shader_entry.get().strip()
        if not name:
            self._append_log("请输入光影包名称")
            return
        self._reset_progress()
        self._run_bg(self._mod_job, lambda: self.core.install_shaderpack(name))

    def _mod_job(self, fn) -> None:
        try:
            fn()
        finally:
            self.after(0, self._refresh_mods)

    def _on_import_modpack(self) -> None:
        path = filedialog.askopenfilename(
            title="选择整合包", filetypes=[("Modrinth 整合包", "*.mrpack"), ("所有文件", "*.*")])
        if not path:
            return
        self._reset_progress()
        self._run_bg(self._mod_job, lambda: self.core.import_modpack(path))

    def _refresh_mods(self) -> None:
        current = self.core.config.get("current_version", "")
        self.mods_version_label.configure(
            text=f"当前版本: {current or '（未选择）'}")
        for w in self.mods_scroll.winfo_children():
            w.destroy()
        if not current:
            ctk.CTkLabel(self.mods_scroll, text="请先在「启动」页选择版本",
                         text_color="#9aa0a6").grid(row=0, column=0, padx=8, pady=8)
            return
        mods = self.core.get_mods_list(current)
        if not mods:
            ctk.CTkLabel(self.mods_scroll, text="该版本暂无模组",
                         text_color="#9aa0a6").grid(row=0, column=0, padx=8, pady=8)
            return
        for i, m in enumerate(mods):
            row = ctk.CTkFrame(self.mods_scroll)
            row.grid(row=i, column=0, sticky="ew", padx=4, pady=3)
            row.grid_columnconfigure(0, weight=1)
            state = "禁用" if m["disabled"] else "启用"
            color = "#c94f4f" if m["disabled"] else "#4fae6e"
            name_label = ctk.CTkLabel(row, text=m["name"], anchor="w")
            name_label.grid(row=0, column=0, sticky="w", padx=8, pady=4)
            state_label = ctk.CTkLabel(row, text=state, text_color=color, width=50)
            state_label.grid(row=0, column=1, padx=4, pady=4)
            if m["disabled"]:
                btn = ctk.CTkButton(row, text="启用", width=70, fg_color="#4fae6e",
                                    hover_color="#5cc080",
                                    command=lambda name=m["name"]: self._toggle_mod(name, False))
            else:
                btn = ctk.CTkButton(row, text="禁用", width=70, fg_color="#8b0000",
                                    hover_color="#a00000",
                                    command=lambda name=m["name"]: self._toggle_mod(name, True))
            btn.grid(row=0, column=2, padx=6, pady=4)

    def _toggle_mod(self, name: str, disable: bool) -> None:
        if disable:
            self.core.mod_disable(name)
        else:
            self.core.mod_enable(name)
        self.after(0, self._refresh_mods)

    # ---------------- 服务器页 ----------------
    def _build_servers_tab(self) -> None:
        tab = self.tab_servers
        tab.grid_columnconfigure(0, weight=1)

        install_frame = ctk.CTkFrame(tab)
        install_frame.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 6))
        ctk.CTkLabel(install_frame, text="下载服务器",
                     font=ctk.CTkFont(weight="bold")).grid(row=0, column=0, padx=12, pady=(12, 6), sticky="w")

        form = ctk.CTkFrame(install_frame, fg_color="transparent")
        form.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 12))

        ctk.CTkLabel(form, text="类型").grid(row=0, column=0, padx=(0, 6), pady=6, sticky="w")
        self.server_type_menu = ctk.CTkOptionMenu(form, values=["paper", "purpur", "vanilla", "forge", "fabric"],
                                                  width=130)
        self.server_type_menu.set("paper")
        self.server_type_menu.grid(row=0, column=1, padx=6, pady=6, sticky="w")
        ctk.CTkLabel(form, text="版本").grid(row=0, column=2, padx=(12, 6), pady=6, sticky="w")
        self.server_version_entry = ctk.CTkEntry(form, placeholder_text="如 1.21.1", width=140)
        self.server_version_entry.grid(row=0, column=3, padx=6, pady=6, sticky="w")
        ctk.CTkLabel(form, text="内存").grid(row=0, column=4, padx=(12, 6), pady=6, sticky="w")
        self.server_mem_entry = ctk.CTkEntry(form, placeholder_text="2G", width=80)
        self.server_mem_entry.insert(0, "2G")
        self.server_mem_entry.grid(row=0, column=5, padx=6, pady=6, sticky="w")
        self.server_install_btn = ctk.CTkButton(form, text="下载", width=80,
                                                command=self._on_install_server)
        self.server_install_btn.grid(row=0, column=6, padx=12, pady=6)

        ctk.CTkLabel(tab, text="已安装服务器",
                     font=ctk.CTkFont(weight="bold")).grid(row=1, column=0, padx=12, pady=(6, 4), sticky="w")
        self.servers_scroll = ctk.CTkScrollableFrame(tab, height=300)
        self.servers_scroll.grid(row=2, column=0, sticky="ew", padx=12, pady=(0, 12))
        self.servers_scroll.grid_columnconfigure(0, weight=1)

    def _on_install_server(self) -> None:
        server_type = self.server_type_menu.get()
        version = self.server_version_entry.get().strip()
        mem = self.server_mem_entry.get().strip() or "2G"
        if not version:
            self._append_log("请输入服务器版本")
            return
        self.server_install_btn.configure(state="disabled")
        self._reset_progress()

        def job():
            try:
                self.core.install_server(server_type, version, mem, mem)
            finally:
                self.after(0, self._server_install_done)

        self._run_bg(job)

    def _server_install_done(self) -> None:
        self.server_install_btn.configure(state="normal")
        self._refresh_servers()

    def _refresh_servers(self) -> None:
        for w in self.servers_scroll.winfo_children():
            w.destroy()
        servers = self.core.get_installed_servers()
        if not servers:
            ctk.CTkLabel(self.servers_scroll, text="暂无服务器，请在上方下载",
                         text_color="#9aa0a6").grid(row=0, column=0, padx=8, pady=8)
            return
        for i, s in enumerate(servers):
            row = ctk.CTkFrame(self.servers_scroll)
            row.grid(row=i, column=0, sticky="ew", padx=4, pady=3)
            row.grid_columnconfigure(0, weight=1)
            ctk.CTkLabel(row, text=s, anchor="w").grid(row=0, column=0, sticky="w", padx=8, pady=4)
            ctk.CTkButton(row, text="启动", width=70, fg_color="#4fae6e", hover_color="#5cc080",
                          command=lambda sid=s: self._run_bg(self.core.launch_server, sid)).grid(
                row=0, column=1, padx=4, pady=4)
            ctk.CTkButton(row, text="停止", width=70, fg_color="#8b0000", hover_color="#a00000",
                          command=self.core.stop_server).grid(row=0, column=2, padx=4, pady=4)
            ctk.CTkButton(row, text="配置", width=70,
                          command=lambda sid=s: self.core.server_config(sid)).grid(
                row=0, column=3, padx=6, pady=4)

    # ---------------- 设置页 ----------------
    def _build_settings_tab(self) -> None:
        tab = self.tab_settings
        tab.grid_columnconfigure(0, weight=1)

        # 玩家名
        name_frame = ctk.CTkFrame(tab)
        name_frame.grid(row=0, column=0, sticky="ew", padx=12, pady=(12, 6))
        name_frame.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(name_frame, text="离线玩家名").grid(row=0, column=0, padx=12, pady=10, sticky="w")
        self.player_name_entry = ctk.CTkEntry(name_frame, placeholder_text="Player")
        self.player_name_entry.insert(0, self.core.config.get("player_name", "Player"))
        self.player_name_entry.grid(row=0, column=1, padx=6, pady=10, sticky="ew")
        ctk.CTkButton(name_frame, text="保存", width=80,
                      command=self._on_save_player_name).grid(row=0, column=2, padx=(6, 12), pady=10)

        # 内存
        mem_frame = ctk.CTkFrame(tab)
        mem_frame.grid(row=1, column=0, sticky="ew", padx=12, pady=6)
        ctk.CTkLabel(mem_frame, text="内存设置").grid(row=0, column=0, padx=12, pady=10, sticky="w")
        ctk.CTkLabel(mem_frame, text="最大 (Xmx)").grid(row=0, column=1, padx=(12, 6), pady=10, sticky="w")
        self.max_mem_entry = ctk.CTkEntry(mem_frame, width=90)
        self.max_mem_entry.insert(0, self.core.config.get("max_memory", "2G"))
        self.max_mem_entry.grid(row=0, column=2, padx=6, pady=10)
        ctk.CTkLabel(mem_frame, text="最小 (Xms)").grid(row=0, column=3, padx=(12, 6), pady=10, sticky="w")
        self.min_mem_entry = ctk.CTkEntry(mem_frame, width=90)
        self.min_mem_entry.insert(0, self.core.config.get("min_memory", "1G"))
        self.min_mem_entry.grid(row=0, column=4, padx=6, pady=10)
        ctk.CTkButton(mem_frame, text="保存", width=80,
                      command=self._on_save_memory).grid(row=0, column=5, padx=(12, 12), pady=10)

        # DeepSeek API
        api_frame = ctk.CTkFrame(tab)
        api_frame.grid(row=2, column=0, sticky="ew", padx=12, pady=6)
        api_frame.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(api_frame, text="DeepSeek API Key").grid(row=0, column=0, padx=12, pady=10, sticky="w")
        self.api_key_entry = ctk.CTkEntry(api_frame, show="*", placeholder_text="（可选）")
        if self.core.deepseek_api_key:
            self.api_key_entry.insert(0, self.core.deepseek_api_key)
        self.api_key_entry.grid(row=0, column=1, padx=6, pady=10, sticky="ew")
        ctk.CTkButton(api_frame, text="保存", width=80,
                      command=self._on_save_api_key).grid(row=0, column=2, padx=(6, 12), pady=10)

        # 系统工具
        tool_frame = ctk.CTkFrame(tab)
        tool_frame.grid(row=3, column=0, sticky="ew", padx=12, pady=6)
        ctk.CTkLabel(tool_frame, text="系统工具",
                     font=ctk.CTkFont(weight="bold")).grid(row=0, column=0, padx=12, pady=(12, 6), sticky="w")
        btns = ctk.CTkFrame(tool_frame, fg_color="transparent")
        btns.grid(row=1, column=0, sticky="w", padx=12, pady=(0, 12))
        ctk.CTkButton(btns, text="检测 Java", command=lambda: self._run_bg(self.core.list_all_java)).grid(
            row=0, column=0, padx=(0, 6), pady=6)
        ctk.CTkButton(btns, text="检查更新", command=lambda: self._run_bg(self.core.check_for_updates, False)).grid(
            row=0, column=1, padx=6, pady=6)
        ctk.CTkButton(btns, text="打开游戏目录", command=lambda: self.core._open_folder(MC_DIR)).grid(
            row=0, column=2, padx=6, pady=6)
        ctk.CTkButton(btns, text="打开服务器目录", command=lambda: self.core._open_folder(SERVER_DIR)).grid(
            row=0, column=3, padx=6, pady=6)
        ctk.CTkButton(btns, text="frp 配置", command=self.core.frp_config).grid(
            row=1, column=0, padx=(0, 6), pady=6)
        ctk.CTkButton(btns, text="frp 启动", command=lambda: self._run_bg(self.core.frp_start)).grid(
            row=1, column=1, padx=6, pady=6)
        ctk.CTkButton(btns, text="frp 停止", command=lambda: self._run_bg(self.core.frp_stop)).grid(
            row=1, column=2, padx=6, pady=6)

    def _on_save_player_name(self) -> None:
        self.core.set_player_name(self.player_name_entry.get())
        self._update_player_display()

    def _on_save_memory(self) -> None:
        mx = self.max_mem_entry.get().strip() or "2G"
        ms = self.min_mem_entry.get().strip() or "1G"
        self.core.config["max_memory"] = mx
        self.core.config["min_memory"] = ms
        self.core.save_config()
        self._append_log(f"内存设置已保存: Xmx{mx} Xms{ms}")

    def _on_save_api_key(self) -> None:
        self.core.set_api_key(self.api_key_entry.get())

    # ---------------- 日志页 ----------------
    def _build_log_tab(self) -> None:
        tab = self.tab_log
        tab.grid_columnconfigure(0, weight=1)
        tab.grid_rowconfigure(0, weight=1)

        self.log_textbox = ctk.CTkTextbox(tab, wrap="word")
        self.log_textbox.grid(row=0, column=0, sticky="nsew", padx=12, pady=(12, 6))
        self.log_textbox.configure(state="disabled")

        btn_frame = ctk.CTkFrame(tab, fg_color="transparent")
        btn_frame.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 12))
        ctk.CTkButton(btn_frame, text="清空日志", width=100,
                      command=self._clear_log).grid(row=0, column=0, padx=(0, 6), pady=6, sticky="w")
        ctk.CTkButton(btn_frame, text="打开日志文件", width=120,
                      command=lambda: self._open_log_file()).grid(row=0, column=1, padx=6, pady=6, sticky="w")

    def _clear_log(self) -> None:
        self.log_textbox.configure(state="normal")
        self.log_textbox.delete("1.0", "end")
        self.log_textbox.configure(state="disabled")

    def _open_log_file(self) -> None:
        if os.path.isfile(LOG_FILE):
            self.core._open_with_editor(LOG_FILE)
        else:
            self._append_log("日志文件尚不存在")

    # ------------------------------------------------------------------
    # 标签切换
    # ------------------------------------------------------------------
    def _on_tab_changed(self) -> None:
        current = self.tabview.get()
        if current == "模组":
            self._refresh_mods()
        elif current == "服务器":
            self._refresh_servers()
        elif current == "版本":
            self._refresh_versions()

    # ------------------------------------------------------------------
    # 关闭
    # ------------------------------------------------------------------
    def _on_close(self) -> None:
        try:
            self.core.close()
        except Exception:  # noqa: BLE001
            pass
        self.destroy()
