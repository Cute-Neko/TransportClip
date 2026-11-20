package main

// Win32 剪贴板写入:同时放置 PNG(注册格式)、CF_DIBV5、CF_DIB 三种格式,
// 兼容 Chromium(Electron/浏览器)、Word、QQ、微信等各类粘贴目标。
// 不依赖剪贴板所有者窗口,写完即释放,不使用延迟渲染。

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/draw"
	_ "image/png"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	moduser32   = windows.NewLazySystemDLL("user32.dll")
	modkernel32 = windows.NewLazySystemDLL("kernel32.dll")

	pOpenClipboard         = moduser32.NewProc("OpenClipboard")
	pCloseClipboard        = moduser32.NewProc("CloseClipboard")
	pEmptyClipboard        = moduser32.NewProc("EmptyClipboard")
	pSetClipboardData      = moduser32.NewProc("SetClipboardData")
	pRegisterClipboardFmtA = moduser32.NewProc("RegisterClipboardFormatA")

	pGlobalAlloc    = modkernel32.NewProc("GlobalAlloc")
	pGlobalLock     = modkernel32.NewProc("GlobalLock")
	pGlobalUnlock   = modkernel32.NewProc("GlobalUnlock")
	pGlobalFree     = modkernel32.NewProc("GlobalFree")
)

const (
	gmemMoveable = 0x0002
	cfDIB        = 8
	cfDIBV5      = 17
)

var pngFormatID uint32 // RegisterClipboardFormat("PNG")

func init() {
	name := []byte("PNG\x00")
	n, _, _ := pRegisterClipboardFmtA.Call(uintptr(unsafe.Pointer(&name[0])))
	pngFormatID = uint32(n)
}

// setHGLOBAL 分配全局内存并放入剪贴板。成功后内存归系统所有,失败时需自行释放。
func setHGLOBAL(format uint32, data []byte) error {
	size := uintptr(len(data))
	h, _, _ := pGlobalAlloc.Call(gmemMoveable, size)
	if h == 0 {
		return fmt.Errorf("GlobalAlloc failed")
	}
	ptr, _, _ := pGlobalLock.Call(h)
	if ptr == 0 {
		pGlobalFree.Call(h)
		return fmt.Errorf("GlobalLock failed")
	}
	copy(unsafe.Slice((*byte)(unsafe.Pointer(ptr)), len(data)), data)
	pGlobalUnlock.Call(h)
	r, _, err := pSetClipboardData.Call(uintptr(format), h)
	if r == 0 {
		pGlobalFree.Call(h)
		return fmt.Errorf("SetClipboardData(%d): %w", format, err)
	}
	return nil
}

// pngToNRGBA 把 PNG 解码为未预乘的 NRGBA(必要时转换)。
func pngToNRGBA(b []byte) (*image.NRGBA, error) {
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		return nil, fmt.Errorf("png decode: %w", err)
	}
	if n, ok := img.(*image.NRGBA); ok {
		return n, nil
	}
	out := image.NewNRGBA(img.Bounds())
	draw.Draw(out, out.Bounds(), img, img.Bounds().Min, draw.Src)
	return out, nil
}

// buildDIBV5 构造 BITMAPV5HEADER + 自底向上 BGRA(未预乘 alpha)。
func buildDIBV5(m *image.NRGBA) []byte {
	w, h := m.Bounds().Dx(), m.Bounds().Dy()
	stride := m.Stride
	buf := make([]byte, 124+w*h*4)
	put32 := func(off int, v uint32) { binary.LittleEndian.PutUint32(buf[off:], v) }
	put32(0, 124)   // bV5Size
	put32(4, uint32(w))
	put32(8, uint32(h)) // 正数 = 自底向上
	binary.LittleEndian.PutUint16(buf[12:], 1)  // planes
	binary.LittleEndian.PutUint16(buf[14:], 32) // bit count
	put32(16, 3)           // BI_BITFIELDS
	put32(20, uint32(w*h*4))
	put32(24, 3780) // 96 dpi
	put32(28, 3780)
	put32(40, 0x00FF0000) // red
	put32(44, 0x0000FF00) // green
	put32(48, 0x000000FF) // blue
	put32(52, 0xFF000000) // alpha
	put32(56, 0x73524742) // 'sRGB'
	put32(108, 4)         // LCS_GM_IMAGES

	pix := buf[124:]
	for y := 0; y < h; y++ {
		src := m.Pix[(h-1-y)*stride : (h-1-y)*stride+w*4]
		dst := pix[y*w*4 : (y+1)*w*4]
		for x := 0; x < w; x++ {
			r, g, b, a := src[x*4], src[x*4+1], src[x*4+2], src[x*4+3]
			dst[x*4], dst[x*4+1], dst[x*4+2], dst[x*4+3] = b, g, r, a
		}
	}
	return buf
}

// buildDIB 构造 BITMAPINFOHEADER(40)+ 3 个掩码 + BGRA 数据。
func buildDIB(v5 []byte, w, h int) []byte {
	total := 40 + 12 + len(v5)-124
	buf := make([]byte, total)
	put32 := func(off int, v uint32) { binary.LittleEndian.PutUint32(buf[off:], v) }
	put32(0, 40)
	put32(4, uint32(w))
	put32(8, uint32(h))
	binary.LittleEndian.PutUint16(buf[12:], 1)
	binary.LittleEndian.PutUint16(buf[14:], 32)
	put32(16, 3)
	put32(20, uint32(w*h*4))
	copy(buf[40:], v5[40:52]) // 红绿蓝掩码
	copy(buf[52:], v5[124:])  // 像素
	return buf
}

var clipMu sync.Mutex

const cfHDROP = 15

// writeFilesToClipboard 用 CF_HDROP 把文件复制到剪贴板,
// 效果等同资源管理器里选中文件按 Ctrl+C(DROPFILES + 双 NUL 结尾的 UTF-16 路径列表)。
// 参考: https://learn.microsoft.com/zh-cn/windows/win32/shell/clipboard
func writeFilesToClipboard(paths []string) error {
	clipMu.Lock()
	defer clipMu.Unlock()

	var list []uint16
	for _, p := range paths {
		u, err := windows.UTF16FromString(p)
		if err != nil {
			return err
		}
		list = append(list, u...)
	}
	list = append(list, 0) // 列表结尾的第二个 NUL

	size := uintptr(20 + len(list)*2) // DROPFILES 头 20 字节
	h, _, _ := pGlobalAlloc.Call(gmemMoveable, size)
	if h == 0 {
		return fmt.Errorf("GlobalAlloc failed")
	}
	ptr, _, _ := pGlobalLock.Call(h)
	if ptr == 0 {
		pGlobalFree.Call(h)
		return fmt.Errorf("GlobalLock failed")
	}
	buf := unsafe.Slice((*byte)(unsafe.Pointer(ptr)), size)
	for i := range buf {
		buf[i] = 0
	}
	binary.LittleEndian.PutUint32(buf[0:], 20) // pFiles = sizeof(DROPFILES)
	binary.LittleEndian.PutUint32(buf[16:], 1) // fWide = TRUE
	raw := (*[1 << 30]byte)(unsafe.Pointer(&list[0]))[:len(list)*2 : len(list)*2]
	copy(buf[20:], raw)
	pGlobalUnlock.Call(h)

	// 打开剪贴板(等待其他程序释放)
	opened := false
	for i := 0; i < 30; i++ {
		if r, _, _ := pOpenClipboard.Call(0); r != 0 {
			opened = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !opened {
		pGlobalFree.Call(h)
		return fmt.Errorf("剪贴板被其他程序占用")
	}
	defer pCloseClipboard.Call()
	if r, _, _ := pEmptyClipboard.Call(); r == 0 {
		pGlobalFree.Call(h)
		return fmt.Errorf("EmptyClipboard failed")
	}
	r, _, err := pSetClipboardData.Call(cfHDROP, h)
	if r == 0 {
		pGlobalFree.Call(h)
		return fmt.Errorf("SetClipboardData(CF_HDROP): %w", err)
	}
	return nil
}

// writeImageToClipboard 将 PNG 字节写入系统剪贴板(三种格式)。
// 其他程序可能短暂占用剪贴板,内部最多重试约 3 秒。
func writeImageToClipboard(pngBytes []byte) error {
	clipMu.Lock()
	defer clipMu.Unlock()

	m, err := pngToNRGBA(pngBytes)
	if err != nil {
		return err
	}
	v5 := buildDIBV5(m)
	dib := buildDIB(v5, m.Bounds().Dx(), m.Bounds().Dy())
	pngWithNul := append(append([]byte{}, pngBytes...), 0)

	// 打开剪贴板(等待其他程序释放)
	opened := false
	for i := 0; i < 30; i++ {
		if r, _, _ := pOpenClipboard.Call(0); r != 0 {
			opened = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !opened {
		return fmt.Errorf("剪贴板被其他程序占用")
	}
	defer pCloseClipboard.Call()

	if r, _, _ := pEmptyClipboard.Call(); r == 0 {
		return fmt.Errorf("EmptyClipboard failed")
	}

	var errs []string
	if pngFormatID != 0 {
		if err := setHGLOBAL(pngFormatID, pngWithNul); err != nil {
			errs = append(errs, "PNG: "+err.Error())
		}
	}
	if err := setHGLOBAL(cfDIBV5, v5); err != nil {
		errs = append(errs, "DIBV5: "+err.Error())
	}
	if err := setHGLOBAL(cfDIB, dib); err != nil {
		errs = append(errs, "DIB: "+err.Error())
	}
	if len(errs) == 3 {
		return fmt.Errorf("全部格式写入失败: %v", errs)
	}
	if len(errs) > 0 {
		logf("剪贴板部分格式失败(不影响使用): %v", errs)
	}
	return nil
}
