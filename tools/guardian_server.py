#!/usr/bin/env python3
"""
보호자 화면 (시연용). 차량 앱의 보호자 알림 웹훅을 받아 휴대폰 모양 페이지로 보여준다.
  python3 tools/guardian_server.py            # http://127.0.0.1:8099
  adb reverse tcp:8099 tcp:8099               # 에뮬레이터 → 호스트
앱: 가족 설정 → "다른 보호자에게 알림" 켜기 (URL 비우면 http://127.0.0.1:8099/event)
받은 이벤트는 메모리에만 두고 events.jsonl 로그(선택 --log)에만 남긴다.
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

EVENTS = []
LOG = sys.argv[sys.argv.index("--log") + 1] if "--log" in sys.argv else None

PAGE = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>피코 보호자 알림</title>
<style>
:root{--bg:#121212;--card:#1c1c1c;--line:#3d3d3d;--fg:#f4efe6;--mute:#b4aca2;--amber:#ffb547;--alert:#ff7a59}
body{margin:0;background:#000;font-family:-apple-system,"Apple SD Gothic Neo",sans-serif;display:flex;justify-content:center}
.phone{width:390px;min-height:100vh;background:var(--bg);color:var(--fg);padding:24px 16px;box-sizing:border-box}
h1{font-size:20px;margin:0 0 4px}.sub{color:var(--mute);font-size:13px;margin-bottom:20px}
.ev{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px;margin-bottom:10px;animation:in .3s}
.ev.alert{border-color:var(--alert)}.ev .t{font-size:17px;font-weight:600}.ev.alert .t{color:var(--alert)}
.ev .m{color:var(--mute);font-size:13px;margin-top:4px}.dot{display:inline-block;width:8px;height:8px;background:var(--amber);margin-right:8px}
.ev.alert .dot{background:var(--alert)}.empty{color:var(--mute);font-size:14px}
@keyframes in{from{opacity:0;transform:translateY(-6px)}to{opacity:1}}
</style></head><body><div class="phone">
<h1>피코 드라이브 · 보호자</h1><div class="sub">차에서 자동으로 보내요 · 별칭과 좌석만, 위치는 없어요</div>
<div id="list"><div class="empty">아직 알림이 없어요</div></div></div>
<script>
const label={departed:"출발했어요",arrived:"도착했어요",alighted:"내렸어요",left_behind:"뒷좌석 확인 알림",left_behind_cleared:"뒷좌석 확인 완료"};
function who(c){return c.map(x=>x.alias||x.seat).join(", ")}
let shown=0;
async function tick(){
  const r=await fetch("/events");const evs=await r.json();if(evs.length===shown)return;shown=evs.length;
  document.getElementById("list").innerHTML=evs.slice().reverse().map(e=>{
    const alert=e.type==="left_behind";const t=new Date(e.at).toLocaleTimeString("ko-KR");
    const w=who(e.children||[]);const title=(w?w+" ":"")+(label[e.type]||e.type);
    const m=alert?"운전석이 비었는데 뒷좌석 착석 신호가 남아 있어요 · 보조 알림":e.children.map(c=>c.seat).join(", ");
    return `<div class="ev ${alert?"alert":""}"><div class="t"><span class="dot"></span>${title}</div><div class="m">${t}${m?" · "+m:""}</div></div>`}).join("")}
setInterval(tick,1000);tick();
</script></body></html>"""


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/events":
            self._send(200, json.dumps(EVENTS, ensure_ascii=False), "application/json; charset=utf-8")
        else:
            self._send(200, PAGE, "text/html; charset=utf-8")

    def do_POST(self):
        if self.path != "/event":
            return self._send(404, "", "text/plain")
        n = int(self.headers.get("Content-Length", 0))
        try:
            ev = json.loads(self.rfile.read(n))
        except ValueError:
            return self._send(400, "bad json", "text/plain")
        ev["received"] = int(time.time() * 1000)
        EVENTS.append(ev)
        if LOG:
            with open(LOG, "a") as f:
                f.write(json.dumps(ev, ensure_ascii=False) + "\n")
        print("event", ev.get("type"), [c.get("seat") for c in ev.get("children", [])], flush=True)
        self._send(200, "ok", "text/plain")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print("guardian page http://127.0.0.1:8099", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8099), H).serve_forever()
