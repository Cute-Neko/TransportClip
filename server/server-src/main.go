// transport-relay: Android → Windows 剪贴板图片中继服务。
// 仅监听本机回环地址,由 nginx 以 HTTPS/WSS 反向代理对外;认证使用共享令牌。
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	maxBody      = 16 << 20        // 单张图片上限 16MB
	maxPixels    = 40_000_000      // 解码前防御:宽*高上限,防解压炸弹
	ackTimeout   = 12 * time.Second // 等待 Windows 写入剪贴板 ACK
	dedupKeep    = 512             // 记住最近 N 条消息 ID
	dedupTTL     = 10 * time.Minute
	recvQueue    = 32
	pingInterval = 20 * time.Second
	pongWait     = 45 * time.Second
	maxConns     = 8 // WS 连接总数上限
)

// ---------------- 令牌限流(单用户场景,简单令牌桶) ----------------

type rateLimiter struct {
	mu       sync.Mutex
	tokens   float64
	last     time.Time
	refill   float64 // tokens per second
	burst    float64
}

func newRateLimiter(perMinute, burst float64) *rateLimiter {
	return &rateLimiter{tokens: burst, last: time.Now(), refill: perMinute / 60.0, burst: burst}
}

func (r *rateLimiter) allow() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	r.tokens += now.Sub(r.last).Seconds() * r.refill
	if r.tokens > r.burst {
		r.tokens = r.burst
	}
	r.last = now
	if r.tokens < 1 {
		return false
	}
	r.tokens--
	return true
}

// ---------------- 中继核心 ----------------

type ackResult struct {
	ok  bool
	err string
}

type winConn struct {
	ws *websocket.Conn
	wm sync.Mutex // 串行化同一连接的写操作
}

func (c *winConn) sendText(b []byte) error {
	c.wm.Lock()
	defer c.wm.Unlock()
	c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.ws.WriteMessage(websocket.TextMessage, b)
}

func (c *winConn) sendBinary(b []byte) error {
	c.wm.Lock()
	defer c.wm.Unlock()
	c.ws.SetWriteDeadline(time.Now().Add(30 * time.Second))
	return c.ws.WriteMessage(websocket.BinaryMessage, b)
}

type relay struct {
	tokenHash [32]byte

	mu        sync.Mutex
	conns     map[*winConn]struct{}
	waiters   map[string]chan ackResult // msgID -> 等待 ACK 的上传请求
	delivered map[string]time.Time     // 已投递消息 ID(去重)
	order     []string                 // delivered 的插入顺序,用于淘汰
	started   time.Time
	served    uint64
	limiter   *rateLimiter
}

func newRelay(token string) *relay {
	return &relay{
		tokenHash: sha256.Sum256([]byte(token)),
		conns:     make(map[*winConn]struct{}),
		waiters:   make(map[string]chan ackResult),
		delivered: make(map[string]time.Time),
		started:   time.Now(),
		limiter:   newRateLimiter(30, 60), // 每分钟 30 张,突发 60
	}
}

func (r *relay) auth(req *http.Request) bool {
	h := req.Header.Get("Authorization")
	const prefix = "bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		sum := sha256.Sum256([]byte(h[len(prefix):]))
		return subtle.ConstantTimeCompare(sum[:], r.tokenHash[:]) == 1
	}
	return false
}

func (r *relay) noteDelivered(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	// 顺带清理过期条目
	if _, ok := r.delivered[id]; !ok {
		r.delivered[id] = now
		r.order = append(r.order, id)
	}
	for len(r.order) > dedupKeep {
		old := r.order[0]
		r.order = r.order[1:]
		delete(r.delivered, old)
	}
	for id, t := range r.delivered { // TTL 清理(条目少,O(n) 可接受)
		if now.Sub(t) > dedupTTL {
			delete(r.delivered, id)
		}
	}
}

var errNoConn = fmt.Errorf("no windows client connected")

// pushAndWait 向所有在线 Windows 客户端推送图片并等待第一个 ACK。
func (r *relay) pushAndWait(id string, png []byte) error {
	meta, _ := json.Marshal(map[string]any{"type": "image", "id": id, "length": len(png), "ts": time.Now().UnixMilli()})

	r.mu.Lock()
	if len(r.conns) == 0 {
		r.mu.Unlock()
		return errNoConn
	}
	ch := make(chan ackResult, 1)
	r.waiters[id] = ch
	conns := make([]*winConn, 0, len(r.conns))
	for c := range r.conns {
		conns = append(conns, c)
	}
	r.mu.Unlock()

	defer func() {
		r.mu.Lock()
		delete(r.waiters, id)
		r.mu.Unlock()
	}()

	var pushErr error
	for _, c := range conns {
		if err := c.sendText(meta); err != nil {
			pushErr = err
			continue
		}
		if err := c.sendBinary(png); err != nil {
			pushErr = err
		}
	}
	if pushErr != nil {
		log.Printf("push error for %s: %v", id, pushErr)
	}

	select {
	case ack := <-ch:
		if ack.ok {
			r.noteDelivered(id)
			return nil
		}
		return fmt.Errorf("windows clipboard write failed: %s", ack.err)
	case <-time.After(ackTimeout):
		return fmt.Errorf("ack timeout")
	}
}

// ---------------- HTTP handlers ----------------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func (r *relay) handleHealth(w http.ResponseWriter, req *http.Request) {
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (r *relay) handleStatus(w http.ResponseWriter, req *http.Request) {
	if !r.auth(req) {
		writeJSON(w, 401, map[string]any{"error": "unauthorized"})
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	writeJSON(w, 200, map[string]any{
		"ok": true, "windows_online": len(r.conns),
		"uptime_sec": int(time.Since(r.started).Seconds()), "served": r.served,
	})
}

// validatePNG 检查 PNG 魔数并解析 IHDR 宽高。
func validatePNG(b []byte) error {
	if len(b) < 26 {
		return fmt.Errorf("too short")
	}
	magic := []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}
	if string(b[:8]) != string(magic) {
		return fmt.Errorf("not a PNG")
	}
	if string(b[12:16]) != "IHDR" {
		return fmt.Errorf("missing IHDR")
	}
	w := binary.BigEndian.Uint32(b[16:20])
	h := binary.BigEndian.Uint32(b[20:24])
	if w == 0 || h == 0 || w > 12000 || h > 12000 || w*h > maxPixels {
		return fmt.Errorf("bad dimensions %dx%d", w, h)
	}
	return nil
}

func (r *relay) handleUpload(w http.ResponseWriter, req *http.Request) {
	if !r.auth(req) {
		writeJSON(w, 401, map[string]any{"status": "unauthorized"})
		return
	}
	if req.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"status": "method not allowed"})
		return
	}
	if !r.limiter.allow() {
		writeJSON(w, 429, map[string]any{"status": "rate limited"})
		return
	}

	body := http.MaxBytesReader(w, req.Body, maxBody)
	png, err := io.ReadAll(body)
	if err != nil {
		writeJSON(w, 413, map[string]any{"status": "too large or broken body"})
		return
	}
	if err := validatePNG(png); err != nil {
		writeJSON(w, 400, map[string]any{"status": "invalid image", "detail": err.Error()})
		return
	}

	id := req.Header.Get("X-Msg-Id")
	if id == "" {
		buf := make([]byte, 16)
		rand.Read(buf)
		id = hex.EncodeToString(buf)
	}

	// 去重:同一 ID 且已投递 → 直接按成功返回,不重复覆盖剪贴板
	r.mu.Lock()
	_, done := r.delivered[id]
	r.served++
	r.mu.Unlock()
	if done {
		writeJSON(w, 200, map[string]any{"status": "delivered", "id": id, "deduped": true})
		return
	}

	start := time.Now()
	err = r.pushAndWait(id, png)
	elapsed := time.Since(start).Round(time.Millisecond)
	if err != nil {
		if err == errNoConn {
			log.Printf("upload %s (%d bytes) rejected: windows offline", id, len(png))
			writeJSON(w, 409, map[string]any{"status": "offline", "id": id})
			return
		}
		log.Printf("upload %s (%d bytes) failed after %v: %v", id, len(png), elapsed, err)
		writeJSON(w, 504, map[string]any{"status": "failed", "id": id, "detail": err.Error()})
		return
	}
	log.Printf("upload %s (%d bytes) delivered in %v", id, len(png), elapsed)
	writeJSON(w, 200, map[string]any{"status": "delivered", "id": id, "elapsed_ms": elapsed.Milliseconds()})
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  64 << 10,
	WriteBufferSize: 256 << 10,
}

func (r *relay) handleWS(w http.ResponseWriter, req *http.Request) {
	if !r.auth(req) {
		writeJSON(w, 401, map[string]any{"status": "unauthorized"})
		return
	}
	ws, err := upgrader.Upgrade(w, req, nil)
	if err != nil {
		return
	}

	r.mu.Lock()
	if len(r.conns) >= maxConns {
		r.mu.Unlock()
		ws.Close()
		return
	}
	c := &winConn{ws: ws}
	r.conns[c] = struct{}{}
	n := len(r.conns)
	r.mu.Unlock()
	log.Printf("windows client connected (online=%d, remote via nginx)", n)

	defer func() {
		r.mu.Lock()
		delete(r.conns, c)
		n := len(r.conns)
		r.mu.Unlock()
		ws.Close()
		log.Printf("windows client disconnected (online=%d)", n)
	}()

	ws.SetReadLimit(maxBody + 4096)
	ws.SetReadDeadline(time.Now().Add(pongWait))
	ws.SetPongHandler(func(string) error { ws.SetReadDeadline(time.Now().Add(pongWait)); return nil })

	done := make(chan struct{})
	defer close(done)
	go func() { // 心跳
		t := time.NewTicker(pingInterval)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				c.wm.Lock()
				ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
				ws.WriteMessage(websocket.PingMessage, nil)
				c.wm.Unlock()
			case <-done:
				return
			}
		}
	}()

	for {
		mt, data, err := ws.ReadMessage()
		if err != nil {
			return
		}
		if mt != websocket.TextMessage {
			continue
		}
		var msg struct {
			Type string `json:"type"`
			ID   string `json:"id"`
			OK   bool   `json:"ok"`
			Err  string `json:"err"`
		}
		if json.Unmarshal(data, &msg) != nil {
			continue
		}
		switch msg.Type {
		case "ack":
			r.mu.Lock()
			ch := r.waiters[msg.ID]
			r.mu.Unlock()
			if ch != nil {
				select {
				case ch <- ackResult{ok: msg.OK, err: msg.Err}:
				default:
				}
			} else {
				log.Printf("late ack for %s ignored", msg.ID)
			}
		case "ping": // 应用层保活(可选)
			c.sendText([]byte(`{"type":"pong"}`))
		}
	}
}

func loadToken(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	tok := strings.TrimSpace(string(b))
	tok = strings.TrimPrefix(tok, "transport-relay-token:")
	tok = strings.TrimSpace(tok)
	if len(tok) < 32 {
		return "", fmt.Errorf("token too short (need >= 32 chars)")
	}
	return tok, nil
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8787", "listen address (loopback behind nginx)")
	tokenFile := flag.String("token-file", "", "path to file containing the shared token")
	flag.Parse()

	if *tokenFile == "" {
		log.Fatal("--token-file is required")
	}
	token, err := loadToken(*tokenFile)
	if err != nil {
		log.Fatalf("load token: %v", err)
	}

	r := newRelay(token)
	mux := http.NewServeMux()
	mux.HandleFunc("/tr-relay/health", r.handleHealth)
	mux.HandleFunc("/tr-relay/status", r.handleStatus)
	mux.HandleFunc("/tr-relay/upload", r.handleUpload)
	mux.HandleFunc("/tr-relay/ws", r.handleWS)

	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Printf("transport-relay listening on %s (behind nginx TLS)", *addr)
	log.Fatal(srv.ListenAndServe())
}
