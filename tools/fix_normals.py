"""Opraví nenormalizované / nulové normály v GLB (na místě, beze změny velikosti souboru).
Použití: python3 tools/fix_normals.py vstup.glb výstup.glb
"""
import json, struct, sys
import numpy as np

src, dst = sys.argv[1], sys.argv[2]
raw = bytearray(open(src, 'rb').read())
jl = struct.unpack('<I', raw[12:16])[0]
g = json.loads(raw[20:20 + jl])
bin_off = 20 + jl + 8
fixed = 0
for m in g['meshes']:
    for p in m['primitives']:
        ai = p['attributes'].get('NORMAL')
        if ai is None: continue
        a = g['accessors'][ai]; bv = g['bufferViews'][a['bufferView']]
        stride = bv.get('byteStride', 12)
        off = bin_off + bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        for k in range(a['count']):
            o = off + k * stride
            v = np.frombuffer(bytes(raw[o:o + 12]), np.float32).astype(np.float64)
            n = np.linalg.norm(v)
            if not np.isfinite(n) or abs(n - 1) > 0.0005:
                v = v / n if np.isfinite(n) and n > 1e-6 else np.array([0, 1.0, 0])
                raw[o:o + 12] = v.astype(np.float32).tobytes(); fixed += 1
open(dst, 'wb').write(raw)
print(src.split('/')[-1], 'opraveno normál:', fixed)
