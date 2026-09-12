#!/usr/bin/env python3
"""从仓库根目录的源图生成 Kelly 用到的全部图标。

建议源图 1024×1024 PNG（透明底最好）。256 也能用，但 Dock / 高分屏会糊。
默认会裁掉四周留白，让主体铺满，避免托盘里显得过小。

    python3 sync_icons.py
    python3 sync_icons.py --as-is    # 不裁边，原样使用
"""

from __future__ import annotations

import argparse
from collections import deque
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ICONS = ROOT / "src" / "main" / "resources" / "icons"
JPACKAGE = ROOT / "src" / "main" / "jpackage"
LINUX = JPACKAGE / "linux"

# jpackage / WiX：只放 16/32/48 的 32-bit BMP DIB，不要 PNG-in-ICO。
WIX_SIZES = (16, 32, 48)
ICONSET_SPECS = (
    (16, "icon_16x16.png"),
    (32, "icon_16x16@2x.png"),
    (32, "icon_32x32.png"),
    (64, "icon_32x32@2x.png"),
    (128, "icon_128x128.png"),
    (256, "icon_128x128@2x.png"),
    (256, "icon_256x256.png"),
    (512, "icon_256x256@2x.png"),
    (512, "icon_512x512.png"),
    (1024, "icon_512x512@2x.png"),
)


def main() -> int:
    parser = argparse.ArgumentParser(description="从 icon.png 生成各平台图标")
    parser.add_argument("--as-is", action="store_true", help="不裁切留白，按源图像素使用")
    args = parser.parse_args()

    normal = ROOT / "icon.png"
    alert = _alert_source()
    if not normal.is_file():
        print(f"找不到 {normal}", file=sys.stderr)
        return 1
    if alert is None:
        print("找不到 icon_alert.png 或 icon_alter.png", file=sys.stderr)
        return 1

    ICONS.mkdir(parents=True, exist_ok=True)
    JPACKAGE.mkdir(parents=True, exist_ok=True)
    LINUX.mkdir(parents=True, exist_ok=True)

    normal_px = knock_out_light_background(load_rgba(normal))
    alert_px = knock_out_light_background(load_rgba(alert))
    _warn_if_small(normal, normal_px)
    _warn_if_small(alert, alert_px)

    if not args.as_is:
        normal_px = fit_subject(normal_px)
        alert_px = fit_subject(alert_px)

    write_png(ICONS / "kelly.png", *normal_px)
    write_png(ICONS / "kelly-alert.png", *alert_px)
    write_png(ICONS / "tray.png", *normal_px)
    write_png(ICONS / "tray-alert.png", *alert_px)
    write_png(LINUX / "kelly.png", *normal_px)

    ico_bytes = build_bmp_ico(normal_px, WIX_SIZES)
    alert_ico = build_bmp_ico(alert_px, WIX_SIZES)
    (JPACKAGE / "kelly.ico").write_bytes(ico_bytes)
    (ICONS / "kelly.ico").write_bytes(ico_bytes)
    (ICONS / "tray.ico").write_bytes(ico_bytes)
    (ICONS / "tray-alert.ico").write_bytes(alert_ico)

    with tempfile.TemporaryDirectory(prefix="kelly-master-") as tmp:
        master = Path(tmp) / "kelly.png"
        write_png(master, *normal_px)
        write_icns(master, ICONS / "kelly.icns")

    print("已更新：")
    for path in (
        ICONS / "kelly.png",
        ICONS / "kelly-alert.png",
        ICONS / "kelly.ico",
        ICONS / "kelly.icns",
        ICONS / "tray.png",
        ICONS / "tray-alert.png",
        ICONS / "tray.ico",
        ICONS / "tray-alert.ico",
        JPACKAGE / "kelly.ico",
        LINUX / "kelly.png",
    ):
        print(f"  {path.relative_to(ROOT)}  ({path.stat().st_size} bytes)")
    return 0


def _warn_if_small(path: Path, px: tuple[int, int, bytes]) -> None:
    w, h, _ = px
    if min(w, h) < 512:
        print(
            f"提示：{path.name} 是 {w}×{h}，建议换成 1024×1024。"
            "本次会先裁掉留白再生成各尺寸，高分屏仍可能发糊。",
            file=sys.stderr,
        )


def _alert_source() -> Path | None:
    for name in ("icon_alert.png", "icon_alter.png"):
        path = ROOT / name
        if path.is_file():
            return path
    return None


def load_rgba(path: Path) -> tuple[int, int, bytes]:
    try:
        from PIL import Image

        im = Image.open(path).convert("RGBA")
        return im.width, im.height, im.tobytes()
    except ImportError:
        return decode_png_rgba(path.read_bytes())


def decode_png_rgba(data: bytes) -> tuple[int, int, bytes]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("不是 PNG")
    pos = 8
    width = height = bit_depth = color_type = None
    idat = bytearray()
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        ctype = data[pos + 4 : pos + 8]
        chunk = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"IEND":
            break
    if width is None or bit_depth != 8 or color_type not in (2, 6):
        raise ValueError("仅支持 8-bit RGB/RGBA PNG，可 pip install pillow 后重试")
    raw = zlib.decompress(bytes(idat))
    bpp = 4 if color_type == 6 else 3
    stride = width * bpp
    rows: list[bytes] = []
    i = 0
    prev = bytearray(stride)
    for _ in range(height):
        filt = raw[i]
        scan = bytearray(raw[i + 1 : i + 1 + stride])
        i += 1 + stride
        recon = _unfilter(filt, scan, prev, bpp)
        prev = recon
        if bpp == 3:
            rgba = bytearray(width * 4)
            for x in range(width):
                rgba[x * 4 : x * 4 + 3] = recon[x * 3 : x * 3 + 3]
                rgba[x * 4 + 3] = 255
            rows.append(bytes(rgba))
        else:
            rows.append(bytes(recon))
    return width, height, b"".join(rows)


def _unfilter(filt: int, scan: bytearray, prev: bytearray, bpp: int) -> bytearray:
    out = bytearray(len(scan))
    for i, b in enumerate(scan):
        a = out[i - bpp] if i >= bpp else 0
        up = prev[i]
        ul = prev[i - bpp] if i >= bpp else 0
        if filt == 0:
            out[i] = b
        elif filt == 1:
            out[i] = (b + a) & 255
        elif filt == 2:
            out[i] = (b + up) & 255
        elif filt == 3:
            out[i] = (b + ((a + up) // 2)) & 255
        elif filt == 4:
            out[i] = (b + _paeth(a, up, ul)) & 255
        else:
            raise ValueError(f"未知 PNG filter: {filt}")
    return out


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def write_png(path: Path, width: int, height: int, rgba: bytes) -> None:
    def chunk(tag: bytes, body: bytes) -> bytes:
        crc = zlib.crc32(tag + body) & 0xFFFFFFFF
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", crc)

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(rgba[y * stride : (y + 1) * stride])
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def knock_out_light_background(src: tuple[int, int, bytes], max_delta: int = 36) -> tuple[int, int, bytes]:
    """从边缘洪水填充，把近白画布打成透明，避免白底和白边渗进轮廓。"""
    w, h, data = src
    corners = (0, (w - 1) * 4, (h - 1) * w * 4, ((h - 1) * w + (w - 1)) * 4)
    seeds: list[tuple[int, int, int]] = []
    for i in corners:
        r, g, b, a = data[i : i + 4]
        if a >= 16 and r > 220 and g > 220 and b > 220:
            seeds.append((r, g, b))
    if len(seeds) < 4:
        return src
    cr = sum(s[0] for s in seeds) // 4
    cg = sum(s[1] for s in seeds) // 4
    cb = sum(s[2] for s in seeds) // 4
    px = bytearray(data)
    seen = bytearray(w * h)
    q: deque[tuple[int, int]] = deque()

    def is_bg(x: int, y: int) -> bool:
        i = (y * w + x) * 4
        r, g, b, a = px[i], px[i + 1], px[i + 2], px[i + 3]
        if a < 16:
            return True
        if r < 200 or g < 200 or b < 200:
            return False
        return abs(r - cr) + abs(g - cg) + abs(b - cb) <= max_delta

    def push(x: int, y: int) -> None:
        idx = y * w + x
        if seen[idx] or not is_bg(x, y):
            return
        seen[idx] = 1
        q.append((x, y))

    for x in range(w):
        push(x, 0)
        push(x, h - 1)
    for y in range(h):
        push(0, y)
        push(w - 1, y)

    clear = b"\x00\x00\x00\x00"
    while q:
        x, y = q.popleft()
        i = (y * w + x) * 4
        px[i : i + 4] = clear
        if x:
            push(x - 1, y)
        if x + 1 < w:
            push(x + 1, y)
        if y:
            push(x, y - 1)
        if y + 1 < h:
            push(x, y + 1)
    return w, h, bytes(px)


def opaque_bbox(w: int, h: int, rgba: bytes) -> tuple[int, int, int, int]:
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        row = y * w * 4
        for x in range(w):
            if rgba[row + x * 4 + 3] < 16:
                continue
            if x < minx:
                minx = x
            if y < miny:
                miny = y
            if x > maxx:
                maxx = x
            if y > maxy:
                maxy = y
    if maxx < 0:
        return 0, 0, w - 1, h - 1
    return minx, miny, maxx, maxy


def fit_subject(src: tuple[int, int, bytes], pad: float = 0.08) -> tuple[int, int, bytes]:
    """裁掉透明留白，主体放进正方形；空隙保持透明，不填白底。"""
    w, h, rgba = src
    x0, y0, x1, y1 = opaque_bbox(w, h, rgba)
    bw, bh = x1 - x0 + 1, y1 - y0 + 1
    if max(bw, bh) >= int(min(w, h) * 0.82):
        return src
    side = max(1, int(max(bw, bh) / max(0.2, 1 - 2 * pad)))
    cx = (x0 + x1) / 2
    cy = (y0 + y1) / 2
    left = int(round(cx - side / 2))
    top = int(round(cy - side / 2))
    cropped = bytearray(side * side * 4)
    for y in range(side):
        sy = top + y
        for x in range(side):
            sx = left + x
            if 0 <= sx < w and 0 <= sy < h:
                si = (sy * w + sx) * 4
                di = (y * side + x) * 4
                cropped[di : di + 4] = rgba[si : si + 4]
    return side, side, bytes(cropped)


def _sample_premul(pixels: bytes, sw: int, x: int, y: int) -> tuple[float, float, float, float]:
    i = (y * sw + x) * 4
    a = pixels[i + 3]
    if a == 0:
        return 0.0, 0.0, 0.0, 0.0
    s = a / 255.0
    return pixels[i] * s, pixels[i + 1] * s, pixels[i + 2] * s, float(a)


def scale_rgba(src: tuple[int, int, bytes], size: int) -> bytes:
    sw, sh, pixels = src
    if sw == size and sh == size:
        return pixels
    dst = bytearray(size * size * 4)
    for y in range(size):
        fy = (y + 0.5) * sh / size - 0.5
        y0 = int(fy)
        y1 = min(sh - 1, max(0, y0 + 1))
        y0 = max(0, min(sh - 1, y0))
        ty = 0.0 if y0 == y1 else fy - y0
        for x in range(size):
            fx = (x + 0.5) * sw / size - 0.5
            x0 = int(fx)
            x1 = min(sw - 1, max(0, x0 + 1))
            x0 = max(0, min(sw - 1, x0))
            tx = 0.0 if x0 == x1 else fx - x0
            c00 = _sample_premul(pixels, sw, x0, y0)
            c10 = _sample_premul(pixels, sw, x1, y0)
            c01 = _sample_premul(pixels, sw, x0, y1)
            c11 = _sample_premul(pixels, sw, x1, y1)
            di = (y * size + x) * 4
            ch = [0.0, 0.0, 0.0, 0.0]
            for k in range(4):
                a = c00[k] + (c10[k] - c00[k]) * tx
                b = c01[k] + (c11[k] - c01[k]) * tx
                ch[k] = a + (b - a) * ty
            alpha = max(0.0, min(255.0, ch[3]))
            if alpha < 0.5:
                continue
            inv = 255.0 / alpha
            dst[di] = max(0, min(255, int(ch[0] * inv + 0.5)))
            dst[di + 1] = max(0, min(255, int(ch[1] * inv + 0.5)))
            dst[di + 2] = max(0, min(255, int(ch[2] * inv + 0.5)))
            dst[di + 3] = int(alpha + 0.5)
    return bytes(dst)


def build_bmp_ico(src: tuple[int, int, bytes], sizes: tuple[int, ...]) -> bytes:
    images: list[bytes] = []
    for size in sizes:
        images.append(_dib(scale_rgba(src, size), size))
    count = len(images)
    offset = 6 + 16 * count
    out = bytearray(struct.pack("<HHH", 0, 1, count))
    for size, img in zip(sizes, images):
        out.extend(struct.pack("<BBBBHHII", size, size, 0, 0, 1, 32, len(img), offset))
        offset += len(img)
    for img in images:
        out.extend(img)
    return bytes(out)


def _dib(rgba: bytes, size: int) -> bytes:
    xor = bytearray(size * size * 4)
    for y in range(size):
        src_y = size - 1 - y
        for x in range(size):
            r, g, b, a = rgba[(src_y * size + x) * 4 : (src_y * size + x) * 4 + 4]
            i = (y * size + x) * 4
            xor[i : i + 4] = bytes((b, g, r, a))
    and_row = ((size + 31) // 32) * 4
    mask = bytes(and_row * size)
    header = struct.pack("<IIIHHIIIIII", 40, size, size * 2, 1, 32, 0, len(xor), 0, 0, 0, 0)
    return header + bytes(xor) + mask


def write_icns(src_png: Path, dest: Path) -> None:
    iconutil = shutil.which("iconutil")
    sips = shutil.which("sips")
    if not iconutil or not sips:
        print("未找到 sips/iconutil，跳过 kelly.icns（请在 macOS 上运行）", file=sys.stderr)
        return
    with tempfile.TemporaryDirectory(prefix="kelly-iconset-") as tmp:
        iconset = Path(tmp) / "kelly.iconset"
        iconset.mkdir()
        for size, name in ICONSET_SPECS:
            out = iconset / name
            subprocess.run(
                [sips, "-z", str(size), str(size), str(src_png), "--out", str(out)],
                check=True,
                capture_output=True,
            )
        subprocess.run([iconutil, "-c", "icns", str(iconset), "-o", str(dest)], check=True, capture_output=True)


if __name__ == "__main__":
    raise SystemExit(main())
