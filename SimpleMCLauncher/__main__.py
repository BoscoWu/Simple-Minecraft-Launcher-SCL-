
import sys, os
if getattr(sys, 'frozen', False):
    sys.path.insert(0, os.path.join(sys._MEIPASS, 'SimpleMCLauncher'))
# __main__.py
import tkinter as tk
from gui import SimpleMCLauncherGUI

def main():
    root = tk.Tk()
    app = SimpleMCLauncherGUI(root)
    root.mainloop()

if __name__ == "__main__":
    main()