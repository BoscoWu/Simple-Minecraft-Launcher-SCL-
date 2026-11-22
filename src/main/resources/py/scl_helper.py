"""Python 侧模块：版本过滤、愚人节判定等纯逻辑。

Java 通过子进程调用本脚本，输出 JSON 给 Java 解析。
选这些功能的原因是：不依赖任何第三方库，失败可完全降级到 Java 实现。

为降低进程启动开销，本脚本只用标准库，导入极简。
"""
import json
import sys

# 已知愚人节版本 ID（发布日期均为 4 月 1 日，作为 ID 兜底）
KNOWN_APRIL = frozenset((
    '15w14a', '1.rv-pre1', '3d shareware v1.34', '20w14infinite',
    '21w13a', '22w13oneblockatatime', '23w13a_or_b',
    '24w14potato', '25w14craftmine', '26w14a',
))

APRIL_KEYWORDS = ('shareware', 'oneblockatatime', 'craftmine', '_or_b', 'rv-pre')


def is_april_fools(vid, release_time):
    """判断是否为愚人节版本。

    :param vid: 版本 ID（原始大小写）
    :param release_time: ISO 8601 发布时间
    """
    # 1. 发布日期为 4 月 1 日（最可靠）
    if release_time and ('-04-01' in release_time or '/04/01' in release_time):
        return True

    if not vid:
        return False

    low = vid.lower()

    # 2. 已知版本 ID
    if low in KNOWN_APRIL:
        return True

    # 3. 关键词兜底
    for kw in APRIL_KEYWORDS:
        if kw in low:
            return True
    if low.endswith('potato'):
        return True

    # 4. 含非 ASCII（某些清单写成 20w14∞）
    for ch in vid:
        if ord(ch) > 127:
            return True
    return False


def classify_versions(versions):
    """把版本清单分成四组，返回 {组名: [id, ...]}。"""
    groups = {'release': [], 'snapshot': [], 'old': [], 'april': []}
    get = dict.get
    for v in versions:
        t = get(v, 'type') or ''
        vid = get(v, 'id') or ''
        if t == 'release':
            groups['release'].append(vid)
        elif t == 'snapshot':
            if is_april_fools(vid, get(v, 'releaseTime') or ''):
                groups['april'].append(vid)
            else:
                groups['snapshot'].append(vid)
        else:
            groups['old'].append(vid)
    return groups


def filter_loader_versions(versions, mc_version, loader):
    """按 Minecraft 版本过滤加载器版本，返回 (列表, 说明)。"""
    if not mc_version:
        return versions, 'no-mc-version'

    mc = mc_version.strip()
    if loader in ('fabric', 'quilt'):
        # Fabric/Quilt 的 loader 版本与 MC 版本无关
        return versions, 'loader-independent'

    if loader == 'neoforge':
        # NeoForge: MC 1.21.1 -> 21.1.x
        parts = mc.split('.')
        if len(parts) >= 2:
            minor = parts[1]
            patch = ''.join(c for c in parts[2] if c.isdigit()) if len(parts) >= 3 else '0'
            prefix = minor + '.' + patch + '.'
            return [v for v in versions if v.startswith(prefix)], 'prefix=' + prefix

    if loader == 'forge':
        # Forge: 完整版本号形如 1.21.1-52.1.21
        prefix = mc + '-'
        n = len(prefix)
        return [v[n:] for v in versions if v.startswith(prefix)], 'prefix=' + prefix

    return versions, 'unknown-loader'


def decode_webp(b64_data, size=48):
    """把 base64 的 WebP 解码并缩放成 PNG（base64 返回）。

    Java 的 JavaFX Image 不认 WebP，而 Modrinth 图标全是 .webp。
    这里借 Pillow 解码，解不出来就让 Java 保留占位图。

    :return: (ok, png_b64 或错误信息)
    """
    try:
        from PIL import Image
    except ImportError:
        return False, 'pillow-not-installed'
    try:
        import base64
        import io
        raw = base64.b64decode(b64_data)
        im = Image.open(io.BytesIO(raw))
        im = im.convert('RGBA')
        im.thumbnail((size, size), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, format='PNG')
        return True, base64.b64encode(buf.getvalue()).decode('ascii')
    except Exception as e:
        return False, '%s: %s' % (type(e).__name__, e)


def main():
    try:
        raw = sys.stdin.read()
        req = json.loads(raw) if raw.strip() else {}
    except Exception as e:
        sys.stdout.write(json.dumps({'ok': False, 'error': 'bad request: %s' % e}))
        return 1

    action = req.get('action', '')
    try:
        if action == 'classify':
            groups = classify_versions(req.get('versions') or [])
            sys.stdout.write(json.dumps({
                'ok': True,
                'groups': groups,
                'counts': {k: len(v) for k, v in groups.items()},
            }))
        elif action == 'filter_loader':
            result, note = filter_loader_versions(
                req.get('versions') or [],
                req.get('mcVersion') or '',
                req.get('loader') or '',
            )
            sys.stdout.write(json.dumps({'ok': True, 'versions': result, 'note': note}))
        elif action == 'decode_webp':
            ok, payload = decode_webp(
                req.get('data') or '',
                int(req.get('size') or 48),
            )
            if ok:
                sys.stdout.write(json.dumps({'ok': True, 'png': payload}))
            else:
                sys.stdout.write(json.dumps({'ok': False, 'error': payload}))
        elif action == 'ping':
            try:
                from PIL import Image  # noqa: F401
                has_pillow = True
            except ImportError:
                has_pillow = False
            sys.stdout.write(json.dumps({
                'ok': True,
                'version': sys.version.split()[0],
                'impl': 'python',
                'pillow': has_pillow,
            }))
        else:
            sys.stdout.write(json.dumps({'ok': False, 'error': 'unknown action: %s' % action}))
            return 1
    except Exception as e:
        sys.stdout.write(json.dumps({'ok': False, 'error': '%s: %s' % (type(e).__name__, e)}))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
