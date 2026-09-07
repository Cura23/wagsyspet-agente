#!/usr/bin/env python3
"""Converte um dump `xwd` (ZPixmap 24/32 bpp TrueColor) em PNG, sem PIL. Uso: xwd2png.py in.xwd out.png"""
import struct
import sys
import zlib

src, dst = sys.argv[1], sys.argv[2]
data = open(src, "rb").read()
# Header XWD (25 x uint32 big-endian) + nome da janela + colormap + pixels
hdr = struct.unpack(">25I", data[:100])
(header_size, file_version, pixmap_format, pixmap_depth, width, height, xoffset, byte_order,
 bitmap_unit, bitmap_bit_order, bitmap_pad, bits_per_pixel, bytes_per_line, visual_class,
 red_mask, green_mask, blue_mask, bits_per_rgb, colormap_entries, ncolors, window_width,
 window_height, window_x, window_y, window_bdrwidth) = hdr
assert file_version == 7 and pixmap_format == 2, f"formato nao suportado: v{file_version} fmt{pixmap_format}"
assert bits_per_pixel in (24, 32), f"bpp {bits_per_pixel} nao suportado"
pixels_off = header_size + ncolors * 12
bpp = bits_per_pixel // 8


def shift_of(mask):
    s = 0
    while mask and not (mask & 1):
        mask >>= 1
        s += 1
    return s


rs, gs, bs = shift_of(red_mask), shift_of(green_mask), shift_of(blue_mask)
fmt = (">" if byte_order == 1 else "<") + ("I" if bpp == 4 else "")
rows = bytearray()
for y in range(height):
    line = data[pixels_off + y * bytes_per_line: pixels_off + y * bytes_per_line + width * bpp]
    out = bytearray(width * 3)
    if bpp == 4 and rs == 16 and gs == 8 and bs == 0:
        # caminho rapido: 0x00RRGGBB
        if byte_order == 0:  # LSBFirst na memoria: B,G,R,X
            out[0::3] = line[2::4]; out[1::3] = line[1::4]; out[2::3] = line[0::4]
        else:                # MSBFirst: X,R,G,B
            out[0::3] = line[1::4]; out[1::3] = line[2::4]; out[2::3] = line[3::4]
    else:
        for x in range(width):
            px = line[x * bpp:(x + 1) * bpp]
            v = int.from_bytes(px, "big" if byte_order == 1 else "little")
            out[x * 3] = (v & red_mask) >> rs
            out[x * 3 + 1] = (v & green_mask) >> gs
            out[x * 3 + 2] = (v & blue_mask) >> bs
    rows += b"\x00" + out


def chunk(tag, payload):
    c = struct.pack(">I", len(payload)) + tag + payload
    return c + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)


png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b"")
open(dst, "wb").write(png)
print(f"{dst}: {width}x{height} ({len(png)} bytes)")
