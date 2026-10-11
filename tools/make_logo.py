"""MinePeak's logo, as PEAKCRAFT's: the icon (a block mountain shaped like an A, a snow cap, grass legs, a red flag on
its summit) and under it the name, MINEPEAK, in 3D block letters, the A of PEAK a little mountain of its own and
PEAK's letters snowy on top, on a sky. The block textures are drawn here (16x16 stone, snow, grass, cloth): nothing of
Minecraft's or PEAK's, so it can ship in the mod. Its layout and 3D look follow PEAKCRAFT's tools/make_logo.py.

    python tools/make_logo.py [out_dir]      ->  icon.png (512x512: the icon over the name), logo.png (the name)
"""
import os
import random
import sys

from PIL import Image

CELL = 16          # one block = 16 texture pixels
DEPTH = 9          # 3D extrusion, in pixels
OUTLINE = (24, 22, 24, 255)

LETTERS = {
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "I": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"],
    "N": ["#...#", "##..#", "#.#.#", "#.#.#", "#..##", "#...#", "#...#"],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "P": ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
}

# The A of PEAK: a stepped block mountain, 9 wide, rising 2 rows above the letters; a hollow and two legs make it an A.
MOUNTAIN = [
    "....s....",
    "...sss...",
    "...sss...",
    "..stsss..",
    "..tt.tt..",
    ".ttttttt.",
    ".ttttttt.",
    "ggg...ggg",
    "ggg...ggg",
]
MOUNTAIN_RISE = 2


# ---- the block textures, drawn here ----

def noise_tex(base, spread, seed, specks=()):
    """A 16x16 block face: base colour, each pixel varied by up to spread, with clumps of speck colours."""
    rnd = random.Random(seed)
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            d = rnd.randint(-spread, spread)
            px[x, y] = tuple(max(0, min(255, c + d)) for c in base) + (255,)
    for colour, count, size in specks:
        for _ in range(count):
            cx, cy = rnd.randrange(16), rnd.randrange(16)
            for _ in range(size):
                px[cx % 16, cy % 16] = colour + (255,)
                cx += rnd.choice((-1, 0, 1)); cy += rnd.choice((-1, 0, 1))
    return im


def textures():
    stone = noise_tex((124, 124, 126), 6, 1, [((96, 96, 100), 9, 4), ((150, 150, 152), 6, 3)])
    snow = noise_tex((240, 244, 248), 4, 2, [((214, 226, 238), 10, 3)])
    dirt = noise_tex((134, 94, 62), 10, 3, [((104, 70, 44), 10, 3), ((160, 116, 80), 6, 2)])
    grass = dirt.copy()
    gp = grass.load()
    rnd = random.Random(4)
    greens = [(112, 172, 70), (96, 156, 58), (128, 186, 84)]
    for x in range(16):
        h = 3 + rnd.randint(0, 2) + (rnd.random() < 0.25) * 2   # grass hanging over the dirt, ragged
        for y in range(h):
            gp[x, y] = rnd.choice(greens) + (255,)
    wool = noise_tex((196, 44, 44), 8, 5, [((168, 32, 34), 12, 3), ((220, 70, 64), 6, 2)])
    return {"stone": stone, "snow": snow, "grass": grass, "dirt": dirt, "wool": wool}


def shade(im, k):
    px = im.load()
    out = im.copy()
    o = out.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            o[x, y] = (min(255, int(r * k)), min(255, int(g * k)), min(255, int(b * k)), a)
    return out


def build(blocks, word="MINEPEAK", mountain_at=6, gap_after=3, snowy_from=4):
    # Lay out cells: (cx, cy) -> block name, cy=0 is the letters' top row.
    cells, peak_cells = {}, set()
    cx = 0
    summit = None
    for i, ch in enumerate(word):
        if i == mountain_at:
            for ry, row in enumerate(MOUNTAIN):
                for rx, c in enumerate(row):
                    if c != ".":
                        cells[(cx + rx, ry - MOUNTAIN_RISE)] = {"s": "snow", "t": "stone", "g": "grass"}[c]
            summit = (cx + 4, -MOUNTAIN_RISE)
            cx += len(MOUNTAIN[0]) + 1
        else:
            for ry, row in enumerate(LETTERS[ch]):
                for rx, c in enumerate(row):
                    if c == "#":
                        cells[(cx + rx, ry)] = "stone"
                        if i >= snowy_from:
                            peak_cells.add((cx + rx, ry))
            cx += len(LETTERS[ch][0]) + 1
        if i == gap_after:
            cx += 1   # a little gap between MINE and PEAK

    min_cy = min(c[1] for c in cells)
    flag_h = 38   # pole height above the summit, pixels
    pad = 6
    top = pad + flag_h
    W = cx * CELL + DEPTH + 2 * pad
    H = top + (max(c[1] for c in cells) - min_cy + 1) * CELL + DEPTH + pad

    def pos(c):
        return pad + c[0] * CELL, top + (c[1] - min_cy) * CELL

    face = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for c, name in cells.items():
        face.alpha_composite(blocks[name], pos(c))

    rnd = random.Random(7)
    fp = face.load()
    snow = blocks["snow"].load()
    # Snow on every top edge of PEAK's letters, with a few drips
    for (x0c, y0c) in peak_cells:
        if (x0c, y0c - 1) in cells:
            continue
        x0, y0 = pos((x0c, y0c))
        for x in range(CELL):
            depth = 4 + (rnd.random() < 0.35) * rnd.randint(1, 5)
            for y in range(depth):
                fp[x0 + x, y0 + y] = snow[x, y]

    # Summit flag: a wooden pole and a red pennant
    sx, sy = pos(summit)
    pole_x = sx + CELL // 2 - 1
    for y in range(sy - flag_h, sy):
        for x in (pole_x, pole_x + 1):
            fp[x, y] = (110, 82, 45, 255) if x == pole_x else (140, 106, 60, 255)
    wool = blocks["wool"].load()
    for y in range(15):                       # a pennant: widest at the pole's middle, pointed at the tip
        width = max(1, 22 - abs(7 - y) * 3)
        for x in range(width):
            r, gg, b, a = wool[x % 16, y % 16]
            fp[pole_x + 2 + x, sy - flag_h + 1 + y] = (r, gg, b, 255)

    # 3D: the faces pushed back down-right, darker the further back
    alpha = face.getchannel("A")
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for k in range(DEPTH, 0, -1):
        dark = shade(face, 0.35 + 0.25 * (DEPTH - k) / DEPTH)
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        layer.paste(dark, (k // 2, k), alpha)
        out.alpha_composite(layer)
    # lit top edges on the face
    lit = face.copy()
    lp = lit.load()
    ap = alpha.load()
    for y in range(1, H):
        for x in range(W):
            if ap[x, y] and not ap[x, y - 1]:
                r, gg, b, a = lp[x, y]
                lp[x, y] = (min(255, int(r * 1.3)), min(255, int(gg * 1.3)), min(255, int(b * 1.3)), a)
    out.alpha_composite(lit)

    # Outline round the whole silhouette
    mask = out.getchannel("A").point(lambda v: 255 if v > 0 else 0)
    m = mask.load()
    res = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    rp = res.load()
    for y in range(H):
        for x in range(W):
            if m[x, y]:
                continue
            near = False
            for dy in (-2, -1, 0, 1, 2):
                for dx in (-2, -1, 0, 1, 2):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < W and 0 <= yy < H and m[xx, yy] and abs(dx) + abs(dy) <= 3:
                        near = True
                        break
                if near:
                    break
            if near:
                rp[x, y] = OUTLINE
    res.alpha_composite(out)
    return res


def fit(im, w, h):
    """im scaled to fit w x h: doubled first (blocks stay crisp), then smoothly to size."""
    im = im.resize((im.width * 2, im.height * 2), Image.NEAREST)
    k = min(w / im.width, h / im.height)
    return im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)


def icon(blocks, size=512):
    """The square icon: the mountain on its own over the name, on a sky."""
    sky = Image.new("RGBA", (size, size))
    px = sky.load()
    for y in range(size):
        t = y / size
        c = (int(96 + 70 * t), int(150 + 60 * t), int(235 + 15 * t), 255)
        for x in range(size):
            px[x, y] = c
    mountain = fit(build(blocks, word="A", mountain_at=0, gap_after=-1, snowy_from=99), size * 0.66, size * 0.66)
    name = fit(build(blocks), size * 0.94, size * 0.22)
    gap = int(size * 0.035)
    y0 = (size - mountain.height - gap - name.height) // 2
    sky.alpha_composite(mountain, ((size - mountain.width) // 2, y0))
    sky.alpha_composite(name, ((size - name.width) // 2, y0 + mountain.height + gap))
    return sky


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    os.makedirs(out, exist_ok=True)
    blocks = textures()
    icon(blocks).save(os.path.join(out, "icon.png"))
    build(blocks).save(os.path.join(out, "logo.png"))
    print("icon.png and logo.png ->", os.path.abspath(out))


if __name__ == "__main__":
    main()
