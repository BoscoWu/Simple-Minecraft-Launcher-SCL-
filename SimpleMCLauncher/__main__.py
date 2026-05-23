# __main__.py
import tkinter as tk
from gui import SimpleMCLauncherGUI

def main():
    root = tk.Tk()
    app = SimpleMCLauncherGUI(root)
    root.mainloop()

if __name__ == "__main__":
    main()