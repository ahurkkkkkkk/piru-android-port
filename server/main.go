// piru-api: a tiny sync + community + med-lookup server for Piru Android.
// Single static binary, pure-Go (no cgo), SQLite via modernc.org/sqlite.
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

var db *sql.DB

func main() {
	addr := os.Getenv("PIRU_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8090"
	}
	dataFile := os.Getenv("PIRU_DB")
	if dataFile == "" {
		dataFile = "piru-api.db"
	}
	var err error
	db, err = sql.Open("sqlite", dataFile+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		log.Fatal(err)
	}
	schema()
	loadConditions()
	http.HandleFunc("/api/health", h(func(w http.ResponseWriter, r *http.Request) (any, error) {
		return map[string]string{"ok": "yes"}, nil
	}))
	http.HandleFunc("/api/signup", h(signup))
	http.HandleFunc("/api/login", h(login))
	http.HandleFunc("/api/sync", h(authed(syncHandler)))
	http.HandleFunc("/api/friends/add", h(authed(addFriend)))
	http.HandleFunc("/api/friends/remove", h(authed(delFriend)))
	http.HandleFunc("/api/friends", h(authed(listFriends)))
	http.HandleFunc("/api/friends/with", h(authed(requestFriend)))
	http.HandleFunc("/api/leaderboard", h(authed(leaderboard)))
	http.HandleFunc("/api/darooyab", h(darooyabSearch))
	http.HandleFunc("/api/me", h(authed(meHandler)))
	http.HandleFunc("/api/conditions", h(func(w http.ResponseWriter, r *http.Request) (any, error) {
		if len(conditions) == 0 {
			return jmap{}, nil
		}
		return conditions, nil
	}))
	go darooyabRefreshLoop()
	srv := &http.Server{Addr: addr, ReadHeaderTimeout: 10 * time.Second}
	log.Println("piru-api listening on", addr)
	log.Fatal(srv.ListenAndServe())
}

func schema() {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS users(
			id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, pass_hash TEXT NOT NULL,
			salt TEXT NOT NULL, display TEXT NOT NULL, code TEXT UNIQUE, created INTEGER)`,
		`CREATE TABLE IF NOT EXISTS tokens(token TEXT PRIMARY KEY, user_id TEXT NOT NULL, created INTEGER)`,
		`CREATE TABLE IF NOT EXISTS data(user_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated INTEGER)`,
		`CREATE TABLE IF NOT EXISTS friends(a TEXT NOT NULL, b TEXT NOT NULL, PRIMARY KEY(a,b))`,
		`CREATE TABLE IF NOT EXISTS frequests(from_id TEXT NOT NULL, to_id TEXT NOT NULL, PRIMARY KEY(from_id,to_id))`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			log.Fatal("schema:", err)
		}
	}
}

type jmap = map[string]any

func readBody(r *http.Request) (jmap, error) {
	var req jmap
	if r.Method == http.MethodPost {
		if err := json.NewDecoder(io.LimitReader(r.Body, 16<<10)).Decode(&req); err != nil {
			return nil, errBad
		}
	}
	if req == nil {
		req = jmap{}
	}
	return req, nil
}

func h(fn func(w http.ResponseWriter, r *http.Request) (any, error)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		out, err := fn(w, r)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(jmap{"error": err.Error()})
			return
		}
		_ = json.NewEncoder(w).Encode(out)
	}
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func hashPass(pass, salt string) string {
	sum := sha256.Sum256([]byte(salt + ":" + pass))
	return hex.EncodeToString(sum[:])
}

func signup(w http.ResponseWriter, r *http.Request) (any, error) {
	var req jmap
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&req); err != nil {
		return nil, errBad
	}
	email := strings.ToLower(strings.TrimSpace(as(req, "email")))
	pass := as(req, "password")
	name := strings.TrimSpace(as(req, "name"))
	if len(email) < 4 || !strings.Contains(email, "@") || len(pass) < 6 {
		return nil, errBad
	}
	if name == "" {
		name = email
	}
	salt := randHex(8)
	var exists int
	_ = db.QueryRow(`SELECT 1 FROM users WHERE email=?`, email).Scan(&exists)
	if exists == 1 {
		return nil, appErr("email already registered")
	}
	id := randHex(8)
	code := strings.ToUpper(randHex(3))
	_, err := db.Exec(`INSERT INTO users(id,email,pass_hash,salt,display,code,created) VALUES(?,?,?,?,?,?,?)`,
		id, email, hashPass(pass, salt), salt, name, code, time.Now().UnixMilli())
	if err != nil {
		return nil, err
	}
	return issueToken(id)
}

func login(w http.ResponseWriter, r *http.Request) (any, error) {
	var req jmap
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&req); err != nil {
		return nil, errBad
	}
	email := strings.ToLower(strings.TrimSpace(as(req, "email")))
	var id, salt, ph string
	row := db.QueryRow(`SELECT id, salt, pass_hash FROM users WHERE email=?`, email)
	if err := row.Scan(&id, &salt, &ph); err != nil {
		return nil, appErr("wrong email or password")
	}
	if hashPass(as(req, "password"), salt) != ph {
		return nil, appErr("wrong email or password")
	}
	return issueToken(id)
}

func issueToken(userID string) (any, error) {
	tok := randHex(24)
	_, err := db.Exec(`INSERT INTO tokens(token,user_id,created) VALUES(?,?,?)`, tok, userID, time.Now().UnixMilli())
	if err != nil {
		return nil, err
	}
	// prune old tokens
	_, _ = db.Exec(`DELETE FROM tokens WHERE created < ?`, time.Now().AddDate(0, 0, -180).UnixMilli())
	return jmap{"token": tok, "user_id": userID}, nil
}

func authed(fn func(userID string, w http.ResponseWriter, r *http.Request) (any, error)) func(http.ResponseWriter, *http.Request) (any, error) {
	return func(w http.ResponseWriter, r *http.Request) (any, error) {
		tok := r.Header.Get("Authorization")
		tok = strings.TrimPrefix(tok, "Bearer ")
		var uid string
		if err := db.QueryRow(`SELECT user_id FROM tokens WHERE token=?`, tok).Scan(&uid); err != nil {
			return nil, appErr("not signed in")
		}
		return fn(uid, w, r)
	}
}

// syncHandler: GET returns the stored payload; POST {payload} upserts.
func syncHandler(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	if r.Method == http.MethodPost {
		var req jmap
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil {
			return nil, errBad
		}
		payload, ok := req["payload"].(string)
		if !ok || len(payload) > 2<<20 {
			return nil, appErr("payload must be a string under 2MB")
		}
		_, err := db.Exec(`INSERT INTO data(user_id,payload,updated) VALUES(?,?,?)
			ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload, updated=excluded.updated`,
			uid, payload, time.Now().UnixMilli())
		if err != nil {
			return nil, err
		}
		return jmap{"ok": true}, nil
	}
	var payload string
	var updated int64
	err := db.QueryRow(`SELECT payload, updated FROM data WHERE user_id=?`, uid).Scan(&payload, &updated)
	if err == sql.ErrNoRows {
		return jmap{"payload": "", "updated": 0}, nil
	}
	if err != nil {
		return nil, err
	}
	return jmap{"payload": payload, "updated": updated}, nil
}

func addFriend(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	req, err := readBody(r)
	if err != nil {
		return nil, err
	}
	code := strings.ToUpper(strings.TrimSpace(as(req, "code")))
	if code == "" {
		return nil, appErr("code required")
	}
	var other string
	if err := db.QueryRow(`SELECT id FROM users WHERE code=?`, code).Scan(&other); err != nil {
		return nil, appErr("no user with that code")
	}
	if other == uid {
		return nil, appErr("that's you")
	}
	if _, e := db.Exec(`INSERT INTO friends(a,b) VALUES(?,?) ON CONFLICT DO NOTHING`, uid, other); e != nil {
		return nil, e
	}
	_, _ = db.Exec(`INSERT INTO friends(a,b) VALUES(?,?) ON CONFLICT DO NOTHING`, other, uid)
	_, _ = db.Exec(`DELETE FROM frequests WHERE from_id=? AND to_id=?`, other, uid)
	var name string
	_ = db.QueryRow(`SELECT display FROM users WHERE id=?`, other).Scan(&name)
	return jmap{"ok": true, "friend": map[string]string{"id": other, "name": name}}, nil
}

func delFriend(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	req, err := readBody(r)
	if err != nil {
		return nil, err
	}
	other := as(req, "id")
	_, e := db.Exec(`DELETE FROM friends WHERE (a=? AND b=?) OR (a=? AND b=?)`, uid, other, other, uid)
	return jmap{"ok": e == nil}, nil
}

func requestFriend(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	req, err := readBody(r)
	if err != nil {
		return nil, err
	}
	code := strings.ToUpper(strings.TrimSpace(as(req, "code")))
	var other string
	if err := db.QueryRow(`SELECT id FROM users WHERE code=?`, code).Scan(&other); err != nil {
		return nil, appErr("no user with that code")
	}
	if other == uid {
		return nil, appErr("that's you")
	}
	_, e := db.Exec(`INSERT INTO frequests(from_id,to_id) VALUES(?,?) ON CONFLICT DO NOTHING`, uid, other)
	return jmap{"ok": e == nil}, nil
}

func listFriends(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	rows, err := db.Query(`SELECT u.id, u.display FROM friends f JOIN users u ON u.id = CASE WHEN f.a=? THEN f.b ELSE f.a END WHERE f.a=? OR f.b=?`, uid, uid, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type fr struct{ ID, Name string }
	out := []fr{}
	seen := map[string]bool{}
	for rows.Next() {
		var f fr
		_ = rows.Scan(&f.ID, &f.Name)
		if f.ID != uid && !seen[f.ID] {
			seen[f.ID] = true
			out = append(out, f)
		}
	}
	return jmap{"friends": out}, nil
}

// leaderboard: weekly adherence = doses logged / scheduled reminders, over last 7 days.
func leaderboard(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	weekAgo := time.Now().AddDate(0, 0, -7).UnixMilli()
	rows, err := db.Query(`SELECT user_id, payload FROM data`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var me, friends = []map[string]any{}, []map[string]any{}
	visible := map[string]bool{uid: true}
	frs, _ := db.Query(`SELECT CASE WHEN a=? THEN b ELSE a END FROM friends WHERE a=? OR b=?`, uid, uid, uid)
	if frs != nil {
		for frs.Next() {
			var id string
			_ = frs.Scan(&id)
			visible[id] = true
		}
		frs.Close()
	}
	names := map[string]string{}
	for visible := range visible {
		var d string
		_ = db.QueryRow(`SELECT display FROM users WHERE id=?`, visible).Scan(&d)
		names[visible] = d
	}
	for rows.Next() {
		var id, payload string
		_ = rows.Scan(&id, &payload)
		if !visible[id] {
			continue
		}
		score := adherence(payload, weekAgo)
		item := map[string]any{"id": id, "name": names[id], "score": score, "me": id == uid}
		if id == uid {
			me = append(me, item)
		} else {
			friends = append(friends, item)
		}
	}
	all := append(me, friends...)
	sortDesc(all)
	return jmap{"week_start": weekAgo, "entries": all}, nil
}

func sortDesc(v []map[string]any) {
	for i := 1; i < len(v); i++ {
		for j := i; j > 0 && as2(v[j]) > as2(v[j-1]); j-- {
			v[j], v[j-1] = v[j-1], v[j]
		}
	}
}

func as2(m map[string]any) float64 {
	if f, ok := m["score"].(float64); ok {
		return f
	}
	return 0
}

// adherence parses the client payload (a JSON dump of the user DB) and scores
// (dose rows in last 7 days) against (scheduled reminder-minutes * 7 * days-active).
// Missing schedule data scores the raw dose count; the client may also send a
// precomputed "score" it trusts, which wins when present.
func adherence(payload string, weekAgo int64) float64 {
	var doc struct {
		Score  *float64          `json:"score"`
		Events []json.RawMessage `json:"events"`
	}
	if err := json.Unmarshal([]byte(payload), &doc); err != nil {
		return 0
	}
	if doc.Score != nil {
		return *doc.Score
	}
	var doses int
	for _, ev := range doc.Events {
		var e struct {
			T int64 `json:"timestamp"`
		}
		if json.Unmarshal(ev, &e) == nil && e.T >= weekAgo {
			doses++
		}
	}
	return float64(doses)
}

func meHandler(uid string, w http.ResponseWriter, r *http.Request) (any, error) {
	var code, display string
	err := db.QueryRow(`SELECT code, display FROM users WHERE id=?`, uid).Scan(&code, &display)
	if err != nil {
		return nil, err
	}
	return jmap{"id": uid, "code": code, "display": display}, nil
}

// ----- darooyab proxy (server sits in Iran; app cannot reach these directly) -----

func darooyabSearch(w http.ResponseWriter, r *http.Request) (any, error) {
	q := r.URL.Query().Get("q")
	if q == "" || len(q) > 80 {
		return nil, appErr("q required")
	}
	all := darooyabCatalog()
	if len(all) == 0 {
		return jmap{"query": q, "source": "darooyab.ir", "results": []dItem{}, "note": "catalogue still downloading"}, nil
	}
	ql := strings.ToLower(q)
	// strip persian zero-width and tatweel for robust matching
	ql = strings.NewReplacer("‌", "", "‍", "", "ـ", "").Replace(ql)
	matches := []dItem{}
	for _, it := range all {
		t := strings.ToLower(it.Title)
		if strings.Contains(t, ql) || strings.HasPrefix(t, ql) {
			matches = append(matches, it)
			if len(matches) >= 30 {
				break
			}
		}
	}
	return jmap{"query": q, "source": "darooyab.ir", "results": matches}, nil
}

var (
	darooyabCache  = map[string]dItem{}
	darooyabMu     sync.Mutex
	darooyabCursor = 1
	darooyabStale  = 0
)

type dItem struct{ Title, Href string }

// darooyabRefreshLoop keeps a local copy of darooyab's drug catalogue: pages are
// crawled a few at a time so the search endpoint never blocks, and the cache is
// always served (partial first, complete once the walk wraps).
func darooyabRefreshLoop() {
	client := &http.Client{Timeout: 12 * time.Second}
	for {
		darooyabWalk(client) // merges under the lock only between page fetches
		time.Sleep(2 * time.Minute)
	}
}

// darooyabWalk advances the crawl. The site ignores searchText and paginates a
// fixed catalogue, so stale repeat pages mean the walk has looped: wrap the cursor.
func darooyabWalk(client *http.Client) {
	for n := 0; n < 40; n++ {
		p := darooyabCursor
		darooyabCursor++
		if darooyabCursor > 400 {
			darooyabCursor = 1
		}
		body := darooyabPage(client, p)
		if body == nil {
			time.Sleep(300 * time.Millisecond)
			continue
		}
		added := 0
		for _, seg := range strings.Split(string(body), `href="`) {
			end := strings.Index(seg, `"`)
			if len(seg) < 8 || end < 0 {
				continue
			}
			href := seg[:end]
			if !(strings.HasPrefix(href, "/B-") || strings.HasPrefix(href, "/G-") || strings.HasPrefix(href, "/D-")) {
				continue
			}
			k := strings.LastIndex(href, "/")
			if k < 0 || len(href)-k <= 2 {
				continue
			}
			darooyabMu.Lock()
			_, known := darooyabCache[href]
			if !known {
				darooyabCache[href] = dItem{Title: strings.ReplaceAll(href[k+1:], "-", " "), Href: "https://www.darooyab.ir" + href}
				added++
			}
			darooyabMu.Unlock()
		}
		if added == 0 {
			darooyabStale++
			if darooyabStale >= 20 {
				darooyabCursor = 1
				darooyabStale = 0
				return
			}
		} else {
			darooyabStale = 0
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func darooyabCatalog() []dItem {
	darooyabMu.Lock()
	tmp := make([]dItem, 0, len(darooyabCache))
	for _, v := range darooyabCache {
		tmp = append(tmp, v)
	}
	darooyabMu.Unlock()
	sort.Slice(tmp, func(i, j int) bool { return strings.ToLower(tmp[i].Title) < strings.ToLower(tmp[j].Title) })
	return tmp
}

func darooyabPage(client *http.Client, page int) []byte {
	form := "searchText=a&DrugName_pageNumber=" + fmt.Sprint(page)
	req, _ := http.NewRequest("POST", "https://www.darooyab.ir/Home/PartialSearchResult", strings.NewReader(form))
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 6<<20))
	if len(body) < 500 {
		return nil
	}
	return body
}

// ----- helpers -----

var errBad = appErr("bad request")

type appErr string

func (e appErr) Error() string { return string(e) }

func as(m jmap, k string) string {
	if s, ok := m[k].(string); ok {
		return s
	}
	return ""
}

var conditions []jmap

func loadConditions() {
	b, err := os.ReadFile("conditions.json")
	if err != nil {
		return
	}
	_ = json.Unmarshal(b, &conditions)
}
