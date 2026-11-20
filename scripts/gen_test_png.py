# 生成测试 PNG(纯标准库):白底、黑框、红对角线、右下角黑色方块
# 用法: python gen_test_png.py <输出.png> [宽] [高]
import struct, sys, zlib

def png_chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

def main(path, w=320, h=120):
    rows = []
    for y in range(h):
        row = bytearray([0])  # filter type 0
        for x in range(w):
            if x < 3 or x >= w - 3 or y < 3 or y >= h - 3:
                px = (0, 0, 0, 255)            # 黑框
            elif abs((x + y) - (w + h) // 2) < 4:
                px = (200, 20, 20, 255)        # 红对角带
            elif w - 40 <= x < w - 16 and h - 40 <= y < h - 16:
                px = (0, 0, 0, 255)            # 右下黑块
            else:
                px = (255, 255, 255, 255)      # 白底
            row += bytes(px)
        rows.append(bytes(row))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8bit RGBA
    png = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr) + png_chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + png_chunk(b"IEND", b"")
    open(path, "wb").write(png)
    print(f"wrote {path} ({w}x{h}, {len(png)} bytes)")

if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 320, int(sys.argv[3]) if len(sys.argv) > 3 else 120)
