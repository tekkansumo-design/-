#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BOOKOFF 在庫店舗チェッカー Web版

使い方:
  pip install flask requests
  python bookoff_web.py
  → ブラウザで http://localhost:5000 を開く

Android(Termux):
  pkg install python
  pip install flask requests
  python bookoff_web.py
  → http://127.0.0.1:5000
"""

import csv
import html as html_mod
import io
import json
import queue
import random
import re
import smtplib
import threading
import time
import traceback
from datetime import datetime
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from flask import Flask, Response, abort, jsonify, request

# ═══════════════════════════════════════════════
BASE_DIR = Path(__file__).resolve().parent
IDS_FILE = BASE_DIR / "bookoff_ids.json"
MAIL_CONF = BASE_DIR / "bookoff_mail_config.json"
PORT = 5000

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
}

PREFECTURES = [
    "北海道", "青森", "岩手", "宮城", "秋田", "山形", "福島",
    "茨城", "栃木", "群馬", "埼玉", "千葉", "東京", "神奈川",
    "新潟", "富山", "石川", "福井", "山梨", "長野",
    "岐阜", "静岡", "愛知", "三重",
    "滋賀", "京都", "大阪", "兵庫", "奈良", "和歌山",
    "鳥取", "島根", "岡山", "広島", "山口",
    "徳島", "香川", "愛媛", "高知",
    "福岡", "佐賀", "長崎", "熊本", "大分", "宮崎", "鹿児島", "沖縄",
]

BACKOFF = [15, 30, 60, 120, 180]

MIN_WORKERS = 1
MAX_WORKERS = 5
START_WORKERS = 3
THREADS = MAX_WORKERS          # スレッド数。実際の同時接続数は Session が絞る

SHOP_PATH_RE = re.compile(r"/shop/shop\d+", re.I)
SHOP_HOST_RE = re.compile(r"(^|\.)bookoff\.co\.jp$", re.I)

# ═══════════════════════════════════════════════
#  状態
# ═══════════════════════════════════════════════
STATE = {
    "running": False,
    "total": 0,
    "done": 0,
    "workers": START_WORKERS,
    "results": {},      # pid -> {"name":..., "shops":[{"name","url"}], "error":...}
    "started": None,
}
CANCEL = threading.Event()
LOCK = threading.Lock()

# SSE は接続ごとに専用キューを持つ（グローバル 1 本だと古いイベントが
# 次の実行に混ざる／複数タブでイベントを奪い合う）
SUBSCRIBERS = set()
SUB_LOCK = threading.Lock()
SUB_QSIZE = 2000


def publish(kind, data):
    """全 SSE 購読者へイベントを配る。購読者が居なければ捨てる。"""
    with SUB_LOCK:
        subs = list(SUBSCRIBERS)
    for q in subs:
        try:
            q.put_nowait((kind, data))
        except queue.Full:
            pass


def snapshot_results():
    """反復中に worker が書き込んで RuntimeError にならないようコピーを返す。"""
    with LOCK:
        return list(STATE["results"].items())


def load_ids():
    if not IDS_FILE.exists():
        IDS_FILE.write_text(json.dumps(["0016309421", "0001189556"],
                                       ensure_ascii=False, indent=1),
                            encoding="utf-8")
    try:
        d = json.loads(IDS_FILE.read_text(encoding="utf-8"))
        if isinstance(d, dict):
            d = d.get("ids", [])
        return [str(x).strip().zfill(10) for x in d if str(x).strip()]
    except Exception as e:
        print(f"[warn] {IDS_FILE.name} を読めません: {e}")
        return []


def save_ids(ids):
    seen, out = set(), []
    for i in ids:
        i = re.sub(r"\D", "", str(i))
        if len(i) >= 8:
            i = i.zfill(10)
            if i not in seen:
                seen.add(i)
                out.append(i)
    IDS_FILE.write_text(json.dumps(out, ensure_ascii=False, indent=1),
                        encoding="utf-8")
    return out


def load_mail():
    if MAIL_CONF.exists():
        try:
            return json.loads(MAIL_CONF.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"from": "", "password": "", "to": "",
            "host": "smtp.gmail.com", "port": 587}


def save_mail(d):
    MAIL_CONF.write_text(json.dumps(d, ensure_ascii=False, indent=2),
                         encoding="utf-8")


def body_json():
    """不正 JSON は 400 で弾く。null 等でも 500 にしない。

    ここで空 dict を返すと、壊れたリクエストで ID 一覧が空上書きされる。
    """
    d = request.get_json(silent=True)
    if not isinstance(d, dict):
        abort(400, description="JSON オブジェクトが必要です")
    return d


# ═══════════════════════════════════════════════
#  スクレイピング
# ═══════════════════════════════════════════════
class Session:
    """アダプティブ並列制御。403/503 で段階的に待って worker を減らす。

    workers はスレッド数ではなく「同時に飛ばしてよいリクエスト数」で、
    acquire()/release() で実際に上限として効かせる。
    """

    def __init__(self):
        self.s = requests.Session()
        self.s.headers.update(HEADERS)
        self.workers = START_WORKERS
        self.ok_streak = 0
        self.err_level = 0
        self.active = 0
        self.cv = threading.Condition()
        STATE["workers"] = self.workers

    def acquire(self):
        """同時実行枠を 1 つ取る。中止されたら False。"""
        with self.cv:
            while self.active >= self.workers and not CANCEL.is_set():
                self.cv.wait(0.5)
            if CANCEL.is_set():
                return False
            self.active += 1
            return True

    def release(self):
        with self.cv:
            self.active -= 1
            self.cv.notify()

    def on_error(self):
        with self.cv:
            self.ok_streak = 0
            self.workers = max(MIN_WORKERS, self.workers - 1)
            wait = BACKOFF[min(self.err_level, len(BACKOFF) - 1)]
            self.err_level += 1
            STATE["workers"] = self.workers
        return wait

    def on_ok(self):
        with self.cv:
            self.ok_streak += 1
            self.err_level = 0
            if self.ok_streak >= 5 and self.workers < MAX_WORKERS:
                self.workers += 1
                self.ok_streak = 0
                STATE["workers"] = self.workers
                self.cv.notify_all()   # 枠が増えたので待機中スレッドを起こす

    def wake_all(self):
        with self.cv:
            self.cv.notify_all()


def _sleep(sec):
    """中止ボタンで即座に抜けられる sleep（最大 180 秒待たされない）。"""
    return CANCEL.wait(sec)


class PageScan(HTMLParser):
    """商品ページから h1 と <a href> を拾うだけの軽量パーサ。

    bs4 も "html.parser"（＝この標準パーサ）を使っていたので解析能力は同等。
    依存を減らすため直接使う（Pydroid 3 で pip するのが flask と requests
    だけで済む）。
    """

    SKIP = {"script", "style"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._links = []         # [[href, [text片, ...]], ...] 出現順
        self._open = []          # 開いている <a> の _links 上の位置（None=href無し）
        self.h1 = None
        self._h1_depth = 0
        self._h1_buf = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip += 1
        elif tag == "a":
            href = dict(attrs).get("href")
            if href:
                self._links.append([href, []])
                self._open.append(len(self._links) - 1)
            else:
                self._open.append(None)
        elif tag == "h1":
            self._h1_depth += 1

    def handle_endtag(self, tag):
        if tag in self.SKIP:
            self._skip = max(0, self._skip - 1)
        elif tag == "a":
            if self._open:
                self._open.pop()
        elif tag == "h1" and self._h1_depth:
            self._h1_depth -= 1
            if self._h1_depth == 0 and self.h1 is None:
                self.h1 = norm_text("".join(self._h1_buf))

    def handle_data(self, data):
        if self._skip:
            return
        if self._open and self._open[-1] is not None:
            self._links[self._open[-1]][1].append(data)
        if self._h1_depth:
            self._h1_buf.append(data)

    def close(self):
        super().close()
        if self.h1 is None and self._h1_buf:   # </h1> 閉じ忘れでも拾う
            self.h1 = norm_text("".join(self._h1_buf))

    @property
    def links(self):
        return [(href, norm_text("".join(buf))) for href, buf in self._links]


def norm_text(s):
    return re.sub(r"\s+", " ", s).strip()


def scan_page(html_text):
    """HTML から (h1, [(href, text), ...]) を返す。"""
    p = PageScan()
    try:
        p.feed(html_text)
    except Exception:
        pass                        # 壊れた HTML でもそこまでの結果を使う
    p.close()
    return p.h1, p.links


def parse_shops(base_url, links):
    """リンク一覧から在庫店舗を抜く。相対 URL にも対応。"""
    shops, seen = [], set()
    for raw_href, text in links:
        href = urljoin(base_url, raw_href.strip())
        u = urlparse(href)
        if u.scheme not in ("http", "https"):
            continue
        if not SHOP_HOST_RE.search(u.hostname or ""):
            continue
        if not SHOP_PATH_RE.search(u.path):
            continue          # 「店舗検索」など実店舗でないリンクを除外
        if not text or text == "店舗検索":
            continue
        key = f"{u.netloc}{u.path}"
        if key in seen:
            continue
        seen.add(key)
        shops.append({"name": text, "url": href})
    return shops


def fetch_stores(sess: Session, pid: str):
    """商品ページから在庫店舗を取る。成功するまでリトライ（中止で打ち切り）。"""
    url = f"https://shopping.bookoff.co.jp/used/{pid}"
    while not CANCEL.is_set():
        if not sess.acquire():
            break
        try:
            r = sess.s.get(url, timeout=25)
        except requests.RequestException as e:
            sess.release()
            wait = sess.on_error()
            publish("status", {"msg": f"通信エラー ({type(e).__name__}) "
                                      f"{wait}秒待機 / 並列 {sess.workers}"})
            _sleep(wait)
            continue
        else:
            sess.release()

        if r.status_code in (403, 429, 503):
            wait = sess.on_error()
            publish("status", {"msg": f"HTTP {r.status_code} — {wait}秒待機 "
                                      f"/ 並列 {sess.workers}"})
            _sleep(wait + random.uniform(0, 2))
            continue
        if r.status_code == 404:
            return {"name": f"(404) {pid}", "shops": [], "error": "404"}
        if r.status_code != 200:
            wait = sess.on_error()
            publish("status", {"msg": f"HTTP {r.status_code} — {wait}秒待機"})
            _sleep(wait)
            continue

        sess.on_ok()
        h1, links = scan_page(r.text)
        return {"name": h1 or pid, "shops": parse_shops(url, links),
                "error": None}

    return {"name": pid, "shops": [], "error": "cancelled"}


def run_check():
    ids = load_ids()
    with LOCK:
        STATE.update(running=True, total=len(ids), done=0,
                     results={}, started=datetime.now().isoformat(),
                     workers=START_WORKERS)
    sess = Session()
    pending = list(ids)
    idx = 0
    threads = []

    def worker():
        nonlocal idx
        while not CANCEL.is_set():
            with LOCK:
                if idx >= len(pending):
                    return
                pid = pending[idx]
                idx += 1
            try:
                res = fetch_stores(sess, pid)
            except Exception:
                # 例外でスレッドが死ぬと done が total に届かず永久に終わらない
                traceback.print_exc()
                res = {"name": pid, "shops": [], "error": "internal error"}
            with LOCK:
                STATE["results"][pid] = res
                STATE["done"] += 1
                done, total = STATE["done"], STATE["total"]
            publish("row", {"pid": pid, "name": res["name"],
                            "count": len(res["shops"]),
                            "shops": res["shops"], "error": res["error"],
                            "done": done, "total": total,
                            "workers": sess.workers})

    try:
        for _ in range(THREADS):
            t = threading.Thread(target=worker, daemon=True)
            t.start()
            threads.append(t)
        for t in threads:
            t.join()
    finally:
        sess.wake_all()
        with LOCK:
            STATE["running"] = False
            done, total = STATE["done"], STATE["total"]
        publish("end", {"done": done, "total": total,
                        "cancelled": CANCEL.is_set()})


def start_run():
    """二重起動を防いで実行スレッドを立てる。起動したら True。"""
    with LOCK:
        if STATE["running"]:
            return False
        STATE["running"] = True          # ここで先に立てて TOCTOU を潰す
    CANCEL.clear()
    threading.Thread(target=run_check, daemon=True).start()
    return True


# ═══════════════════════════════════════════════
#  メール
# ═══════════════════════════════════════════════
def esc(s):
    return html_mod.escape(str(s), quote=True)


def build_mail_html(prefs):
    rows, total = "", 0
    for pid, r in snapshot_results():
        hits = [s for s in r["shops"]
                if not prefs or any(p in s["name"] for p in prefs)]
        if not hits:
            continue
        total += 1
        shop_html = "<br>".join(
            f'<a href="{esc(s["url"])}" style="color:#e0a458;text-decoration:none">'
            f'{esc(s["name"])}</a>' for s in hits)
        rows += (
            '<tr><td style="padding:10px 12px;border-bottom:1px solid #2a2620">'
            f'<div style="color:#f0e6da;font-size:14px;font-weight:600">{esc(r["name"])}</div>'
            f'<div style="margin-top:5px;font-size:12px;line-height:1.7">{shop_html}</div>'
            '</td></tr>')

    now = datetime.now().strftime("%Y/%m/%d %H:%M")
    html = (
        '<html><body style="margin:0;padding:16px;background:#16110d;'
        'font-family:sans-serif">'
        '<h2 style="color:#e0a458;font-size:16px;margin:0 0 4px">'
        'BOOKOFF 在庫レポート</h2>'
        f'<p style="color:#a08d78;margin:0 0 14px;font-size:12px">{now} / {total}件</p>'
        '<table width="100%" cellpadding="0" cellspacing="0" '
        'style="background:#17150f;border-radius:8px;border:1px solid #2a2620">'
        f'{rows}</table></body></html>'
    )
    return html, total


# ═══════════════════════════════════════════════
#  Flask
# ═══════════════════════════════════════════════
app = Flask(__name__)


@app.get("/")
def index():
    return Response(PAGE, mimetype="text/html; charset=utf-8")


@app.get("/api/state")
def api_state():
    with LOCK:
        items = [{"pid": p, "name": r["name"], "count": len(r["shops"]),
                  "shops": r["shops"], "error": r["error"]}
                 for p, r in STATE["results"].items()]
        return jsonify({"running": STATE["running"], "total": STATE["total"],
                        "done": STATE["done"], "workers": STATE["workers"],
                        "items": items})


@app.get("/api/ids")
def api_ids():
    return jsonify(load_ids())


@app.post("/api/ids")
def api_ids_save():
    return jsonify(save_ids(body_json().get("ids", [])))


@app.get("/api/update_ids")
def api_update_ids():
    """ブックマークレットからの GET リダイレクト用（CORS回避）。

    mode=add で既存の一覧に追記する。お気に入りをページごとに取り込むとき
    上書きだと前のページ分が消えてしまう。
    """
    raw = request.args.get("ids", "")
    incoming = re.split(r"[,\s|]+", raw)
    if request.args.get("mode") == "add":
        before = load_ids()
        ids = save_ids(before + incoming)
        note = f"新規{len(ids) - len(before)}件を追加（合計{len(ids)}件）"
    else:
        ids = save_ids(incoming)
        note = f"{len(ids)}件を保存しました"
    return Response(
        f"<meta charset='utf-8'><body style='background:#16110d;color:#e0a458;"
        f"font-family:sans-serif;padding:40px'>{html_mod.escape(note)}。"
        f"このタブは閉じて構いません。</body>",
        mimetype="text/html; charset=utf-8")


@app.get("/api/prefectures")
def api_prefs():
    found = set()
    for _, r in snapshot_results():
        for s in r["shops"]:
            for p in PREFECTURES:
                if p in s["name"]:
                    found.add(p)
    return jsonify({"all": PREFECTURES, "found": sorted(found,
                    key=PREFECTURES.index)})


@app.get("/run")
def run():
    # 購読キューは「実行スレッドを立てる前」に登録する。
    # 後だと開始直後の row イベントを取りこぼす。
    q = queue.Queue(maxsize=SUB_QSIZE)
    with SUB_LOCK:
        SUBSCRIBERS.add(q)
    start_run()

    def stream():
        try:
            yield ": connected\n\n"
            while True:
                try:
                    kind, data = q.get(timeout=15)
                except queue.Empty:
                    yield ": ping\n\n"          # プロキシのバッファ/切断対策
                    with LOCK:
                        if not STATE["running"]:
                            break
                    continue
                yield (f"event: {kind}\n"
                       f"data: {json.dumps(data, ensure_ascii=False)}\n\n")
                if kind == "end":
                    break
        finally:
            # クライアント切断（GeneratorExit）でも必ず購読解除する
            with SUB_LOCK:
                SUBSCRIBERS.discard(q)

    return Response(stream(), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache",
                             "Connection": "keep-alive",
                             "X-Accel-Buffering": "no"})


@app.post("/api/cancel")
def api_cancel():
    CANCEL.set()
    return jsonify({"ok": True})


@app.post("/api/reset")
def api_reset():
    with LOCK:
        if STATE["running"]:
            return jsonify({"ok": False, "msg": "実行中はリセットできません"}), 409
        STATE.update(results={}, done=0, total=0)
    return jsonify({"ok": True})


@app.get("/api/csv")
def api_csv():
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["商品ID", "商品名", "在庫店舗数", "店舗"])
    for pid, r in snapshot_results():
        w.writerow([pid, r["name"], len(r["shops"]),
                    " / ".join(s["name"] for s in r["shops"])])
    return Response(buf.getvalue().encode("utf-8-sig"), mimetype="text/csv",
                    headers={"Content-Disposition":
                             "attachment; filename=bookoff.csv"})


@app.get("/api/mail_config")
def api_mail_get():
    c = load_mail()
    c["password"] = "****" if c.get("password") else ""
    return jsonify(c)


@app.post("/api/mail_config")
def api_mail_set():
    d = body_json()
    cur = load_mail()
    if d.get("password") and d["password"] != "****":
        cur["password"] = d["password"]
    for k in ("from", "to", "host", "port"):
        if k in d:
            cur[k] = d[k]
    save_mail(cur)
    return jsonify({"ok": True})


@app.post("/api/mail_preview")
def api_mail_preview():
    html, total = build_mail_html(body_json().get("prefectures", []))
    return Response(html, mimetype="text/html; charset=utf-8")


@app.post("/api/mail_send")
def api_mail_send():
    c = load_mail()
    if not (c.get("from") and c.get("password") and c.get("to")):
        return jsonify({"ok": False, "msg": "メール設定が未入力です"}), 400
    html, total = build_mail_html(body_json().get("prefectures", []))
    msg = MIMEMultipart("alternative")
    msg["Subject"] = f"BOOKOFF 在庫レポート {datetime.now():%m/%d %H:%M} ({total}件)"
    msg["From"], msg["To"] = c["from"], c["to"]
    msg.attach(MIMEText(html, "html", "utf-8"))
    try:
        with smtplib.SMTP(c["host"], int(c["port"]), timeout=30) as sv:
            sv.starttls()
            sv.login(c["from"], c["password"])
            sv.send_message(msg)
    except Exception as e:
        return jsonify({"ok": False, "msg": str(e)}), 500
    return jsonify({"ok": True, "msg": f"{total}件を送信しました"})


@app.get("/debug/<pid>")
def debug(pid):
    s = Session()
    r = s.s.get(f"https://shopping.bookoff.co.jp/used/{pid}", timeout=25)
    h1, links = scan_page(r.text)
    url = f"https://shopping.bookoff.co.jp/used/{pid}"
    out = [f"HTTP {r.status_code} / {len(r.text)} bytes",
           "h1: " + (h1 or "-"),
           "--- 抽出された店舗 ---"]
    for sh in parse_shops(url, links):
        out.append(f'{sh["name"]} -> {sh["url"]}')
    out.append("--- shop を含む全リンク ---")
    for href, text in links:
        if "shop" in href:
            out.append(f'{text[:40]!r} -> {href[:90]}')
    out += ["", "--- raw html ---", r.text]
    return Response("\n".join(out), mimetype="text/plain; charset=utf-8")


# ═══════════════════════════════════════════════
PAGE = r"""<!doctype html><html lang="ja"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BOOKOFF 在庫店舗チェッカー</title>
<link href="https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
:root{--bg:#16110d;--panel:#1d1712;--line:#2a2620;--ink:#f0e6da;--dim:#a08d78;
--hit:#e0a458;--warn:#c8553d;--ok:#7f9c6d}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font-size:14px;
font-family:"Yu Gothic UI","Meiryo",system-ui,sans-serif}
.mono{font-family:"Space Mono",Consolas,monospace}
header{position:sticky;top:0;z-index:9;background:var(--panel);
border-bottom:1px solid var(--line);padding:10px 12px}
h1{font-size:14px;margin:0 0 8px;letter-spacing:.06em;font-weight:700;color:var(--hit)}
.row{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
button{background:var(--line);color:var(--ink);border:1px solid #3a352c;
border-radius:6px;padding:7px 13px;font-size:13px;cursor:pointer}
button:hover{background:#3a352c}
button.go{background:var(--hit);color:#1b1309;border-color:var(--hit);font-weight:700}
button.stop{background:var(--warn);color:#fff;border-color:var(--warn)}
button:disabled{opacity:.4;cursor:default}
#bar{height:3px;background:var(--line);margin-top:9px;border-radius:2px;overflow:hidden}
#bar div{height:100%;width:0;background:var(--hit);transition:width .25s}
#bar.on div{animation:pulse 1.4s ease-in-out infinite}
@keyframes pulse{50%{opacity:.45}}
#stat{color:var(--dim);font-size:12px;margin-top:6px}
.tabs{display:flex;gap:4px;margin-top:8px}
.tab{padding:5px 12px;border-radius:6px 6px 0 0;font-size:12px;cursor:pointer;
color:var(--dim);border:1px solid transparent}
.tab.on{background:var(--bg);color:var(--hit);border-color:var(--line);border-bottom-color:var(--bg)}
main{padding:8px 12px 40px}
table{width:100%;border-collapse:collapse}
td,th{padding:8px 6px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th{color:var(--dim);font-size:11px;font-weight:600}
a{color:var(--hit);text-decoration:none}a:hover{text-decoration:underline}
.cnt{text-align:right;font-weight:700}
.cnt.z{color:var(--dim);font-weight:400}
.shops{color:var(--dim);font-size:12px;line-height:1.7;margin-top:4px}
.err{color:var(--warn);font-size:11px;margin-top:4px}
dialog{background:var(--panel);color:var(--ink);border:1px solid var(--line);
border-radius:10px;padding:16px;width:min(600px,94vw)}
dialog::backdrop{background:#000a}
textarea,input{width:100%;background:#120e0a;color:var(--ink);
border:1px solid var(--line);border-radius:6px;padding:8px;font-size:13px}
textarea{height:180px;font-family:Consolas,monospace}
label{display:block;color:var(--dim);font-size:11px;margin:9px 0 3px}
.chips{display:flex;flex-wrap:wrap;gap:4px;margin:8px 0;max-height:170px;overflow:auto}
.chip{border:1px solid var(--line);border-radius:12px;padding:3px 9px;font-size:11px;
cursor:pointer;color:var(--dim)}
.chip.on{background:var(--hit);color:#1b1309;border-color:var(--hit);font-weight:700}
</style></head><body>
<header>
<h1>BOOKOFF 在庫店舗チェッカー</h1>
<div class="row">
<button class="go" id="bRun">チェック開始</button>
<button class="stop" id="bStop" disabled>中止</button>
<button id="bIds">商品ID</button>
<button id="bMail">メール</button>
<button id="bCsv">CSV</button>
<button id="bReset">リセット</button>
</div>
<div id="bar"><div></div></div>
<div id="stat" class="mono">待機中</div>
<div class="tabs"><div class="tab on" data-v="item">商品別</div>
<div class="tab" data-v="shop">店舗別</div></div>
</header>
<main><div id="view"></div></main>

<dialog id="dIds">
<div style="font-weight:600">商品ID（1行1件）</div>
<textarea id="idsBox"></textarea>
<label>お気に入りから取り込む</label>
<div style="color:var(--dim);font-size:11px;line-height:1.6">
下の1行をブックマークとして登録し、ログイン済みのお気に入りページで実行すると、
そのページの商品IDが<b>追記</b>されます。ページを送りながら繰り返せます。</div>
<textarea id="bmBox" readonly style="height:70px;font-size:11px;margin-top:5px"></textarea>
<div class="row" style="justify-content:flex-end;margin-top:10px">
<button onclick="dIds.close()">閉じる</button><button class="go" id="idsSave">保存</button></div>
</dialog>

<dialog id="dMail">
<div style="font-weight:600">メール送信</div>
<label>都道府県で絞り込み（未選択＝全件）</label>
<div class="chips" id="prefs"></div>
<label>送信元 Gmail</label><input id="mFrom">
<label>アプリパスワード</label><input id="mPw" type="password">
<label>送信先</label><input id="mTo">
<div class="row" style="justify-content:flex-end;margin-top:12px">
<button onclick="dMail.close()">閉じる</button>
<button id="mPrev">プレビュー</button>
<button class="go" id="mSend">送信</button></div>
<div id="mMsg" style="color:var(--dim);font-size:12px;margin-top:8px"></div>
</dialog>

<script>
let ITEMS={},VIEW='item',ES=null;
const $=s=>document.querySelector(s);
// 属性値にも埋めるので " ' も落とす（店舗名/URL はスクレイプ由来）
const esc=s=>String(s==null?'':s).replace(/[&<>"']/g,
  c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pct=(d,t)=>t>0?(d/t*100)+'%':'0%';

document.querySelectorAll('.tab').forEach(t=>t.onclick=()=>{
  document.querySelectorAll('.tab').forEach(x=>x.classList.remove('on'));
  t.classList.add('on'); VIEW=t.dataset.v; render();});

function render(){
  const list=Object.values(ITEMS);
  if(VIEW==='item'){
    list.sort((a,b)=>b.count-a.count);
    $('#view').innerHTML='<table><thead><tr><th>商品</th><th class="cnt">店舗</th></tr></thead><tbody>'+
      list.map(r=>`<tr><td>
        <a href="https://shopping.bookoff.co.jp/used/${esc(r.pid)}" target="_blank" rel="noopener">${esc(r.name)}</a>
        ${r.count?`<div class="shops">${r.shops.map(s=>`<a href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.name)}</a>`).join(' ・ ')}</div>`:''}
        ${r.error?`<div class="err">${esc(r.error)}</div>`:''}
        </td><td class="cnt mono ${r.count?'':'z'}">${r.count}</td></tr>`).join('')+
      '</tbody></table>';
  }else{
    const by={};
    list.forEach(r=>r.shops.forEach(s=>{(by[s.name]=by[s.name]||{url:s.url,items:[]}).items.push(r)}));
    const keys=Object.keys(by).sort((a,b)=>by[b].items.length-by[a].items.length);
    $('#view').innerHTML='<table><thead><tr><th>店舗</th><th class="cnt">点数</th></tr></thead><tbody>'+
      keys.map(k=>`<tr><td><a href="${esc(by[k].url)}" target="_blank" rel="noopener">${esc(k)}</a>
        <div class="shops">${by[k].items.map(r=>esc(r.name)).join(' ・ ')}</div></td>
        <td class="cnt mono">${by[k].items.length}</td></tr>`).join('')+'</tbody></table>';
  }
}

async function sync(){
  const d=await(await fetch('/api/state')).json();
  ITEMS={}; d.items.forEach(i=>ITEMS[i.pid]=i); render();
  $('#bar div').style.width=pct(d.done,d.total);
  return d;
}
sync();

function finish(txt){
  if(ES){ES.close();ES=null;}
  $('#bRun').disabled=false;$('#bStop').disabled=true;
  $('#bar').classList.remove('on');
  if(txt)$('#stat').textContent=txt;
}

$('#bRun').onclick=()=>{
  $('#bRun').disabled=true;$('#bStop').disabled=false;$('#bar').classList.add('on');
  $('#stat').textContent='接続中...';
  ES=new EventSource('/run');
  // 再接続時は取りこぼした行を /api/state で補完する
  ES.onopen=()=>{if(Object.keys(ITEMS).length)sync();};
  ES.addEventListener('row',e=>{const d=JSON.parse(e.data);
    ITEMS[d.pid]={pid:d.pid,name:d.name,count:d.count,shops:d.shops,error:d.error};render();
    $('#bar div').style.width=pct(d.done,d.total);
    $('#stat').textContent=`${d.done} / ${d.total}　並列 ${d.workers}`;});
  ES.addEventListener('status',e=>{
    $('#stat').textContent=JSON.parse(e.data).msg;});
  ES.addEventListener('end',e=>{const d=JSON.parse(e.data);
    finish(d.cancelled?`中止（${d.done}件）`:`完了 ${d.done}件`);});
  ES.onerror=()=>{
    // EventSource は自動再接続する。CLOSED のときだけ本当に終わり。
    if(ES&&ES.readyState===EventSource.CLOSED){finish('接続が切れました');}
    else{$('#stat').textContent='再接続中...';}};
};
$('#bStop').onclick=()=>{$('#bStop').disabled=true;
  $('#stat').textContent='中止しています...';
  fetch('/api/cancel',{method:'POST'});};
$('#bCsv').onclick=()=>location.href='/api/csv';
$('#bReset').onclick=async()=>{
  const r=await fetch('/api/reset',{method:'POST'});
  if(!r.ok){$('#stat').textContent=(await r.json()).msg;return;}
  ITEMS={};render();$('#stat').textContent='リセットしました';
  $('#bar div').style.width='0%';};

const BM="javascript:(function(){var s=[],k={},h=document.documentElement.innerHTML,"
  +"re=/\\/used\\/(\\d{8,12})/g,m;while((m=re.exec(h))!==null){if(!k[m[1]]){k[m[1]]=1;s.push(m[1]);}}"
  +"if(!s.length){alert('\u5546\u54c1ID\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093');return;}"
  +"location.href='"+location.origin+"/api/update_ids?mode=add&ids='+s.join(',');})()";
$('#bIds').onclick=async()=>{
  const ids=await(await fetch('/api/ids')).json();
  $('#idsBox').value=ids.join('\n'); $('#bmBox').value=BM; dIds.showModal();};
$('#idsSave').onclick=async()=>{
  const ids=$('#idsBox').value.split('\n').map(s=>s.trim()).filter(Boolean);
  const r=await(await fetch('/api/ids',{method:'POST',
    headers:{'Content-Type':'application/json'},body:JSON.stringify({ids})})).json();
  dIds.close();$('#stat').textContent=r.length+'件を保存しました';};

$('#bMail').onclick=async()=>{
  const p=await(await fetch('/api/prefectures')).json();
  const src=p.found.length?p.found:p.all;
  $('#prefs').innerHTML=src.map(x=>`<span class="chip" data-p="${esc(x)}">${esc(x)}</span>`).join('');
  $('#prefs').querySelectorAll('.chip').forEach(c=>c.onclick=()=>c.classList.toggle('on'));
  const c=await(await fetch('/api/mail_config')).json();
  $('#mFrom').value=c.from||'';$('#mTo').value=c.to||'';$('#mPw').value=c.password||'';
  dMail.showModal();};
const picked=()=>[...$('#prefs').querySelectorAll('.chip.on')].map(c=>c.dataset.p);
async function saveMail(){await fetch('/api/mail_config',{method:'POST',
  headers:{'Content-Type':'application/json'},
  body:JSON.stringify({from:$('#mFrom').value,password:$('#mPw').value,to:$('#mTo').value})});}
$('#mPrev').onclick=async()=>{await saveMail();
  const r=await fetch('/api/mail_preview',{method:'POST',
    headers:{'Content-Type':'application/json'},body:JSON.stringify({prefectures:picked()})});
  const w=window.open('');w.document.write(await r.text());};
$('#mSend').onclick=async()=>{await saveMail();$('#mMsg').textContent='送信中...';
  try{
    const r=await(await fetch('/api/mail_send',{method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({prefectures:picked()})})).json();
    $('#mMsg').textContent=r.msg;
  }catch(e){$('#mMsg').textContent='送信に失敗しました: '+e;}};
</script></body></html>
"""

if __name__ == "__main__":
    load_ids()
    print(f"→ http://127.0.0.1:{PORT}   IDリスト: {IDS_FILE}")
    app.run(host="0.0.0.0", port=PORT, threaded=True, debug=False)
