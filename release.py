#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Simple MC Launcher 一键打包发布脚本

用法：
    python release.py                    # 交互式输入版本号
    python release.py 4.0.2              # 直接指定版本号
    python release.py 4.0.2 --no-upload  # 只打包不上传
    python release.py 4.0.2 --draft      # 上传为草稿 Release

流程：
    1. 校验版本号格式
    2. 同步版本号到 pom.xml
    3. 检查工作区是否干净、是否已存在同名 tag
    4. mvn clean package 构建 jar
    5. jpackage 打包成 Windows .exe 安装包
    6. git 提交版本号变更 + 打 tag
    7. 推送提交与 tag 到 GitHub（触发 CI），或直接调用 GitHub API 创建 Release 并上传 exe

依赖：
    - JDK 21+（含 jpackage，需 WiX Toolset 3.14+ 才能打 .exe）
    - Maven
    - Git（已配置好凭据）
    - requests（pip install requests），或通过环境变量 GITHUB_TOKEN 使用纯 HTTP 上传
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent
POM_FILE = PROJECT_ROOT / "pom.xml"
TARGET_DIR = PROJECT_ROOT / "target"
JPACKAGE_INPUT = PROJECT_ROOT / "jpackage-input"
APP_NAME = "SimpleMCL"
MAIN_CLASS = "com.simplemc.launcher.Main"
GITHUB_OWNER = "BoscoWu"
GITHUB_REPO = "Simple-Minecraft-Launcher-SCL-"

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")

# 常见安装位置（PATH 找不到时自动回退查找）
TOOL_CANDIDATES = {
    "mvn": [
        Path(r"C:\Users\Administrator\AppData\Local\maven\apache-maven-3.9.9\bin\mvn.cmd"),
        Path(r"C:\Program Files\Apache\maven\bin\mvn.cmd"),
        Path(r"C:\apache-maven\bin\mvn.cmd"),
    ],
    "jpackage": [
        Path(r"C:\Program Files\Java\jdk-21\bin\jpackage.exe"),
        Path(r"C:\Program Files\Java\jdk-21.0.12.1\bin\jpackage.exe"),
        Path(r"F:\Java\jdk-25\bin\jpackage.exe"),
    ],
}


# ---------------------------------------------------------------------------
# 输出辅助
# ---------------------------------------------------------------------------
class C:
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    RED = "\033[91m"
    BLUE = "\033[94m"
    BOLD = "\033[1m"
    RESET = "\033[0m"


def info(msg: str) -> None:
    print(f"{C.BLUE}[信息]{C.RESET} {msg}")


def ok(msg: str) -> None:
    print(f"{C.GREEN}[成功]{C.RESET} {msg}")


def warn(msg: str) -> None:
    print(f"{C.YELLOW}[警告]{C.RESET} {msg}")


def fail(msg: str) -> None:
    print(f"{C.RED}[失败]{C.RESET} {msg}")


def die(msg: str, code: int = 1) -> None:
    fail(msg)
    sys.exit(code)


# ---------------------------------------------------------------------------
# 命令执行
# ---------------------------------------------------------------------------
def run(cmd: list[str], cwd: Optional[Path] = None, check: bool = True,
        capture: bool = False) -> subprocess.CompletedProcess:
    """执行命令，统一处理错误输出。"""
    printable = " ".join(str(c) for c in cmd)
    info(f"执行: {printable}")
    try:
        result = subprocess.run(
            cmd,
            cwd=str(cwd or PROJECT_ROOT),
            check=False,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=capture,
        )
    except FileNotFoundError:
        die(f"找不到命令: {cmd[0]}，请确认已安装并加入 PATH")

    if check and result.returncode != 0:
        if capture and result.stderr:
            fail(result.stderr.strip())
        die(f"命令执行失败（退出码 {result.returncode}）: {printable}")
    return result


def git(*args: str, check: bool = True, capture: bool = True) -> str:
    result = run(["git", *args], check=check, capture=capture)
    return (result.stdout or "").strip() if capture else ""


# ---------------------------------------------------------------------------
# 环境检查
# ---------------------------------------------------------------------------
def resolve_tool(name: str) -> str:
    """在 PATH 中查找命令；找不到时回退到 TOOL_CANDIDATES 里的常见路径。"""
    found = shutil.which(name)
    if found:
        return found
    for candidate in TOOL_CANDIDATES.get(name, []):
        if candidate.exists():
            return str(candidate)
    die(
        f"未找到 {name}，请先安装并加入 PATH"
        + (f"\n  已尝试: {[str(c) for c in TOOL_CANDIDATES.get(name, [])]}" if TOOL_CANDIDATES.get(name) else "")
    )
    return ""  # 不会执行


def check_tools() -> dict[str, str]:
    info("检查构建工具...")
    tools = {}
    for tool, args in (("git", ["--version"]), ("mvn", ["-v"]), ("jpackage", ["--version"])):
        path = resolve_tool(tool)
        try:
            subprocess.run([path, *args], capture_output=True, check=False, timeout=120)
        except OSError as e:
            die(f"{tool} 无法执行: {e}")
        tools[tool] = path
    ok("工具检查通过（git / mvn / jpackage）")
    for k, v in tools.items():
        print(f"    {k}: {v}")
    return tools


def check_wix() -> None:
    """jpackage 打 .exe 需要 WiX Toolset。"""
    candidates = [
        Path(r"C:\Program Files (x86)\WiX Toolset v3.14\bin\candle.exe"),
        Path(r"C:\Program Files (x86)\WiX Toolset v3.11\bin\candle.exe"),
        Path(r"C:\Program Files\WiX Toolset v3.14\bin\candle.exe"),
    ]
    if any(p.exists() for p in candidates):
        ok("检测到 WiX Toolset")
        return
    if shutil.which("candle"):
        ok("检测到 WiX Toolset（PATH）")
        return
    die(
        "未检测到 WiX Toolset，jpackage 无法生成 .exe 安装包。\n"
        "  请安装: https://github.com/wixtoolset/wix3/releases (选择 wix314.exe)\n"
        "  或改用 --type app-image 生成免安装目录（脚本已提供 --app-image 参数）"
    )


def check_clean_worktree() -> None:
    info("检查 Git 工作区...")
    status = git("status", "--porcelain")
    if status:
        warn("工作区存在未提交的改动：")
        for line in status.splitlines()[:20]:
            print(f"    {line}")
        answer = input("是否继续？未提交的改动会被一并提交 (y/N): ").strip().lower()
        if answer != "y":
            die("已取消", 0)
    ok("工作区检查完成")


def check_tag_not_exists(tag: str) -> None:
    if git("tag", "-l", tag):
        die(f"Tag {tag} 已存在，请换一个版本号")
    ok(f"Tag {tag} 可用")


def check_up_to_date() -> None:
    """确保本地分支与远端同步，避免推送阶段才失败。

    远端领先时直接退出：此时打 tag 会让版本落后于远端代码，
    必须先 pull/rebase 再发布。
    """
    info("检查与远端仓库的同步状态...")
    branch = git("rev-parse", "--abbrev-ref", "HEAD") or "main"
    if branch == "HEAD":
        die("当前处于 detached HEAD 状态，请先切回分支再发布")

    fetch = run(["git", "fetch", "origin", branch], check=False, capture=True)
    if fetch.returncode != 0:
        warn("无法连接远端仓库，跳过同步检查")
        return

    counts = git("rev-list", "--left-right", "--count", f"HEAD...origin/{branch}")
    try:
        ahead, behind = (int(x) for x in counts.split())
    except ValueError:
        warn("无法解析同步状态，跳过检查")
        return

    if behind > 0:
        die(
            f"本地分支落后远端 {behind} 个提交，请先同步后再发布：\n"
            f"    git pull --rebase origin {branch}\n"
            f"  （远端可能有你在网页上做的改动，例如删除文件/修改 README）"
        )
    if ahead > 0:
        info(f"本地领先远端 {ahead} 个提交，将一并推送")
    ok(f"同步状态正常（领先 {ahead}，落后 {behind}）")


def check_remote_tag_not_exists(tag: str) -> None:
    """检查远端是否已存在同名 tag（防止重复发布）。"""
    result = run(["git", "ls-remote", "--tags", "origin", f"refs/tags/{tag}"],
                 check=False, capture=True)
    if result.returncode != 0:
        warn("无法查询远端 tag（网络问题），将在推送阶段校验")
        return
    if (result.stdout or "").strip():
        die(f"远端已存在 tag {tag}，请换一个版本号，或先删除远端 tag：\n"
            f"    git push origin :refs/tags/{tag}")
    ok(f"远端 tag {tag} 可用")


# ---------------------------------------------------------------------------
# 版本号同步
# ---------------------------------------------------------------------------
def update_pom_version(version: str) -> None:
    """把 pom.xml 里的 <version>3.x.x</version> 改成新版本号。"""
    info(f"同步版本号到 pom.xml -> {version}")
    content = POM_FILE.read_text(encoding="utf-8")

    # 只替换 <project> 直接子级版本（紧跟 artifactId 之后的那一个）
    pattern = re.compile(
        r"(<artifactId>simplemc-launcher</artifactId>\s*\n\s*)<version>[^<]+</version>",
        re.MULTILINE,
    )
    new_content, count = pattern.subn(rf"\g<1><version>{version}</version>", content, count=1)

    if count == 0:
        # 兜底：替换第一个出现的 <version>x.y.z</version>
        new_content, count = re.subn(
            r"<version>\d+\.\d+\.\d+</version>",
            f"<version>{version}</version>",
            content,
            count=1,
        )
    if count == 0:
        die("无法在 pom.xml 中定位版本号")

    POM_FILE.write_text(new_content, encoding="utf-8")
    ok(f"pom.xml 已更新为 {version}")


# ---------------------------------------------------------------------------
# 构建与打包
# ---------------------------------------------------------------------------
def build_jar(mvn: str = "mvn") -> Path:
    info("开始 Maven 构建...")
    run([mvn, "-q", "clean", "package", "-DskipTests"])
    if not TARGET_DIR.is_dir():
        die("构建失败：未生成 target 目录")

    jars = [
        p for p in TARGET_DIR.glob("*.jar")
        if not re.search(r"(sources|javadoc)", p.name) and not p.name.startswith("original-")
    ]
    if not jars:
        die("构建失败：target 目录下没有可用的 jar")
    # 取体积最大的那个（shade 产物）
    jar = max(jars, key=lambda p: p.stat().st_size)
    ok(f"构建完成: {jar.name} ({jar.stat().st_size / 1024 / 1024:.1f} MB)")
    return jar


def package_exe(jar: Path, version: str, app_image: bool = False,
                jpackage: str = "jpackage") -> Path:
    """用 jpackage 生成 .exe 安装包（或 app-image 免安装目录）。"""
    info("开始 jpackage 打包...")

    if JPACKAGE_INPUT.exists():
        shutil.rmtree(JPACKAGE_INPUT)
    JPACKAGE_INPUT.mkdir(parents=True)

    shutil.copy2(jar, JPACKAGE_INPUT / jar.name)

    out_dir = TARGET_DIR / "output"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    cmd = [
        jpackage,
        "--type", "app-image" if app_image else "exe",
        "--name", APP_NAME,
        "--app-version", version,
        "--input", str(JPACKAGE_INPUT),
        "--main-jar", jar.name,
        "--main-class", MAIN_CLASS,
        "--dest", str(out_dir),
        "--java-options", "-Dfile.encoding=UTF-8",
    ]
    if not app_image:
        cmd += ["--win-shortcut", "--win-menu", "--win-dir-chooser"]

    run(cmd)

    if app_image:
        exe = out_dir / APP_NAME / f"{APP_NAME}.exe"
    else:
        exes = list(out_dir.glob("*.exe"))
        if not exes:
            die("jpackage 执行完成但未生成 .exe 文件")
        exe = exes[0]
    if not exe.exists():
        die(f"打包失败：未找到 {exe}")
    ok(f"打包完成: {exe} ({exe.stat().st_size / 1024 / 1024:.1f} MB)")
    return exe


# ---------------------------------------------------------------------------
# Git 提交与推送
# ---------------------------------------------------------------------------
def commit_and_tag(version: str, tag: str) -> None:
    info("提交版本号变更并打 tag...")
    run(["git", "add", "-A"])
    result = run(["git", "commit", "-m", f"chore: release v{version}"], check=False, capture=True)
    if result.returncode != 0 and "nothing to commit" not in (result.stdout or ""):
        warn("没有需要提交的变更，跳过提交")

    run(["git", "tag", "-a", tag, "-m", f"Release v{version}"])
    ok(f"已创建 tag: {tag}")


def push(tag: str) -> None:
    info("推送到 GitHub...")
    branch = git("rev-parse", "--abbrev-ref", "HEAD") or "main"

    result = run(["git", "push", "origin", branch], check=False, capture=True)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        if "rejected" in stderr or "fetch first" in stderr or "non-fast-forward" in stderr:
            die(
                "推送被拒绝：远端有你本地没有的提交。\n"
                f"  请执行以下命令同步后重试：\n"
                f"    git pull --rebase origin {branch}\n"
                f"    git push origin {branch}\n"
                f"    git push origin {tag}\n"
                f"  （本地 tag {tag} 已创建，重新同步后可直接推送）"
            )
        die(f"推送分支失败:\n{stderr}")

    result = run(["git", "push", "origin", tag], check=False, capture=True)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        if "already exists" in stderr:
            die(
                f"远端已存在 tag {tag}。若确认要覆盖：\n"
                f"    git push --force origin {tag}"
            )
        die(f"推送 tag 失败:\n{stderr}")

    ok(f"已推送分支 {branch} 与 tag {tag}")
    info("GitHub Actions 将被触发，自动构建并上传 exe 到 Release")


# ---------------------------------------------------------------------------
# GitHub Release API（不走 CI 时的直传方案）
# ---------------------------------------------------------------------------
def _github_token() -> Optional[str]:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token
    token_file = PROJECT_ROOT / ".github_token"
    if token_file.exists():
        return token_file.read_text(encoding="utf-8").strip() or None
    return None


def _api(method: str, url: str, token: str, payload: Optional[dict] = None) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        die(f"GitHub API 请求失败 {e.code}: {detail}")


def upload_release(version: str, tag: str, exe: Path, draft: bool = False) -> None:
    """通过 GitHub API 直接创建 Release 并上传 exe。"""
    token = _github_token()
    if not token:
        die(
            "未找到 GitHub Token。请设置环境变量 GITHUB_TOKEN，\n"
            "  或在项目根目录创建 .github_token 文件写入 Personal Access Token（需 repo 权限）"
        )

    info("通过 GitHub API 创建 Release...")
    api_base = f"https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}"

    # 如果 Release 已存在，先取回
    try:
        req = urllib.request.Request(f"{api_base}/releases/tags/{tag}")
        req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=60) as resp:
            release = json.loads(resp.read().decode("utf-8"))
        warn(f"Release {tag} 已存在，将追加资产")
    except urllib.error.HTTPError:
        release = _api("POST", f"{api_base}/releases", token, {
            "tag_name": tag,
            "name": f"v{version}",
            "body": f"Simple MC Launcher v{version}\n\n自动构建发布。",
            "draft": draft,
            "prerelease": False,
        })
    ok(f"Release 就绪: {release.get('html_url', tag)}")

    # 上传资产
    upload_url = release["upload_url"].split("{")[0]
    info(f"上传 {exe.name} ...")
    asset_url = f"{upload_url}?name={urllib.parse.quote(exe.name)}&label={urllib.parse.quote(exe.name)}"
    data = exe.read_bytes()
    req = urllib.request.Request(asset_url, data=data, method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/octet-stream")
    req.add_header("Content-Length", str(len(data)))
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            asset = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        die(f"上传资产失败 {e.code}: {e.read().decode('utf-8', 'replace')}")

    ok(f"上传完成: {asset.get('browser_download_url', exe.name)}")


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def suggest_next_version() -> str:
    """基于最新 tag 推荐下一个补丁版本号。"""
    latest = git("describe", "--tags", "--abbrev=0", check=False).lstrip("vV")
    parts = latest.split(".")
    if len(parts) == 3 and all(p.isdigit() for p in parts):
        return f"{parts[0]}.{parts[1]}.{int(parts[2]) + 1}"
    return "4.0.2"


def ask_version() -> str:
    suggestion = suggest_next_version()
    while True:
        raw = input(f"请输入版本号（回车使用 {suggestion}，如 4.0.2）: ").strip().lstrip("vV")
        if not raw:
            raw = suggestion
        if VERSION_RE.match(raw):
            return raw
        fail("版本号格式错误，应为 x.y.z（例如 4.0.2）")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Simple MC Launcher 一键打包发布",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("version", nargs="?", help="版本号，如 4.0.2；省略则交互式输入")
    parser.add_argument("--no-upload", action="store_true", help="只打包，不提交/推送/上传")
    parser.add_argument("--no-push", action="store_true", help="打包并本地提交，但不推送")
    parser.add_argument("--api", action="store_true",
                        help="用 GitHub API 直接创建 Release 并上传 exe（不走 Actions）")
    parser.add_argument("--draft", action="store_true", help="--api 模式下创建为草稿 Release")
    parser.add_argument("--app-image", action="store_true",
                        help="生成免安装目录而非 .exe 安装包（无需 WiX）")
    args = parser.parse_args()

    version = (args.version or "").strip().lstrip("vV")
    if not version:
        version = ask_version()
    elif not VERSION_RE.match(version):
        die(f"版本号格式错误: {version}（应为 x.y.z）")

    tag = f"v{version}"
    print()
    print(f"{C.BOLD}===== Simple MC Launcher 发布流程 ====={C.RESET}")
    print(f"  版本号 : {version}")
    print(f"  Tag    : {tag}")
    print(f"  模式   : {'仅打包' if args.no_upload else ('API 直传' if args.api else '推送 tag 触发 CI')}")
    print()

    check_tools_result = check_tools()
    check_tag_not_exists(tag)
    if not args.no_upload:
        check_up_to_date()
        check_remote_tag_not_exists(tag)
        check_clean_worktree()

    update_pom_version(version)
    jar = build_jar(check_tools_result["mvn"])

    if not args.app_image:
        check_wix()
    exe = package_exe(jar, version, app_image=args.app_image,
                      jpackage=check_tools_result["jpackage"])

    if args.no_upload:
        ok(f"打包完成（未上传）: {exe}")
        return

    commit_and_tag(version, tag)

    if args.api:
        upload_release(version, tag, exe, draft=args.draft)
        if not args.no_push:
            push(tag)
    elif args.no_push:
        warn("已跳过推送（--no-push）")
    else:
        push(tag)

    print()
    ok(f"发布完成！版本 v{version}")
    print(f"  Release: https://github.com/{GITHUB_OWNER}/{GITHUB_REPO}/releases/tag/{tag}")
    print(f"  产物   : {exe}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
        die("用户中断", 130)
