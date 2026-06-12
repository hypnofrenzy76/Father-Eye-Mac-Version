#!/usr/bin/env python3
"""Generate the Father Eye app icon: a nazar (evil eye) amulet rendered in a
blackscale (dark monochrome) palette.

Pure-stdlib PNG writer (zlib + struct); Pillow is not installable on the
build Mac's system Python. Output is a 1024x1024 RGBA master PNG. The .icns
is produced from it by sips + iconutil (see gen_icon.sh in this directory).

Band layout (classic nazar, dark palette, radii in master pixels):
  outer disc   r 500  charcoal #16161b -> #08080b radial falloff
  middle ring  r 348  dark slate #2e2e35
  sclera ring  r 238  light gray #9a9aa2 (the traditional white, darkened)
  iris         r 152  deep charcoal #1d1d23
  pupil        r  72  black #000000
Plus a thin outer rim line, a soft top-light, and a specular highlight.
"""
import math
import struct
import sys
import zlib

SIZE = 1024
CX = CY = SIZE / 2.0

# (radius, (r, g, b)) outermost first. Edges are blended over BLUR px.
BANDS = [
    (500.0, (22, 22, 27)),
    (348.0, (46, 46, 53)),
    (238.0, (154, 154, 162)),
    (152.0, (29, 29, 35)),
    (72.0, (0, 0, 0)),
]
BLUR = 2.0
RIM_COLOR = (62, 62, 70)
RIM_WIDTH = 6.0


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3.0 - 2.0 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def pixel(px, py):
    dx = px - CX
    dy = py - CY
    d = math.hypot(dx, dy)
    outer_r = BANDS[0][0]

    # Alpha: opaque inside the amulet, anti-aliased edge.
    alpha = 1.0 - smoothstep(outer_r - BLUR, outer_r + BLUR, d)
    if alpha <= 0.0:
        return (0, 0, 0, 0)

    # Base colour: walk bands inward, blending at each boundary.
    r, g, b = BANDS[0][1]
    for radius, col in BANDS[1:]:
        t = 1.0 - smoothstep(radius - BLUR, radius + BLUR, d)
        r = lerp(r, col[0], t)
        g = lerp(g, col[1], t)
        b = lerp(b, col[2], t)

    # Radial falloff on the outer disc: darker toward the edge.
    if d > BANDS[1][0]:
        fall = smoothstep(BANDS[1][0], outer_r, d)
        r = lerp(r, 8, fall * 0.75)
        g = lerp(g, 8, fall * 0.75)
        b = lerp(b, 11, fall * 0.75)

    # Thin rim line at the outer edge for definition against dark docks.
    rim = 1.0 - smoothstep(RIM_WIDTH, RIM_WIDTH + BLUR,
                           abs(d - (outer_r - RIM_WIDTH)))
    r = lerp(r, RIM_COLOR[0], rim * 0.8)
    g = lerp(g, RIM_COLOR[1], rim * 0.8)
    b = lerp(b, RIM_COLOR[2], rim * 0.8)

    # Soft top-light: brighten the upper hemisphere slightly.
    top = max(0.0, -dy / outer_r)
    gain = 1.0 + 0.18 * top
    r, g, b = r * gain, g * gain, b * gain

    # Specular highlight: soft ellipse upper-left over iris/pupil.
    hx, hy = CX - 110.0, CY - 130.0
    hd = math.hypot((px - hx) / 1.4, py - hy)
    spec = (1.0 - smoothstep(20.0, 95.0, hd)) * 0.55
    r = lerp(r, 210, spec)
    g = lerp(g, 210, spec)
    b = lerp(b, 218, spec)

    return (int(min(255, r)), int(min(255, g)), int(min(255, b)),
            int(round(alpha * 255)))


def write_png(path):
    raw = bytearray()
    for y in range(SIZE):
        raw.append(0)  # filter type 0 per scanline
        for x in range(SIZE):
            raw.extend(pixel(x + 0.5, y + 0.5))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "fathereye-1024.png"
    write_png(out)
    print("wrote", out)
