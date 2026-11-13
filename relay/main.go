// PayamBin Relay — سرور واسطه برای دسترسی به پیام‌بین از اینترنت
//
// گوشی با یک اتصال رمزدار و دائمی به این سرور وصل می‌شود؛ مرورگر از هر جا
// با رمز وارد می‌شود و درخواست‌ها از همان اتصال به گوشی می‌رسند.
// فقط کتابخانه‌ی استاندارد Go؛ یک فایل اجرایی بدون پیش‌نیاز.
package main

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	fOpen  = 1 // درخواست جدید (JSON)
	fData  = 2 // بدنه
	fEnd   = 3 // پایان بدنه
	fResp  = 4 // سرآیند پاسخ (JSON)
	fClose = 5 // لغو
	fPing  = 6
	fPong  = 7

	maxFrame = 64 * 1024
)

// ---------------- پیکربندی ----------------

type config struct {
	Password string `json:"password"`
	AgentKey string `json:"agent_key"`
	Secret   string `json:"cookie_secret"`
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func randPassword() string {
	const abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 16)
	rand.Read(b)
	out := make([]byte, 16)
	for i := range b {
		out[i] = abc[int(b[i])%len(abc)]
	}
	return string(out)
}

func loadConfig(path string) (*config, bool) {
	c := &config{}
	created := false
	if data, err := os.ReadFile(path); err == nil {
		json.Unmarshal(data, c)
	}
	if c.Password == "" {
		c.Password = randPassword()
		created = true
	}
	if c.AgentKey == "" {
		c.AgentKey = randHex(24)
		created = true
	}
	if c.Secret == "" {
		c.Secret = randHex(32)
		created = true
	}
	if created {
		data, _ := json.MarshalIndent(c, "", "  ")
		if err := os.WriteFile(path, data, 0600); err != nil {
			log.Fatalf("cannot write %s: %v", path, err)
		}
	}
	return c, created
}

// گواهی خودامضا (اگر گواهی دامنه داده نشده باشد)
func selfSigned(certPath, keyPath string) (tls.Certificate, error) {
	if c, err := tls.LoadX509KeyPair(certPath, keyPath); err == nil {
		return c, nil
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
	tpl := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "PayamBin Relay"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(20, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"payambin-relay"},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tpl, &tpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, err
	}
	kb, _ := x509.MarshalECPrivateKey(key)
	os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600)
	os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0600)
	return tls.LoadX509KeyPair(certPath, keyPath)
}

func certPin(c tls.Certificate) string {
	sum := sha256.Sum256(c.Certificate[0])
	return hex.EncodeToString(sum[:])
}

// ---------------- اتصال گوشی (agent) ----------------

type stream struct {
	ch     chan frame
	closed chan struct{}
	once   sync.Once
}

func (s *stream) close() { s.once.Do(func() { close(s.closed) }) }

type frame struct {
	t    byte
	id   uint32
	data []byte
}

type agent struct {
	conn    net.Conn
	r       *bufio.Reader
	wmu     sync.Mutex
	mu      sync.Mutex
	streams map[uint32]*stream
	nextID  uint32
	dead    chan struct{}
	once    sync.Once
	since   time.Time
	remote  string
}

func (a *agent) write(t byte, id uint32, data []byte) error {
	a.wmu.Lock()
	defer a.wmu.Unlock()
	for {
		n := len(data)
		if n > maxFrame {
			n = maxFrame
		}
		hdr := make([]byte, 9)
		hdr[0] = t
		binary.BigEndian.PutUint32(hdr[1:], id)
		binary.BigEndian.PutUint32(hdr[5:], uint32(n))
		a.conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
		if _, err := a.conn.Write(hdr); err != nil {
			a.kill()
			return err
		}
		if n > 0 {
			if _, err := a.conn.Write(data[:n]); err != nil {
				a.kill()
				return err
			}
		}
		data = data[n:]
		if len(data) == 0 {
			return nil
		}
	}
}

func (a *agent) kill() {
	a.once.Do(func() {
		close(a.dead)
		a.conn.Close()
		a.mu.Lock()
		for _, s := range a.streams {
			s.close()
		}
		a.mu.Unlock()
	})
}

func (a *agent) readLoop() {
	defer a.kill()
	hdr := make([]byte, 9)
	for {
		a.conn.SetReadDeadline(time.Now().Add(75 * time.Second))
		if _, err := io.ReadFull(a.r, hdr); err != nil {
			return
		}
		t := hdr[0]
		id := binary.BigEndian.Uint32(hdr[1:])
		n := binary.BigEndian.Uint32(hdr[5:])
		if n > maxFrame {
			return
		}
		data := make([]byte, n)
		if _, err := io.ReadFull(a.r, data); err != nil {
			return
		}
		switch t {
		case fPing:
			go a.write(fPong, 0, nil)
		case fPong:
		default:
			a.mu.Lock()
			s := a.streams[id]
			a.mu.Unlock()
			if s == nil {
				continue
			}
			select {
			case s.ch <- frame{t, id, data}:
			case <-s.closed:
			default:
				// مرورگر کند است (مثلاً پخش زنده) → این جریان قطع شود تا بقیه گیر نکنند
				s.close()
				go a.write(fClose, id, nil)
			}
		}
	}
}

func (a *agent) pinger() {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-a.dead:
			return
		case <-t.C:
			if a.write(fPing, 0, nil) != nil {
				return
			}
		}
	}
}

func (a *agent) open() (uint32, *stream) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.nextID++
	s := &stream{ch: make(chan frame, 512), closed: make(chan struct{})}
	a.streams[a.nextID] = s
	return a.nextID, s
}

func (a *agent) done(id uint32) {
	a.mu.Lock()
	if s := a.streams[id]; s != nil {
		s.close()
		delete(a.streams, id)
	}
	a.mu.Unlock()
}

// ---------------- سرور ----------------

type server struct {
	cfg      *config
	mu       sync.Mutex
	ag       *agent
	failures map[string][]time.Time
}

func (s *server) current() *agent {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ag != nil {
		select {
		case <-s.ag.dead:
			s.ag = nil
		default:
		}
	}
	return s.ag
}

func clientIP(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

func (s *server) handleAgent(w http.ResponseWriter, r *http.Request) {
	key := r.Header.Get("X-Agent-Key")
	if subtle.ConstantTimeCompare([]byte(key), []byte(s.cfg.AgentKey)) != 1 {
		time.Sleep(time.Second)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijack unsupported", 500)
		return
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		return
	}
	rw.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: payambin-agent\r\nConnection: Upgrade\r\n\r\n")
	rw.Flush()
	conn.SetDeadline(time.Time{})
	a := &agent{conn: conn, r: rw.Reader, streams: map[uint32]*stream{}, dead: make(chan struct{}), since: time.Now(), remote: clientIP(r)}
	s.mu.Lock()
	old := s.ag
	s.ag = a
	s.mu.Unlock()
	if old != nil {
		old.kill()
	}
	log.Printf("گوشی وصل شد از %s", a.remote)
	go a.pinger()
	a.readLoop()
	log.Printf("گوشی قطع شد")
}

// --- ورود مرورگر ---

func (s *server) sign(v string) string {
	m := hmac.New(sha256.New, []byte(s.cfg.Secret))
	m.Write([]byte(v))
	return hex.EncodeToString(m.Sum(nil))
}

func (s *server) makeCookie() string {
	exp := fmt.Sprint(time.Now().Add(30 * 24 * time.Hour).Unix())
	return exp + "." + s.sign(exp+"|"+s.cfg.Password)
}

func (s *server) validCookie(r *http.Request) bool {
	c, err := r.Cookie("pbr")
	if err != nil {
		return false
	}
	parts := strings.SplitN(c.Value, ".", 2)
	if len(parts) != 2 {
		return false
	}
	var exp int64
	fmt.Sscan(parts[0], &exp)
	if time.Now().Unix() > exp {
		return false
	}
	return hmac.Equal([]byte(parts[1]), []byte(s.sign(parts[0]+"|"+s.cfg.Password)))
}

func (s *server) tooMany(ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	var keep []time.Time
	for _, t := range s.failures[ip] {
		if time.Since(t) < 15*time.Minute {
			keep = append(keep, t)
		}
	}
	s.failures[ip] = keep
	return len(keep) >= 6
}

func (s *server) fail(ip string) {
	s.mu.Lock()
	s.failures[ip] = append(s.failures[ip], time.Now())
	s.mu.Unlock()
}

const loginPage = `<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>پیام‌بین — ورود</title><style>
body{font-family:"Segoe UI",Tahoma,sans-serif;background:#f3f5f7;display:grid;place-items:center;min-height:100vh;margin:0}
form{background:#fff;border:1px solid #e3e7ec;border-radius:16px;padding:28px;width:min(340px,90vw);text-align:center}
h1{font-size:19px;margin:0 0 6px}p{color:#64748b;margin:0 0 16px;font-size:14px;line-height:1.8}
input{width:100%%;box-sizing:border-box;padding:11px;border:1px solid #e3e7ec;border-radius:10px;font-size:16px;direction:ltr;text-align:center}
button{width:100%%;margin-top:12px;padding:11px;border:0;border-radius:10px;background:#0f766e;color:#fff;font-size:15px;cursor:pointer}
.e{color:#b91c1c;margin-top:10px;min-height:20px}</style></head><body>
<form method="post" action="/__login"><h1>پیام‌بین — دسترسی از اینترنت</h1><p>رمز سرور واسطه را وارد کنید</p>
<input type="password" name="password" autofocus autocomplete="current-password"><button>ورود</button><div class="e">%s</div></form></body></html>`

func (s *server) handleLogin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	ip := clientIP(r)
	if r.Method == http.MethodPost {
		if s.tooMany(ip) {
			w.WriteHeader(429)
			fmt.Fprintf(w, loginPage, "تلاش زیاد؛ ۱۵ دقیقه صبر کنید")
			return
		}
		pw := r.FormValue("password")
		if subtle.ConstantTimeCompare([]byte(pw), []byte(s.cfg.Password)) == 1 {
			http.SetCookie(w, &http.Cookie{Name: "pbr", Value: s.makeCookie(), Path: "/", HttpOnly: true, Secure: true,
				SameSite: http.SameSiteStrictMode, MaxAge: 30 * 24 * 3600})
			http.Redirect(w, r, "/", http.StatusSeeOther)
			log.Printf("ورود موفق از %s", ip)
			return
		}
		s.fail(ip)
		time.Sleep(800 * time.Millisecond)
		w.WriteHeader(401)
		fmt.Fprintf(w, loginPage, "رمز اشتباه است")
		return
	}
	fmt.Fprintf(w, loginPage, "")
}

var hopHeaders = map[string]bool{
	"connection": true, "keep-alive": true, "proxy-connection": true, "transfer-encoding": true,
	"upgrade": true, "te": true, "trailer": true, "x-pb-relay": true, "expect": true,
}

func (s *server) handleProxy(w http.ResponseWriter, r *http.Request) {
	if !s.validCookie(r) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.Error(w, `{"error":"relay login required"}`, http.StatusUnauthorized)
			return
		}
		http.Redirect(w, r, "/__login", http.StatusSeeOther)
		return
	}
	a := s.current()
	if a == nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusServiceUnavailable)
		fmt.Fprint(w, `<!doctype html><html dir="rtl"><meta charset="utf-8"><body style="font-family:Tahoma;text-align:center;padding:60px;color:#334155">
<h2>📵 گوشی به سرور وصل نیست</h2><p>گوشی خاموش است، اینترنت ندارد، یا «دسترسی از اینترنت» در پیام‌بین خاموش است.</p>
<p><a href="/">دوباره امتحان کنید</a></p></body></html>`)
		return
	}

	// سرآیندها (کوکی ورود سرور و سرآیندهای اتصال حذف می‌شوند)
	hdr := map[string]string{}
	for k, v := range r.Header {
		lk := strings.ToLower(k)
		if hopHeaders[lk] {
			continue
		}
		if lk == "cookie" {
			var keep []string
			for _, part := range strings.Split(strings.Join(v, "; "), ";") {
				p := strings.TrimSpace(part)
				if p != "" && !strings.HasPrefix(p, "pbr=") {
					keep = append(keep, p)
				}
			}
			if len(keep) > 0 {
				hdr["Cookie"] = strings.Join(keep, "; ")
			}
			continue
		}
		hdr[k] = strings.Join(v, ", ")
	}
	hdr["X-PB-Relay"] = s.cfg.AgentKey // گوشی بداند درخواست از سرور واسطه‌ی مجاز آمده
	hdr["X-Forwarded-For"] = clientIP(r)
	if r.ContentLength > 0 {
		hdr["Content-Length"] = fmt.Sprint(r.ContentLength)
	}
	open, _ := json.Marshal(map[string]any{"method": r.Method, "path": r.URL.RequestURI(), "headers": hdr})

	id, st := a.open()
	defer a.done(id)
	if a.write(fOpen, id, open) != nil {
		http.Error(w, "phone disconnected", 502)
		return
	}
	// بدنه‌ی درخواست
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := r.Body.Read(buf)
			if n > 0 {
				if a.write(fData, id, append([]byte(nil), buf[:n]...)) != nil {
					return
				}
			}
			if err != nil {
				break
			}
		}
		a.write(fEnd, id, nil)
	}()

	flusher, _ := w.(http.Flusher)
	ctx := r.Context()
	gotHead := false
	timeout := time.NewTimer(60 * time.Second)
	defer timeout.Stop()
	for {
		select {
		case <-ctx.Done():
			a.write(fClose, id, nil)
			return
		case <-st.closed:
			if !gotHead {
				http.Error(w, "phone disconnected", 502)
			}
			return
		case <-timeout.C:
			if !gotHead {
				a.write(fClose, id, nil)
				http.Error(w, "phone timeout", 504)
				return
			}
		case f := <-st.ch:
			switch f.t {
			case fResp:
				var resp struct {
					Status  int               `json:"status"`
					Headers map[string]string `json:"headers"`
				}
				json.Unmarshal(f.data, &resp)
				for k, v := range resp.Headers {
					if hopHeaders[strings.ToLower(k)] {
						continue
					}
					w.Header().Set(k, v)
				}
				if resp.Status == 0 {
					resp.Status = 200
				}
				w.WriteHeader(resp.Status)
				if flusher != nil {
					flusher.Flush()
				}
				gotHead = true
			case fData:
				if _, err := w.Write(f.data); err != nil {
					a.write(fClose, id, nil)
					return
				}
				if flusher != nil {
					flusher.Flush()
				}
			case fEnd, fClose:
				if !gotHead {
					http.Error(w, "phone error", 502)
				}
				return
			}
		}
	}
}

func (s *server) handleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	a := s.current()
	json.NewEncoder(w).Encode(map[string]any{"phone": a != nil})
}

func main() {
	addr := flag.String("listen", ":8443", "آدرس و پورت")
	certFile := flag.String("cert", "", "فایل گواهی دامنه (اختیاری)")
	keyFile := flag.String("key", "", "فایل کلید گواهی (اختیاری)")
	host := flag.String("host", "", "آدرس عمومی سرور (IP یا دامنه) برای کد راه‌اندازی")
	dir := flag.String("dir", ".", "پوشه‌ی تنظیمات")
	resetPw := flag.Bool("new-password", false, "ساخت رمز ورود جدید")
	flag.Parse()

	os.MkdirAll(*dir, 0700)
	cfgPath := filepath.Join(*dir, "relay.json")
	if *resetPw {
		c, _ := loadConfig(cfgPath)
		c.Password = randPassword()
		data, _ := json.MarshalIndent(c, "", "  ")
		os.WriteFile(cfgPath, data, 0600)
	}
	cfg, _ := loadConfig(cfgPath)

	var cert tls.Certificate
	var err error
	pin := ""
	if *certFile != "" {
		cert, err = tls.LoadX509KeyPair(*certFile, *keyFile)
	} else {
		cert, err = selfSigned(filepath.Join(*dir, "relay-cert.pem"), filepath.Join(*dir, "relay-key.pem"))
		pin = certPin(cert)
	}
	if err != nil {
		log.Fatalf("گواهی: %v", err)
	}

	s := &server{cfg: cfg, failures: map[string][]time.Time{}}
	mux := http.NewServeMux()
	mux.HandleFunc("/__agent", s.handleAgent)
	mux.HandleFunc("/__login", s.handleLogin)
	mux.HandleFunc("/__status", s.handleStatus)
	mux.HandleFunc("/", s.handleProxy)

	_, port, _ := net.SplitHostPort(*addr)
	h := *host
	if h == "" {
		h = "IP-سرور"
	}
	code := fmt.Sprintf("pbrelay://%s:%s?k=%s", h, port, cfg.AgentKey)
	if pin != "" {
		code += "&p=" + pin
	}
	fmt.Println("════════════════════════════════════════════════════")
	fmt.Println(" PayamBin Relay")
	fmt.Println("════════════════════════════════════════════════════")
	fmt.Printf(" آدرس مرورگر:   https://%s:%s\n", h, port)
	fmt.Printf(" رمز ورود:      %s\n", cfg.Password)
	fmt.Println(" کد راه‌اندازی گوشی (در پیام‌بین ← تنظیمات ← دسترسی از اینترنت):")
	fmt.Println(" " + code)
	fmt.Println("════════════════════════════════════════════════════")

	srv := &http.Server{
		Addr:    *addr,
		Handler: mux,
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
			NextProtos:   []string{"http/1.1"}, // HTTP/1.1 تا اتصال گوشی (Hijack) کار کند
		},
		ReadHeaderTimeout: 20 * time.Second,
		ErrorLog:          log.New(io.Discard, "", 0),
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatal(err)
	}
	if err := srv.Serve(tls.NewListener(ln, srv.TLSConfig)); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
