#!/usr/bin/env python3
"""Собирает итоговый APK: base.apk из aapt2 + classes.dex, с выравниванием."""
import struct
import sys
import zipfile
import zlib

ALIGN = 4


def load(src, dex):
    entries = [["classes.dex", open(dex, "rb").read(), False]]
    with zipfile.ZipFile(src) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            entries.append([info.filename, z.read(info.filename), True])
    for e in entries:
        if e[0] == "resources.arsc":
            e[2] = False  # Android 11+ требует несжатый resources.arsc
    return entries


def build(entries, out):
    buf = bytearray()
    central = bytearray()
    for name, data, compress in entries:
        nb = name.encode("utf-8")
        if compress:
            comp = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = comp.compress(data) + comp.flush()
            method = 8
        else:
            payload = data
            method = 0
        crc = zlib.crc32(data) & 0xFFFFFFFF

        extra = b""
        if method == 0:
            pad = (-(len(buf) + 30 + len(nb))) % ALIGN
            while pad != 0 and pad < 4:
                pad += ALIGN
            if pad:
                # APK alignment extra field: id 0xD935, длина = весь паддинг
                extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)

        offset = len(buf)
        buf += struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x0800, method,
                           0, 0, crc, len(payload), len(data), len(nb), len(extra))
        buf += nb + extra + payload
        central += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0x0800,
                               method, 0, 0, crc, len(payload), len(data),
                               len(nb), len(extra), 0, 0, 0, 0, offset)
        central += nb + extra

    cd_offset = len(buf)
    buf += central
    buf += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries),
                       len(central), cd_offset, 0)
    with open(out, "wb") as f:
        f.write(buf)
    print("apk:", out, len(buf), "bytes,", len(entries), "entries")


if __name__ == "__main__":
    build(load(sys.argv[1], sys.argv[2]), sys.argv[3])
