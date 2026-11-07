# setup.py
# Simple MC Launcher (https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-)
# Copyright (C) 2026 BoscoWu
#
# This library is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 2.1 of the License, or (at your option) any later version.

import os
from setuptools import setup, find_packages

# 读取 README（如果存在）
readme_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "README.md")
if os.path.isfile(readme_path):
    with open(readme_path, "r", encoding="utf-8") as fh:
        long_description = fh.read()
else:
    long_description = "一个简单易用的 Minecraft 启动器。"

setup(
    name="simple-mc-launcher",
    version="3.1.0",
    author="BoscoWu",
    author_email="WuBosco1@outlook.com",
    description="一个简单易用的 Minecraft 启动器，支持原版、Forge、Fabric、NeoForge、Quilt，内置模组管理、服务器管理、微软登录等。",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/BoscoWu/Simple-Minecraft-Launcher-SCL-",
    packages=find_packages(),
    include_package_data=True,
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: MIT License",
        "Operating System :: OS Independent",
    ],
    python_requires=">=3.9",
    install_requires=[
        "requests>=2.25.0",
        "minecraft-launcher-lib>=8.0",
        "customtkinter>=5.2.0",
        "urllib3>=1.26.0",
    ],
    extras_require={
        "dev": ["build", "twine"],
    },
    entry_points={
        "console_scripts": [
            "mclauncher-gui = SimpleMCLauncher.__main__:main",
        ],
    },
)
