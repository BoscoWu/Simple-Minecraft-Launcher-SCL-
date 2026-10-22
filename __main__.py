# __main__.py
# Simple MC Launcher (https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-)
# Copyright (C) 2026 BoscoWu
#
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.

import sys
import os

if getattr(sys, "frozen", False):
    sys.path.insert(0, os.path.join(sys._MEIPASS, "SimpleMCLauncher"))

from . import utils  # noqa: E402
from . import gui  # noqa: E402


def main() -> None:
    utils.setup_logging()
    app = gui.SimpleMCLauncherGUI()
    app.mainloop()


if __name__ == "__main__":
    main()
