// Command dapprobe talks to Victoria 3's built-in script debugger, which is a
// Debug Adapter Protocol server on a loopback TCP socket. Launch the game with
// -script_debugger and point this at it.
//
// It is the reference client for this repository: every transcript in
// ../../docs/SCRIPT_DEBUGGER.md was produced with it, and it is the quickest
// way to check a protocol question against a running game before committing
// the plugin to an assumption. It is a probe, not a library -- the plugin
// speaks DAP through IntelliJ rather than through this.
//
// Run it from this directory, which is its own Go module:
//
//	go run . -script sessions/inspect.jsonl
//	go run . -addr 127.0.0.1:8080 -script sessions/logpoint-tap.jsonl -idle 6
//
// The script is one JSON object per line. A line with a "command" is sent as a
// DAP request; the tool fills in seq/type and waits for the response. Lines
// starting with # are comments. Three directives drive the parts that need a
// reply before the next step can be built:
//
//	{"_wait": 1.5}                              drain events for N seconds
//	{"_explore": true}                          stackTrace -> scopes -> variables, recursively
//	{"_eval": ["this.gdp", ...], "frameId": 0}  evaluate a batch, one line each
//
// Everything sent and received is printed, except during _explore/_eval where
// the raw traffic is suppressed in favour of a readable tree.
//
// Two things about this server are worth knowing before you read its output.
// Requests that need a halted game (stackTrace, scopes, variables, evaluate)
// fail with "not stopped" while it is running, so a session that reads data
// has to pause or hit a breakpoint first. And unrecognised commands are
// answered success:true with an empty body rather than rejected, so you cannot
// feature-detect by probing -- the initialize response is the only capability
// signal.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type msg map[string]any

var (
	conn    net.Conn
	seq     = 1
	seqMu   sync.Mutex
	resps   = map[int]msg{}
	respCh  = map[int]chan msg{}
	respMu  sync.Mutex
	eventsN int
	quiet   bool
)

func send(m msg) int {
	seqMu.Lock()
	defer seqMu.Unlock()
	m["seq"] = seq
	m["type"] = "request"
	id := seq
	seq++
	b, _ := json.Marshal(m)
	if !quiet {
		fmt.Printf("\n>>> %s\n", string(b))
	}
	fmt.Fprintf(conn, "Content-Length: %d\r\n\r\n", len(b))
	conn.Write(b)
	return id
}

// request sends and waits for the matching response, registering the waiter
// before the send so a fast reply cannot be missed.
func request(cmd string, args msg, d time.Duration) msg {
	ch := make(chan msg, 1)
	seqMu.Lock()
	id := seq
	seqMu.Unlock()
	respMu.Lock()
	respCh[id] = ch
	respMu.Unlock()
	send(msg{"command": cmd, "arguments": args})
	select {
	case m := <-ch:
		return m
	case <-time.After(d):
		return nil
	}
}

func reader(r *bufio.Reader, done chan struct{}) {
	defer close(done)
	for {
		length := -1
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				return
			}
			line = strings.TrimRight(line, "\r\n")
			if line == "" {
				break
			}
			if strings.HasPrefix(strings.ToLower(line), "content-length:") {
				length, _ = strconv.Atoi(strings.TrimSpace(line[len("content-length:"):]))
			}
		}
		if length < 0 {
			return
		}
		buf := make([]byte, length)
		for off := 0; off < length; {
			n, err := r.Read(buf[off:])
			if err != nil {
				return
			}
			off += n
		}
		var m msg
		if err := json.Unmarshal(buf, &m); err != nil {
			fmt.Printf("<<< [unparsable] %s\n", string(buf))
			continue
		}
		pretty, _ := json.MarshalIndent(m, "", "  ")
		switch m["type"] {
		case "response":
			rs := 0
			if f, ok := m["request_seq"].(float64); ok {
				rs = int(f)
			}
			if !quiet {
				fmt.Printf("<<< RESPONSE (to %d)\n%s\n", rs, string(pretty))
			}
			respMu.Lock()
			resps[rs] = m
			if ch, ok := respCh[rs]; ok {
				select {
				case ch <- m:
				default:
				}
			}
			respMu.Unlock()
		case "event":
			eventsN++
			fmt.Printf("<<< EVENT\n%s\n", string(pretty))
		default:
			fmt.Printf("<<< %s\n", string(pretty))
		}
	}
}

func waitFor(id int, d time.Duration) {
	ch := make(chan msg, 1)
	respMu.Lock()
	if _, ok := resps[id]; ok {
		respMu.Unlock()
		return
	}
	respCh[id] = ch
	respMu.Unlock()
	select {
	case <-ch:
	case <-time.After(d):
		fmt.Printf("[timeout waiting for response to %d]\n", id)
	}
}

func body(m msg) map[string]any {
	if m == nil {
		return nil
	}
	b, _ := m["body"].(map[string]any)
	return b
}

func explore(maxDepth, maxChildren int) {
	quiet = true
	defer func() { quiet = false }()
	fmt.Println("\n================ EXPLORE ================")
	b := body(request("stackTrace", msg{"threadId": 1, "startFrame": 0, "levels": 100}, 8*time.Second))
	if b == nil {
		fmt.Println("[explore] no stackTrace body -- probably not stopped")
		fmt.Println("========== END EXPLORE ==========")
		return
	}
	fmt.Printf("[explore] totalFrames=%v\n", b["totalFrames"])
	frames, _ := b["stackFrames"].([]any)
	for fi, fa := range frames {
		f, _ := fa.(map[string]any)
		src := ""
		if s, ok := f["source"].(map[string]any); ok {
			src = fmt.Sprintf("%v", s["name"])
		}
		fmt.Printf("\nFRAME[%d] id=%v name=%q line=%v source=%s\n", fi, f["id"], f["name"], f["line"], src)
		sb := body(request("scopes", msg{"frameId": f["id"]}, 8*time.Second))
		if sb == nil {
			continue
		}
		scopes, _ := sb["scopes"].([]any)
		for _, sa := range scopes {
			s, _ := sa.(map[string]any)
			fmt.Printf("  SCOPE %q ref=%v expensive=%v\n", s["name"], s["variablesReference"], s["expensive"])
			if r, ok := s["variablesReference"].(float64); ok && r > 0 {
				walk(int(r), "    ", 1, maxDepth, maxChildren)
			}
		}
	}
	fmt.Println("========== END EXPLORE ==========")
}

func walk(ref int, indent string, depth, maxDepth, maxChildren int) {
	if depth > maxDepth {
		fmt.Printf("%s... (max depth)\n", indent)
		return
	}
	vb := body(request("variables", msg{"variablesReference": ref}, 8*time.Second))
	if vb == nil {
		return
	}
	vars, _ := vb["variables"].([]any)
	for i, va := range vars {
		if i >= maxChildren {
			fmt.Printf("%s... (%d more)\n", indent, len(vars)-i)
			break
		}
		m, _ := va.(map[string]any)
		child := 0.0
		if c, ok := m["variablesReference"].(float64); ok {
			child = c
		}
		suffix := ""
		if child > 0 {
			suffix = fmt.Sprintf(" [ref=%v]", m["variablesReference"])
		}
		fmt.Printf("%s%v = %v%s\n", indent, m["name"], m["value"], suffix)
		if child > 0 {
			walk(int(child), indent+"  ", depth+1, maxDepth, maxChildren)
		}
	}
}

func evalBatch(exprs []any, frameID any, ctx string) {
	quiet = true
	defer func() { quiet = false }()
	fmt.Printf("\n=========== EVAL (context=%s frameId=%v) ===========\n", ctx, frameID)
	for _, ea := range exprs {
		e, _ := ea.(string)
		args := msg{"expression": e, "context": ctx}
		if frameID != nil {
			args["frameId"] = frameID
		}
		m := request("evaluate", args, 8*time.Second)
		if m == nil {
			fmt.Printf("  %-46s -> [timeout]\n", e)
			continue
		}
		if ok, _ := m["success"].(bool); !ok {
			fmt.Printf("  %-46s -> FAIL %v\n", e, m["message"])
			continue
		}
		res := ""
		if b := body(m); b != nil {
			res = fmt.Sprintf("%v", b["result"])
			if r, ok := b["variablesReference"].(float64); ok && r > 0 {
				res += fmt.Sprintf("  [ref=%v]", b["variablesReference"])
			}
		}
		fmt.Printf("  %-46s -> %s\n", e, res)
	}
	fmt.Println("=========== END EVAL ===========")
}

func main() {
	addr := flag.String("addr", "127.0.0.1:6111", "script debugger address")
	script := flag.String("script", "", "path to a .jsonl session script")
	idle := flag.Float64("idle", 2, "seconds to keep draining events after the script")
	flag.Parse()
	if *script == "" {
		flag.Usage()
		os.Exit(2)
	}

	var err error
	conn, err = net.DialTimeout("tcp", *addr, 5*time.Second)
	if err != nil {
		fmt.Printf("dial %s: %v\n", *addr, err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Printf("connected to %s\n", *addr)

	done := make(chan struct{})
	go reader(bufio.NewReaderSize(conn, 1<<20), done)

	f, err := os.Open(*script)
	if err != nil {
		fmt.Printf("open %s: %v\n", *script, err)
		os.Exit(1)
	}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		var m msg
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			fmt.Printf("[bad script line] %v: %s\n", err, line)
			continue
		}
		switch {
		case m["_explore"] != nil:
			explore(4, 40)
		case m["_eval"] != nil:
			list, _ := m["_eval"].([]any)
			ctx := "repl"
			if c, ok := m["context"].(string); ok {
				ctx = c
			}
			evalBatch(list, m["frameId"], ctx)
		case m["_wait"] != nil:
			d := time.Duration(m["_wait"].(float64) * float64(time.Second))
			fmt.Printf("\n... waiting %v\n", d)
			time.Sleep(d)
		default:
			waitFor(send(m), 8*time.Second)
		}
	}
	f.Close()

	fmt.Printf("\n... draining events for %.1fs\n", *idle)
	select {
	case <-done:
	case <-time.After(time.Duration(*idle * float64(time.Second))):
	}
	fmt.Printf("\n=== done, %d events ===\n", eventsN)
}
