"""Generuje modely rekvizit pro efekty (app/src/main/assets/models/props/*.glb).

Spuštění:  python3 tools/make_props.py
Rozměry jsou v jednotkách hlavy Hoppy (hlava je široká ~0.32).
Každý model = jedna nebo víc barevných částí, materiál unlit (kreslený vzhled).
"""
import json, math, os, struct

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "props")


def lathe(profile, seg=16):
    """Rotační těleso kolem osy Y z profilu [(r, y), ...]."""
    v, n, idx = [], [], []
    for i, (r, y) in enumerate(profile):
        # normála z tečny profilu
        r0, y0 = profile[max(i - 1, 0)]; r1, y1 = profile[min(i + 1, len(profile) - 1)]
        tx, ty = r1 - r0, y1 - y0
        ln = math.hypot(tx, ty) or 1
        nr, ny = ty / ln, -tx / ln
        for s in range(seg + 1):
            a = 2 * math.pi * s / seg
            c, si = math.cos(a), math.sin(a)
            v.append((r * c, y, r * si)); n.append((nr * c, ny, nr * si))
    for i in range(len(profile) - 1):
        for s in range(seg):
            a = i * (seg + 1) + s; b = a + seg + 1
            idx += [a, a + 1, b, a + 1, b + 1, b]
    return v, n, idx


def sphere(r, sy=1.0, pinch=0.0, seg=14, rings=10):
    prof = []
    for k in range(rings + 1):
        t = math.pi * k / rings           # 0 = vršek
        rr = r * math.sin(t)
        if pinch:                          # kapka: vršek do špičky
            rr *= (1 - pinch * max(0, math.cos(t))) if t < math.pi / 2 else 1
        prof.append((max(rr, 1e-5), r * sy * math.cos(t)))
    return lathe(prof, seg)


def extrude(poly, depth):
    """Vytlačí 2D obrys (ve směru Z), střed vějířem (obrys musí být hvězdicový kolem 0,0)."""
    v, n, idx = [], [], []
    d = depth / 2
    for z, nz in ((d, 1), (-d, -1)):
        base = len(v)
        v.append((0, 0, z)); n.append((0, 0, nz))
        for x, y in poly:
            v.append((x, y, z)); n.append((0, 0, nz))
        m = len(poly)
        for i in range(m):
            a, b = base + 1 + i, base + 1 + (i + 1) % m
            idx += [base, a, b] if nz > 0 else [base, b, a]
    m = len(poly)
    for i in range(m):
        (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % m]
        ex, ey = x1 - x0, y1 - y0; ln = math.hypot(ex, ey) or 1
        nx, ny = ey / ln, -ex / ln
        b = len(v)
        for (x, y, z) in ((x0, y0, d), (x1, y1, d), (x1, y1, -d), (x0, y0, -d)):
            v.append((x, y, z)); n.append((nx, ny, 0))
        idx += [b, b + 2, b + 1, b, b + 3, b + 2]
    return v, n, idx


def transform(part, dx=0, dy=0, dz=0, rz=0):
    v, n, i = part
    c, s = math.cos(rz), math.sin(rz)
    v = [(x * c - y * s + dx, x * s + y * c + dy, z + dz) for x, y, z in v]
    n = [(x * c - y * s, x * s + y * c, z) for x, y, z in n]
    return v, n, i


def write_glb(name, parts):
    """parts = [((v, n, idx), (r, g, b, a)), ...]"""
    bin_ = bytearray(); views = []; accs = []; prims = []; mats = []
    def add(data, target):
        off = len(bin_); bin_.extend(data)
        while len(bin_) % 4: bin_.append(0)
        views.append({"buffer": 0, "byteOffset": off, "byteLength": len(data), "target": target})
        return len(views) - 1
    for (v, n, idx), col in parts:
        pv = add(b"".join(struct.pack("<3f", *p) for p in v), 34962)
        lo = [min(p[k] for p in v) for k in range(3)]; hi = [max(p[k] for p in v) for k in range(3)]
        accs.append({"bufferView": pv, "componentType": 5126, "count": len(v), "type": "VEC3", "min": lo, "max": hi})
        nv = add(b"".join(struct.pack("<3f", *p) for p in n), 34962)
        accs.append({"bufferView": nv, "componentType": 5126, "count": len(n), "type": "VEC3"})
        iv = add(b"".join(struct.pack("<H", i) for i in idx), 34963)
        accs.append({"bufferView": iv, "componentType": 5123, "count": len(idx), "type": "SCALAR"})
        mats.append({"pbrMetallicRoughness": {"baseColorFactor": list(col), "metallicFactor": 0, "roughnessFactor": 1},
                     "extensions": {"KHR_materials_unlit": {}}, "doubleSided": True,
                     **({"alphaMode": "BLEND"} if col[3] < 1 else {})})
        prims.append({"attributes": {"POSITION": len(accs) - 3, "NORMAL": len(accs) - 2},
                      "indices": len(accs) - 1, "material": len(mats) - 1})
    gl = {"asset": {"version": "2.0", "generator": "hoppy make_props.py"},
          "extensionsUsed": ["KHR_materials_unlit"],
          "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}],
          "meshes": [{"name": name, "primitives": prims}], "materials": mats,
          "accessors": accs, "bufferViews": views, "buffers": [{"byteLength": len(bin_)}]}
    js = json.dumps(gl, separators=(",", ":")).encode()
    while len(js) % 4: js += b" "
    total = 12 + 8 + len(js) + 8 + len(bin_)
    with open(os.path.join(OUT, name + ".glb"), "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
        f.write(struct.pack("<II", len(bin_), 0x004E4942)); f.write(bin_)
    print(name, len(bin_), "B")


def heart_poly(s, n=40):
    pts = []
    for k in range(n):
        t = 2 * math.pi * k / n
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((x * s / 32, (y + 2) * s / 32))
    return pts


def star_poly(r, ri, n=5):
    return [((r if k % 2 == 0 else ri) * math.sin(math.pi * k / n), (r if k % 2 == 0 else ri) * math.cos(math.pi * k / n))
            for k in range(2 * n)]


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    RED, PINK = (0.93, 0.12, 0.25, 1), (1.0, 0.45, 0.65, 1)
    write_glb("heart", [(extrude(heart_poly(0.07), 0.022), RED)])
    write_glb("heart_pink", [(extrude(heart_poly(0.05), 0.016), PINK)])
    write_glb("star", [(extrude(star_poly(0.03, 0.013), 0.012), (1.0, 0.85, 0.15, 1))])
    # mrkev: kužel špičkou dolů (-Y), nahoře natě
    carrot = lathe([(0.001, -0.17), (0.012, -0.12), (0.022, -0.05), (0.028, 0.0), (0.02, 0.012), (0.001, 0.014)], 14)
    leaves = [transform(lathe([(0.008, 0.0), (0.006, 0.03), (0.001, 0.07)], 6), dx=dx, dy=0.008, rz=rz)
              for dx, rz in ((-0.008, 0.35), (0.0, 0.0), (0.008, -0.35))]
    write_glb("carrot", [(carrot, (1.0, 0.5, 0.08, 1))] + [(l, (0.25, 0.7, 0.2, 1)) for l in leaves])
    write_glb("drop_blood", [(sphere(0.011, 1.25, 0.85), (0.75, 0.02, 0.05, 1))])
    write_glb("drop_sweat", [(sphere(0.016, 1.3, 0.85), (0.55, 0.85, 1.0, 0.85))])
    write_glb("blob_vomit", [(sphere(0.016, 0.85), (0.62, 0.55, 0.05, 1))])
    write_glb("crumb", [(sphere(0.007, 0.8, 0, 6, 4), (1.0, 0.55, 0.1, 1))])
    write_glb("puff", [(sphere(0.013, 1.0, 0, 8, 6), (0.92, 0.97, 1.0, 0.8))])
    # teploměr: tyčinka podél +Z (ven z pusy), červená baňka na konci
    stick = lathe([(0.0001, 0.0), (0.0055, 0.002), (0.0055, 0.12), (0.0001, 0.122)], 10)
    stick = ([(x, z, y) for x, y, z in stick[0]], [(x, z, y) for x, y, z in stick[1]], stick[2])
    bulb = sphere(0.009)
    bulb = ([(x, y, z + 0.004) for x, y, z in bulb[0]], bulb[1], bulb[2])
    write_glb("thermometer", [(stick, (0.95, 0.95, 0.97, 1)), (bulb, (0.85, 0.1, 0.12, 1))])
