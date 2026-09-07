#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""アーティストCDリスト × BOOKOFF オンライン在庫チェッカー

アーティスト名からCD（アルバム／シングル）の一覧を作り、持っているものに
チェックを入れて保存しつつ、BOOKOFF オンライン（shopping.bookoff.co.jp）の
在庫と価格を自動で引いて表にまとめる。

  pip install flask requests
  python artist_cd_web.py
  → http://127.0.0.1:5001

作品一覧は MusicBrainz（無料・鍵不要）から取る。1秒1リクエストの約束が
あるので、この中で間隔を空けている。

bookoff_web.py（店舗在庫チェッカー）とは別プロセスで、ポートも別。
同時に両方立てて構わない。
"""

import json
import queue
import random
import re
import threading
import time
import unicodedata
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote, urljoin, urlparse

import requests
from flask import Flask, Response, abort, jsonify, request

# ═══════════════════════════════════════════════
#  設定
# ═══════════════════════════════════════════════
BASE_DIR = Path(__file__).resolve().parent
OWNED_FILE = BASE_DIR / "artist_cd_owned.json"
PORT = 5001

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
}

# MusicBrainz は連絡先入りの User-Agent を必須にしている。
MB_BASE = "https://musicbrainz.org/ws/2"
MB_UA = ("BookoffArtistCD/1.0 "
         "( https://github.com/tekkansumo-design/- )")
MB_INTERVAL = 1.1          # 秒。1req/sec の約束に少し余裕を持たせる
MB_LOCK = threading.Lock()
MB_LAST = [0.0]

BOOKOFF_ORIGIN = "https://shopping.bookoff.co.jp"
# 検索URLの形は変わることがあるので候補を順に試し、商品リンクが取れた形を覚える。
SEARCH_TEMPLATES = [
    BOOKOFF_ORIGIN + "/search/keyword/{kw}",
    BOOKOFF_ORIGIN + "/search/?keyword={kw}",
    BOOKOFF_ORIGIN + "/search?keyword={kw}",
    BOOKOFF_ORIGIN + "/search/?q={kw}",
]
GOOD_TEMPLATE = [None]     # 当たった形を覚える箱

BACKOFF = [15, 30, 60, 120, 180]
MIN_WORKERS = 1
MAX_WORKERS = 6
START_WORKERS = 3
RAMP_AFTER = 4
THREADS = MAX_WORKERS

PROD_RE = re.compile(r"/(used|goods)/(\d{6,})")
PRICE_RE = re.compile(r"(?:[¥￥]\s*([0-9][0-9,]*)|([0-9][0-9,]*)\s*円)")
SOLD_RE = re.compile(r"売り?切れ|品切れ|在庫切れ|在庫がありません|SOLD\s*OUT", re.I)
INSTOCK_RE = re.compile(r"カートに入れる|在庫あり|購入手続き|残り\s*\d+")
NOHIT_RE = re.compile(r"該当(する|の)(商品|検索結果)(は)?(ありません|見つかりません)"
                      r"|検索結果は?\s*0\s*件|0\s*件でした")

KIND_JA = {"album": "アルバム", "single": "シングル", "ep": "EP", "other": "その他"}
KIND_MAP = {"Album": "album", "Single": "single", "EP": "ep"}
SUB_JA = {
    "Compilation": "編集盤", "Live": "ライブ", "Remix": "リミックス",
    "Soundtrack": "サントラ", "Demo": "デモ", "DJ-mix": "DJミックス",
    "Mixtape/Street": "ミックステープ", "Interview": "インタビュー",
    "Audiobook": "朗読", "Spokenword": "語り", "Audio drama": "ドラマ",
    "Field recording": "フィールド録音",
}

# ═══════════════════════════════════════════════
#  状態
# ═══════════════════════════════════════════════
STATE = {
    "running": False,
    "total": 0,
    "done": 0,
    "workers": START_WORKERS,
    "results": {},      # rgid -> 在庫チェック結果
}
PLAN = {"artist": "", "items": []}      # 次に /run で流す対象
CANCEL = threading.Event()
LOCK = threading.Lock()
DISCO_CACHE = {}        # mbid -> [作品, ...]
CACHE_LOCK = threading.Lock()

SUBSCRIBERS = set()
SUB_LOCK = threading.Lock()
SUB_QSIZE = 4000


def publish(kind, data):
    """SSE 購読者へイベントを配る。誰も居なければ捨てる。"""
    with SUB_LOCK:
        subs = list(SUBSCRIBERS)
    for q in subs:
        try:
            q.put_nowait((kind, data))
        except queue.Full:
            pass


def body_json():
    d = request.get_json(silent=True)
    if not isinstance(d, dict):
        abort(400, description="JSON オブジェクトが必要です")
    return d


def norm_text(s):
    return re.sub(r"\s+", " ", s).strip()


PUNCT_RE = re.compile(r"[\s　!-/:-@\[-`{-~・…‐‑‒–—―〜～"
                      r"「」『』【】〔〕（）［］｛｝、。，．〈〉《》]")


def norm_key(s):
    """突き合わせ用のキー。全角半角・大小・記号・空白の違いを吸収する。"""
    s = unicodedata.normalize("NFKC", str(s or "")).lower()
    return PUNCT_RE.sub("", s)


# ═══════════════════════════════════════════════
#  持っているCDの保存
# ═══════════════════════════════════════════════
OWNED_LOCK = threading.Lock()


def load_owned():
    if not OWNED_FILE.exists():
        return {}
    try:
        d = json.loads(OWNED_FILE.read_text(encoding="utf-8"))
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}          # 壊れていても起動は止めない


def save_owned(d):
    """書き換え中に落ちても元が消えないよう、一時ファイル経由で差し替える。"""
    tmp = OWNED_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(OWNED_FILE)


def set_owned(key, owned, meta):
    with OWNED_LOCK:
        d = load_owned()
        if owned:
            rec = dict(meta or {})
            rec["ts"] = time.strftime("%Y-%m-%d %H:%M:%S")
            d[key] = rec
        else:
            d.pop(key, None)      # 外したものは残さない（ファイルを太らせない）
        save_owned(d)
        return len(d)


# ═══════════════════════════════════════════════
#  MusicBrainz（作品一覧）
# ═══════════════════════════════════════════════
MB_SESSION = requests.Session()
MB_SESSION.headers.update({"User-Agent": MB_UA, "Accept": "application/json"})


def mb_get(path, params):
    """1秒1リクエストを守りつつ GET。503（絞られた）はしばらく待って再試行。"""
    params = dict(params, fmt="json")
    for attempt in range(4):
        with MB_LOCK:
            gap = MB_INTERVAL - (time.monotonic() - MB_LAST[0])
            if gap > 0:
                time.sleep(gap)
            try:
                r = MB_SESSION.get(f"{MB_BASE}/{path}", params=params, timeout=25)
            finally:
                MB_LAST[0] = time.monotonic()
        if r.status_code == 503:
            time.sleep(2 + attempt * 2)
            continue
        if r.status_code != 200:
            raise RuntimeError(f"MusicBrainz HTTP {r.status_code}")
        return r.json()
    raise RuntimeError("MusicBrainz が混んでいます（503）。少し置いて試してください")


def search_artists(name, limit=8):
    d = mb_get("artist", {"query": name, "limit": limit})
    out = []
    for a in d.get("artists", []):
        area = (a.get("area") or {}).get("name") or a.get("country") or ""
        # 日本語名は別名に入っていることが多いので拾って添える
        alias = ""
        for al in a.get("aliases") or []:
            v = al.get("name") or ""
            if v and v != a.get("name") and re.search(r"[ぁ-んァ-ヶ一-龠]", v):
                alias = v
                break
        out.append({
            "id": a.get("id"), "name": a.get("name") or "",
            "alias": alias, "area": area,
            "type": a.get("type") or "",
            "note": a.get("disambiguation") or "",
            "score": a.get("score") or 0,
        })
    return out


def fetch_discography(mbid, force=False):
    """アーティストの全リリースグループを取る（100件ずつ）。"""
    if not force:
        with CACHE_LOCK:
            if mbid in DISCO_CACHE:
                return DISCO_CACHE[mbid]

    items, offset, total = [], 0, None
    while True:
        d = mb_get("release-group", {"artist": mbid, "limit": 100, "offset": offset})
        groups = d.get("release-groups", [])
        if total is None:
            total = d.get("release-group-count", len(groups))
        for g in groups:
            primary = g.get("primary-type") or ""
            secs = g.get("secondary-types") or []
            items.append({
                "id": g.get("id"),
                "title": g.get("title") or "",
                "date": g.get("first-release-date") or "",
                "kind": KIND_MAP.get(primary, "other"),
                "primary": primary,
                "sub": "／".join(SUB_JA.get(s, s) for s in secs),
                "note": g.get("disambiguation") or "",
            })
        offset += len(groups)
        if not groups or offset >= (total or 0) or offset >= 600:
            break

    # 新しい順。日付なしは末尾へ
    items.sort(key=lambda x: (x["date"] or "0000"), reverse=True)
    with CACHE_LOCK:
        DISCO_CACHE[mbid] = items
    return items


# ═══════════════════════════════════════════════
#  BOOKOFF オンライン（在庫・価格）
# ═══════════════════════════════════════════════
class Session:
    """同時接続をこちらで絞り、403/503 が出たら段階的に下げる。

    bookoff_web.py と同じ考え方。workers はスレッド数ではなく
    「同時に飛ばしてよいリクエスト数」。
    """

    def __init__(self):
        self.s = requests.Session()
        self.s.headers.update(HEADERS)
        adapter = requests.adapters.HTTPAdapter(
            pool_connections=MAX_WORKERS * 2, pool_maxsize=MAX_WORKERS * 2)
        self.s.mount("https://", adapter)
        self.s.mount("http://", adapter)
        self.workers = START_WORKERS
        self.ok_streak = 0
        self.err_level = 0
        self.active = 0
        self.cv = threading.Condition()
        STATE["workers"] = self.workers

    def acquire(self):
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
            if self.ok_streak >= RAMP_AFTER and self.workers < MAX_WORKERS:
                self.workers += 1
                self.ok_streak = 0
                STATE["workers"] = self.workers
                self.cv.notify_all()

    def wake_all(self):
        with self.cv:
            self.cv.notify_all()


def _sleep(sec):
    """中止ボタンで即抜けられる sleep。"""
    return CANCEL.wait(sec)


BLOCK_TAGS = {"div", "li", "tr", "td", "th", "p", "br", "section", "article",
              "ul", "ol", "dl", "dd", "dt", "span", "h1", "h2", "h3", "h4", "table"}


class ResultScan(HTMLParser):
    """検索結果ページから「商品リンク」と、その後ろに続く文字を拾う。

    見た目のクラス名は変わりやすいが、商品ページが /used/<番号> である
    ことは商品ページ側の作りから動かしにくい。そこを手がかりにする。
    """

    SKIP = {"script", "style", "noscript"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        # ("prod", used/goods, 番号) / ("text", 文字) / ("atext", 番号, 文字)
        self.events = []
        self._skip = 0
        self._a_pid = None      # いま開いている商品リンクの番号

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip += 1
            return
        if tag == "a":
            href = (dict(attrs).get("href") or "").strip()
            m = PROD_RE.search(urlparse(urljoin(BOOKOFF_ORIGIN, href)).path)
            self._a_pid = m.group(2) if m else None
            if m:
                self.events.append(("prod", m.group(1), m.group(2)))
        if tag in BLOCK_TAGS:
            self.events.append(("text", " "))      # 語が繋がってしまうのを防ぐ

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if tag in self.SKIP:
            self._skip = max(0, self._skip - 1)
        elif tag == "a":
            self._a_pid = None
        elif tag in BLOCK_TAGS:
            self.events.append(("text", " "))

    def handle_data(self, data):
        if self._skip or not data.strip():
            return
        if self._a_pid:
            # 商品リンクの文字はそれ自体が商品名。周りの「中古CD」等と混ぜない
            self.events.append(("atext", self._a_pid, data))
        self.events.append(("text", data))


def _price_of(text):
    m = PRICE_RE.search(text)
    if not m:
        return None
    raw = m.group(1) or m.group(2) or ""
    try:
        v = int(raw.replace(",", ""))
    except ValueError:
        return None
    return v if 1 <= v <= 9999999 else None


def parse_results(html_text):
    """検索結果ページ → [{pid, new, title, price, soldout, url}, ...]"""
    p = ResultScan()
    try:
        p.feed(html_text)
    except Exception:
        pass                    # 壊れた HTML でもそこまでを使う
    p.close()

    blocks, cur, buf, atext = [], None, [], []
    for ev in p.events:
        if ev[0] == "prod":
            kind, pid = ev[1], ev[2]
            if cur and cur[1] == pid:
                continue        # 画像リンクと題名リンクで同じ商品が2回出る
            if cur:
                blocks.append((cur, buf, atext))
            cur, buf, atext = (kind, pid), [], []
        elif cur is None:
            continue
        elif ev[0] == "atext":
            if ev[1] == cur[1]:
                atext.append(ev[2])
        elif len(buf) < 80:
            buf.append(ev[1])
    if cur:
        blocks.append((cur, buf, atext))

    items, seen = [], set()
    for (kind, pid), buf, atext in blocks:
        text = norm_text(" ".join(buf))
        price = _price_of(text)
        # 題名はリンクの文字をそのまま使う。画像だけのリンクなら周りの文字から拾い、
        # 価格や状態表示の手前で切る。
        title = norm_text(" ".join(atext))[:120]
        if not title:
            cut = len(text)
            for rx in (PRICE_RE, SOLD_RE, INSTOCK_RE):
                m = rx.search(text)
                if m and m.start() < cut:
                    cut = m.start()
            title = norm_text(text[:cut])[:120] or norm_text(text)[:120]
        rec = {
            "pid": pid,
            "new": kind == "goods",
            "title": title,
            "price": price,
            "soldout": bool(SOLD_RE.search(text)),
            "url": f"{BOOKOFF_ORIGIN}/{kind}/{pid}",
        }
        if pid in seen:          # 同じ商品が複数枠に出ることがある
            continue
        seen.add(pid)
        items.append(rec)
    return items


def bookoff_search(sess, keyword):
    """検索して商品一覧を返す。(items, 使ったURL, エラー文字列)"""
    kw = quote(keyword, safe="")
    tries = ([GOOD_TEMPLATE[0]] if GOOD_TEMPLATE[0] else []) + \
            [t for t in SEARCH_TEMPLATES if t != GOOD_TEMPLATE[0]]
    last_url, last_err = "", None
    nohit = False

    for tpl in tries:
        url = tpl.format(kw=kw)
        last_url = url
        while not CANCEL.is_set():
            if not sess.acquire():
                return [], url, "cancelled"
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
            if r.status_code != 200:
                last_err = f"HTTP {r.status_code}"
                break            # このURLの形が違う。次の候補へ

            sess.on_ok()
            items = parse_results(r.text)
            if items:
                GOOD_TEMPLATE[0] = tpl
                return items, url, None
            if NOHIT_RE.search(r.text):
                # ページは正しく開けている。単に商品が無いだけ
                GOOD_TEMPLATE[0] = tpl
                nohit = True
            break

        if CANCEL.is_set():
            return [], url, "cancelled"
        if nohit:
            return [], last_url, None

    return [], last_url, last_err or "検索結果を読み取れませんでした"


def score_item(item, akey, tkey):
    """作品名との一致度。題名が一致しないものは採らない。"""
    ik = norm_key(item["title"])
    if not ik or not tkey:
        return 0
    s = 0
    if tkey in ik:
        s = 3
    elif len(tkey) >= 6 and tkey[:len(tkey) * 2 // 3] in ik:
        s = 2               # 「(初回限定盤)」等で末尾が削れている場合
    if s and akey and akey in ik:
        s += 2
    return s


def pick_best(items, artist, title):
    akey, tkey = norm_key(artist), norm_key(title)
    scored = []
    for it in items:
        s = score_item(it, akey, tkey)
        if s >= 3:
            scored.append((s, it))
    if not scored:
        return None, 0
    # アーティスト名まで一致したものがあるなら、それ以外は同名の別物とみなす
    if any(s >= 5 for s, _ in scored):
        scored = [p for p in scored if p[0] >= 5]
    # 在庫あり優先 → 一致度が高い順 → 安い順
    scored.sort(key=lambda p: (p[1]["soldout"], -p[0],
                               p[1]["price"] if p[1]["price"] is not None else 10 ** 9))
    return scored[0][1], len(scored)


def check_one(sess, artist, item):
    """1作品ぶんの在庫照会。"""
    title = item.get("title") or ""
    # アーティスト名込み → 作品名だけ、の順に試す（同じ語なら1回だけ）
    words = list(dict.fromkeys(w for w in (f"{artist} {title}".strip(), title) if w))
    for kw in words:
        items, url, err = bookoff_search(sess, kw)
        if err == "cancelled":
            return {"state": "cancelled"}
        if err:
            return {"state": "error", "note": err, "search": url}
        best, hits = pick_best(items, artist, title)
        if best:
            return {
                "state": "sold" if best["soldout"] else "stock",
                "price": best["price"],
                "name": best["title"],
                "url": best["url"],
                "new": best["new"],
                "hits": hits,
                "search": url,
            }
        if kw == words[-1]:
            return {"state": "none", "search": url}
    return {"state": "none"}


# ═══════════════════════════════════════════════
#  実行
# ═══════════════════════════════════════════════
def run_check():
    artist = PLAN["artist"]
    targets = list(PLAN["items"])
    with LOCK:
        STATE.update(running=True, total=len(targets), done=0,
                     results={}, workers=START_WORKERS)
    publish("status", {"msg": f"{len(targets)}件を照会します"})

    sess = Session()
    work = queue.Queue()
    for it in targets:
        work.put(it)

    def worker():
        while not CANCEL.is_set():
            try:
                it = work.get_nowait()
            except queue.Empty:
                return
            try:
                res = check_one(sess, artist, it)
            except Exception as e:                    # 1件の失敗で全体を止めない
                res = {"state": "error", "note": f"{type(e).__name__}: {e}"}
            res["id"] = it.get("id")
            with LOCK:
                STATE["results"][it.get("id")] = res
                STATE["done"] += 1
                done, total = STATE["done"], STATE["total"]
            publish("row", res)
            publish("progress", {"done": done, "total": total,
                                 "workers": sess.workers})

    threads = [threading.Thread(target=worker, daemon=True)
               for _ in range(min(THREADS, max(1, len(targets))))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    with LOCK:
        STATE["running"] = False
    publish("status", {"msg": "中止しました" if CANCEL.is_set() else "完了"})
    publish("end", {"cancelled": CANCEL.is_set()})


def start_run():
    with LOCK:
        if STATE["running"]:
            return False
    CANCEL.clear()
    threading.Thread(target=run_check, daemon=True).start()
    return True


# ═══════════════════════════════════════════════
#  Flask
# ═══════════════════════════════════════════════
app = Flask(__name__)


@app.get("/")
def index():
    return Response(PAGE, mimetype="text/html; charset=utf-8")


@app.get("/api/artists")
def api_artists():
    q = (request.args.get("q") or "").strip()
    if not q:
        return jsonify({"ok": False, "msg": "アーティスト名を入れてください"}), 400
    try:
        return jsonify({"ok": True, "artists": search_artists(q)})
    except Exception as e:
        return jsonify({"ok": False, "msg": f"{type(e).__name__}: {e}"}), 502


@app.get("/api/discography")
def api_discography():
    mbid = (request.args.get("mbid") or "").strip()
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", mbid):
        return jsonify({"ok": False, "msg": "アーティストIDが不正です"}), 400
    try:
        items = fetch_discography(mbid, force=request.args.get("force") == "1")
    except Exception as e:
        return jsonify({"ok": False, "msg": f"{type(e).__name__}: {e}"}), 502
    owned = load_owned()
    for it in items:
        it["owned"] = it["id"] in owned
    return jsonify({"ok": True, "items": items, "owned": len(owned)})


@app.get("/api/owned")
def api_owned_get():
    return jsonify(load_owned())


@app.post("/api/owned")
def api_owned_set():
    d = body_json()
    key = str(d.get("id") or "")
    if not key:
        return jsonify({"ok": False, "msg": "id がありません"}), 400
    n = set_owned(key, bool(d.get("owned")), d.get("meta") or {})
    return jsonify({"ok": True, "count": n})


@app.post("/api/plan")
def api_plan():
    """これから在庫を見る作品を預ける。EventSource は GET しか出せないため。"""
    with LOCK:
        if STATE["running"]:
            return jsonify({"ok": False, "msg": "実行中です"}), 409
    d = body_json()
    items = d.get("items") or []
    if not isinstance(items, list) or not items:
        return jsonify({"ok": False, "msg": "対象がありません"}), 400
    PLAN["artist"] = str(d.get("artist") or "")
    PLAN["items"] = [{"id": str(x.get("id") or ""), "title": str(x.get("title") or "")}
                     for x in items[:500] if isinstance(x, dict)]
    return jsonify({"ok": True, "count": len(PLAN["items"])})


@app.get("/run")
def run():
    # 購読キューは実行スレッドを立てる前に登録する。後だと最初の行を取りこぼす。
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
                    yield ": ping\n\n"
                    with LOCK:
                        if not STATE["running"]:
                            break
                    continue
                yield (f"event: {kind}\n"
                       f"data: {json.dumps(data, ensure_ascii=False)}\n\n")
                if kind == "end":
                    break
        finally:
            with SUB_LOCK:
                SUBSCRIBERS.discard(q)

    return Response(stream(), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache",
                             "Connection": "keep-alive",
                             "X-Accel-Buffering": "no"})


@app.get("/api/state")
def api_state():
    with LOCK:
        return jsonify({"running": STATE["running"], "total": STATE["total"],
                        "done": STATE["done"], "workers": STATE["workers"],
                        "results": dict(STATE["results"])})


@app.post("/api/cancel")
def api_cancel():
    CANCEL.set()
    return jsonify({"ok": True})


@app.get("/diag")
def diag():
    """検索ページをどう読み取ったかをそのまま出す。

    サイトの作りが変わって在庫が取れなくなったとき、ここを見れば
    「URLの形が違うのか」「商品リンクが拾えていないのか」が分かる。
    """
    kw = request.args.get("kw") or "宇多田ヒカル First Love"
    CANCEL.clear()
    sess = Session()
    out = [f"キーワード: {kw}", ""]
    for tpl in ([GOOD_TEMPLATE[0]] if GOOD_TEMPLATE[0] else []) + \
               [t for t in SEARCH_TEMPLATES if t != GOOD_TEMPLATE[0]]:
        url = tpl.format(kw=quote(kw, safe=""))
        try:
            r = sess.s.get(url, timeout=25)
        except Exception as e:
            out.append(f"[NG] {url}\n     {type(e).__name__}: {e}")
            continue
        items = parse_results(r.text) if r.status_code == 200 else []
        out.append(f"[{r.status_code}] {url}\n"
                   f"     {len(r.text)} bytes / 商品リンク {len(items)} 件 "
                   f"/ 該当なし表記 {'あり' if NOHIT_RE.search(r.text) else 'なし'}")
        for it in items[:20]:
            out.append(f"     - {it['pid']} {'新品' if it['new'] else '中古'} "
                       f"{it['price']}円 {'品切' if it['soldout'] else '在庫'} "
                       f"| {it['title'][:60]}")
        if items:
            out += ["", "--- 上のページの生 HTML ---", r.text]
            break
    return Response("\n".join(out), mimetype="text/plain; charset=utf-8")


# ═══════════════════════════════════════════════
#  画面（Web 版とアプリ版で共有）
#
#  UI_HTML と UI_JS は共通。通信部分だけを T（トランスポート）に閉じ込め、
#  Web 版は WEB_JS、Android 版は tools/gen_android_asset.py の APP_JS で
#  差し替える。画面を直したいときはこのファイルだけを編集する。
# ═══════════════════════════════════════════════
UI_HTML = r"""<!doctype html><html lang="ja"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>アーティストCDリスト × BOOKOFF</title>
<style>
:root{--bg:#16110d;--panel:#1d1712;--line:#2a2620;--ink:#f0e6da;--dim:#a08d78;
--hit:#e0a458;--warn:#c8553d;--ok:#7f9c6d;--chip:#241d16}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font-size:14px;
font-family:"Yu Gothic UI","Meiryo",system-ui,sans-serif;
-webkit-text-size-adjust:100%}
.mono{font-variant-numeric:tabular-nums}
header{position:sticky;top:0;z-index:9;background:var(--panel);
border-bottom:1px solid var(--line);padding:10px 12px 8px}
h1{font-size:13px;margin:0 0 8px;letter-spacing:.06em;font-weight:700;color:var(--hit)}
.row{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
button{background:var(--line);color:var(--ink);border:1px solid #3a352c;
border-radius:6px;padding:7px 13px;font-size:13px;cursor:pointer;white-space:nowrap}
button:hover{background:#3a352c}
button.go{background:var(--hit);color:#1b1309;border-color:var(--hit);font-weight:700}
button.stop{background:var(--warn);color:#fff;border-color:var(--warn)}
button:disabled{opacity:.4;cursor:default}
input,select{background:#120e0a;color:var(--ink);border:1px solid var(--line);
border-radius:6px;padding:7px 9px;font-size:13px}
input[type=text]{flex:1;min-width:150px}
input[type=checkbox]{width:auto;accent-color:var(--hit)}
#bar{height:3px;background:var(--line);margin-top:9px;border-radius:2px;overflow:hidden}
#bar div{height:100%;width:0;background:var(--hit);transition:width .25s}
#bar.on div{animation:pulse 1.4s ease-in-out infinite}
@keyframes pulse{50%{opacity:.45}}
#stat{color:var(--dim);font-size:12px;margin-top:6px}
#sum{color:var(--dim);font-size:11px;margin-top:4px;line-height:1.7}
#sum b{color:var(--hit);font-weight:700}
#sum .sep{opacity:.4;margin:0 7px}
.sw{display:flex;align-items:center;gap:5px;font-size:11px;color:var(--dim);
white-space:nowrap;margin:0;cursor:pointer;user-select:none}
.sw.on{color:var(--hit)}
.filt{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin-top:8px}
#cands{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}
#cands:empty{display:none}
.chip{border:1px solid var(--line);border-radius:13px;padding:4px 11px;font-size:12px;
cursor:pointer;color:var(--dim);background:var(--chip);line-height:1.5}
.chip:hover{border-color:var(--hit);color:var(--ink)}
.chip.on{background:var(--hit);color:#1b1309;border-color:var(--hit);font-weight:700}
.chip .s{opacity:.7;font-size:11px;margin-left:6px}
.chip.on .s{opacity:.85}
main{padding:0 12px 40px}
a{color:var(--hit);text-decoration:none}
a:hover{text-decoration:underline}
.tw{overflow-x:auto;border:1px solid var(--line);border-radius:10px;
background:var(--panel);margin-top:10px}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{padding:7px 9px;border-bottom:1px solid var(--line);text-align:left;
vertical-align:top}
th{position:sticky;top:0;background:#241d16;color:var(--dim);font-size:11px;
font-weight:600;white-space:nowrap;cursor:pointer;z-index:2}
th:hover{color:var(--hit)}
th .ar{color:var(--hit);margin-left:3px}
tbody tr:hover{background:#221b14}
tbody tr.own{background:#1b1a12}
tbody tr.own:hover{background:#232113}
td.c{text-align:center;width:44px}
td.ti{font-weight:600;line-height:1.5;min-width:190px;word-break:break-word}
td.ti .sub{display:inline-block;margin-left:7px;font-size:10px;font-weight:400;
color:var(--dim);border:1px solid var(--line);border-radius:9px;padding:1px 7px}
td.kd{white-space:nowrap;font-size:11px;color:var(--dim)}
td.kd .b{display:inline-block;border-radius:9px;padding:2px 8px;
border:1px solid var(--line);background:var(--chip)}
td.kd .b.album{color:var(--hit);border-color:#4a3a24}
td.kd .b.single{color:#8fb0c8;border-color:#2e3d49}
td.dt{white-space:nowrap;color:var(--dim);font-size:12px}
td.st{white-space:nowrap;font-weight:700;font-size:12px}
td.st.ok{color:var(--ok)}
td.st.no{color:var(--warn)}
td.st.dim{color:var(--dim);font-weight:400}
td.pr{white-space:nowrap;text-align:right;font-weight:700}
td.bo{color:var(--dim);font-size:12px;line-height:1.5;min-width:170px;
word-break:break-word}
td.bo .nw{color:#8fb0c8;font-size:10px;border:1px solid #2e3d49;border-radius:8px;
padding:1px 6px;margin-left:5px}
.empty{color:var(--dim);font-size:13px;text-align:center;padding:50px 14px;line-height:2}
.empty .big{font-size:15px;color:var(--ink);display:block;margin-bottom:6px}
@media(max-width:600px){
 th,td{padding:6px 7px;font-size:12px}
 .h-date,td.dt{display:none}        /* 幅が足りない。発売日は伏せる */
 td.ti{min-width:120px}
 /* 商品名が長いと行が間延びするので1行に収める（続きは横スクロールで） */
 td.bo{min-width:110px;max-width:170px;white-space:nowrap;overflow:hidden;
   text-overflow:ellipsis}
 td.kd{font-size:10px}
}
.saved{position:fixed;right:14px;bottom:14px;background:var(--panel);color:var(--hit);
border:1px solid var(--hit);border-radius:8px;padding:8px 14px;font-size:12px;
opacity:0;transition:opacity .2s;pointer-events:none;z-index:20}
.saved.on{opacity:1}
</style></head><body>
<header>
<h1>アーティストCDリスト × BOOKOFF オンライン</h1>
<div class="row">
<input type="text" id="artist" placeholder="アーティスト名（例: 宇多田ヒカル）">
<button class="go" id="bFind">アーティスト検索</button>
</div>
<div id="cands"></div>
<div class="filt">
<label class="sw on" id="swAl"><input type="checkbox" id="fAlbum" checked>アルバム</label>
<label class="sw on" id="swSg"><input type="checkbox" id="fSingle" checked>シングル</label>
<label class="sw" id="swEp"><input type="checkbox" id="fEp">EP</label>
<label class="sw" id="swOt"><input type="checkbox" id="fOther">その他</label>
<select id="fOwn">
<option value="all">所有：すべて</option>
<option value="yes">持っているもの</option>
<option value="no">持っていないもの</option>
</select>
<label class="sw" id="swSt"><input type="checkbox" id="fStock">在庫ありのみ</label>
<input type="text" id="q" placeholder="タイトルでしぼり込み" style="max-width:210px">
</div>
<div class="row" style="margin-top:8px">
<button class="go" id="bRun" disabled>BOOKOFF在庫チェック</button>
<button class="stop" id="bStop" disabled>中止</button>
<label class="sw on" id="swNew"><input type="checkbox" id="fresh" checked>未照会のみ</label>
<input type="text" id="bkName" style="max-width:190px;flex:0 1 190px"
 placeholder="BOOKOFF検索に使う名前"
 title="BOOKOFF は日本語表記で探すほうがよく当たります。ここを直せば検索語も変わります。">
<button id="bCsv">CSV</button>
<button id="bDiag">診断</button>
</div>
<div id="bar"><div></div></div>
<div id="stat" class="mono">アーティスト名を入れて検索してください</div>
<div id="sum" class="mono"></div>
</header>
<main><div id="view"></div></main>
<div class="saved" id="saved">保存しました</div>
"""

UI_JS = r"""
const $=s=>document.querySelector(s);
const esc=s=>String(s==null?'':s).replace(/[&<>"']/g,
  c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const IN_APP=!!window.App;
// アプリ内では新規タブが開けないので同一画面で遷移させ、Kotlin 側が外部ブラウザに渡す
const LT=IN_APP?'':' target="_blank" rel="noopener"';
const KIND={album:'アルバム',single:'シングル',ep:'EP',other:'その他'};
const KORD={album:0,single:1,ep:2,other:3};
const SORD={stock:0,sold:1,none:2,error:3};

let ART=null;          // 選んだアーティスト {id,name}
let ROWS=[];           // 作品一覧
let ST={};             // rgid -> 在庫チェック結果
let RUNNING=false, TIMER=null;
let SORT={k:'date',d:-1};

const yen=n=>n==null?'':'¥'+Number(n).toLocaleString('ja-JP');

function toast(msg){
  const el=$('#saved'); el.textContent=msg; el.classList.add('on');
  clearTimeout(el._t); el._t=setTimeout(()=>el.classList.remove('on'),1200);
}
function setStat(s){ $('#stat').textContent=s; }

/* ── しぼり込み ───────────────────────────── */
function filters(){
  return {album:$('#fAlbum').checked,single:$('#fSingle').checked,
          ep:$('#fEp').checked,other:$('#fOther').checked,
          own:$('#fOwn').value,stock:$('#fStock').checked,
          q:$('#q').value.trim().toLowerCase()};
}
function visible(){
  const f=filters();
  let a=ROWS.filter(r=>{
    if(!f[r.kind]) return false;
    if(f.own==='yes'&&!r.owned) return false;
    if(f.own==='no'&&r.owned) return false;
    if(f.stock&&(ST[r.id]||{}).state!=='stock') return false;
    if(f.q&&!(r.title+' '+(r.sub||'')).toLowerCase().includes(f.q)) return false;
    return true;
  });
  const k=SORT.k,d=SORT.d;
  a.sort((x,y)=>{
    let c=0;
    if(k==='kind') c=KORD[x.kind]-KORD[y.kind];
    else if(k==='title') c=x.title.localeCompare(y.title,'ja');
    else if(k==='date') c=(x.date||'').localeCompare(y.date||'');
    else if(k==='stock') c=(SORD[(ST[x.id]||{}).state]??9)-(SORD[(ST[y.id]||{}).state]??9);
    else if(k==='price') c=((ST[x.id]||{}).price??Infinity)-((ST[y.id]||{}).price??Infinity);
    else if(k==='own') c=(x.owned?0:1)-(y.owned?0:1);
    if(c===0) c=(x.date||'').localeCompare(y.date||'');
    return c*d;
  });
  return a;
}

/* ── 表 ──────────────────────────────────── */
function stockCell(r){
  const s=ST[r.id];
  if(!s) return ['dim','—'];
  if(s.state==='stock') return ['ok','○ 在庫あり'];
  if(s.state==='sold') return ['no','× 品切れ'];
  if(s.state==='error') return ['no','! '+esc(s.note||'エラー')];
  if(s.state==='checking') return ['dim','照会中…'];
  return ['dim','− 該当なし'];
}
function boCell(r){
  const s=ST[r.id]; if(!s) return '';
  if(s.url) return '<a href="'+esc(s.url)+'"'+LT+'>'+
    esc(s.name||'商品ページ')+'</a>'+(s.new?'<span class="nw">新品</span>':'')+
    (s.hits>1?'<span class="nw" style="color:var(--dim);border-color:var(--line)">他'+
      (s.hits-1)+'件</span>':'');
  if(s.search) return '<a href="'+esc(s.search)+'"'+LT+'>検索結果を見る</a>';
  return '';
}
function rowHtml(r){
  const [cls,txt]=stockCell(r), s=ST[r.id]||{};
  return '<tr data-id="'+esc(r.id)+'"'+(r.owned?' class="own"':'')+'>'+
   '<td class="c"><input type="checkbox" data-own="'+esc(r.id)+'"'+
     (r.owned?' checked':'')+'></td>'+
   '<td class="kd"><span class="b '+r.kind+'">'+KIND[r.kind]+'</span></td>'+
   '<td class="ti">'+esc(r.title)+
     (r.sub?'<span class="sub">'+esc(r.sub)+'</span>':'')+
     (r.note?'<span class="sub">'+esc(r.note)+'</span>':'')+'</td>'+
   '<td class="dt mono">'+esc(r.date||'—')+'</td>'+
   '<td class="st '+cls+'">'+txt+'</td>'+
   '<td class="pr mono">'+(s.price!=null?yen(s.price):'')+'</td>'+
   '<td class="bo">'+boCell(r)+'</td></tr>';
}
function th(k,label){
  return '<th data-s="'+k+'" class="h-'+k+'">'+label+
    (SORT.k===k?'<span class="ar">'+(SORT.d>0?'▲':'▼')+'</span>':'')+'</th>';
}
function render(){
  const rows=visible();
  if(!ROWS.length){
    $('#view').innerHTML='<div class="empty"><span class="big">'+
      'アーティストを検索してください</span>'+
      'CD（アルバム・シングル）の一覧を作り、<br>'+
      '持っているものにチェックを入れて保存できます。<br>'+
      'そのまま BOOKOFF オンラインの在庫と値段も引きます。</div>';
    summary(); return;
  }
  if(!rows.length){
    $('#view').innerHTML='<div class="empty">しぼり込みに合う作品がありません。</div>';
    summary(); return;
  }
  $('#view').innerHTML='<div class="tw"><table><thead><tr>'+
    th('own','所有')+th('kind','種別')+th('title','タイトル')+th('date','発売')+
    th('stock','在庫')+th('price','価格')+'<th>BOOKOFF の商品</th>'+
    '</tr></thead><tbody>'+rows.map(rowHtml).join('')+'</tbody></table></div>';
  summary();
}
function patch(id){
  const tr=document.querySelector('tr[data-id="'+CSS.escape(id)+'"]');
  if(!tr){ schedule(); return; }
  const r=ROWS.find(x=>x.id===id); if(!r) return;
  const [cls,txt]=stockCell(r), s=ST[id]||{};
  const st=tr.querySelector('.st'); st.className='st '+cls; st.innerHTML=txt;
  tr.querySelector('.pr').textContent=s.price!=null?yen(s.price):'';
  tr.querySelector('.bo').innerHTML=boCell(r);
  if(filters().stock) schedule();
  summary();
}
function schedule(){         // しぼり込み中は並びが変わるのでまとめて描き直す
  clearTimeout(TIMER); TIMER=setTimeout(render,400);
}
function summary(){
  if(!ROWS.length){ $('#sum').textContent=''; return; }
  const vis=visible();
  const own=ROWS.filter(r=>r.owned).length;
  const stock=ROWS.filter(r=>(ST[r.id]||{}).state==='stock');
  const want=stock.filter(r=>!r.owned);
  const sum=want.reduce((a,r)=>a+((ST[r.id]||{}).price||0),0);
  $('#sum').innerHTML='全<b>'+ROWS.length+'</b>件<span class="sep">|</span>'+
    '表示<b>'+vis.length+'</b>件<span class="sep">|</span>'+
    '所有<b>'+own+'</b>件<span class="sep">|</span>'+
    '在庫あり<b>'+stock.length+'</b>件<span class="sep">|</span>'+
    '未所有で在庫あり<b>'+want.length+'</b>件'+
    (sum?'（合計 '+yen(sum)+'）':'');
}

/* ── アーティスト検索 ─────────────────────── */
async function findArtist(){
  const q=$('#artist').value.trim();
  if(!q) return;
  $('#cands').innerHTML=''; setStat('アーティストを探しています…');
  try{
    const d=await T.artists(q);
    if(!d.ok){ setStat('× '+d.msg); return; }
    if(!d.artists.length){ setStat('見つかりませんでした'); return; }
    $('#cands').innerHTML=d.artists.map((a,i)=>
      '<div class="chip" data-mbid="'+esc(a.id)+'" data-name="'+esc(a.name)+
      '" data-alias="'+esc(a.alias||'')+'">'+
      esc(a.name)+(a.alias?'<span class="s">'+esc(a.alias)+'</span>':'')+
      '<span class="s">'+esc([a.area,a.type,a.note].filter(Boolean).join(' / '))+
      '</span></div>').join('');
    setStat('候補から選んでください（'+d.artists.length+'件）');
  }catch(e){ setStat('× 通信エラー: '+e); }
}
async function loadDisco(mbid,name,chip){
  ART={id:mbid,name:name};
  // BOOKOFF は日本語表記でないと当たらない。別名（日本語）があればそちらを使う
  $('#bkName').value=(chip&&chip.dataset.alias)||name;
  document.querySelectorAll('#cands .chip').forEach(c=>c.classList.remove('on'));
  if(chip) chip.classList.add('on');
  setStat(name+' の作品一覧を読み込んでいます…');
  $('#bRun').disabled=true;
  try{
    const d=await T.disco(mbid);
    if(!d.ok){ setStat('× '+d.msg); return; }
    ROWS=d.items; ST={};
    setStat(name+'：'+ROWS.length+'件の作品を読み込みました');
    $('#bRun').disabled=false;
    render();
  }catch(e){ setStat('× 通信エラー: '+e); }
}

/* ── 所有チェック（即保存） ───────────────── */
async function toggleOwn(id,owned){
  const r=ROWS.find(x=>x.id===id); if(!r) return;
  r.owned=owned;
  const tr=document.querySelector('tr[data-id="'+CSS.escape(id)+'"]');
  if(tr) tr.classList.toggle('own',owned);
  summary();
  try{
    const res=await T.setOwned(id,owned,
      {title:r.title,artist:ART?ART.name:'',kind:r.kind,date:r.date});
    toast(owned?'保存しました（所有 '+res.count+'件）':'外しました（所有 '+res.count+'件）');
  }catch(e){ toast('× 保存できませんでした'); r.owned=!owned; render(); }
  if(filters().own!=='all') schedule();
}

/* ── 在庫チェック ─────────────────────────── */
async function startCheck(){
  if(RUNNING||!ART) return;
  let targets=visible();
  if($('#fresh').checked) targets=targets.filter(r=>!ST[r.id]);
  if(!targets.length){ setStat('照会する作品がありません（未照会のみを外すと再照会できます）'); return; }
  if(targets.length>60&&!confirm(targets.length+'件を BOOKOFF に照会します。\n'+
     '相手のサイトに負担をかけないよう間隔を空けるので時間がかかります。よろしいですか？')) return;
  const plan=await T.plan($('#bkName').value.trim()||ART.name,
    targets.map(r=>({id:r.id,title:r.title})));
  if(!plan.ok){ setStat('× '+plan.msg); return; }
  targets.forEach(r=>{ ST[r.id]={state:'checking'}; patch(r.id); });
  RUNNING=true; $('#bRun').disabled=true; $('#bStop').disabled=false;
  $('#bar').classList.add('on'); $('#bar div').style.width='0%';
  T.start();
}
/* 実行中の知らせ。Web は SSE、アプリは Kotlin からここへ入る */
function __event(kind,d){
  if(kind==='status') setStat(d.msg);
  else if(kind==='progress'){
    $('#bar div').style.width=(d.total?d.done/d.total*100:0)+'%';
    setStat(d.done+' / '+d.total+' 件（並列 '+d.workers+'）');
  }
  else if(kind==='row'){ ST[d.id]=d; patch(d.id); }
  else if(kind==='end'){ stopUi(); render(); }
  else if(kind==='lost'){ if(RUNNING) setStat('接続が切れました'); stopUi(); }
}
function stopUi(){
  RUNNING=false;
  T.stopStream();
  $('#bRun').disabled=!ART; $('#bStop').disabled=true;
  $('#bar').classList.remove('on');
  Object.keys(ST).forEach(k=>{ if(ST[k].state==='checking') delete ST[k]; });
}
async function stopCheck(){
  await T.cancel();
  setStat('中止しています…');
}

/* ── CSV ─────────────────────────────────── */
function csv(){
  const q=s=>'"'+String(s==null?'':s).replace(/"/g,'""')+'"';
  const lines=[['所有','種別','タイトル','副題','発売日','在庫','価格',
                'BOOKOFF商品名','URL'].map(q).join(',')];
  visible().forEach(r=>{
    const s=ST[r.id]||{};
    const st={stock:'在庫あり',sold:'品切れ',none:'該当なし',error:'エラー'}[s.state]||'未照会';
    lines.push([r.owned?'○':'',KIND[r.kind],r.title,r.sub||'',r.date||'',st,
                s.price!=null?s.price:'',s.name||'',s.url||''].map(q).join(','));
  });
  T.csv(lines.join('\r\n'),(ART?ART.name:'cd')+'_bookoff.csv');
}

/* ── 配線 ────────────────────────────────── */
$('#bFind').onclick=findArtist;
$('#artist').addEventListener('keydown',e=>{ if(e.key==='Enter') findArtist(); });
$('#cands').addEventListener('click',e=>{
  const c=e.target.closest('.chip'); if(!c) return;
  loadDisco(c.dataset.mbid,c.dataset.name,c);
});
$('#view').addEventListener('change',e=>{
  const cb=e.target.closest('input[data-own]'); if(!cb) return;
  toggleOwn(cb.dataset.own,cb.checked);
});
$('#view').addEventListener('click',e=>{
  const t=e.target.closest('th[data-s]'); if(!t) return;
  const k=t.dataset.s;
  SORT = SORT.k===k ? {k:k,d:-SORT.d} : {k:k,d:(k==='date'||k==='own')?-1:1};
  render();
});
[['fAlbum','swAl'],['fSingle','swSg'],['fEp','swEp'],['fOther','swOt'],
 ['fStock','swSt'],['fresh','swNew']].forEach(([id,sw])=>{
  $('#'+id).addEventListener('change',()=>{
    $('#'+sw).classList.toggle('on',$('#'+id).checked);
    if(id!=='fresh') render();
  });
});
$('#fOwn').addEventListener('change',render);
$('#q').addEventListener('input',schedule);
$('#bRun').onclick=startCheck;
$('#bStop').onclick=stopCheck;
$('#bCsv').onclick=csv;
$('#bDiag').onclick=()=>T.diag(
  ($('#bkName').value.trim()+' '+(ROWS[0]?ROWS[0].title:'')).trim());
render();
"""

# Web 版の通信。Flask の API と SSE を叩く。
WEB_JS = r"""
let ES=null;
const _post=async(u,o)=>(await fetch(u,{method:'POST',
  headers:{'Content-Type':'application/json'},body:JSON.stringify(o)})).json();
const T={
  artists:async q=>(await fetch('/api/artists?q='+encodeURIComponent(q))).json(),
  disco:async mbid=>(await fetch('/api/discography?mbid='+
    encodeURIComponent(mbid))).json(),
  setOwned:(id,owned,meta)=>_post('/api/owned',{id:id,owned:owned,meta:meta}),
  plan:(artist,items)=>_post('/api/plan',{artist:artist,items:items}),
  start(){
    ES=new EventSource('/run');
    ['status','progress','row','end'].forEach(k=>
      ES.addEventListener(k,e=>__event(k,JSON.parse(e.data))));
    ES.onerror=()=>__event('lost',{});
  },
  stopStream(){ if(ES){ ES.close(); ES=null; } },
  cancel:async()=>fetch('/api/cancel',{method:'POST'}),
  diag:kw=>window.open('/diag?kw='+encodeURIComponent(kw),'_blank'),
  csv(text,name){
    const a=document.createElement('a');
    a.href=URL.createObjectURL(new Blob(['\ufeff'+text],
      {type:'text/csv;charset=utf-8'}));
    a.download=name; a.click(); URL.revokeObjectURL(a.href);
  }
};
"""

PAGE = UI_HTML + "\n<script>\n" + WEB_JS + "\n" + UI_JS + "\n</script></body></html>\n"

if __name__ == "__main__":
    print(f"→ http://127.0.0.1:{PORT}   所有リスト: {OWNED_FILE}")
    app.run(host="0.0.0.0", port=PORT, threaded=True, debug=False)
