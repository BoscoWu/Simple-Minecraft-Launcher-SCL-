# __main__.py
"""程序入口：启动图形界面。"""
import sys
import os

if getattr(sys, "frozen", False):
    # 打包成 exe 后，把解包目录加入模块搜索路径
    sys.path.insert(0, os.path.join(sys._MEIPASS, "SimpleMCLauncher"))

from . import utils  # noqa: E402
from . import gui  # noqa: E402


def main() -> None:
    utils.setup_logging()
    app = gui.SimpleMCLauncherGUI()
    app.mainloop()


if __name__ == "__main__":
    main()
