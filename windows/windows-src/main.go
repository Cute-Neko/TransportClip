package main

// TransportClip:Windows 托盘客户端。
// 通过 WSS 长连接接收平板发来的手写公式截图,自动写入系统剪贴板并回 ACK。
// 不抢焦点、不模拟按键,用户自己在目标输入框 Ctrl+V。

import (
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/getlantern/systray"
	"github.com/gorilla/websocket"
	"golang.org/x/sys/windows/registry"
)

//go:embed icons/*.ico
var iconFS embed.FS

func icon(name string) []byte {
	b, err := iconFS.ReadFile("icons/" + name)
	if err != nil {
		return nil
	}
	return b
}

const (
	appName    = "TransportClip"
	runRegPath = `Software\Microsoft\Windows\CurrentVersion\Run`
	historyMax = 100
)

type config struct {
	ServerURL string `json:"server_url"`
	TokenEnc  string `json:"token_dpapi"` // DPAPI 加密后的令牌
	Token     string `json:"token"`       // 兼容手写明文,首次加载后转为加密存储
	FileMode  *bool  `json:"file_mode"`   // 剪贴板模式:true=CF_HDROP 文件模式(默认),false=图片模式
}

// fileMode 返回当前剪贴板模式,未配置时默认文件模式。
func fileMode() bool {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	return cfg.FileMode == nil || *cfg.FileMode
}

func setFileMode(on bool) {
	cfgMu.Lock()
	cfg.FileMode = &on
	cfgMu.Unlock()
	saveConfig()
}

var (
	cfgDir     string
	historyDir string
	logPath    string
	cfgPath    string
	cfg        config
	cfgMu      sync.Mutex

	logFile *os.File

	stateMu   sync.Mutex
	wsConn    *websocket.Conn
	lastImage string

	mStatus    *systray.MenuItem
	mCopyLast  *systray.MenuItem
	mRecent    []*systray.MenuItem
	mAutoStart *systray.MenuItem

	reconnectNow = make(chan struct{}, 1)
)

func logf(format string, args ...any) {
	log.Printf(format, args...)
}


// ---------------- 配置 ----------------

func loadConfig() {
	os.MkdirAll(historyDir, 0o700)

	if b, err := os.ReadFile(cfgPath); err == nil {
		json.Unmarshal(b, &cfg)
	}
	if cfg.ServerURL == "" {
		cfg.ServerURL = "wss://relay.example.com/tr-relay/ws"
	}
	if cfg.Token != "" && cfg.TokenEnc == "" { // 明文 → DPAPI 加密
		if enc, err := dpapiProtect([]byte(cfg.Token)); err == nil && enc != "" {
			cfg.TokenEnc = enc
			cfg.Token = ""
			logf("配置中的明文令牌已转为 DPAPI 加密存储")
		}
	}
	b, _ := json.MarshalIndent(cfg, "", "  ")
	os.WriteFile(cfgPath, append(b, '\n'), 0o600)
}

func saveConfig() {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	b, _ := json.MarshalIndent(cfg, "", "  ")
	os.WriteFile(cfgPath, append(b, '\n'), 0o600)
}

func token() string {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	if cfg.TokenEnc != "" {
		if plain, err := dpapiUnprotect(cfg.TokenEnc); err == nil {
			return string(plain)
		}
	}
	return cfg.Token
}

// ---------------- 日志 ----------------

func setupLog() {
	f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return
	}
	logFile = f
	log.SetOutput(io.MultiWriter(f, os.Stderr))
	log.SetFlags(log.LstdFlags)
}

// ---------------- 历史图片(按批次分组) ----------------

// histState 记录当前批次,重启后仍延续 60 秒分组窗口。
type histState struct {
	Batch    string `json:"batch"`
	Count    int    `json:"count"`
	LastUnix int64  `json:"last_unix"`
}

func statePath() string { return filepath.Join(historyDir, "state.json") }

func loadHistState() histState {
	var st histState
	if b, err := os.ReadFile(statePath()); err == nil {
		json.Unmarshal(b, &st)
	}
	return st
}

func saveHistState(st histState) {
	os.WriteFile(statePath(), mustJSON(st), 0o600)
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

// saveHistory:距上一张接收超过 60 秒 → 新批次文件夹(以该张接收时间命名);
// 否则沿用当前文件夹,编号递增;文件名 #N.png。返回完整路径。
func saveHistory(id string, png []byte) string {
	_ = id
	now := time.Now()
	st := loadHistState()
	if st.Batch == "" || now.Sub(time.Unix(st.LastUnix, 0)) > 60*time.Second {
		st.Batch = now.Format("20060102-150405")
		st.Count = 0
	}
	st.Count++
	st.LastUnix = now.Unix()
	dir := filepath.Join(historyDir, st.Batch)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		logf("创建批次目录失败: %v", err)
		return ""
	}
	p := filepath.Join(dir, fmt.Sprintf("#%d.png", st.Count))
	if err := os.WriteFile(p, png, 0o600); err != nil {
		logf("保存历史图片失败: %v", err)
		return ""
	}
	saveHistState(st)
	trimHistory()
	return p
}

// 历史条目:批次目录名(旧版根目录文件以其文件名前 17 位时间戳参与排序)+ #N 编号
type histEntry struct {
	path  string
	batch string
	num   int
}

func fileNameNum(name string) int {
	if !strings.HasPrefix(name, "#") {
		return 0
	}
	n, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(name, "#"), ".png"))
	if err != nil {
		return 0
	}
	return n
}

// listHistory 返回全部历史文件,新的在前。
func listHistory() []histEntry {
	var ents []histEntry
	items, _ := os.ReadDir(historyDir)
	for _, d := range items {
		if d.IsDir() {
			files, _ := os.ReadDir(filepath.Join(historyDir, d.Name()))
			for _, f := range files {
				if !f.IsDir() && strings.HasSuffix(f.Name(), ".png") {
					ents = append(ents, histEntry{
						path:  filepath.Join(historyDir, d.Name(), f.Name()),
						batch: d.Name(),
						num:   fileNameNum(f.Name()),
					})
				}
			}
		} else if strings.HasSuffix(d.Name(), ".png") {
			// 旧版平铺文件,文件名前 17 位是时间戳
			legacy := d.Name()
			key := legacy
			if len(legacy) >= 17 {
				key = legacy[:17]
			}
			ents = append(ents, histEntry{
				path:  filepath.Join(historyDir, legacy),
				batch: key,
				num:   1 << 30, // 同(旧)批次内按完整文件名排序
			})
		}
	}
	sort.Slice(ents, func(i, j int) bool {
		if ents[i].batch != ents[j].batch {
			return ents[i].batch > ents[j].batch // 批次时间戳,字典序即时间序
		}
		if ents[i].num != ents[j].num {
			return ents[i].num > ents[j].num
		}
		return ents[i].path > ents[j].path
	})
	return ents
}

func listRecent(n int) []string {
	ents := listHistory()
	out := make([]string, 0, n)
	for i, e := range ents {
		if i >= n {
			break
		}
		out = append(out, e.path)
	}
	return out
}

// trimHistory 按整批删除最旧批次,直到总数 ≤ historyMax(至少保留最新一批)。
func trimHistory() {
	for {
		ents := listHistory()
		if len(ents) <= historyMax {
			return
		}
		// 找出最旧批次的全部成员
		oldest := ents[len(ents)-1].batch
		batchCount := 0
		for _, e := range ents {
			if e.batch == oldest {
				batchCount++
			}
		}
		// 只剩一批时不删
		if batchCount == len(ents) {
			return
		}
		if dir := filepath.Join(historyDir, oldest); isDir(dir) {
			os.RemoveAll(dir)
		} else {
			for _, e := range ents {
				if e.batch == oldest {
					os.Remove(e.path)
				}
			}
		}
		logf("历史超过 %d 张,已删除最旧批次 %s", historyMax, oldest)
	}
}

func isDir(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

func recentTitle(p string) string {
	dir := filepath.Base(filepath.Dir(p))
	name := strings.TrimSuffix(filepath.Base(p), ".png")
	if t, err := time.Parse("20060102-150405", dir); err == nil {
		return t.Format("01-02 15:04:05") + " · " + name
	}
	return name
}

// ---------------- 开机自启 ----------------

func autoStartEnabled() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runRegPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue(appName)
	return err == nil
}

func setAutoStart(on bool) {
	k, err := registry.OpenKey(registry.CURRENT_USER, runRegPath, registry.SET_VALUE)
	if err != nil {
		logf("打开注册表失败: %v", err)
		return
	}
	defer k.Close()
	if on {
		exe, _ := os.Executable()
		k.SetStringValue(appName, `"`+exe+`"`)
	} else {
		k.DeleteValue(appName)
	}
}

// ---------------- 状态显示 ----------------

type uiState int

const (
	stateUnconfigured uiState = iota
	stateConnecting
	stateOnline
	stateOffline
)

func setUI(s uiState) {
	var ico []byte
	var text string
	switch s {
	case stateOnline:
		ico = icon("ico_green.ico")
		text = "已连接,等待推送"
	case stateConnecting:
		ico = icon("ico_yellow.ico")
		text = "连接中…"
	case stateUnconfigured:
		ico = icon("ico_gray.ico")
		text = "未配置令牌"
	default:
		ico = icon("ico_red.ico")
		text = "已断开,自动重连中"
	}
	systray.SetIcon(ico)
	systray.SetTooltip("TransportClip - " + text)
	if mStatus != nil {
		mStatus.SetTitle("状态:" + text)
	}
}

// ---------------- WebSocket 客户端 ----------------

func wsLoop() {
	backoff := time.Second
	for {
		tk := token()
		if tk == "" {
			setUI(stateUnconfigured)
			select {
			case <-reconnectNow:
			case <-time.After(5 * time.Second):
			}
			continue
		}
		setUI(stateConnecting)
		hdr := http.Header{"Authorization": {"Bearer " + tk}}
		connectedAt := time.Now()
		c, _, err := websocket.DefaultDialer.Dial(cfg.ServerURL, hdr)
		if err != nil {
			logf("连接失败: %v", err)
			setUI(stateOffline)
			sleepInterruptible(backoff)
			backoff = min(backoff*2, 30*time.Second)
			continue
		}
		logf("已连接 %s", cfg.ServerURL)
		stateMu.Lock()
		wsConn = c
		stateMu.Unlock()
		setUI(stateOnline)

		err = readLoop(c)
		stateMu.Lock()
		wsConn = nil
		stateMu.Unlock()
		c.Close()
		logf("连接断开: %v", err)
		setUI(stateOffline)

		if time.Since(connectedAt) > time.Minute {
			backoff = time.Second // 连接曾稳定,重置退避
		}
		sleepInterruptible(backoff)
		backoff = min(backoff*2, 30*time.Second)
	}
}

func sleepInterruptible(d time.Duration) {
	jitter := time.Duration(rand.Int63n(int64(500 * time.Millisecond)))
	select {
	case <-reconnectNow:
	case <-time.After(d + jitter):
	}
}

func readLoop(c *websocket.Conn) error {
	resetDeadline := func() { c.SetReadDeadline(time.Now().Add(90 * time.Second)) }
	resetDeadline()
	c.SetPongHandler(func(string) error { resetDeadline(); return nil })
	for {
		mt, data, err := c.ReadMessage()
		if err != nil {
			return err
		}
		resetDeadline()
		if mt != websocket.TextMessage {
			continue
		}
		var meta struct {
			Type   string `json:"type"`
			ID     string `json:"id"`
			Length int    `json:"length"`
		}
		if json.Unmarshal(data, &meta) != nil || meta.Type != "image" {
			continue
		}
		mt2, png, err := c.ReadMessage()
		if err != nil {
			return err
		}
		if mt2 != websocket.BinaryMessage {
			continue
		}
		if meta.Length > 0 && len(png) != meta.Length {
			logf("图片 %s 长度不符(meta=%d 实际=%d),丢弃", meta.ID, meta.Length, len(png))
			sendAck(c, meta.ID, false, "length mismatch")
			continue
		}
		go handleImage(meta.ID, png, c)
	}
}

func handleImage(id string, png []byte, c *websocket.Conn) {
	start := time.Now()
	p := saveHistory(id, png)
	var err error
	mode := "图片"
	if fileMode() {
		mode = "文件"
		if p == "" {
			err = fmt.Errorf("保存文件失败,无法以文件模式写入剪贴板")
		} else {
			err = writeFilesToClipboard([]string{p})
		}
	} else {
		err = writeImageToClipboard(png)
	}
	if err != nil {
		logf("图片 %s (%d bytes) 写剪贴板失败(%s模式): %v", id, len(png), mode, err)
		sendAck(c, id, false, err.Error())
		return
	}
	if p != "" {
		stateMu.Lock()
		lastImage = p
		stateMu.Unlock()
		refreshRecentMenu()
	}
	sendAck(c, id, true, "")
	logf("图片 %s (%d bytes) 已按%s模式写入剪贴板(%s),耗时 %v",
		id, len(png), mode, p, time.Since(start).Round(time.Millisecond))
}

func sendAck(c *websocket.Conn, id string, ok bool, errMsg string) {
	b, _ := json.Marshal(map[string]any{"type": "ack", "id": id, "ok": ok, "err": errMsg})
	c.WriteMessage(websocket.TextMessage, b)
}

// ---------------- 托盘菜单 ----------------

func copyImageFile(p string) {
	if fileMode() {
		// 文件模式:CF_HDROP,保留原文件名
		if err := writeFilesToClipboard([]string{p}); err != nil {
			logf("复制文件到剪贴板失败: %v", err)
		} else {
			logf("已复制文件到剪贴板: %s", p)
		}
		return
	}
	b, err := os.ReadFile(p)
	if err != nil {
		logf("读取 %s 失败: %v", p, err)
		return
	}
	if err := writeImageToClipboard(b); err != nil {
		logf("重新写入剪贴板失败: %v", err)
	} else {
		logf("已重新写入剪贴板: %s", filepath.Base(p))
	}
}

func copyLastImage() {
	stateMu.Lock()
	p := lastImage
	stateMu.Unlock()
	if p == "" {
		files := listRecent(1)
		if len(files) == 0 {
			logf("没有可复制的图片")
			return
		}
		p = files[0]
	}
	copyImageFile(p)
}

func refreshRecentMenu() {
	files := listRecent(10)
	for i, m := range mRecent {
		if i < len(files) {
			m.SetTitle(recentTitle(files[i]))
			m.Show()
		} else {
			m.SetTitle("(空)")
			m.Hide()
		}
	}
}

func onReady() {
	systray.SetTooltip("TransportClip")

	mStatus = systray.AddMenuItem("状态:启动中", "连接状态")
	mStatus.Disable()
	mCopyLast = systray.AddMenuItem("复制最近图片 (Ctrl+Alt+V)", "把最近收到的图片重新写入剪贴板")

	mModeMenu := systray.AddMenuItem("剪贴板模式", "文件模式=粘贴为文件(CF_HDROP),图片模式=粘贴为图片")
	mModeFile := mModeMenu.AddSubMenuItem("文件模式(默认)", "等同资源管理器里选中文件按 Ctrl+C")
	mModeImage := mModeMenu.AddSubMenuItem("图片模式", "直接粘贴为图片")
	refreshModeChecks := func() {
		if fileMode() {
			mModeFile.Check()
			mModeImage.Uncheck()
		} else {
			mModeFile.Uncheck()
			mModeImage.Check()
		}
	}
	refreshModeChecks()

	systray.AddSeparator()

	mRecentMenu := systray.AddMenuItem("最近图片", "点击条目重新写入剪贴板")
	for i := 0; i < 10; i++ {
		m := mRecentMenu.AddSubMenuItem("(空)", "重新写入剪贴板")
		mRecent = append(mRecent, m)
	}
	refreshRecentMenu()
	systray.AddSeparator()

	mAutoStart = systray.AddMenuItem("开机自启", "注册到当前用户启动项")
	if autoStartEnabled() {
		mAutoStart.Check()
	}
	mOpenConfig := systray.AddMenuItem("编辑配置文件", "服务器地址与令牌")
	mOpenLog := systray.AddMenuItem("查看日志", "运行日志")
	mReconnect := systray.AddMenuItem("立即重连", "立即重试连接")
	systray.AddSeparator()
	mQuit := systray.AddMenuItem("退出", "退出程序")

	if os.Getenv("TC_NO_WS") == "" {
		go wsLoop()
	}
	if os.Getenv("TC_NO_HOTKEY") == "" {
		startHotkey(copyLastImage)
	}
	setUI(stateConnecting)

	for i := range mRecent {
		go func(i int) {
			for range mRecent[i].ClickedCh {
				files := listRecent(10)
				if i < len(files) {
					copyImageFile(files[i])
				}
			}
		}(i)
	}

	go func() {
		for {
			select {
			case <-mCopyLast.ClickedCh:
				copyLastImage()
			case <-mModeFile.ClickedCh:
				setFileMode(true)
				refreshModeChecks()
			case <-mModeImage.ClickedCh:
				setFileMode(false)
				refreshModeChecks()
			case <-mAutoStart.ClickedCh:
				if autoStartEnabled() {
					setAutoStart(false)
					mAutoStart.Uncheck()
				} else {
					setAutoStart(true)
					mAutoStart.Check()
				}
			case <-mOpenConfig.ClickedCh:
				exec.Command("notepad.exe", cfgPath).Start()
			case <-mOpenLog.ClickedCh:
				exec.Command("notepad.exe", logPath).Start()
			case <-mReconnect.ClickedCh:
				select {
				case reconnectNow <- struct{}{}:
				default:
				}
			case <-mQuit.ClickedCh:
				systray.Quit()
				return
			}
		}
	}()
}

func main() {
	base, err := os.UserConfigDir()
	if err != nil {
		base = "."
	}
	cfgDir = filepath.Join(base, appName)
	historyDir = filepath.Join(cfgDir, "history")
	logPath = filepath.Join(cfgDir, "transportclip.log")
	cfgPath = filepath.Join(cfgDir, "config.json")

	setupLog()
	loadConfig()

	exe, _ := os.Executable()
	logf("TransportClip 启动 (%s), 配置: %s", exe, cfgPath)

	systray.Run(onReady, func() {
		logf("退出")
		if logFile != nil {
			logFile.Close()
		}
	})
}
