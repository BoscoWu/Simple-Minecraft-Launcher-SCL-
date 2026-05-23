# setup.py
from setuptools import setup, find_packages

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()

setup(
    name="simple-mc-launcher",
    version="3.0.2",
    author="BoscoWu",
    author_email="WuBosco1@outlook.com",  # 请替换为你的邮箱
    description="一个简单易用的 Minecraft 启动器，支持原版、Forge、Fabric、NeoForge、Quilt，内置模组管理、服务器管理、微软登录等。",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-",  # 你的仓库地址
    packages=find_packages(),  # 自动发现包（需要 __init__.py）
    include_package_data=True,
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: MIT License",
        "Operating System :: OS Independent",
    ],
    python_requires=">=3.7",
    install_requires=[
        "requests>=2.25.0",
        "minecraft-launcher-lib>=7.1,<8.0",  # 注意：为了兼容 NeoForge/Quilt，固定使用 7.x
        "pyperclip>=1.8.0",
        "urllib3>=1.26.0",
    ],
    extras_require={
        "dev": ["build", "twine"],
    },
    entry_points={
        "console_scripts": [
            "mclauncher-gui = SimpleMCLauncher.__main__:main",  # 命令行入口
        ],
    },
)