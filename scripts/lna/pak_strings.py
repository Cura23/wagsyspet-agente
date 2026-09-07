#!/usr/bin/env python3
"""Extrai strings de um .pak do Chrome (formato v5) e filtra por termos. Uso: pak_strings.py <pak> <regex>..."""
import re
import struct
import sys

path, patterns = sys.argv[1], [re.compile(p, re.I) for p in sys.argv[2:]]
data = open(path, "rb").read()
version, encoding = struct.unpack_from("<IB", data, 0)
assert version == 5, f"versao {version} nao suportada"
resource_count, alias_count = struct.unpack_from("<HH", data, 8)
pos = 12
entries = [struct.unpack_from("<HI", data, pos + i * 6) for i in range(resource_count + 1)]
enc = {1: "utf-8", 2: "utf-16-le"}.get(encoding, "utf-8")
hits = 0
for (rid, off), (_, nxt) in zip(entries, entries[1:]):
    raw = data[off:nxt]
    try:
        s = raw.decode(enc)
    except UnicodeDecodeError:
        continue
    if not s.isprintable() and "\n" not in s:
        continue
    if any(p.search(s) for p in patterns):
        hits += 1
        print(f"[{rid}] {s!r}")
print(f"-- {hits} strings (total {resource_count}, encoding {enc})", file=sys.stderr)
