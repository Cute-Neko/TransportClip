# 生成 TransportClip 托盘图标(BMP 格式的 .ico,32x32,不依赖第三方库)
# 用法: python gen_icons.py <输出目录>
import struct, sys, os, math

def make_ico(color, path):
    # color: (r, g, b) 圆点主色
    W = H = 32
    cx, cy, radius = 15.5, 15.5, 12.0
    edge = 1.6  # 描边宽度
    dark = tuple(int(c * 0.55) for c in color)  # 描边色

    # 像素缓冲(自顶向下,BGRA)
    rows = []
    for y in range(H):
        row = bytearray()
        for x in range(W):
            d = math.hypot(x + 0.5 - (cx + 0.5), y + 0.5 - (cy + 0.5))
            if d <= radius - edge:
                bgra = (color[2], color[1], color[0], 255)
            elif d <= radius:
                bgra = (dark[2], dark[1], dark[0], 255)
            else:
                bgra = (0, 0, 0, 0)
            row += bytes(bgra)
        rows.append(bytes(row))

    # XOR 位图需自底向上
    xor = b"".join(rows[::-1])
    and_mask = b"\x00" * (H * ((W + 31) // 32) * 4)  # alpha 已处理,AND 掩码全 0

    # BITMAPINFOHEADER(height = XOR + AND)
    hdr = struct.pack(
        "<IiiHHIIiiII", 40, W, H * 2, 1, 32,
        0, len(xor) + len(and_mask), 0, 0, 0, 0
    )
    img = hdr + xor + and_mask

    icondir = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII", W, H, 0, 0, 1, 32, len(img), 6 + 16)
    with open(path, "wb") as f:
        f.write(icondir + entry + img)

if __name__ == "__main__":
    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(outdir, exist_ok=True)
    make_ico((0x60, 0x60, 0x60), os.path.join(outdir, "ico_gray.ico"))    # 灰:未配置
    make_ico((0xE0, 0xB0, 0x20), os.path.join(outdir, "ico_yellow.ico"))  # 黄:连接中
    make_ico((0x20, 0xA0, 0x30), os.path.join(outdir, "ico_green.ico"))   # 绿:已连接
    make_ico((0xC0, 0x30, 0x30), os.path.join(outdir, "ico_red.ico"))     # 红:断开
    print("icons written to", outdir)
