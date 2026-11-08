# gui.py
import io
import os
import threading
import time
import urllib.request
import webbrowser
from tkinter import filedialog


import customtkinter as ctk
from PIL import Image, ImageDraw

try:
    from tkinterdnd2 import DND_FILES, TkinterDnD
    _DND_AVAILABLE = True
except Exception:  # noqa: BLE001
    DND_FILES = None
    TkinterDnD = None
    _DND_AVAILABLE = False

from core import LauncherCore, LOADER_NAMES
from utils import (
    VERSION_OF_LAUNCHER, MC_DIR, SERVER_DIR, JAVA_DIR, LOG_FILE, get_logger,
    resource_path,
)

log = get_logger("launcher.gui")

ctk.set_appearance_mode("light")
ctk.set_default_color_theme("blue")

APP_TITLE = "极简 MC 启动器"

COLOR_PRIMARY = "#3572b0"
COLOR_PRIMARY_LIGHT = "#4a90d9"
COLOR_BG = "#eaf2f9"
COLOR_WHITE = "#ffffff"
COLOR_TEXT = "#333333"
COLOR_MUTED = "#888888"

NAV_ITEMS = ("启动", "下载", "服务器", "设置")
DOWNLOAD_CATEGORIES = ("游戏", "模组", "整合包", "数据包", "资源包", "光影包", "世界", "收藏夹")

MODRINTH_CATEGORIES = {
    "模组": ("mod", "🧩"),
    "整合包": ("modpack", "📦"),
    "数据包": ("datapack", "🗂"),
    "资源包": ("resourcepack", "🎨"),
    "光影包": ("shader", "✨"),
    "世界": ("world", "🌍"),
}
MODRINTH_WORLD_QUERY = "world"

# 全局图标缓存，防止图片被 Python 垃圾回收导致变白
_ICON_CACHE = {}
_CTK_IMAGES = {}

def get_grass_icon():
    if "grass_local" in _ICON_CACHE:
        return _ICON_CACHE["grass_local"]
    img = Image.new("RGBA", (64, 64), (139, 195, 74, 255))
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 24, 64, 64], fill=(121, 85, 72, 255))
    for i in range(0, 64, 8):
        draw.rectangle([i, 16, i+7, 23], fill=(76, 175, 80, 255))
        for j in range(24, 64, 8):
            if (i + j) % 16 == 0:
                draw.rectangle([i, j, i+7, j+7], fill=(93, 64, 55, 255))
    _ICON_CACHE["grass_local"] = img
    return img


class SimpleMCLauncherGUI(ctk.CTk, TkinterDnD.DnDWrapper if _DND_AVAILABLE else object):

    def __init__(self):
        super().__init__()
        if _DND_AVAILABLE:
            try:
                self.TkdndVersion = TkinterDnD._require(self)
            except Exception:
                pass
        self.title(APP_TITLE)
        self.geometry("1080x760")
        self.minsize(960, 680)
        # 关键：先隐藏主窗口，避免与闪屏同时出现
        try:
            self.withdraw()
        except Exception:  # noqa: BLE001
            pass
        self._set_window_icon()
        # 去掉系统标题栏，改用顶部栏自绘（含图标、标题、导航、窗口按钮）
        try:
            self.overrideredirect(True)
        except Exception:  # noqa: BLE001
            pass

        self.grid_rowconfigure(0, weight=0) 
        self.grid_rowconfigure(1, weight=1) 
        self.grid_columnconfigure(0, weight=1)

        self._init_state()

        # ---- 先弹出启动闪屏，主界面在后台构建完成后再显示 ----
        self._splash = self._create_splash()
        self.update_idletasks()

        self.core = LauncherCore(
            log_callback=self._thread_safe_log,
            status_callback=self._thread_safe_status,
            progress_callback=self._thread_safe_progress,
            server_log_callback=self._thread_safe_server_log,
        )

        self.after(80, self._flush_log_queue)

        # 分步构建主界面，让闪屏有机会刷新
        self.after(60, self._build_ui_deferred)

    # ---------------- 启动闪屏 ----------------
    SPLASH_W = 380
    SPLASH_H = 260

    def _create_splash(self):
        """创建居中显示的无边框启动画面。"""
        sp = ctk.CTkToplevel(self)
        sp.overrideredirect(True)          # 无标题栏
        sp.attributes("-topmost", True)
        sp.configure(fg_color=COLOR_WHITE)

        w, h = self.SPLASH_W, self.SPLASH_H
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        x = (sw - w) // 2
        y = (sh - h) // 2
        sp.geometry(f"{w}x{h}+{x}+{y}")

        # 闪屏也要设置图标，否则任务栏会显示默认图标
        try:
            if os.path.isfile(self.ICON_FILE):
                sp.iconbitmap(self.ICON_FILE)
        except Exception:  # noqa: BLE001
            pass
        try:
            from tkinter import Toplevel as _TL  # noqa: F401
            if getattr(self, "_icon_tk_ref", None) is not None:
                sp.iconphoto(True, self._icon_tk_ref)
        except Exception:  # noqa: BLE001
            pass

        # 图标
        img_label = ctk.CTkLabel(sp, text="")
        img_label.pack(pady=(28, 10))
        try:
            img = self._load_icon_image(96)
            sp_img = ctk.CTkImage(light_image=img, size=(96, 96))
            img_label.configure(image=sp_img)
            img_label._image_ref = sp_img
        except Exception:  # noqa: BLE001
            img_label.configure(text="📦", font=ctk.CTkFont(size=56))

        ctk.CTkLabel(
            sp, text=APP_TITLE,
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color="#0057a5").pack()

        sp_status = ctk.CTkLabel(
            sp, text="正在启动...", font=ctk.CTkFont(size=12),
            text_color=COLOR_MUTED)
        sp_status.pack(pady=(6, 10))

        sp_bar = ctk.CTkProgressBar(sp, width=240, height=8, mode="indeterminate")
        sp_bar.pack()
        sp_bar.start()

        version = ctk.CTkLabel(
            sp, text=VERSION_OF_LAUNCHER, font=ctk.CTkFont(size=10),
            text_color="#b0b6bd")
        version.pack(side="bottom", pady=10)

        sp._sp_bar = sp_bar
        sp._sp_status = sp_status
        return sp

    def _splash_status(self, text: str) -> None:
        try:
            if getattr(self, "_splash", None) and self._splash.winfo_exists():
                self._splash._sp_status.configure(text=text)
                self.update_idletasks()
        except Exception:  # noqa: BLE001
            pass

    def _close_splash(self) -> None:
        """关闭闪屏并显示主窗口。"""
        try:
            if getattr(self, "_splash", None) and self._splash.winfo_exists():
                self._splash._sp_bar.stop()
                self._splash.destroy()
        except Exception:  # noqa: BLE001
            pass
        self._splash = None
        try:
            self.deiconify()               # 显示主窗口
            self.lift()
            self.focus_force()
        except Exception:  # noqa: BLE001
            pass
        # 主窗口真正显示后再恢复任务栏（此时 ShowWindow 不会提前暴露主窗口）
        self.after(50, self._restore_taskbar_presence)

    def _build_ui_deferred(self) -> None:
        """分步构建主界面，每步之间让出时间片刷新闪屏。"""
        steps = [
            ("加载界面...", self._build_ui),
            ("读取版本列表...", self._refresh_versions),
            ("初始化服务器...", self._refresh_servers),
            ("加载玩家信息...", self._update_player_display),
            ("准备就绪", self._switch_tab0),
        ]

        def run_step(i: int = 0) -> None:
            if i >= len(steps):
                self._close_splash()
                # 启动后延迟自动检查更新
                if self.core.config.get("auto_check_update", True):
                    self.after(2500, lambda: self._run_bg(self._auto_update_worker))
                self.protocol("WM_DELETE_WINDOW", self._on_close)
                return
            text, fn = steps[i]
            self._splash_status(text)
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                log.warning("启动步骤失败 %s: %s", text, e)
            self.after(30, lambda: run_step(i + 1))

        run_step()

    def _switch_tab0(self) -> None:
        self._switch_tab(0)

    # ---------------- 窗口图标 ----------------
    # 打包后图标在 _MEIPASS 里，必须用 resource_path 定位
    ICON_FILE = resource_path("icon.ico")

    # 16x16 像素设计网格：B=亮蓝 D=暗蓝 .=透明
    _ICON_GRID = (
        '................',
        '................',
        '......BBBBBBBB..',
        '.....BBBBBBBBBD.',
        '....BBBBBBBBBDDD',
        '....BBBBBBBBBDDD',
        '....BBBB...BDDDD',
        '....BBB.....DDDD',
        '....BBB.....DDDD',
        '....BBB.....DDDD',
        '....BBB.....DDDD',
        '....BBB.....DDDD',
        '....BBBB...BDDDD',
        '....BBBBBBBBBDDD',
        '.....BBBBBBBBBD.',
        '......BBBBBBBB..',
    )

    def _set_window_icon(self) -> None:
        """设置窗口/任务栏图标，优先使用 icon.ico 本身。"""
        # 1. Windows 任务栏归组用的 AppUserModelID（必须在创建窗口后尽早设置）
        self._set_app_user_model_id()

        # 2. iconbitmap：设置标题栏 + 任务栏的 ico
        try:
            if os.path.isfile(self.ICON_FILE):
                self.iconbitmap(self.ICON_FILE)
        except Exception:  # noqa: BLE001
            pass

        # 3. iconphoto：从 icon.ico 读出多尺寸图，让任务栏/Alt-Tab 用同一图标
        try:
            from PIL import ImageTk
            img = self._load_icon_image()
            self._icon_tk_ref = ImageTk.PhotoImage(img)
            self.iconphoto(True, self._icon_tk_ref)
        except Exception:  # noqa: BLE001
            pass

    @classmethod
    def _load_icon_image(cls, size: int = 256):
        """从 icon.ico 读取指定尺寸的图像；失败则回退到内置网格绘制。"""
        try:
            if os.path.isfile(cls.ICON_FILE):
                ico = Image.open(cls.ICON_FILE)
                sizes = sorted(ico.info.get("sizes", []), reverse=True)
                # 选一个接近目标尺寸的档位，避免糊
                target = None
                for s in sizes:
                    if s[0] >= size:
                        target = s
                if target is None and sizes:
                    target = sizes[0]
                if target:
                    ico.size = target
                    ico.load()
                    img = ico.convert("RGBA")
                    if img.size[0] != size:
                        img = img.resize((size, size), Image.LANCZOS)
                    return img
        except Exception:  # noqa: BLE001
            pass
        return cls._render_icon(size)

    def _set_app_user_model_id(self) -> None:
        """Windows：设置 AppUserModelID，让任务栏使用本程序图标而非 python.exe。"""
        if os.name != "nt":
            return
        try:
            import ctypes

            app_id = "BoscoWu.SimpleMCLauncher"
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
                ctypes.c_wchar_p(app_id))
        except Exception:  # noqa: BLE001
            pass

    @classmethod
    def _render_icon(cls, size: int):
        """按网格渲染 3D 蓝色方框图标。"""
        bright = (2, 156, 255, 255)
        dark = (0, 98, 177, 255)
        im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        px = im.load()
        gw = len(cls._ICON_GRID[0])
        gh = len(cls._ICON_GRID)
        for y in range(size):
            gy = y * gh // size
            row = cls._ICON_GRID[gy]
            for x in range(size):
                gx = x * gw // size
                ch = row[gx]
                if ch == "B":
                    px[x, y] = bright
                elif ch == "D":
                    px[x, y] = dark
        return im

    def _init_state(self) -> None:
        self._progress_total = 0
        self._progress_current = 0
        self._last_progress_t = 0.0
        self._avatar_ref = None
        self._last_avatar_name = ""
        self._cached_versions = []
        self._selected_mc_version = ""
        self._loader_radios: dict[str, ctk.CTkRadioButton] = {}
        self.current_version = ""
        self._updating_player_display = False
        self._modrinth_cache: dict[str, list] = {}
        self._dl_initialized = False
        self._log_queue: list[str] = []

    def _thread_safe_log(self, message: str) -> None:
        self._log_queue.append(str(message))

    def _flush_log_queue(self) -> None:
        if not self._log_queue:
            self.after(80, self._flush_log_queue)
            return
        box = getattr(self, "download_log", None)
        if box is not None:
            batch = "\n".join(self._log_queue) + "\n"
            self._log_queue.clear()
            try:
                box.configure(state="normal")
                box.insert("end", batch)
                box.see("end")
                box.configure(state="disabled")
            except Exception:
                pass
        self.after(80, self._flush_log_queue)

    def _thread_safe_status(self, message: str) -> None:
        self.after(0, lambda: self._set_status(message))

    def _thread_safe_progress(self, current: int, total: int) -> None:
        self.after(0, self._set_progress, current, total)

    def _thread_safe_server_log(self, message: str) -> None:
        self.after(0, self._append_server_log, message)

    def _append_server_log(self, message: str) -> None:
        try:
            self.server_log_box.configure(state="normal")
            self.server_log_box.insert("end", str(message) + "\n")
            self.server_log_box.see("end")
            self.server_log_box.configure(state="disabled")
        except Exception:
            pass

    def _set_status(self, message: str) -> None:
        try:
            self.status_label.configure(text=str(message))
            if hasattr(self, "download_status"):
                self.download_status.configure(text=str(message))
        except Exception:
            pass

    def _set_progress(self, current: int, total: int) -> None:
        if total and total > 0:
            self._progress_total = total
            self._progress_current = current
        else:
            self._progress_current = current

        now = time.monotonic()
        if not hasattr(self, "_speed_samples"):
            self._speed_samples = []
        
        self._speed_samples.append((now, self._progress_current))
        while len(self._speed_samples) > 2 and now - self._speed_samples[0][0] > 2.0:
            self._speed_samples.pop(0)

        if now - self._last_progress_t < 0.1 and current < total:
            return
        self._last_progress_t = now

        try:
            if self._progress_total > 0:
                frac = min(1.0, max(0.0, self._progress_current / self._progress_total))
            else:
                frac = 0.0
            self.download_progress.set(frac)

            speed_text = ""
            if len(self._speed_samples) >= 2:
                t0, b0 = self._speed_samples[0]
                t1, b1 = self._speed_samples[-1]
                dt = t1 - t0
                if dt > 0.5:
                    speed = (b1 - b0) / dt
                    if speed > 0:
                        speed_text = f"  {self._fmt_size(speed)}/s"
                        remaining = self._progress_total - self._progress_current
                        if remaining > 0 and self._progress_total > 0:
                            eta = remaining / speed
                            speed_text += f"  剩余 {self._fmt_eta(eta)}"

            self.download_percent.configure(
                text=f"{int(frac * 100)}%{speed_text}")
        except Exception:
            pass

    @staticmethod
    def _fmt_size(n: float) -> str:
        if n >= 1024 * 1024:
            return f"{n / 1024 / 1024:.1f} MB"
        if n >= 1024:
            return f"{n / 1024:.0f} KB"
        return f"{n:.0f} B"

    @staticmethod
    def _fmt_eta(seconds: float) -> str:
        seconds = int(max(0, seconds))
        if seconds < 60:
            return f"{seconds}秒"
        if seconds < 3600:
            return f"{seconds // 60}分{seconds % 60}秒"
        return f"{seconds // 3600}时{(seconds % 3600) // 60}分"

    def _run_bg(self, fn, *args) -> None:
        threading.Thread(target=fn, args=args, daemon=True).start()

    def _restore_taskbar_presence(self) -> None:
        """去掉 WS_EX_TOOLWINDOW，加回 WS_EX_APPWINDOW，让窗口重新出现在任务栏。

        Tk 在 overrideredirect(True) 下会给顶层窗口加上 WS_EX_TOOLWINDOW，
        该样式会让窗口从任务栏和 Alt-Tab 里消失。
        """
        if os.name != "nt":
            return
        try:
            import ctypes

            user32 = ctypes.windll.user32
            GWL_EXSTYLE = -20
            WS_EX_TOOLWINDOW = 0x00000080
            WS_EX_APPWINDOW = 0x00040000
            WS_EX_TOPMOST = 0x00000008
            SWP_NOMOVE = 0x0002
            SWP_NOSIZE = 0x0001
            SWP_NOZORDER = 0x0004
            SWP_FRAMECHANGED = 0x0020
            SW_HIDE = 0
            SW_SHOW = 5

            hwnd = self.winfo_id()
            root = user32.GetAncestor(hwnd, 2)  # GA_ROOT：真正的顶层窗口
            if not root:
                root = hwnd

            ex = user32.GetWindowLongW(root, GWL_EXSTYLE)
            new_ex = (ex & ~WS_EX_TOOLWINDOW) | WS_EX_APPWINDOW
            user32.SetWindowLongW(root, GWL_EXSTYLE, new_ex)

            # 必须隐藏再显示，任务栏才会重新收录这个窗口
            user32.ShowWindow(root, SW_HIDE)
            user32.ShowWindow(root, SW_SHOW)
            user32.SetWindowPos(
                root, 0, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED)

            # 重新应用一次图标，确保任务栏按钮显示 icon.ico
            self._apply_taskbar_icon(root)

            log.debug("已恢复任务栏显示: exstyle 0x%08X -> 0x%08X",
                      ex & 0xFFFFFFFF, new_ex & 0xFFFFFFFF)
        except Exception as e:  # noqa: BLE001
            log.warning("恢复任务栏显示失败: %s", e)

    def _apply_taskbar_icon(self, hwnd: int) -> None:
        """用 WM_SETICON 直接把 icon.ico 的两个尺寸设到窗口上。"""
        try:
            import ctypes

            if not os.path.isfile(self.ICON_FILE):
                return
            user32 = ctypes.windll.user32
            WM_SETICON = 0x0080
            ICON_SMALL = 0
            ICON_BIG = 1
            IMAGE_ICON = 1
            LR_LOADFROMFILE = 0x00000010
            LR_DEFAULTSIZE = 0x00000040

            for size, which in ((32, ICON_BIG), (16, ICON_SMALL)):
                hicon = user32.LoadImageW(
                    None, ctypes.c_wchar_p(self.ICON_FILE), IMAGE_ICON,
                    size, size, LR_LOADFROMFILE)
                if hicon:
                    user32.SendMessageW(
                        hwnd, WM_SETICON, which, ctypes.c_void_p(hicon))
        except Exception as e:  # noqa: BLE001
            log.debug("设置任务栏图标失败: %s", e)

    # ---------------- 自定义标题栏 ----------------
    def _build_window_controls(self) -> None:
        """右上角窗口按钮：— □ ×"""
        ctrl = ctk.CTkFrame(self.topbar, fg_color="transparent")
        ctrl.pack(side="right", padx=(0, 6))

        btn_style = {
            "width": 42, "height": 30,
            "fg_color": "transparent",
            "hover_color": COLOR_PRIMARY_LIGHT,
            "text_color": "white",
            "font": ctk.CTkFont(size=15),
            "corner_radius": 4,
        }

        self.btn_min = ctk.CTkButton(ctrl, text="—", command=self._on_minimize, **btn_style)
        self.btn_min.pack(side="left", padx=1)

        self.btn_max = ctk.CTkButton(ctrl, text="□", command=self._on_toggle_max, **btn_style)
        self.btn_max.pack(side="left", padx=1)

        self.btn_close = ctk.CTkButton(
            ctrl, text="×", width=42, height=30,
            fg_color="transparent", hover_color="#e81123",
            text_color="white", font=ctk.CTkFont(size=17),
            corner_radius=4, command=self._on_close)
        self.btn_close.pack(side="left", padx=1)

        self._is_maximized = False

    def _on_minimize(self) -> None:
        try:
            self.iconify()
        except Exception:  # noqa: BLE001
            pass

    def _on_toggle_max(self) -> None:
        try:
            if self._is_maximized:
                self.state("normal")
                self._is_maximized = False
            else:
                self.state("zoomed")
                self._is_maximized = True
        except Exception:  # noqa: BLE001
            # 部分平台不支持 zoomed，用几何尺寸模拟
            try:
                if self._is_maximized:
                    self.geometry(f"{self._normal_size}+{self._normal_pos}")
                else:
                    self._normal_size = f"{self.winfo_width()}x{self.winfo_height()}"
                    self._normal_pos = f"+{self.winfo_x()}+{self.winfo_y()}"
                    sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
                    self.geometry(f"{sw}x{sh}+0+0")
                self._is_maximized = not self._is_maximized
            except Exception:  # noqa: BLE001
                pass

    # ---------------- 窗口拖动 ----------------
    def _enable_window_drag(self) -> None:
        """让顶部栏可拖动窗口（无原生标题栏时需要）。"""
        drag = {"x": 0, "y": 0}

        def start(event):
            if self._is_maximized:
                return
            drag["x"] = event.x_root - self.winfo_x()
            drag["y"] = event.y_root - self.winfo_y()

        def move(event):
            if self._is_maximized:
                return
            x = event.x_root - drag["x"]
            y = event.y_root - drag["y"]
            self.geometry(f"+{x}+{y}")

        # CTkFrame 的实际绘制由内部 canvas 完成，必须绑到底层 canvas 才生效
        targets = []
        for widget in (self.topbar, self.topbar_logo):
            targets.append(widget)
            inner = getattr(widget, "_canvas", None)
            if inner is not None:
                targets.append(inner)

        for w in targets:
            try:
                w.bind("<Button-1>", start, add="+")
                w.bind("<B1-Motion>", move, add="+")
                # 双击顶栏切换最大化
                w.bind("<Double-Button-1>", lambda e: self._on_toggle_max(), add="+")
            except Exception:  # noqa: BLE001
                pass

    def _build_ui(self) -> None:
        self.configure(fg_color=COLOR_BG)

        self.topbar = ctk.CTkFrame(self, fg_color=COLOR_PRIMARY, corner_radius=0, height=56)
        self.topbar.grid(row=0, column=0, sticky="ew")
        self.topbar.grid_propagate(False)

        # 图标 + 标题（放在 "Simple MC" 前面）
        self.topbar_logo = ctk.CTkLabel(self.topbar, text="", width=30, height=30)
        self.topbar_logo.pack(side="left", padx=(22, 8))
        try:
            icon_img = self._load_icon_image(32)
            self._topbar_logo_ref = ctk.CTkImage(
                light_image=icon_img, dark_image=icon_img, size=(26, 26))
            self.topbar_logo.configure(image=self._topbar_logo_ref)
        except Exception:  # noqa: BLE001
            self.topbar_logo.configure(text="⚡", font=ctk.CTkFont(size=16),
                                       text_color="white")

        logo = ctk.CTkLabel(
            self.topbar, text="Simple MC",
            font=ctk.CTkFont(size=20, weight="bold"), text_color="white")
        logo.pack(side="left", padx=(0, 10))

        self.nav_frame = ctk.CTkFrame(self.topbar, fg_color="transparent")
        self.nav_frame.pack(side="left", padx=10)
        self.nav_buttons = []
        for i, name in enumerate(NAV_ITEMS):
            btn = ctk.CTkButton(
                self.nav_frame, text=name, width=70, height=32,
                fg_color="transparent", hover_color=COLOR_PRIMARY_LIGHT,
                text_color="#dce8f5", font=ctk.CTkFont(size=15),
                command=lambda idx=i: self._switch_tab(idx))
            btn.pack(side="left", padx=4)
            self.nav_buttons.append(btn)

        # 窗口控制按钮（替代原生标题栏）
        self._build_window_controls()

        # 状态标签保留（隐藏），供内部 _set_status 调用，不再显示
        self.status_label = ctk.CTkLabel(self.topbar, text="")

        self.main_container = ctk.CTkFrame(self, fg_color=COLOR_BG, corner_radius=0)
        self.main_container.grid(row=1, column=0, sticky="nsew")
        self.main_container.grid_rowconfigure(0, weight=1)
        self.main_container.grid_columnconfigure(1, weight=1) 

        self.sidebar = ctk.CTkFrame(self.main_container, fg_color=COLOR_WHITE, width=260, corner_radius=0)
        self.sidebar.grid(row=0, column=0, sticky="ns")
        self.sidebar.grid_propagate(False)
        self._build_sidebar()

        self.content = ctk.CTkFrame(self.main_container, fg_color=COLOR_BG, corner_radius=0)
        self.content.grid(row=0, column=1, sticky="nsew")

        self.pages = {
            "启动": self._build_home_page(),
            "下载": self._build_download_page(),
            "服务器": self._build_servers_page(),
            "设置": self._build_settings_page(),
        }
        for page in self.pages.values():
            page.place(relx=0, rely=0, relwidth=1, relheight=1)

        # 无原生标题栏，启用顶栏拖动
        self._enable_window_drag()

        self._build_drop_overlay()
        self._setup_drag_drop()

    SUPPORTED_DROP_EXTS = (".zip", ".mrpack", ".mcpack", ".mcaddon")

    def _setup_drag_drop(self) -> None:
        if not _DND_AVAILABLE:
            return
        try:
            self.drop_target_register(DND_FILES)
            self.dnd_bind("<<Drop>>", self._on_drop)
            self.dnd_bind("<<DropEnter>>", self._on_drop_enter)
            self.dnd_bind("<<DragEnter>>", self._on_drop_enter)
            self.dnd_bind("<<DropLeave>>", self._on_drop_leave)
            self.dnd_bind("<<DragLeave>>", self._on_drop_leave)
        except Exception:
            pass

    def _build_drop_overlay(self) -> None:
        self.drop_overlay = ctk.CTkFrame(self, fg_color="#1a6db5", corner_radius=0)
        inner = ctk.CTkFrame(self.drop_overlay, fg_color="transparent")
        inner.place(relx=0.5, rely=0.5, anchor="center")

        ctk.CTkLabel(inner, text="📦", font=ctk.CTkFont(size=64)).pack(pady=(0, 12))
        ctk.CTkLabel(
            inner, text="松开即可导入整合包",
            font=ctk.CTkFont(size=26, weight="bold"), text_color="white").pack()
        ctk.CTkLabel(
            inner, text="支持 .zip / .mrpack / .mcpack / .mcaddon",
            font=ctk.CTkFont(size=14), text_color="#dce8f5").pack(pady=(8, 0))
        self._drop_overlay_visible = False

    def _on_drop_enter(self, _event=None):
        if not self._drop_overlay_visible:
            self.drop_overlay.place(relx=0, rely=0, relwidth=1, relheight=1)
            self.drop_overlay.lift()
            self._drop_overlay_visible = True
        return "copy"

    def _on_drop_leave(self, _event=None):
        if self._drop_overlay_visible:
            self.drop_overlay.place_forget()
            self._drop_overlay_visible = False

    def _on_drop(self, event):
        self._on_drop_leave()
        try:
            paths = self.tk.splitlist(event.data) if event.data else ()
        except Exception:
            paths = [event.data] if event.data else []

        files = []
        for p in paths:
            p = str(p).strip().strip("{}")
            if os.path.isfile(p) and p.lower().endswith(self.SUPPORTED_DROP_EXTS):
                files.append(p)

        if not files:
            self._thread_safe_log(
                "拖入的文件不受支持。请拖入 .zip / .mrpack / .mcpack / .mcaddon 整合包")
            return "copy"

        for f in files:
            self._thread_safe_log(f"检测到整合包: {os.path.basename(f)}")
            self._run_bg(self.core.import_modpack, f)
        try:
            self._switch_tab(NAV_ITEMS.index("下载"))
            self._show_download_category("整合包")
        except Exception:
            pass
        return "copy"

    def _build_sidebar(self) -> None:
        login_box = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        login_box.pack(pady=(20, 8))
        self.offline_btn = ctk.CTkButton(
            login_box, text="离线登录", width=100, height=26,
            font=ctk.CTkFont(size=11), command=self._on_offline_login)
        self.offline_btn.pack(side="left", padx=4)
        self.online_btn = ctk.CTkButton(
            login_box, text="正版登录", width=100, height=26,
            font=ctk.CTkFont(size=11), command=self._show_login_dialog)
        self.online_btn.pack(side="left", padx=4)

        self.avatar_label = ctk.CTkLabel(self.sidebar, text="", width=80, height=80)
        self.avatar_label.pack(pady=(6, 4))
        try:
            _img = self._make_default_avatar()
            self._avatar_ref = ctk.CTkImage(light_image=_img, size=(80, 80))
            self.avatar_label.configure(image=self._avatar_ref)
        except Exception:
            self.avatar_label.configure(text="👤")

        self.player_name_label = ctk.CTkLabel(
            self.sidebar, text="Player",
            font=ctk.CTkFont(size=18, weight="bold"), text_color=COLOR_TEXT)
        self.player_name_label.pack(pady=(2, 0))

        self.offline_name_entry = ctk.CTkEntry(
            self.sidebar, width=160, height=30, justify="center")
        self.offline_name_entry.bind("<Return>", lambda e: self._save_offline_name())
        self.offline_name_entry.bind("<FocusOut>", lambda e: self._save_offline_name())

        self.login_status_label = ctk.CTkLabel(
            self.sidebar, text="离线验证",
            font=ctk.CTkFont(size=13), text_color=COLOR_MUTED)
        self.login_status_label.pack(pady=(2, 0))

        self.launch_btn = ctk.CTkButton(
            self.sidebar, text="启动游戏", height=60,
            font=ctk.CTkFont(size=16, weight="bold"),
            fg_color="#ebf4ff", text_color="#0057a5",
            border_width=1, border_color="#0057a5",
            hover_color="#dceafc",
            command=self._on_launch)
        self.launch_btn.pack(side="bottom", fill="x", padx=20, pady=(0, 8))

        self.current_version_label = ctk.CTkLabel(
            self.sidebar, text="未选择版本",
            font=ctk.CTkFont(size=12), text_color=COLOR_MUTED)
        self.current_version_label.pack(side="bottom")

        inst_box = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        inst_box.pack(side="bottom", fill="x", padx=20, pady=(8, 16))
        ctk.CTkButton(inst_box, text="实例选择", width=100, height=32,
                      command=self._show_instance_select_dialog).pack(side="left", padx=(0, 6))
        ctk.CTkButton(inst_box, text="实例设置", width=100, height=32,
                      command=self._show_instance_settings_dialog).pack(side="left")

    def _build_home_page(self) -> ctk.CTkFrame:
        page = ctk.CTkFrame(self.content, fg_color=COLOR_BG, corner_radius=0)
        title = ctk.CTkLabel(
            page, text="欢迎使用极简 MC 启动器",
            font=ctk.CTkFont(size=24, weight="bold"), text_color="#0057a5")
        title.pack(pady=(60, 10))
        info = ctk.CTkLabel(
            page,
            text=(f"版本: {VERSION_OF_LAUNCHER}\n游戏目录: {MC_DIR}"),
            font=ctk.CTkFont(size=13), text_color=COLOR_MUTED, justify="center")
        info.pack(pady=10)
        self.home_status = ctk.CTkLabel(page, text="", font=ctk.CTkFont(size=13))
        self.home_status.pack(pady=6)
        return page

    def _build_settings_page(self) -> ctk.CTkFrame:
        page = ctk.CTkFrame(self.content, fg_color=COLOR_BG, corner_radius=0)

        scroll = ctk.CTkScrollableFrame(page, fg_color=COLOR_BG)
        scroll.pack(fill="both", expand=True, padx=16, pady=12)

        cfg = self.core.config

        box1 = self._settings_card(scroll, "启动选项")

        self.set_isolation = self._settings_row(
            box1, "默认实例隔离",
            combo=["隔离所有实例", "共享游戏目录", "使用 .minecraft 目录"],
            default=cfg.get("isolation_mode", "隔离所有实例"))

        self.set_window_title = self._settings_row(
            box1, "游戏窗口标题", entry=True,
            value=cfg.get("window_title", "Minecraft"))

        self.set_custom_info = self._settings_row(
            box1, "自定义信息", entry=True,
            value=cfg.get("custom_info", "SimpleMCLauncher"))

        self.set_visible_after = self._settings_row(
            box1, "启动器可见性",
            combo=["游戏启动后仍保持不变", "游戏启动后最小化", "游戏启动后隐藏"],
            default=cfg.get("visible_after", "游戏启动后仍保持不变"))

        self.set_priority = self._settings_row(
            box1, "进程优先级",
            combo=["普通", "高（优先保证游戏运行）", "实时（不建议）"],
            default=cfg.get("process_priority", "普通"))

        self.set_window_size = self._settings_row(
            box1, "窗口大小",
            combo=["默认", "1920x1080", "1280x720", "1024x768", "854x480"],
            default=cfg.get("game_window_size", "默认"))

        self.set_auth_mode = self._settings_row(
            box1, "正版验证方式",
            combo=["设备代码流", "浏览器回调"],
            default=cfg.get("auth_mode", "设备代码流"))

        self.set_proxy = self._settings_row(
            box1, "代理设置",
            combo=["Java 默认", "不使用代理", "使用系统代理"],
            default=cfg.get("proxy_mode", "Java 默认"))

        box2 = self._settings_card(scroll, "游戏内存")

        self.mem_mode_var = ctk.StringVar(value=cfg.get("memory_mode", "自定义"))
        rb_box = ctk.CTkFrame(box2, fg_color="transparent")
        rb_box.pack(fill="x", padx=16, pady=(2, 6))
        ctk.CTkRadioButton(rb_box, text="自动配置", variable=self.mem_mode_var,
                           value="自动配置", command=self._on_mem_mode_change).pack(side="left", padx=(0, 20))
        ctk.CTkRadioButton(rb_box, text="自定义", variable=self.mem_mode_var,
                           value="自定义", command=self._on_mem_mode_change).pack(side="left")

        try:
            cur_max = int(str(cfg.get("max_memory", "2G")).rstrip("Gg"))
        except Exception:
            cur_max = 2
        try:
            cur_min = int(str(cfg.get("min_memory", "1G")).rstrip("Gg"))
        except Exception:
            cur_min = 1

        self.max_mem_label = ctk.CTkLabel(box2, text=f"最大内存（-Xmx）：{cur_max}G",
                                          text_color=COLOR_TEXT, anchor="w")
        self.max_mem_label.pack(fill="x", padx=16)
        self.max_mem_slider = ctk.CTkSlider(box2, from_=1, to=32, number_of_steps=31,
                                            command=self._on_max_mem_change)
        self.max_mem_slider.set(cur_max)
        self.max_mem_slider.pack(fill="x", padx=16, pady=(2, 10))

        self.min_mem_label = ctk.CTkLabel(box2, text=f"初始内存（-Xms）：{cur_min}G",
                                          text_color=COLOR_TEXT, anchor="w")
        self.min_mem_label.pack(fill="x", padx=16)
        self.min_mem_slider = ctk.CTkSlider(box2, from_=1, to=32, number_of_steps=31,
                                            command=self._on_min_mem_change)
        self.min_mem_slider.set(cur_min)
        self.min_mem_slider.pack(fill="x", padx=16, pady=(2, 12))

        self._on_mem_mode_change()

        box3 = self._settings_card(scroll, "高级启动选项")
        self.set_jvm_args = self._settings_row(
            box3, "JVM 参数", entry=True,
            value=cfg.get("jvm_args", ""), placeholder="-XX:+UseG1GC ...")
        self.set_game_args = self._settings_row(
            box3, "游戏参数", entry=True,
            value=cfg.get("game_args", ""), placeholder="额外传给 Minecraft 的参数")

        box4 = self._build_update_card(scroll)

        box5 = self._settings_card(scroll, "工具")
        tools = ctk.CTkFrame(box5, fg_color="transparent")
        tools.pack(fill="x", padx=16, pady=(2, 14))
        for text, cmd in (
            ("安装 Java 21", self._on_install_java),
            ("检测 Java", self._on_detect_java),
            ("打开游戏目录", lambda: self._open_path(str(MC_DIR))),
            ("打开日志", lambda: self._open_path(str(LOG_FILE))),
        ):
            ctk.CTkButton(tools, text=text, width=110, height=30,
                          command=cmd).pack(side="left", padx=(0, 8))

        save_bar = ctk.CTkFrame(page, fg_color="transparent")
        save_bar.pack(fill="x", padx=16, pady=(0, 12))
        ctk.CTkButton(save_bar, text="保存设置", width=140, height=38,
                      font=ctk.CTkFont(size=15, weight="bold"),
                      command=self._save_settings).pack(side="right")
        ctk.CTkButton(save_bar, text="恢复默认", width=110, height=38,
                      fg_color="#9aa0a6", hover_color="#8a9096",
                      command=self._reset_settings).pack(side="right", padx=(0, 10))

        return page

    # ---------------- 更新 ----------------
    def _build_update_card(self, parent) -> ctk.CTkFrame:
        """设置页的「启动器更新」卡片。"""
        box = self._settings_card(parent, "启动器更新")

        row = ctk.CTkFrame(box, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=(0, 6))

        self.update_version_label = ctk.CTkLabel(
            row, text=f"当前版本：{VERSION_OF_LAUNCHER}",
            text_color=COLOR_TEXT, anchor="w")
        self.update_version_label.pack(side="left")

        self.update_status_label = ctk.CTkLabel(
            row, text="", text_color=COLOR_MUTED, anchor="w")
        self.update_status_label.pack(side="left", padx=(16, 0))

        self.update_btn = ctk.CTkButton(
            row, text="检查更新", width=110, height=30,
            command=self._on_check_update)
        self.update_btn.pack(side="right")

        btn_row = ctk.CTkFrame(box, fg_color="transparent")
        btn_row.pack(fill="x", padx=16, pady=(0, 12))

        # 自动检查开关
        self.auto_update_var = ctk.StringVar(
            value="开启" if self.core.config.get("auto_check_update", True) else "关闭")

        def on_toggle(choice=None):
            self.core.config["auto_check_update"] = (self.auto_update_var.get() == "开启")
            self.core.save_config()

        ctk.CTkLabel(btn_row, text="启动时自动检查：", text_color=COLOR_TEXT).pack(side="left")
        ctk.CTkSegmentedButton(
            btn_row, values=["开启", "关闭"], variable=self.auto_update_var,
            command=on_toggle, width=140).pack(side="left", padx=(0, 16))

        # 下载并安装按钮（默认隐藏，发现更新后出现）
        self.install_update_btn = ctk.CTkButton(
            btn_row, text="下载并安装", width=120, height=30,
            fg_color="#2FA84F", hover_color="#268A40",
            command=self._on_install_update)
        self._pending_update_url = None

        return box

    def _auto_update_worker(self) -> None:
        """启动时的静默检查：有新版本就弹窗提示。"""
        try:
            has_new = self.core.check_for_updates(silent=True)
        except Exception:  # noqa: BLE001
            return
        if not has_new or not self.core._latest_release:
            return
        tag = self.core._latest_release.get("tag_name", "")
        self.core.log(f"自动检查：发现新版本 {tag}")
        self.after(0, lambda: self._prompt_update(tag))

    def _prompt_update(self, tag: str) -> None:
        import tkinter.messagebox as mb
        ans = mb.askyesno(
            "发现新版本",
            f"检测到新版本 {tag}\n当前版本 {VERSION_OF_LAUNCHER}\n\n是否立即下载并更新？")
        if not ans:
            self.core.log("已跳过本次更新，可在「设置 → 启动器更新」中手动检查")
            return
        self._switch_tab(NAV_ITEMS.index("设置"))
        self._on_install_update()

    # ---------------- Java 工具 ----------------
    def _on_install_java(self) -> None:
        self._thread_safe_status("正在安装 Java 21...")
        self._run_bg(self._install_java_worker)

    def _install_java_worker(self) -> None:
        ok = False
        try:
            ok = self.core.install_java("21")
        except Exception as e:  # noqa: BLE001
            self.core.java_message = f"安装 Java 失败：{e}"
        msg = getattr(self.core, "java_message", "") or (
            "Java 21 安装完成" if ok else "Java 21 安装失败")
        self.after(0, lambda: self._show_info_dialog(
            "安装 Java", msg if ok else f"{msg}\n\n请检查网络后重试"))

    def _on_detect_java(self) -> None:
        self._thread_safe_status("正在检测 Java...")
        self._run_bg(self._detect_java_worker)

    def _detect_java_worker(self) -> None:
        found: list[str] = []
        try:
            found = self.core.list_all_java() or []
        except Exception as e:  # noqa: BLE001
            self.core.java_message = f"检测 Java 失败：{e}"

        if found:
            lines = "\n".join(f"• {p}" for p in found[:12])
            msg = f"已检测到 {len(found)} 个 Java：\n\n{lines}"
        else:
            msg = ("未检测到任何 Java。\n\n"
                   "游戏启动需要 Java 17 或更高版本，\n"
                   "建议点击「安装 Java 21」自动下载。")
        self.after(0, lambda: self._show_info_dialog("检测 Java", msg))

    def _show_info_dialog(self, title: str, message: str,
                          ask_update: bool = False) -> None:
        """统一的提示弹窗（不写入下载日志）。"""
        import tkinter.messagebox as mb

        # 主窗口可能仍处于隐藏状态（闪屏阶段），先确保可见，
        # 否则模态弹窗会挂在隐藏窗口上而看不见。
        try:
            if not self.winfo_viewable():
                self.deiconify()
                self.update_idletasks()
            parent = self
        except Exception:  # noqa: BLE001
            parent = None

        if ask_update:
            if mb.askyesno(title, f"{message}\n\n是否立即下载并安装？", parent=parent):
                self._on_install_update()
            return
        mb.showinfo(title, message, parent=parent)

    def _on_check_update(self) -> None:
        self.update_btn.configure(state="disabled", text="检查中...")
        self.update_status_label.configure(text="正在检查...", text_color=COLOR_MUTED)
        self.install_update_btn.pack_forget()
        self._run_bg(self._check_update_worker)

    def _check_update_worker(self) -> None:
        try:
            has_new = self.core.check_for_updates(silent=True)
        except Exception:  # noqa: BLE001
            has_new = False
        self.after(0, lambda: self._on_check_update_done(has_new))

    def _on_check_update_done(self, has_new: bool) -> None:
        msg = getattr(self.core, "update_message", "") or ""
        has_release = bool(has_new and self.core._latest_release)

        # 更新设置页控件状态（控件可能尚未创建，容错处理）
        try:
            if hasattr(self, "update_btn"):
                self.update_btn.configure(state="normal", text="检查更新")
            if hasattr(self, "update_status_label"):
                if has_release:
                    tag = self.core._latest_release.get("tag_name", "")
                    self.update_status_label.configure(
                        text=f"发现新版本 {tag}", text_color="#2FA84F")
                else:
                    self.update_status_label.configure(
                        text="已是最新版本", text_color=COLOR_MUTED)
            if hasattr(self, "install_update_btn"):
                if has_release:
                    self.install_update_btn.pack(side="right", padx=(0, 10))
                else:
                    self.install_update_btn.pack_forget()
        except Exception:  # noqa: BLE001
            pass

        # 独立弹窗提示（不写入下载日志）
        if has_release:
            self._show_info_dialog("发现新版本", msg, ask_update=True)
        else:
            self._show_info_dialog("检查更新", msg or "当前已是最新版本")

    def _on_install_update(self) -> None:
        self.install_update_btn.configure(state="disabled", text="下载中...")
        self.update_status_label.configure(text="正在下载更新...", text_color=COLOR_MUTED)
        self._switch_tab(NAV_ITEMS.index("下载"))
        self._run_bg(self._download_update_worker)

    def _download_update_worker(self) -> None:
        path = None
        try:
            path = self.core.download_update()
        except Exception as e:  # noqa: BLE001
            self.core.log(f"下载更新异常: {e}")
        self.after(0, lambda: self._on_download_update_done(path))

    def _on_download_update_done(self, path) -> None:
        try:
            self.install_update_btn.configure(state="normal", text="下载并安装")
        except Exception:  # noqa: BLE001
            pass
        if not path:
            try:
                self.update_status_label.configure(text="下载失败", text_color="#c94f4f")
            except Exception:  # noqa: BLE001
                pass
            return

        # 直接重启更新，不再需要手动替换
        import tkinter.messagebox as mb
        if self.core.apply_update(path):
            self.core.log("更新已就绪，正在重启...")
            try:
                self.update_status_label.configure(text="正在重启...", text_color="#2FA84F")
            except Exception:  # noqa: BLE001
                pass
            # 先正常关闭 UI，再确保进程退出让批处理接管
            self.after(300, self._quit_for_update)
        else:
            mb.showinfo(
                "更新提示",
                f"新版本已保存到：\n{self.core.app_dir()}\n\n"
                "请重新打开程序以使用新版本。")

    def _quit_for_update(self) -> None:
        """退出进程，交给更新脚本替换并重启。"""
        try:
            self.destroy()
        except Exception:  # noqa: BLE001
            pass
        os._exit(0)

    def _settings_card(self, parent, title: str) -> ctk.CTkFrame:
        box = ctk.CTkFrame(parent, fg_color=COLOR_WHITE, corner_radius=8)
        box.pack(fill="x", pady=(0, 10))
        head = ctk.CTkLabel(box, text=title, anchor="w",
                            font=ctk.CTkFont(size=15, weight="bold"),
                            text_color="#0057a5")
        head.pack(fill="x", padx=16, pady=(12, 8))
        return box

    def _settings_row(self, parent, label: str, combo=None, entry=False,
                      default="", value="", placeholder=""):
        row = ctk.CTkFrame(parent, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=3)
        ctk.CTkLabel(row, text=label, width=110, anchor="w",
                     text_color=COLOR_TEXT).pack(side="left", padx=(0, 10))
        if combo is not None:
            widget = ctk.CTkComboBox(row, values=combo, width=420, state="readonly")
            widget.set(default if default in combo else combo[0])
            widget.pack(side="left", fill="x", expand=True)
            return widget
        widget = ctk.CTkEntry(row, width=420, placeholder_text=placeholder)
        if value:
            widget.insert(0, value)
        widget.pack(side="left", fill="x", expand=True)
        return widget

    def _on_mem_mode_change(self) -> None:
        custom = (self.mem_mode_var.get() == "自定义")
        state = "normal" if custom else "disabled"
        try:
            self.max_mem_slider.configure(state=state)
            self.min_mem_slider.configure(state=state)
            self.max_mem_label.configure(
                text_color=COLOR_TEXT if custom else COLOR_MUTED)
            self.min_mem_label.configure(
                text_color=COLOR_TEXT if custom else COLOR_MUTED)
        except Exception:
            pass

    def _on_max_mem_change(self, value) -> None:
        try:
            self.max_mem_label.configure(text=f"最大内存（-Xmx）：{int(round(value))}G")
        except Exception:
            pass

    def _on_min_mem_change(self, value) -> None:
        try:
            self.min_mem_label.configure(text=f"初始内存（-Xms）：{int(round(value))}G")
        except Exception:
            pass

    def _save_settings(self) -> None:
        cfg = self.core.config
        cfg["isolation_mode"] = self.set_isolation.get()
        cfg["window_title"] = self.set_window_title.get()
        cfg["custom_info"] = self.set_custom_info.get()
        cfg["visible_after"] = self.set_visible_after.get()
        cfg["process_priority"] = self.set_priority.get()
        cfg["game_window_size"] = self.set_window_size.get()
        cfg["auth_mode"] = self.set_auth_mode.get()
        cfg["proxy_mode"] = self.set_proxy.get()
        cfg["memory_mode"] = self.mem_mode_var.get()
        cfg["max_memory"] = f"{int(round(self.max_mem_slider.get()))}G"
        cfg["min_memory"] = f"{int(round(self.min_mem_slider.get()))}G"
        cfg["jvm_args"] = self.set_jvm_args.get()
        cfg["game_args"] = self.set_game_args.get()
        self.core.save_config()
        self._thread_safe_status("设置已保存")
        self._thread_safe_log("设置已保存")

    def _reset_settings(self) -> None:
        self.set_isolation.set("隔离所有实例")
        self.set_window_title.delete(0, "end")
        self.set_window_title.insert(0, "Minecraft")
        self.set_custom_info.delete(0, "end")
        self.set_custom_info.insert(0, "SimpleMCLauncher")
        self.set_visible_after.set("游戏启动后仍保持不变")
        self.set_priority.set("普通")
        self.set_window_size.set("默认")
        self.set_auth_mode.set("设备代码流")
        self.set_proxy.set("Java 默认")
        self.mem_mode_var.set("自定义")
        self.max_mem_slider.set(2)
        self.min_mem_slider.set(1)
        self.set_jvm_args.delete(0, "end")
        self.set_game_args.delete(0, "end")
        self._on_mem_mode_change()
        self._thread_safe_log("已恢复默认设置（需点击保存生效）")

    def _build_servers_page(self) -> ctk.CTkFrame:
        page = ctk.CTkFrame(self.content, fg_color=COLOR_BG, corner_radius=0)
        form = ctk.CTkFrame(page, fg_color="transparent")
        form.pack(fill="x", padx=20, pady=(20, 8))
        
        ctk.CTkLabel(form, text="类型").pack(side="left", padx=(0, 6))
        self.server_type = ctk.CTkOptionMenu(
            form, values=["paper", "purpur", "vanilla", "fabric", "forge"], width=110)
        self.server_type.set("paper")
        self.server_type.pack(side="left", padx=(0, 10))
        
        ctk.CTkLabel(form, text="版本").pack(side="left", padx=(0, 6))
        self.server_version_entry = ctk.CTkEntry(form, width=110)
        self.server_version_entry.pack(side="left", padx=(0, 10))
        
        ctk.CTkLabel(form, text="内存").pack(side="left", padx=(0, 6))
        self.server_mem_entry = ctk.CTkEntry(form, width=70)
        self.server_mem_entry.insert(0, "2G")
        self.server_mem_entry.pack(side="left", padx=(0, 10))
        
        ctk.CTkButton(form, text="下载", width=70,
                      command=self._on_install_server).pack(side="left", padx=4)
        ctk.CTkButton(form, text="刷新", width=70,
                      command=self._refresh_servers).pack(side="left", padx=4)

        self.server_list = ctk.CTkScrollableFrame(page, fg_color=COLOR_WHITE, height=110)
        self.server_list.pack(fill="x", padx=20, pady=(0, 8))

        # 控制台标题行：标题 + 清空日志按钮
        console_head = ctk.CTkFrame(page, fg_color="transparent")
        console_head.pack(fill="x", padx=20)
        ctk.CTkLabel(console_head, text="服务器控制台",
                     font=ctk.CTkFont(size=14, weight="bold")).pack(side="left")
        ctk.CTkButton(console_head, text="清空日志", width=80, height=26,
                      fg_color="#9aa0a6", hover_color="#7d8388",
                      font=ctk.CTkFont(size=12),
                      command=self._clear_server_log).pack(side="right")
        self.server_log_box = ctk.CTkTextbox(page, height=240)
        self.server_log_box.pack(fill="both", expand=True, padx=20, pady=(4, 6))
        self.server_log_box.configure(state="disabled")

        cmd_box = ctk.CTkFrame(page, fg_color="transparent")
        cmd_box.pack(fill="x", padx=20, pady=(0, 16))
        self.server_cmd_entry = ctk.CTkEntry(
            cmd_box, height=32, placeholder_text="输入服务器命令，回车发送...")
        self.server_cmd_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.server_cmd_entry.bind("<Return>", lambda e: self._send_server_command())
        
        ctk.CTkButton(cmd_box, text="发送", width=70, height=32,
                      command=self._send_server_command).pack(side="left", padx=(0, 8))
        ctk.CTkButton(cmd_box, text="停止", width=70, height=32,
                      fg_color="#8b0000", hover_color="#a00000",
                      command=lambda: self._run_bg(self.core.stop_server)).pack(side="left")
        return page

    def _build_download_page(self) -> ctk.CTkFrame:
        page = ctk.CTkFrame(self.content, fg_color=COLOR_BG, corner_radius=0)

        self.dl_nav = ctk.CTkFrame(page, fg_color=COLOR_WHITE, width=170, corner_radius=0)
        self.dl_nav.pack(side="left", fill="y")
        self.dl_nav.pack_propagate(False)

        ctk.CTkLabel(
            self.dl_nav, text="下载中心",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color="#0057a5").pack(pady=(20, 12))

        self.dl_nav_buttons: dict[str, ctk.CTkButton] = {}
        for cat in DOWNLOAD_CATEGORIES:
            icon = MODRINTH_CATEGORIES.get(cat, ("", "🎮"))[1]
            if cat == "收藏夹":
                icon = "⭐"
            btn = ctk.CTkButton(
                self.dl_nav, text=f"{icon}  {cat}", height=40, anchor="w",
                fg_color="transparent", text_color=COLOR_TEXT,
                hover_color="#e6f0fa", font=ctk.CTkFont(size=14),
                command=lambda c=cat: self._show_download_category(c))
            btn.pack(fill="x", padx=10, pady=3)
            self.dl_nav_buttons[cat] = btn

        self.dl_content = ctk.CTkFrame(page, fg_color=COLOR_BG, corner_radius=0)
        self.dl_content.pack(side="left", fill="both", expand=True)

        self._build_global_progress()

        self.dl_header = ctk.CTkLabel(
            self.dl_content, text="",
            font=ctk.CTkFont(size=18, weight="bold"), text_color="#0057a5")
        self.dl_header.pack(anchor="w", padx=24, pady=(16, 6))

        self.dl_pages: dict[str, ctk.CTkFrame] = {}
        self.dl_container = ctk.CTkFrame(self.dl_content, fg_color=COLOR_BG, corner_radius=0)
        self.dl_container.pack(fill="both", expand=True, padx=0, pady=0)
        
        for cat in DOWNLOAD_CATEGORIES:
            frame = ctk.CTkFrame(self.dl_container, fg_color=COLOR_BG, corner_radius=0)
            frame.place(relx=0, rely=0, relwidth=1, relheight=1)
            self.dl_pages[cat] = frame

        self._build_game_page()
        self._build_game_version_list(self.dl_pages["游戏"])
        
        for cat in MODRINTH_CATEGORIES:
            self._build_modrinth_page(cat)
            
        self._build_favorites_page()

        self._show_download_category("游戏")
        return page

    def _build_global_progress(self) -> None:
        """底部统一进度条（不含日志显示区）。"""
        bar = ctk.CTkFrame(self.dl_content, fg_color=COLOR_WHITE, corner_radius=0)
        bar.pack(side="bottom", fill="x")

        self.download_progress = ctk.CTkProgressBar(bar, height=14)
        self.download_progress.pack(fill="x", padx=16, pady=(10, 4))
        self.download_progress.set(0)

        row = ctk.CTkFrame(bar, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=(0, 10))
        self.download_status = ctk.CTkLabel(
            row, text="就绪", font=ctk.CTkFont(size=12), text_color=COLOR_MUTED, anchor="w")
        self.download_status.pack(side="left", fill="x", expand=True)
        self.download_percent = ctk.CTkLabel(
            row, text="0%", font=ctk.CTkFont(size=13, weight="bold"), text_color="#0057a5")
        self.download_percent.pack(side="right")

    def _build_game_page(self) -> None:
        frame = self.dl_pages["游戏"]
        install = ctk.CTkFrame(frame, fg_color=COLOR_WHITE, corner_radius=8)
        install.pack(fill="x", padx=20, pady=(10, 8))

        ctk.CTkLabel(install, text="安装游戏版本",
                     font=ctk.CTkFont(size=15, weight="bold"),
                     text_color=COLOR_TEXT).pack(anchor="w", padx=16, pady=(12, 6))

        row = ctk.CTkFrame(install, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=(0, 8))
        ctk.CTkLabel(row, text="加载器：").pack(side="left")
        self.dl_loader_var = ctk.StringVar(value="vanilla")
        for key, label in (("vanilla", "原版"), ("forge", "Forge"), ("fabric", "Fabric"),
                           ("neoforge", "NeoForge"), ("quilt", "Quilt"), ("optifine", "OptiFine")):
            ctk.CTkRadioButton(
                row, text=label, variable=self.dl_loader_var, value=key,
                font=ctk.CTkFont(size=13)).pack(side="left", padx=(0, 10))

        ver_row = ctk.CTkFrame(install, fg_color="transparent")
        ver_row.pack(fill="x", padx=16, pady=(0, 6))

        ctk.CTkLabel(ver_row, text="游戏版本：").pack(side="left", padx=(0, 6))
        self.dl_version_combo = ctk.CTkComboBox(
            ver_row, width=170, values=["加载中..."], state="normal"
        )
        self.dl_version_combo.pack(side="left", padx=(0, 12))
        self.dl_version_entry = self.dl_version_combo

        ctk.CTkLabel(ver_row, text="加载器版本：").pack(side="left", padx=(0, 6))
        self.dl_loader_version_combo = ctk.CTkComboBox(
            ver_row, width=150, values=["最新"], state="disabled"
        )
        self.dl_loader_version_combo.set("最新")
        self.dl_loader_version_combo.pack(side="left", padx=(0, 12))

        ctk.CTkButton(ver_row, text="安装", width=80,
                      command=self._on_download).pack(side="left")

        self.game_list_scroll = ctk.CTkScrollableFrame(frame, fg_color=COLOR_BG)
        self.game_list_scroll.pack(fill="both", expand=True, padx=20, pady=(0, 10))

        self.dl_loader_var.trace_add("write", lambda *_: self._refresh_loader_versions())

    def _refresh_loader_versions(self) -> None:
        loader = self.dl_loader_var.get()
        if loader == "vanilla":
            self.dl_loader_version_combo.configure(values=["最新"])
            self.dl_loader_version_combo.set("最新")
            self.dl_loader_version_combo.configure(state="disabled")
            return

        self.dl_loader_version_combo.configure(state="readonly")
        self.dl_loader_version_combo.configure(values=["加载中..."])
        self.dl_loader_version_combo.set("加载中...")

        def job():
            try:
                from utils import fetch_json
                versions = ["最新"]
                if loader == "fabric":
                    data = fetch_json("https://meta.fabricmc.net/v2/versions/loader")
                    versions += [d.get("version", "") for d in data]
                elif loader == "quilt":
                    data = fetch_json("https://meta.quiltmc.org/v3/versions/loader")
                    versions += [d.get("version", "") for d in data]
                versions = [v for v in versions if v]
                self.after(0, lambda: self._set_loader_versions(versions))
            except Exception:
                self.after(0, lambda: self._set_loader_versions(["最新"]))

        self._run_bg(job)

    def _set_loader_versions(self, versions: list) -> None:
        try:
            self.dl_loader_version_combo.configure(values=versions)
            self.dl_loader_version_combo.set(versions[0] if versions else "最新")
        except Exception:
            pass

    def _build_game_version_list(self, parent) -> None:
        self._run_bg(self._load_versions_job)

    def _load_versions_job(self) -> None:
        try:
            from utils import fetch_json
            if not self._cached_versions:
                manifest = fetch_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
                self._cached_versions = manifest.get("versions", [])

            version_names = [v["id"] for v in self._cached_versions]

            self.after(0, lambda: self._populate_version_combo(version_names))
            self.after(0, lambda: self._render_game_versions_safe())
        except Exception as e:
            self.after(0, lambda: self._render_version_error(str(e)))

    def _populate_version_combo(self, version_names: list) -> None:
        if not hasattr(self, "dl_version_combo") or not version_names:
            return
        self.dl_version_combo.configure(values=version_names)
        current = self.core.config.get("current_version", "")
        base = current
        if base and "-" in base:
            base = base.split("-")[0]
        if base and base in version_names:
            self.dl_version_combo.set(base)
            return
        releases = [v["id"] for v in self._cached_versions if v.get("type") == "release"]
        self.dl_version_combo.set(releases[0] if releases else version_names[0])

    def _render_version_error(self, msg: str) -> None:
        for w in self.game_list_scroll.winfo_children():
            w.destroy()
        ctk.CTkLabel(self.game_list_scroll, text=f"获取版本失败，请检查网络连接\n{msg}",
                     text_color="#c94f4f", justify="center").pack(pady=20)
        ctk.CTkButton(self.game_list_scroll, text="重试", 
                      command=lambda: self._run_bg(self._load_versions_job)).pack(pady=10)

    PAGE_SIZE = 60

    def _render_game_versions_safe(self) -> None:
        for w in self.game_list_scroll.winfo_children():
            w.destroy()

        groups: dict[str, list] = {"正式版": [], "预览版": [], "远古版": [], "愚人节版": []}
        for v in self._cached_versions:
            vid = str(v.get("id", ""))
            vtype = str(v.get("type", "release"))
            low = vid.lower()
            if ("rv" in low or "infinite" in low or "oneblock" in low
                    or "shareware" in low or "potato" in low
                    or "craftmine" in low or "22w13" in low or "23w13" in low):
                groups["愚人节版"].append(vid)
            elif vtype == "release":
                groups["正式版"].append(vid)
            elif vtype == "snapshot":
                groups["预览版"].append(vid)
            else:
                groups["远古版"].append(vid)

        self._rendered_counts: dict[str, int] = {}

        for cat, items in groups.items():
            panel = ctk.CTkFrame(self.game_list_scroll, fg_color=COLOR_WHITE, corner_radius=8)
            panel.pack(fill="x", pady=5)

            header = ctk.CTkButton(
                panel, text=f"▸  {cat}  ({len(items)} 个)", height=38, anchor="w",
                fg_color=COLOR_WHITE, text_color=COLOR_TEXT, hover_color="#eaf2f9",
                font=ctk.CTkFont(size=14, weight="bold"))
            header.pack(fill="x", padx=6, pady=4)

            body = ctk.CTkFrame(panel, fg_color=COLOR_WHITE)
            self._rendered_counts[cat] = 0

            def toggle(b=body, h=header, c=cat, lst=items):
                if b.winfo_ismapped():
                    b.pack_forget()
                    h.configure(text=f"▸  {c}  ({len(lst)} 个)")
                else:
                    b.pack(fill="x", padx=8, pady=(0, 6))
                    h.configure(text=f"▾  {c}  ({len(lst)} 个)")
                    if self._rendered_counts.get(c, 0) == 0 and lst:
                        self._render_page(b, c, lst, reset=True)

            header.configure(command=toggle)

            if cat == "正式版" and items:
                body.pack(fill="x", padx=8, pady=(0, 6))
                header.configure(text=f"▾  {cat}  ({len(items)} 个)")
                self._render_page(body, cat, items, reset=True)

    def _render_page(self, body, cat: str, items: list, reset: bool = False) -> None:
        if reset:
            for w in body.winfo_children():
                w.destroy()
            self._rendered_counts[cat] = 0

        start = self._rendered_counts.get(cat, 0)
        end = min(start + self.PAGE_SIZE, len(items))

        old_more = getattr(self, f"_more_{cat}", None)
        if old_more is not None:
            try:
                old_more.destroy()
            except Exception:
                pass
            setattr(self, f"_more_{cat}", None)

        for vid in items[start:end]:
            self._create_game_card(body, vid)
        self._rendered_counts[cat] = end

        if end < len(items):
            more = ctk.CTkButton(
                body, text=f"加载更多（还有 {len(items) - end} 个）", height=32,
                fg_color="#eaf2f9", text_color="#0057a5", hover_color="#d0e2f5",
                command=lambda b=body, c=cat, l=items: self._render_page(b, c, l))
            more.pack(fill="x", pady=4)
            setattr(self, f"_more_{cat}", more)

    def _create_game_card(self, parent, vid: str):
        row = ctk.CTkFrame(parent, fg_color=COLOR_WHITE, corner_radius=6)
        row.pack(fill="x", pady=4, padx=4)

        img = get_grass_icon()
        ctk_img = ctk.CTkImage(light_image=img, size=(48, 48))
        icon_label = ctk.CTkLabel(
            row, text="", width=48, height=48,
            image=ctk_img, fg_color="#f0f4f8", corner_radius=6
        )
        icon_label._image_ref = ctk_img
        icon_label.pack(side="left", padx=(8, 10), pady=6)

        mid_frame = ctk.CTkFrame(row, fg_color="transparent")
        mid_frame.pack(side="left", fill="both", expand=True, pady=4)

        ctk.CTkLabel(
            mid_frame, text=vid, anchor="w",
            font=ctk.CTkFont(size=14, weight="bold"), text_color=COLOR_TEXT
        ).pack(anchor="w")

        ctk.CTkLabel(
            mid_frame, text=f"Minecraft 原版 {vid}",
            anchor="w", font=ctk.CTkFont(size=11), text_color=COLOR_MUTED
        ).pack(anchor="w")

        ctk.CTkLabel(
            mid_frame, text="[Vanilla]",
            anchor="w", font=ctk.CTkFont(size=10, weight="bold"), text_color="#4a90d9"
        ).pack(anchor="w", pady=(2, 0))

        ctk.CTkButton(
            row, text="安装", width=68, height=30,
            font=ctk.CTkFont(size=12),
            fg_color="#4a90d9", hover_color="#3572b0",
            command=lambda: self._pick_version(vid)
        ).pack(side="right", padx=(0, 10))

        return row

    def _pick_version(self, version: str) -> None:
        self.dl_version_combo.set(version)
        self._on_download()

    def _build_modrinth_page(self, category: str) -> None:
        frame = self.dl_pages[category]

        if category == "整合包":
            imp = ctk.CTkFrame(frame, fg_color=COLOR_WHITE, corner_radius=8)
            imp.pack(fill="x", padx=20, pady=(10, 6))
            row = ctk.CTkFrame(imp, fg_color="transparent")
            row.pack(fill="x", padx=14, pady=10)
            ctk.CTkLabel(
                row, text="📦  把整合包文件直接拖进窗口，或点击右侧选择",
                font=ctk.CTkFont(size=13), text_color=COLOR_TEXT).pack(side="left")
            ctk.CTkButton(
                row, text="选择本地整合包", width=130, height=30,
                command=self._import_modpack).pack(side="right")
            ctk.CTkLabel(
                imp, text="支持 .zip（CurseForge/PCL）· .mrpack（Modrinth）· .mcpack / .mcaddon（基岩版）",
                font=ctk.CTkFont(size=11), text_color=COLOR_MUTED).pack(
                anchor="w", padx=14, pady=(0, 10))

        sbox = ctk.CTkFrame(frame, fg_color="transparent")
        sbox.pack(fill="x", padx=20, pady=(10, 8))
        
        search = ctk.CTkEntry(sbox, height=34, placeholder_text=f"搜索{category}...")
        search.pack(side="left", fill="x", expand=True, padx=(0, 8))
        search.bind("<Return>", lambda e, s=search, c=category: self._refresh_modrinth(c, s.get()))
        
        ctk.CTkButton(sbox, text="搜索", width=80, height=34,
                      command=lambda s=search, c=category: self._refresh_modrinth(c, s.get())).pack(side="left")

        scroll = ctk.CTkScrollableFrame(frame, fg_color=COLOR_BG)
        scroll.pack(fill="both", expand=True, padx=20, pady=(0, 10))

        setattr(self, f"_scroll_{category}", scroll)
        setattr(self, f"_search_{category}", search)
        setattr(self, f"_search_text_{category}", "")

    def _refresh_modrinth(self, category: str, query: str) -> None:
        setattr(self, f"_search_text_{category}", query or "")
        self._modrinth_cache.pop(category, None)
        self._run_bg(self._load_modrinth_job, category, query or "")

    def _load_modrinth_job(self, category: str, query: str) -> None:
        try:
            import json as _json
            import requests as _requests
            project_type = MODRINTH_CATEGORIES[category][0]
            params = {
                "limit": 20,
                "index": "relevance" if query.strip() else "downloads",
            }
            if project_type != "world":
                params["facets"] = _json.dumps([[f"project_type:{project_type}"]])
            if query.strip():
                params["query"] = query.strip()
            elif project_type == "world":
                params["query"] = MODRINTH_WORLD_QUERY

            resp = _requests.get(
                "https://api.modrinth.com/v2/search",
                params=params, timeout=30,
                headers={"User-Agent": f"SimpleMCLauncher/{VERSION_OF_LAUNCHER}"})
            resp.raise_for_status()
            hits = resp.json().get("hits", [])
            self._modrinth_cache[category] = hits
            self.after(0, lambda: self._render_modrinth(category, hits))
        except Exception as e:
            self.after(0, lambda: self._render_modrinth_error(category, str(e)))

    def _render_modrinth_error(self, category: str, msg: str) -> None:
        scroll = getattr(self, f"_scroll_{category}", None)
        if scroll is None:
            return
        for w in scroll.winfo_children():
            w.destroy()
        ctk.CTkLabel(scroll, text=f"获取{category}失败，请检查网络\n{msg}",
                     text_color="#c94f4f", justify="center").pack(pady=20)
        ctk.CTkButton(scroll, text="重试", 
                      command=lambda: self._refresh_modrinth(category, "")).pack(pady=10)

    def _render_modrinth(self, category: str, hits) -> None:
        scroll = getattr(self, f"_scroll_{category}", None)
        if scroll is None:
            return
        for w in scroll.winfo_children():
            w.destroy()

        if not hits:
            ctk.CTkLabel(scroll, text=f"未找到{category}", text_color=COLOR_MUTED).pack(pady=20)
            return

        for item in hits:
            title = item.get("title", "?")
            self._make_modrinth_row(
                scroll, item, "安装", lambda t=title, c=category: self._install_from_modrinth(c, t)
            )

    def _install_from_modrinth(self, category: str, title: str) -> None:
        if category == "模组":
            self._thread_safe_log(f"开始安装模组: {title}")
            self._run_bg(self.core.install_mod, title)
        elif category == "光影包":
            self._thread_safe_log(f"开始安装光影包: {title}")
            self._run_bg(self.core.install_shaderpack, title)
        else:
            self._thread_safe_log(f"{category}「{title}」：请通过 Modrinth 网页下载后放入对应文件夹")

    def _import_modpack(self) -> None:
        path = filedialog.askopenfilename(
            title="选择整合包文件",
            filetypes=[
                ("所有支持的整合包", "*.zip *.mrpack *.mcpack *.mcaddon"),
                ("CurseForge / PCL 整合包", "*.zip"),
                ("Modrinth 整合包", "*.mrpack"),
                ("基岩版资源包", "*.mcpack *.mcaddon"),
                ("所有文件", "*.*"),
            ])
        if not path:
            return
        self._thread_safe_log(f"开始导入整合包: {os.path.basename(path)}")
        self._run_bg(self.core.import_modpack, path)

    def _load_modrinth_icon(self, url: str, label: ctk.CTkLabel) -> None:
        def job():
            if url in _ICON_CACHE:
                self.after(0, lambda: self._apply_icon_to_label(label, _ICON_CACHE[url], url))
                return
                
            try:
                req = urllib.request.Request(url, headers={"User-Agent": f"SimpleMCLauncher/{VERSION_OF_LAUNCHER}"})
                with urllib.request.urlopen(req, timeout=15) as resp:
                    data = resp.read()
                img = Image.open(io.BytesIO(data)).convert("RGBA").resize((48, 48), Image.Resampling.LANCZOS)
                _ICON_CACHE[url] = img
                
                self.after(0, lambda: self._apply_icon_to_label(label, img, url))
            except Exception as e:
                self._thread_safe_log(f"图标加载失败: {e}")
                self.after(0, lambda: label.configure(text="🖼️"))

        self._run_bg(job)

    def _apply_icon_to_label(self, label, pil_img, url):
        try:
            ctk_img = ctk.CTkImage(light_image=pil_img, size=(48, 48))
            _CTK_IMAGES[url] = ctk_img  # 存入全局字典，防止被回收
            label.configure(image=ctk_img, text="")
        except Exception:
            pass

    def _make_modrinth_row(self, parent, item: dict, action_text: str, action) -> None:
        title = item.get("title", "?")
        description = (item.get("description") or "").strip()
        if len(description) > 70:
            description = description[:70] + "…"
        downloads = item.get("downloads", 0)
        date_str = item.get("date_published", "")[:10]
        loaders = item.get("loaders", [])
        loader_tag = " / ".join(loaders[:3]).title() if loaders else "通用"
        icon_url = item.get("icon_url", "")

        row = ctk.CTkFrame(parent, fg_color=COLOR_WHITE, corner_radius=6)
        row.pack(fill="x", pady=4, padx=4)

        icon_label = ctk.CTkLabel(
            row, text="⏳" if icon_url else "📦", font=ctk.CTkFont(size=20),
            width=48, height=48, fg_color="#f0f4f8", corner_radius=6
        )
        icon_label.pack(side="left", padx=(8, 10), pady=8)
        
        if icon_url:
            self._load_modrinth_icon(icon_url, icon_label)

        mid_frame = ctk.CTkFrame(row, fg_color="transparent")
        mid_frame.pack(side="left", fill="both", expand=True, pady=4)

        top_line = ctk.CTkFrame(mid_frame, fg_color="transparent")
        top_line.pack(fill="x", anchor="w")
        ctk.CTkLabel(
            top_line, text=title, anchor="w",
            font=ctk.CTkFont(size=13, weight="bold"), text_color=COLOR_TEXT
        ).pack(side="left")
        ctk.CTkLabel(
            top_line, text=f"⬇ {downloads:,}", anchor="e",
            font=ctk.CTkFont(size=10), text_color=COLOR_MUTED
        ).pack(side="right", padx=(0, 8))

        if description:
            ctk.CTkLabel(
                mid_frame, text=description, anchor="w", justify="left",
                font=ctk.CTkFont(size=10), text_color=COLOR_MUTED
            ).pack(anchor="w", pady=(1, 2))

        meta_frame = ctk.CTkFrame(mid_frame, fg_color="transparent")
        meta_frame.pack(anchor="w", fill="x")
        
        ctk.CTkLabel(
            meta_frame, text=f"[{loader_tag}]",
            font=ctk.CTkFont(size=9, weight="bold"), text_color="#4a90d9"
        ).pack(side="left", padx=(0, 6))
        
        if date_str:
            ctk.CTkLabel(
                meta_frame, text=f"📅 {date_str}",
                font=ctk.CTkFont(size=9), text_color=COLOR_MUTED
            ).pack(side="left")

        ctk.CTkButton(
            row, text=action_text, width=64, height=28,
            font=ctk.CTkFont(size=11),
            fg_color="#4a90d9", hover_color="#3572b0",
            command=action
        ).pack(side="right", padx=(0, 10))

    def _build_favorites_page(self) -> None:
        frame = self.dl_pages["收藏夹"]
        self.fav_scroll = ctk.CTkScrollableFrame(frame, fg_color=COLOR_BG)
        self.fav_scroll.pack(fill="both", expand=True, padx=20, pady=10)
        ctk.CTkLabel(
            self.fav_scroll, text="收藏夹为空（点击 ⭐ 可添加收藏）",
            text_color=COLOR_MUTED).pack(pady=20)

    def _show_download_category(self, category: str) -> None:
        for name, page in self.dl_pages.items():
            if name == category:
                page.lift()
                
        for name, btn in self.dl_nav_buttons.items():
            active = (name == category)
            icon = MODRINTH_CATEGORIES.get(name, ("", "🎮"))[1]
            if name == "收藏夹":
                icon = "⭐"
            btn.configure(
                fg_color="#dceafc" if active else "transparent",
                text_color="#0057a5" if active else COLOR_TEXT,
                text=f"{icon}  {name}",
                font=ctk.CTkFont(size=14, weight="bold" if active else "normal"))
                
        self.dl_header.configure(text=category)

        if category in MODRINTH_CATEGORIES:
            if category in self._modrinth_cache:
                self._render_modrinth(category, self._modrinth_cache[category])
                return
            query = getattr(self, f"_search_text_{category}", "")
            self._refresh_modrinth(category, query)

    def _switch_tab(self, index: int) -> None:
        name = NAV_ITEMS[index]
        
        for key, page in self.pages.items():
            if key == name:
                page.lift()
                
        if name == "启动":
            self.sidebar.grid()
        else:
            self.sidebar.grid_remove()

        for i, btn in enumerate(self.nav_buttons):
            active = (i == index)
            btn.configure(
                fg_color=COLOR_PRIMARY_LIGHT if active else "transparent",
                text_color="white" if active else "#dce8f5")

        if name == "下载":
            if not self._dl_initialized:
                self._dl_initialized = True
                self._show_download_category("游戏")
            else:
                self._show_download_category(self.dl_header.cget("text") or "游戏")
        elif name == "服务器":
            self._refresh_servers()

    def _switch_to_main(self) -> None:
        self._switch_tab(NAV_ITEMS.index("下载"))

    def _on_launch(self) -> None:
        version = self.current_version
        if not version:
            self._thread_safe_log("请先选择要启动的版本")
            return
        self.launch_btn.configure(state="disabled")
        self._run_bg(self._launch_worker, version)

    def _launch_worker(self, version: str) -> None:
        try:
            self.core.launch_game(version)
        finally:
            self.after(0, lambda: self.launch_btn.configure(state="normal"))

    def _save_offline_name(self) -> None:
        if self._updating_player_display:
            return
        if not self.offline_name_entry.winfo_ismapped():
            return
        name = self.offline_name_entry.get().strip() or "Player"
        if name == self.core.config.get("player_name"):
            return
        self.core.set_player_name(name)
        self._update_player_display()

    def _on_offline_login(self) -> None:
        self.core.microsoft_logout()
        self._update_player_display()

    def _on_close(self) -> None:
        try:
            self.core.close()
        except Exception:
            pass
        self.destroy()

    def _update_player_display(self) -> None:
        if self._updating_player_display:
            return
        self._updating_player_display = True
        try:
            logged_in = self.core.is_microsoft_logged_in()
            raw = self.core.get_player_display()
            name = raw.replace("（正版登录）", "").replace("（离线模式）", "").strip() or "Player"

            if logged_in:
                self.player_name_label.configure(text=name)
                self.player_name_label.pack(pady=(2, 0))
                self.offline_name_entry.pack_forget()
                self.login_status_label.configure(text="正版验证")
                self.offline_btn.configure(state="normal")
            else:
                self.player_name_label.pack_forget()
                if not self.offline_name_entry.winfo_ismapped():
                    self.offline_name_entry.pack(pady=(2, 0))
                self.offline_name_entry.delete(0, "end")
                self.offline_name_entry.insert(0, name)
                self.login_status_label.configure(text="离线验证")

            self._load_avatar(name)
        finally:
            self._updating_player_display = False

    AVATAR_SOURCES = (
        "https://api.mcheads.org/head/{name}/256",
        "https://mc-heads.net/avatar/{name}/80"
    )
    _AVATAR_UA = {
        "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                       "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
    }

    def _load_avatar(self, name: str) -> None:
        if name == self._last_avatar_name:
            return
        self._last_avatar_name = name
        self._show_avatar_placeholder()

        def job():
            data = None
            for tpl in self.AVATAR_SOURCES:
                url = tpl.format(name=name)
                try:
                    req = urllib.request.Request(url, headers=self._AVATAR_UA)
                    with urllib.request.urlopen(req, timeout=8) as resp:
                        if resp.status == 200:
                            data = resp.read()
                            if data:
                                break
                except Exception:
                    continue
            if data:
                self.after(0, lambda: self._apply_avatar(data))
            else:
                self._thread_safe_log("无法获取玩家头像，请检查网络")

        self._run_bg(job)

    def _show_avatar_placeholder(self) -> None:
        try:
            img = self._make_default_avatar()
            ctk_img = ctk.CTkImage(light_image=img, size=(80, 80))
            self._avatar_ref = ctk_img
            self.avatar_label.configure(image=ctk_img, text="")
        except Exception:
            pass

    def _apply_avatar(self, data: bytes) -> None:
        try:
            img = Image.open(io.BytesIO(data)).convert("RGBA").resize((80, 80))
            ctk_img = ctk.CTkImage(light_image=img, size=(80, 80))
            self._avatar_ref = ctk_img
            self.avatar_label.configure(image=ctk_img, text="")
        except Exception as e:
            self._thread_safe_log(f"头像解析失败: {e}")
            self._show_avatar_placeholder()

    def _show_login_dialog(self) -> None:
        dialog = ctk.CTkToplevel(self)
        dialog.title("微软正版登录")
        dialog.geometry("440x330")
        dialog.transient(self)
        dialog.grab_set()

        ctk.CTkLabel(dialog, text="请按以下步骤完成登录：",
                     font=ctk.CTkFont(size=15, weight="bold")).pack(pady=(20, 8))
        ctk.CTkLabel(dialog, text="1. 打开浏览器访问: https://www.microsoft.com/link").pack(anchor="w", padx=24)
        ctk.CTkLabel(dialog, text="2. 输入下面的设备代码").pack(anchor="w", padx=24, pady=(2, 10))

        code_label = ctk.CTkLabel(
            dialog, text="正在获取...", font=ctk.CTkFont(size=30, weight="bold"),
            text_color="#0057a5")
        code_label.pack(pady=8)

        status = ctk.CTkLabel(dialog, text="", text_color=COLOR_MUTED)
        status.pack(pady=2)

        btn_box = ctk.CTkFrame(dialog, fg_color="transparent")
        btn_box.pack(pady=12)

        state = {"device": None}

        def copy_code():
            dev = state["device"]
            if not dev:
                return
            self.clipboard_clear()
            self.clipboard_append(dev["user_code"])
            status.configure(text="已复制到剪贴板！")

        def open_browser():
            dev = state["device"]
            if dev:
                webbrowser.open(dev["verify_url"])

        copy_btn = ctk.CTkButton(btn_box, text="复制代码", width=110, state="disabled",
                                 command=copy_code)
        copy_btn.pack(side="left", padx=6)
        open_btn = ctk.CTkButton(btn_box, text="打开浏览器", width=110, state="disabled",
                                 command=open_browser)
        open_btn.pack(side="left", padx=6)
        ctk.CTkButton(btn_box, text="取消", width=90,
                      fg_color="#666666", hover_color="#777777",
                      command=dialog.destroy).pack(side="left", padx=6)

        def fetch_code():
            try:
                dev = self.core.request_device_code()
                state["device"] = dev

                def apply():
                    code_label.configure(text=dev["user_code"])
                    status.configure(text=f"有效期 {dev['expires_in'] // 60} 分钟，登录成功后自动检测")
                    copy_btn.configure(state="normal")
                    open_btn.configure(state="normal")

                self.after(0, apply)
            except Exception as e:
                self.after(0, lambda: status.configure(text=f"获取失败: {e}"))

        self._run_bg(fetch_code)

        def on_close():
            dev = state["device"]
            if dev:
                self._run_bg(
                    self.core.login_with_device_code,
                    dev,
                    lambda: self.after(0, self._update_player_display))
            dialog.destroy()

        dialog.protocol("WM_DELETE_WINDOW", on_close)

    def _show_instance_select_dialog(self) -> None:
        dialog = ctk.CTkToplevel(self)
        dialog.title("选择游戏实例")
        dialog.geometry("340x360")
        dialog.transient(self)
        dialog.grab_set()

        ctk.CTkLabel(dialog, text="请选择要启动的版本：",
                     font=ctk.CTkFont(size=14)).pack(anchor="w", padx=20, pady=(16, 8))

        scroll = ctk.CTkScrollableFrame(dialog, height=200)
        scroll.pack(fill="both", expand=True, padx=20)

        versions = self.core.get_installed_versions()
        if not versions:
            ctk.CTkLabel(scroll, text="暂无已安装版本", text_color=COLOR_MUTED).pack(pady=20)

        for v in versions:
            loader = self.core.detect_loader(v)
            tag = LOADER_NAMES.get(loader, loader)
            pack_name = self.core.get_modpack_name(v)
            if pack_name:
                tag = f"整合包 · {tag}"
            ctk.CTkButton(
                scroll, text=f"{v}   [{tag}]",
                height=34, anchor="w",
                fg_color=COLOR_WHITE, text_color=COLOR_TEXT,
                hover_color="#dceafc", border_width=1, border_color="#d0d0d0",
                command=lambda ver=v: self._select_instance(dialog, ver)).pack(fill="x", pady=3)

    def _select_instance(self, dialog, version: str) -> None:
        self.current_version = version
        self.current_version_label.configure(text=version)
        self.core.config["current_version"] = version
        self.core.save_config()
        dialog.destroy()

    def _show_instance_settings_dialog(self) -> None:
        dialog = ctk.CTkToplevel(self)
        dialog.title("实例设置")
        dialog.geometry("520x300")
        dialog.transient(self)
        dialog.grab_set()

        ctk.CTkLabel(dialog, text="实例设置",
                     font=ctk.CTkFont(size=15, weight="bold")).pack(anchor="w", padx=24, pady=(18, 12))

        try:
            cur_max = int(self.core.config.get("max_memory", "2G").rstrip("Gg"))
        except Exception:
            cur_max = 2

        mem_label = ctk.CTkLabel(dialog, text=f"最大内存: {cur_max}G")
        mem_label.pack(anchor="w", padx=24)

        def on_slider(value):
            gb = max(1, int(round(value)))
            mem_label.configure(text=f"最大内存: {gb}G")
            self.core.config["max_memory"] = f"{gb}G"

        slider = ctk.CTkSlider(dialog, from_=1, to=16, number_of_steps=15, command=on_slider)
        slider.set(cur_max)
        slider.pack(fill="x", padx=24, pady=8)

        tools = ctk.CTkFrame(dialog, fg_color="transparent")
        tools.pack(anchor="w", padx=24, pady=16)
        ctk.CTkButton(tools, text="安装 Java 21", width=110,
                      command=lambda: self._run_bg(self.core.install_java, "21")).pack(side="left", padx=(0, 8))
        ctk.CTkButton(tools, text="检查更新", width=100,
                      command=lambda: self._run_bg(self.core.check_for_updates)).pack(side="left", padx=(0, 8))
        ctk.CTkButton(tools, text="打开数据目录", width=110,
                      command=lambda: self._open_path(str(MC_DIR))).pack(side="left")

        ctk.CTkButton(dialog, text="保存并关闭", width=140,
                      command=lambda: self._save_instance_settings(dialog)).pack(pady=16)

    def _save_instance_settings(self, dialog) -> None:
        self.core.save_config()
        self._thread_safe_log("实例设置已保存")
        dialog.destroy()

    def _refresh_versions(self) -> None:
        versions = self.core.get_installed_versions()
        current = self.core.config.get("current_version", "")
        self.current_version = current if current in versions else (versions[0] if versions else "")
        try:
            self.current_version_label.configure(text=self.current_version or "未选择版本")
        except Exception:
            pass

    def _on_download(self) -> None:
        version = self.dl_version_entry.get().strip()
        if not version or version == "加载中...":
            self._thread_safe_log("请等待版本列表加载完毕，或手动输入正确版本")
            return
        loader = self.dl_loader_var.get()
        self.download_progress.set(0)
        if loader == "vanilla":
            self._thread_safe_log(f"开始下载 Minecraft {version} ...")
            self._run_bg(self.core.install_vanilla, version)
        elif loader == "optifine":
            self._thread_safe_log("OptiFine 需手动下载：请到 optifine.net 下载对应版本后双击安装")
        else:
            lv = self.dl_loader_version_combo.get()
            if lv and lv not in ("最新", "加载中..."):
                self._thread_safe_log(f"开始下载 {loader} {lv} + Minecraft {version} ...")
                self._run_bg(self.core.install_loader, loader, version, lv)
            else:
                self._thread_safe_log(f"开始下载 {loader}（最新）+ Minecraft {version} ...")
                self._run_bg(self.core.install_loader, loader, version)

    def _on_install_server(self) -> None:
        version = self.server_version_entry.get().strip()
        if not version:
            self._thread_safe_log("请输入服务器版本")
            return
        mem = self.server_mem_entry.get().strip() or "2G"
        self._run_bg(self.core.install_server, self.server_type.get(), version, mem, mem)

    def _refresh_servers(self) -> None:
        for w in self.server_list.winfo_children():
            w.destroy()
        servers = self.core.get_installed_servers()
        if not servers:
            ctk.CTkLabel(self.server_list, text="暂无服务器，请先下载",
                         text_color=COLOR_MUTED).pack(pady=10)
            return
        for sid in servers:
            row = ctk.CTkFrame(self.server_list, fg_color="transparent")
            row.pack(fill="x", pady=2)
            ctk.CTkLabel(row, text=sid, anchor="w",
                         text_color=COLOR_TEXT).pack(side="left", fill="x", expand=True, padx=6)
            # 注意：side="right" 时先 pack 的更靠右。
            # 想要显示为 [启动][删除]，就要先 pack 删除，再 pack 启动。
            ctk.CTkButton(row, text="删除", width=60, height=26,
                          fg_color="#c94f4f", hover_color="#a83c3c",
                          command=lambda s=sid: self._on_delete_server(s)).pack(
                side="right", padx=4)
            ctk.CTkButton(row, text="启动", width=60, height=26,
                          command=lambda s=sid: self._start_server(s)).pack(
                side="right", padx=(4, 0))

    def _on_delete_server(self, server_id: str) -> None:
        """确认后删除服务器。"""
        import tkinter.messagebox as mb

        size = self.core.server_size(server_id)
        size_text = f"{size / 1024 / 1024:.1f} MB" if size else "未知"

        # 判断该服务器是否正在运行
        extra = ""
        running_jar = getattr(self.core, "server_jar", "") or ""
        if self.core.is_server_running() and running_jar:
            srv_root = os.path.abspath(self.core.server_dir_of(server_id))
            if os.path.abspath(running_jar).startswith(srv_root):
                extra = "\n\n注意：该服务器正在运行，删除前会先停止它。"

        msg = (
            f"确定要删除服务器吗？\n\n"
            f"服务器：{server_id}\n"
            f"占用空间：{size_text}\n\n"
            f"这会永久删除该服务器的所有文件，包括世界存档、插件与配置，"
            f"操作不可恢复。{extra}"
        )
        try:
            if not self.winfo_viewable():
                self.deiconify()
                self.update_idletasks()
        except Exception:  # noqa: BLE001
            pass

        if not mb.askyesno("删除服务器", msg, parent=self, icon="warning"):
            return

        self._append_server_log(f"> 正在删除服务器 {server_id}")
        self._run_bg(self._delete_server_worker, server_id)

    def _delete_server_worker(self, server_id: str) -> None:
        ok = False
        try:
            ok = self.core.delete_server(server_id)
        except Exception as e:  # noqa: BLE001
            self.core.log(f"删除服务器异常: {e}")
        self.after(0, lambda: self._on_delete_server_done(server_id, ok))

    def _on_delete_server_done(self, server_id: str, ok: bool) -> None:
        self._refresh_servers()
        if ok:
            self._append_server_log(f"> 服务器 {server_id} 已删除")
            self._thread_safe_status(f"已删除 {server_id}")
        else:
            self._show_info_dialog(
                "删除失败",
                f"无法删除服务器 {server_id}。\n\n"
                "常见原因：\n"
                "• 服务器仍在运行，文件被占用\n"
                "• 文件被其他程序打开\n\n"
                "请先点击「停止」，稍后重试，或手动删除目录：\n"
                f"{self.core.server_dir_of(server_id)}")

    def _start_server(self, server_id: str) -> None:
        server_dir = os.path.join(str(SERVER_DIR), server_id)
        if not os.path.isdir(server_dir):
            self._show_info_dialog("启动服务器", f"服务器目录不存在：\n{server_dir}")
            return

        # 首次启动需先同意 EULA
        if not self.core.server_eula_accepted(server_dir):
            if not self._ask_eula(server_id, server_dir):
                self._append_server_log("> 已取消启动（未同意 EULA）")
                return

        self._append_server_log(f"> 启动服务器 {server_id}")
        self._run_bg(self.core.launch_server, server_id)

    def _ask_eula(self, server_id: str, server_dir: str) -> bool:
        """弹出 EULA 同意对话框；同意则写入 eula=true 并返回 True。"""
        import tkinter.messagebox as mb

        try:
            if not self.winfo_viewable():
                self.deiconify()
                self.update_idletasks()
        except Exception:  # noqa: BLE001
            pass

        msg = (
            "首次启动服务器前，需要先同意 Minecraft 最终用户许可协议（EULA）。\n\n"
            "    https://aka.ms/MinecraftEULA\n\n"
            f"服务器：{server_id}\n\n"
            "点击「同意」即表示你接受该协议，程序会写入 eula=true 并启动服务器。\n"
            "点击「不同意」则不会启动。"
        )
        if not mb.askyesno("同意 Minecraft EULA", msg, parent=self):
            return False

        try:
            self.core.set_server_eula(server_dir, True)
            self._append_server_log("> 已同意 EULA，写入 eula=true")
            self._thread_safe_log(f"已同意 {server_id} 的 EULA")
            return True
        except Exception as e:  # noqa: BLE001
            self._show_info_dialog("写入 EULA 失败", f"无法写入 eula.txt：\n{e}")
            return False

    def _clear_server_log(self) -> None:
        """清空服务器控制台日志显示（不影响服务器运行）。"""
        try:
            self.server_log_box.configure(state="normal")
            self.server_log_box.delete("1.0", "end")
            self.server_log_box.configure(state="disabled")
        except Exception:  # noqa: BLE001
            pass

    def _send_server_command(self) -> None:
        cmd = self.server_cmd_entry.get().strip()
        if cmd:
            self.core.send_server_command(cmd)
            self.server_cmd_entry.delete(0, "end")

    def _open_path(self, path: str) -> None:
        try:
            if os.name == "nt":
                os.startfile(path)
            elif os.sys.platform == "darwin":
                import subprocess
                subprocess.Popen(["open", path])
            else:
                import subprocess
                subprocess.Popen(["xdg-open", path])
        except Exception as e:
            self._thread_safe_log(f"无法打开: {e}")