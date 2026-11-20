package main

// 全局热键 Ctrl+Alt+V:把最近收到的图片重新写入剪贴板。
// RegisterHotKey(hwnd=NULL) 绑定到当前线程,用独立线程 + 消息循环接收。

import (
	"runtime"
	"unsafe"
)

const (
	modAlt      = 0x0001
	modControl  = 0x0002
	modNoRepeat = 0x4000
	wmHotkey    = 0x0312
	hotkeyID    = 1
)

var (
	pRegisterHotKey   = moduser32.NewProc("RegisterHotKey")
	pUnregisterHotKey = moduser32.NewProc("UnregisterHotKey")
	pGetMessage       = moduser32.NewProc("GetMessageW")
)

type msg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	pt      struct{ x, y int32 }
}

func startHotkey(onTrigger func()) {
	go func() {
		runtime.LockOSThread()
		r, _, _ := pRegisterHotKey.Call(0, hotkeyID, modControl|modAlt|modNoRepeat, 'V')
		if r == 0 {
			logf("热键 Ctrl+Alt+V 注册失败(可能被其他程序占用),菜单仍可复制最近图片")
			return
		}
		defer pUnregisterHotKey.Call(0, hotkeyID)
		var m msg
		for {
			// GetMessage 阻塞等待;WM_QUIT(返回 0)时退出
			ret, _, _ := pGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
			if int32(ret) <= 0 {
				return
			}
			if m.message == wmHotkey {
				onTrigger()
			}
		}
	}()
}
