"""Картинки для рамки «Полки»: свои, генеративные, под e-ink (чёрно-белые, крупные формы)."""
import math, os, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 758, 848
S = 2  # рисуем вдвое крупнее и уменьшаем — сглаживание
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def canvas(bg=255):
    im = Image.new("L", (W * S, H * S), bg)
    return im, ImageDraw.Draw(im)


def finish(im, name):
    im = im.resize((W, H), Image.LANCZOS)
    im.save(os.path.join(OUT, name + ".png"), optimize=True)


def noise1d(n, rnd, octaves=5, base=6):
    out = np.zeros(n)
    amp, freq, total = 1.0, base, 0
    for _ in range(octaves):
        pts = rnd.random(freq + 2)
        xs = np.linspace(0, freq, n)
        i = xs.astype(int)
        f = xs - i
        f = f * f * (3 - 2 * f)
        out += amp * (pts[i] * (1 - f) + pts[i + 1] * f)
        total += amp
        amp *= 0.5
        freq *= 2
    return out / total


def noise2d(w, h, rnd, octaves=5, base=4):
    out = np.zeros((h, w))
    amp, total = 1.0, 0
    for o in range(octaves):
        g = base * 2 ** o
        small = Image.fromarray((rnd.random((g + 1, g + 1)) * 255).astype(np.uint8))
        big = np.asarray(small.resize((w, h), Image.BICUBIC), dtype=float) / 255
        out += amp * big
        total += amp
        amp *= 0.5
    return out / total


# ---------- 1. Горы ----------
def mountains(seed):
    rnd = np.random.default_rng(seed)
    im, d = canvas()
    w, h = W * S, H * S
    # небо: редкие горизонтальные штрихи
    for y in range(0, int(h * 0.45), 14 * S):
        if rnd.random() < 0.5:
            x0 = rnd.integers(0, w)
            d.line([(x0, y), (x0 + rnd.integers(60, 300) * S, y)], fill=200, width=2 * S)
    # солнце или луна
    cx, cy, r = rnd.integers(w * 0.2, w * 0.8), rnd.integers(h * 0.12, h * 0.3), rnd.integers(50, 90) * S
    if rnd.random() < 0.5:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=0)
    else:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=0, width=6 * S)
    layers = rnd.integers(4, 7)
    for k in range(layers):
        t = k / (layers - 1)
        base = h * (0.38 + 0.5 * t)
        amp = h * (0.28 - 0.18 * t)
        n = noise1d(w, rnd, octaves=6, base=rnd.integers(3, 7))
        ys = base - (n - 0.5) * 2 * amp
        shade = int(205 - 200 * t)
        poly = [(0, h)] + [(x, ys[x]) for x in range(0, w, 2)] + [(w, h)]
        d.polygon(poly, fill=shade)
    # птицы
    for _ in range(rnd.integers(0, 5)):
        bx, by, s = rnd.integers(w * 0.1, w * 0.9), rnd.integers(h * 0.08, h * 0.3), rnd.integers(10, 20) * S
        d.arc([bx - s, by, bx, by + s], 200, 340, fill=0, width=3 * S)
        d.arc([bx, by, bx + s, by + s], 200, 340, fill=0, width=3 * S)
    return im


# ---------- 2. Звёздное небо ----------
def night(seed):
    rnd = np.random.default_rng(seed)
    im, d = canvas(0)
    w, h = W * S, H * S
    stars = []
    for _ in range(420):
        x, y = rnd.integers(0, w), rnd.integers(0, int(h * 0.8))
        r = rnd.choice([1, 1, 1, 2, 2, 3, 4]) * S
        stars.append((x, y, r))
        d.ellipse([x - r, y - r, x + r, y + r], fill=255)
    # созвездия: несколько ломаных из ярких звёзд
    for _ in range(rnd.integers(2, 4)):
        cx, cy = rnd.integers(w * 0.15, w * 0.85), rnd.integers(h * 0.1, h * 0.55)
        pts = [(cx + rnd.integers(-160, 160) * S, cy + rnd.integers(-120, 120) * S) for _ in range(rnd.integers(4, 7))]
        pts.sort()
        d.line(pts, fill=150, width=2 * S)
        for x, y in pts:
            d.ellipse([x - 6 * S, y - 6 * S, x + 6 * S, y + 6 * S], fill=255)
    # месяц
    mx, my, mr = rnd.integers(w * 0.2, w * 0.8), rnd.integers(h * 0.08, h * 0.25), 60 * S
    d.ellipse([mx - mr, my - mr, mx + mr, my + mr], fill=255)
    off = rnd.integers(20, 40) * S
    d.ellipse([mx - mr + off, my - mr - off // 2, mx + mr + off, my + mr - off // 2], fill=0)
    # холмы и ёлки
    n = noise1d(w, rnd, octaves=4, base=3)
    ys = h * 0.82 - (n - 0.5) * h * 0.12
    d.polygon([(0, h)] + [(x, ys[x]) for x in range(0, w, 2)] + [(w, h)], fill=90)
    for _ in range(rnd.integers(6, 14)):
        x = rnd.integers(0, w)
        base = ys[min(x, w - 1)]
        th = rnd.integers(50, 130) * S
        for k in range(4):
            tw = th * (0.5 - k * 0.1)
            d.polygon([(x - tw, base - k * th * 0.22), (x + tw, base - k * th * 0.22), (x, base - th - k * th * 0.05)], fill=0)
    return im


# ---------- 3. Лес фрактальных деревьев ----------
def forest(seed):
    rnd = random.Random(seed)
    im, d = canvas()
    w, h = W * S, H * S
    ground = h * 0.86
    d.rectangle([0, ground, w, h], fill=30)

    def branch(x, y, ang, ln, wd, depth):
        if depth == 0 or ln < 4 * S:
            if rnd.random() < 0.6:
                r = rnd.randint(3, 7) * S
                d.ellipse([x - r, y - r, x + r, y + r], fill=rnd.choice([0, 60, 110]))
            return
        x2, y2 = x + ln * math.cos(ang), y - ln * math.sin(ang)
        d.line([(x, y), (x2, y2)], fill=0, width=max(1, int(wd)))
        for da in (rnd.uniform(0.25, 0.55), -rnd.uniform(0.25, 0.55)):
            branch(x2, y2, ang + da, ln * rnd.uniform(0.66, 0.8), wd * 0.68, depth - 1)

    for k in range(rnd.randint(2, 4)):
        x = rnd.uniform(w * 0.12, w * 0.88)
        branch(x, ground, math.pi / 2 + rnd.uniform(-0.08, 0.08), rnd.uniform(110, 170) * S, rnd.uniform(14, 22) * S, rnd.randint(9, 10))
    # трава
    for x in range(0, w, 5 * S):
        hh = rnd.uniform(8, 30) * S
        d.line([(x, ground), (x + rnd.uniform(-8, 8) * S, ground - hh)], fill=0, width=2 * S)
    return im


# ---------- 4. Лабиринт ----------
def maze(seed):
    rnd = random.Random(seed)
    im, d = canvas()
    w, h = W * S, H * S
    cell = rnd.choice([34, 40, 46]) * S
    cols, rows = (w - 60 * S) // cell, (h - 60 * S) // cell
    ox, oy = (w - cols * cell) // 2, (h - rows * cell) // 2
    seen = [[False] * cols for _ in range(rows)]
    walls = {}
    stack = [(0, 0)]
    seen[0][0] = True
    while stack:
        r, c = stack[-1]
        nb = [(r + dr, c + dc) for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)) if 0 <= r + dr < rows and 0 <= c + dc < cols and not seen[r + dr][c + dc]]
        if not nb:
            stack.pop()
            continue
        nr, nc = rnd.choice(nb)
        walls[frozenset({(r, c), (nr, nc)})] = True
        seen[nr][nc] = True
        stack.append((nr, nc))
    lw = 5 * S
    for r in range(rows):
        for c in range(cols):
            x, y = ox + c * cell, oy + r * cell
            if c + 1 < cols and frozenset({(r, c), (r, c + 1)}) not in walls:
                d.line([(x + cell, y), (x + cell, y + cell)], fill=0, width=lw)
            if r + 1 < rows and frozenset({(r, c), (r + 1, c)}) not in walls:
                d.line([(x, y + cell), (x + cell, y + cell)], fill=0, width=lw)
    d.rectangle([ox, oy, ox + cols * cell, oy + rows * cell], outline=0, width=lw)
    # вход и выход
    d.line([(ox, oy + lw), (ox, oy + cell - lw)], fill=255, width=lw + 2)
    d.line([(ox + cols * cell, oy + rows * cell - cell + lw), (ox + cols * cell, oy + rows * cell - lw)], fill=255, width=lw + 2)
    r0 = cell // 4
    d.ellipse([ox + cell // 2 - r0, oy + cell // 2 - r0, ox + cell // 2 + r0, oy + cell // 2 + r0], fill=0)
    ex, ey = ox + cols * cell - cell // 2, oy + rows * cell - cell // 2
    d.rectangle([ex - r0, ey - r0, ex + r0, ey + r0], fill=0)
    return im


# ---------- 5. Топографическая карта ----------
def topo(seed):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    rnd = np.random.default_rng(seed)
    z = noise2d(W, H, rnd, octaves=5, base=rnd.integers(2, 4))
    fig = plt.figure(figsize=(W / 100, H / 100), dpi=200)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.axis("off")
    lv = np.linspace(z.min(), z.max(), rnd.integers(16, 26))
    ax.contour(z, levels=lv, colors="black", linewidths=[2.2 if i % 5 == 0 else 0.9 for i in range(len(lv))])
    ax.set_xlim(0, W)
    ax.set_ylim(H, 0)
    path = os.path.join(OUT, "_tmp.png")
    fig.savefig(path, dpi=200, facecolor="white")
    plt.close(fig)
    im = Image.open(path).convert("L").resize((W * S, H * S), Image.LANCZOS)
    os.remove(path)
    d = ImageDraw.Draw(im)
    # роза ветров
    cx, cy, r = W * S - 110 * S, 110 * S, 60 * S
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255, outline=0, width=3 * S)
    d.polygon([(cx, cy - r + 6 * S), (cx - 14 * S, cy), (cx + 14 * S, cy)], fill=0)
    d.polygon([(cx, cy + r - 6 * S), (cx - 14 * S, cy), (cx + 14 * S, cy)], outline=0)
    return im


# ---------- 6. Плитки Труше ----------
def truchet(seed):
    rnd = random.Random(seed)
    im, d = canvas()
    w, h = W * S, H * S
    t = rnd.choice([60, 76, 94]) * S
    lw = rnd.choice([6, 9, 12]) * S
    for y in range(-t, h + t, t):
        for x in range(-t, w + t, t):
            if rnd.random() < 0.5:
                d.arc([x - t // 2, y - t // 2, x + t // 2, y + t // 2], 0, 90, fill=0, width=lw)
                d.arc([x + t // 2, y + t // 2, x + 3 * t // 2, y + 3 * t // 2], 180, 270, fill=0, width=lw)
            else:
                d.arc([x + t // 2, y - t // 2, x + 3 * t // 2, y + t // 2], 90, 180, fill=0, width=lw)
                d.arc([x - t // 2, y + t // 2, x + t // 2, y + 3 * t // 2], 270, 360, fill=0, width=lw)
    return im


# ---------- 7. Ночной город ----------
def city(seed):
    rnd = random.Random(seed)
    im, d = canvas(235)
    w, h = W * S, H * S
    # луна и облака
    mx, my, mr = rnd.uniform(w * 0.2, w * 0.8), rnd.uniform(h * 0.1, h * 0.25), 70 * S
    d.ellipse([mx - mr, my - mr, mx + mr, my + mr], fill=255, outline=120, width=3 * S)
    for layer, (shade, top) in enumerate([(150, 0.35), (70, 0.48), (0, 0.6)]):
        x = -rnd.randint(0, 60) * S
        while x < w:
            bw = rnd.randint(50, 130) * S
            bh = rnd.uniform(h * (1 - top) * 0.4, h * (1 - top))
            y0 = h - bh
            d.rectangle([x, y0, x + bw, h], fill=shade)
            if rnd.random() < 0.3:
                d.polygon([(x, y0), (x + bw, y0), (x + bw // 2, y0 - rnd.randint(30, 80) * S)], fill=shade)
            if layer == 2:
                for wy in range(int(y0) + 20 * S, h - 20 * S, 26 * S):
                    for wx in range(x + 12 * S, x + bw - 14 * S, 22 * S):
                        if rnd.random() < 0.28:
                            d.rectangle([wx, wy, wx + 10 * S, wy + 14 * S], fill=255)
            x += bw + rnd.randint(4, 20) * S
    return im


# ---------- 8. Подсолнух (филлотаксис) ----------
def sunflower(seed):
    rnd = random.Random(seed)
    im, d = canvas()
    w, h = W * S, H * S
    cx, cy = w / 2, h / 2
    n = rnd.randint(700, 1100)
    golden = math.pi * (3 - math.sqrt(5))
    c = min(w, h) * 0.47 / math.sqrt(n)
    style = rnd.choice(["dots", "rings", "squares"])
    for i in range(n):
        r = c * math.sqrt(i)
        a = i * golden
        x, y = cx + r * math.cos(a), cy + r * math.sin(a)
        s = 2 * S + i / n * 9 * S
        if style == "dots":
            d.ellipse([x - s, y - s, x + s, y + s], fill=0)
        elif style == "rings":
            d.ellipse([x - s, y - s, x + s, y + s], outline=0, width=2 * S)
        else:
            d.rectangle([x - s * 0.8, y - s * 0.8, x + s * 0.8, y + s * 0.8], fill=0 if i % 3 else 120)
    return im


# ---------- 9. Поток (линии по полю направлений) ----------
def flow(seed):
    rnd = np.random.default_rng(seed)
    im, d = canvas()
    w, h = W * S, H * S
    gw, gh = W // 4, H // 4
    field = noise2d(gw, gh, rnd, octaves=3, base=2) * math.pi * 4
    for _ in range(900):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h)
        pts = [(x, y)]
        for _ in range(rnd.integers(40, 120)):
            gx, gy = int(x / w * (gw - 1)), int(y / h * (gh - 1))
            if not (0 <= gx < gw and 0 <= gy < gh):
                break
            a = field[gy, gx]
            x += math.cos(a) * 4 * S
            y += math.sin(a) * 4 * S
            pts.append((x, y))
        if len(pts) > 5:
            d.line(pts, fill=0, width=2 * S)
    return im


# ---------- 10. Круги-волны (интерференция) ----------
def ripples(seed):
    rnd = random.Random(seed)
    im, d = canvas()
    w, h = W * S, H * S
    centers = [(rnd.uniform(0, w), rnd.uniform(0, h)) for _ in range(rnd.randint(2, 3))]
    step = rnd.choice([16, 20, 24]) * S
    for (cx, cy) in centers:
        for r in range(step, int(math.hypot(w, h)), step):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=0, width=2 * S)
    return im


STYLES = [("gory", mountains), ("nebo", night), ("les", forest), ("labirint", maze), ("karta", topo),
          ("uzor", truchet), ("gorod", city), ("podsolnuh", sunflower), ("potok", flow), ("krugi", ripples)]

per = int(sys.argv[2]) if len(sys.argv) > 2 else 6
for name, fn in STYLES:
    for k in range(per):
        seed = 1000 + sum(map(ord, name)) + k * 37
        im = fn(seed)
        finish(im, f"{name}-{k + 1:02d}")
        print(name, k + 1, flush=True)
