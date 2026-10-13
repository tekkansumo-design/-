#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BOOKOFF 価格差リサーチ（bookoff_web.py から読み込まれる）

ブックオフ公式通販の検索／カテゴリ一覧を巡回し、
  1. 店舗在庫が 1 店舗以上ある商品だけを残す（＝必ずどこかの店舗で買える）
  2. メルカリの売り切れ相場・ヤフオクの落札相場を調べる
  3. 手数料と送料を引いた利益が出るものを並べる
という流れで「店舗で仕入れて利益が出る商品」を探す。

bookoff_web.py と同じフォルダに置けば、Web 版の画面に「利益リサーチ」
ボタンが出る（http://127.0.0.1:5000/profit）。追加で要るライブラリは無い
（メルカリの署名も標準ライブラリだけで行う）。

注意:
  - ブックオフの通販価格と店頭価格は一致しないことがある。仕入れ値は目安。
  - 相場は商品名での検索結果なので別商品が混ざりうる。必ずリンク先で確認する。
  - 各サイトに負荷をかけないよう、サイトごとに間隔を空けて 1 件ずつ取りに行く。
"""

import base64
import csv
import hashlib
import io
import json
import re
import secrets
import statistics
import threading
import time
import traceback
import uuid
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qsl, quote, urlencode, urljoin, urlparse, urlunparse

import requests

BASE_DIR = Path(__file__).resolve().parent
CONF_FILE = BASE_DIR / "bookoff_profit_config.json"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")

DEFAULTS = {
    "pages": 3,             # 一覧を何ページ目まで巡回するか
    "max_cost": 3000,       # これより高いブックオフ価格は調べない
    "min_profit": 300,      # この利益以上を「利益あり」とする
    "min_sold": 2,          # 相場として信用する最低の売れた件数
    "shipping": 230,        # 1 個あたりの送料
    "mercari_fee": 10.0,    # %
    "yahoo_fee": 10.0,      # %（LYP プレミアム会員は 8.8）
    "sold_limit": 30,       # 相場に使う直近の売れた件数
}

# サイトごとの最低リクエスト間隔（秒）
INTERVAL = {"bookoff": 0.8, "mercari": 1.5, "yahoo": 1.5}
BACKOFF = [10, 20, 40, 80]
CACHE_TTL = 6 * 3600


def load_conf():
    c = dict(DEFAULTS)
    if CONF_FILE.exists():
        try:
            d = json.loads(CONF_FILE.read_text(encoding="utf-8"))
            for k in DEFAULTS:
                if k in d:
                    c[k] = type(DEFAULTS[k])(d[k])
        except Exception as e:
            print(f"[warn] {CONF_FILE.name} を読めません: {e}")
    return c


def save_conf(d):
    c = load_conf()
    for k, v in d.items():
        if k in DEFAULTS:
            try:
                c[k] = type(DEFAULTS[k])(v)
            except (TypeError, ValueError):
                pass
    CONF_FILE.write_text(json.dumps(c, ensure_ascii=False, indent=1),
                         encoding="utf-8")
    return c


# ═══════════════════════════════════════════════
#  ES256（メルカリ API の DPoP 署名用。cryptography を入れずに済ませる）
# ═══════════════════════════════════════════════
_P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
_A = _P - 3
_N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551
_G = (0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296,
      0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5)


def _ec_add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0]:
        if (p[1] + q[1]) % _P == 0:
            return None
        lam = (3 * p[0] * p[0] + _A) * pow(2 * p[1], -1, _P) % _P
    else:
        lam = (q[1] - p[1]) * pow(q[0] - p[0], -1, _P) % _P
    x = (lam * lam - p[0] - q[0]) % _P
    return x, (lam * (p[0] - x) - p[1]) % _P


def _ec_mul(k, p):
    r = None
    while k:
        if k & 1:
            r = _ec_add(r, p)
        p = _ec_add(p, p)
        k >>= 1
    return r


def _b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class DPoP:
    """メルカリ Web 版と同じく、使い捨ての鍵で DPoP トークンを作る。"""

    def __init__(self):
        self.d = secrets.randbelow(_N - 1) + 1
        x, y = _ec_mul(self.d, _G)
        self.jwk = {"crv": "P-256", "kty": "EC",
                    "x": _b64u(x.to_bytes(32, "big")),
                    "y": _b64u(y.to_bytes(32, "big"))}

    def sign(self, msg: bytes) -> bytes:
        z = int.from_bytes(hashlib.sha256(msg).digest(), "big")
        while True:
            k = secrets.randbelow(_N - 1) + 1
            r = _ec_mul(k, _G)[0] % _N
            if not r:
                continue
            s = pow(k, -1, _N) * (z + r * self.d) % _N
            if s:
                return r.to_bytes(32, "big") + s.to_bytes(32, "big")

    def token(self, method, url):
        head = {"typ": "dpop+jwt", "alg": "ES256", "jwk": self.jwk}
        body = {"iat": int(time.time()), "jti": str(uuid.uuid4()),
                "htu": url, "htm": method, "uuid": str(uuid.uuid4())}
        enc = lambda o: _b64u(json.dumps(o, separators=(",", ":")).encode())
        signing = f"{enc(head)}.{enc(body)}"
        return f"{signing}.{_b64u(self.sign(signing.encode()))}"


# ═══════════════════════════════════════════════
#  通信（サイトごとに間隔を空け、403/429/503 は待って再試行）
# ═══════════════════════════════════════════════
class Cancelled(Exception):
    pass


class FetchError(Exception):
    pass


class Http:
    def __init__(self, cancel: threading.Event, log=print):
        self.s = requests.Session()
        self.s.headers.update({
            "User-Agent": UA,
            "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
        })
        self.cancel = cancel
        self.log = log
        self.last = {}
        self.lock = {k: threading.Lock() for k in INTERVAL}

    def _wait(self, sec):
        if self.cancel.wait(sec):
            raise Cancelled()

    def request(self, site, method, url, **kw):
        kw.setdefault("timeout", 25)
        for attempt in range(len(BACKOFF) + 1):
            if self.cancel.is_set():
                raise Cancelled()
            with self.lock[site]:              # サイトごとに 1 本ずつ
                gap = INTERVAL[site] - (time.time() - self.last.get(site, 0))
                if gap > 0:
                    self._wait(gap)
                try:
                    r = self.s.request(method, url, **kw)
                    err = None
                except requests.RequestException as e:
                    r, err = None, type(e).__name__
                self.last[site] = time.time()
            if r is not None and r.status_code not in (403, 429, 500, 502, 503):
                return r
            if attempt == len(BACKOFF):
                break
            wait = BACKOFF[attempt]
            self.log(f"{site}: {err or 'HTTP %d' % r.status_code} — {wait}秒待って再試行")
            self._wait(wait)
        raise FetchError(f"{site}: {err or 'HTTP %d' % r.status_code}")


# ═══════════════════════════════════════════════
#  ブックオフ
# ═══════════════════════════════════════════════
BOOKOFF = "https://shopping.bookoff.co.jp"
PID_RE = re.compile(r"/used/(\d{8,12})")


def search_url(keyword):
    return f"{BOOKOFF}/search/keyword/{quote(keyword)}"


def page_url(url, page):
    """一覧 URL の page= を差し替える。1 ページ目は元の URL のまま。"""
    if page <= 1:
        return url
    u = urlparse(url)
    q = [(k, v) for k, v in parse_qsl(u.query, keep_blank_values=True)
         if k != "page"]
    q.append(("page", str(page)))
    return urlunparse(u._replace(query=urlencode(q)))


def parse_list(html):
    """一覧ページの商品 ID を出現順に返す。"""
    seen, out = set(), []
    for m in PID_RE.finditer(html):
        pid = m.group(1).zfill(10)
        if pid not in seen:
            seen.add(pid)
            out.append(pid)
    return out


def _int(s):
    s = re.sub(r"[^\d]", "", str(s or ""))
    return int(s) if s else None


def parse_price(html):
    """商品ページの販売価格（税込）。構造化データ → meta → 本文の順で探す。"""
    for pat in (r'"price"\s*:\s*"?([\d,]+)',
                r'itemprop=["\']price["\'][^>]*content=["\']([\d,]+)',
                r'property=["\']product:price:amount["\'][^>]*content=["\']([\d,]+)'):
        m = re.search(pat, html)
        if m and _int(m.group(1)):
            return _int(m.group(1))
    text = re.sub(r"<[^>]+>", " ", html)
    for pat in (r"(?:販売価格|価格)[^\d￥¥]{0,20}[￥¥]\s*([\d,]+)",
                r"[￥¥]\s*([\d,]+)\s*[（(]?税込",
                r"([\d,]+)\s*円\s*[（(]?税込"):
        m = re.search(pat, text)
        if m and _int(m.group(1)):
            return _int(m.group(1))
    return None


def parse_jan(html):
    m = re.search(r'"gtin13"\s*:\s*"(\d{13})"', html)
    if m:
        return m.group(1)
    text = re.sub(r"<[^>]+>", " ", html)
    m = re.search(r"(?:JAN|ＪＡＮ|ISBN)[^\d]{0,40}(\d{13}|\d{9}[\dX])", text)
    return m.group(1) if m else None


# ═══════════════════════════════════════════════
#  相場
# ═══════════════════════════════════════════════
# まとめ売りは単品の相場を押し上げるので、元の商品名に無ければ除外する
BUNDLE_WORDS = ["セット", "まとめ", "全巻", "大量", "BOX", "ボックス", "初回限定",
                "限定版", "特典のみ", "ケースのみ", "ジャンク", "空箱"]


def clean_keyword(name):
    """ブックオフの商品名を検索向けに整える。"""
    s = name or ""
    s = re.sub(r"【[^】]*】|［[^］]*］|\[[^\]]*\]|〈[^〉]*〉", " ", s)
    s = re.sub(r"[（(](?:中古|新品|帯付|帯なし|訳あり)[^）)]*[）)]", " ", s)
    s = re.sub(r"[/／|｜:：・]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s[:50]


def filter_titles(items, ref_title):
    ref = (ref_title or "").upper()
    out = []
    for it in items:
        t = it["title"].upper()
        if any(w.upper() in t and w.upper() not in ref for w in BUNDLE_WORDS):
            continue
        out.append(it)
    return out


def summarize(items, ref_title, limit):
    items = filter_titles([i for i in items if i["price"] and i["price"] >= 100],
                          ref_title)[:limit]
    if not items:
        return {"n": 0, "median": None, "min": None, "max": None, "items": []}
    prices = [i["price"] for i in items]
    return {"n": len(prices), "median": int(statistics.median(prices)),
            "min": min(prices), "max": max(prices), "items": items[:15]}


MERCARI_API = "https://api.mercari.jp/v2/entities:search"


def mercari_link(kw):
    return "https://jp.mercari.com/search?" + urlencode(
        {"keyword": kw, "status": "sold_out"})


def mercari_sold(http: Http, dpop: DPoP, kw):
    body = {
        "userId": "", "pageSize": 120, "pageToken": "",
        "searchSessionId": uuid.uuid4().hex,
        "indexRouting": "INDEX_ROUTING_UNSPECIFIED", "thumbnailTypes": [],
        "searchCondition": {
            "keyword": kw, "excludeKeyword": "",
            "sort": "SORT_CREATED_TIME", "order": "ORDER_DESC",
            "status": ["STATUS_SOLD_OUT", "STATUS_TRADING"],
            "sizeId": [], "categoryId": [], "brandId": [], "sellerId": [],
            "priceMin": 0, "priceMax": 0, "itemConditionId": [],
            "shippingPayerId": [], "shippingFromArea": [], "shippingMethod": [],
            "colorId": [], "hasCoupon": False, "attributes": [],
            "itemTypes": [], "skuIds": [],
        },
        "defaultDatasets": ["DATASET_TYPE_MERCARI", "DATASET_TYPE_BEYOND"],
        "serviceFrom": "suruga", "withItemBrand": False, "withItemSize": False,
        "withItemPromotions": False, "withItemSizes": False,
        "withShopname": False,
    }
    r = http.request("mercari", "POST", MERCARI_API, json=body, headers={
        "DPoP": dpop.token("POST", MERCARI_API),
        "X-Platform": "web",
        "Accept": "application/json",
        "Origin": "https://jp.mercari.com",
        "Referer": "https://jp.mercari.com/",
    })
    if r.status_code != 200:
        raise FetchError(f"mercari: HTTP {r.status_code}")
    out = []
    for it in r.json().get("items", []):
        iid = it.get("id", "")
        url = (f"https://jp.mercari.com/item/{iid}" if iid.startswith("m")
               else f"https://jp.mercari.com/shops/product/{iid}")
        ts = _int(it.get("updated"))
        out.append({"title": it.get("name", ""), "price": _int(it.get("price")),
                    "url": url,
                    "date": datetime.fromtimestamp(ts).strftime("%m/%d") if ts else ""})
    return out


def yahoo_link(kw):
    return "https://auctions.yahoo.co.jp/closedsearch/closedsearch?" + urlencode(
        {"p": kw, "va": kw, "b": 1, "n": 100})


class YahooScan(HTMLParser):
    """落札相場ページの li.Product から タイトル・URL・落札価格・終了日 を拾う。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.items = []
        self.cur = None
        self.grab = None
        self.buf = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        cls = (a.get("class") or "").split()
        if tag == "li" and "Product" in cls:
            self.cur = {"title": "", "price": None, "url": "", "date": ""}
            self.items.append(self.cur)
            return
        if self.cur is None:
            return
        if tag == "a" and "Product__titleLink" in cls:
            self.cur["url"] = a.get("href", "")
            self.cur["title"] = a.get("title") or a.get("data-auction-title") or ""
            if a.get("data-auction-price"):
                self.cur["price"] = _int(a["data-auction-price"])
            if not self.cur["title"]:
                self.grab, self.buf = "title", []
        elif "Product__priceValue" in cls and self.cur["price"] is None:
            self.grab, self.buf = "price", []
        elif "Product__time" in cls and not self.cur["date"]:
            self.grab, self.buf = "date", []

    def handle_endtag(self, tag):
        if self.grab and tag in ("a", "span", "dd", "div", "p"):
            t = re.sub(r"\s+", " ", "".join(self.buf)).strip()
            if self.grab == "price":
                self.cur["price"] = _int(t)
            else:
                self.cur[self.grab] = t
            self.grab = None

    def handle_data(self, data):
        if self.grab:
            self.buf.append(data)


def parse_yahoo(html):
    p = YahooScan()
    try:
        p.feed(html)
    except Exception:
        pass
    return [i for i in p.items if i["title"] and i["price"]]


def yahoo_sold(http: Http, kw):
    r = http.request("yahoo", "GET", yahoo_link(kw))
    if r.status_code != 200:
        raise FetchError(f"yahoo: HTTP {r.status_code}")
    return parse_yahoo(r.text)


def calc(cost, mer, yah, conf):
    """各売り先の手取りと利益。相場の件数が足りない売り先は使わない。"""
    ship = conf["shipping"]
    out = {}
    for key, mk, fee in (("mercari", mer, conf["mercari_fee"]),
                         ("yahoo", yah, conf["yahoo_fee"])):
        if mk and mk["n"] >= conf["min_sold"] and mk["median"]:
            net = int(mk["median"] * (1 - fee / 100) - ship)
            out[key] = {"net": net,
                        "profit": net - cost if cost is not None else None}
    best = max(out.items(), key=lambda kv: kv[1]["net"], default=(None, None))
    profit = best[1]["profit"] if best[1] else None
    return {"by": out, "best": best[0], "profit": profit,
            "roi": round(profit / cost * 100) if profit is not None and cost else None}


# ═══════════════════════════════════════════════
#  リサーチ本体
# ═══════════════════════════════════════════════
class Research:
    def __init__(self, scan_page, parse_shops):
        self.scan_page = scan_page        # bookoff_web の解析をそのまま使う
        self.parse_shops = parse_shops    # （店舗在庫の判定を 1 か所に保つ）
        self.cancel = threading.Event()
        self.lock = threading.Lock()
        self.cache = {}                   # (site, kw) -> (time, summary)
        self.dpop = DPoP()
        self.state = self._blank()

    def _blank(self):
        return {"running": False, "phase": "待機中", "total": 0, "done": 0,
                "listed": 0, "no_stock": 0, "skipped": 0, "log": [],
                "items": {}, "source": ""}

    def log(self, msg):
        with self.lock:
            self.state["log"] = (self.state["log"] + [
                f"{datetime.now():%H:%M:%S} {msg}"])[-40:]

    def snapshot(self):
        with self.lock:
            s = dict(self.state)
            s["items"] = list(self.state["items"].values())
            return s

    # ── 起動 ──
    def start(self, source):
        source = (source or "").strip()
        if not source:
            return False, "検索キーワードか一覧ページの URL を入れてください"
        with self.lock:
            if self.state["running"]:
                return False, "実行中です"
            self.state = self._blank()
            self.state.update(running=True, source=source)
        self.cancel.clear()
        threading.Thread(target=self._run, args=(source, load_conf()),
                         daemon=True).start()
        return True, "開始しました"

    def stop(self):
        self.cancel.set()

    def _set(self, **kw):
        with self.lock:
            self.state.update(kw)

    def _run(self, source, conf):
        http = Http(self.cancel, self.log)
        try:
            url = source if source.startswith("http") else search_url(source)
            pids = self._collect(http, url, conf["pages"])
            self._set(total=len(pids), listed=len(pids))
            for i, pid in enumerate(pids):
                self._set(phase=f"商品を確認中 {i + 1}/{len(pids)}")
                try:
                    self._one(http, pid, conf)
                except Cancelled:
                    raise
                except FetchError as e:
                    self.log(f"{pid}: {e}")
                except Exception:
                    traceback.print_exc()
                    self.log(f"{pid}: 内部エラー")
                with self.lock:
                    self.state["done"] += 1
            self._set(phase="完了")
        except Cancelled:
            self._set(phase="中止しました")
        except Exception as e:
            traceback.print_exc()
            self._set(phase=f"エラー: {e}")
        finally:
            self._set(running=False)

    def _collect(self, http, url, pages):
        pids, seen = [], set()
        for p in range(1, max(1, pages) + 1):
            self._set(phase=f"一覧を取得中 {p}/{pages} ページ")
            r = http.request("bookoff", "GET", page_url(url, p))
            if r.status_code != 200:
                self.log(f"一覧 {p}ページ目: HTTP {r.status_code}")
                break
            new = [x for x in parse_list(r.text) if x not in seen]
            if not new:                      # ページ送りの終わり
                break
            seen.update(new)
            pids += new
            self.log(f"一覧 {p}ページ目: {len(new)}件")
        if not pids:
            self.log("一覧から商品が見つかりません。URL を確認するか /profit/debug で中身を見てください")
        return pids

    def _one(self, http, pid, conf):
        url = f"{BOOKOFF}/used/{pid}"
        r = http.request("bookoff", "GET", url)
        if r.status_code != 200:
            raise FetchError(f"HTTP {r.status_code}")
        h1, links = self.scan_page(r.text)
        shops = self.parse_shops(url, links)
        if not shops:
            with self.lock:
                self.state["no_stock"] += 1
            return
        price = parse_price(r.text)
        if price is not None and price > conf["max_cost"]:
            with self.lock:
                self.state["skipped"] += 1
            return
        item = {"pid": pid, "url": url, "name": h1 or pid, "price": price,
                "jan": parse_jan(r.text), "shops": shops,
                "keyword": clean_keyword(h1 or "")}
        self._market(http, item, conf)
        with self.lock:
            self.state["items"][pid] = item

    def _cached(self, site, kw, fn):
        hit = self.cache.get((site, kw))
        if hit and time.time() - hit[0] < CACHE_TTL:
            return hit[1]
        v = fn()
        self.cache[(site, kw)] = (time.time(), v)
        return v

    def _market(self, http, item, conf):
        kw = item["keyword"]
        item["mercari_url"], item["yahoo_url"] = mercari_link(kw), yahoo_link(kw)
        errs = []
        mer = yah = None
        if kw:
            try:
                raw = self._cached("mercari", kw,
                                   lambda: mercari_sold(http, self.dpop, kw))
                mer = summarize(raw, item["name"], conf["sold_limit"])
            except FetchError as e:
                errs.append(str(e))
            try:
                raw = self._cached("yahoo", kw, lambda: yahoo_sold(http, kw))
                yah = summarize(raw, item["name"], conf["sold_limit"])
            except FetchError as e:
                errs.append(str(e))
        item.update(mercari=mer, yahoo=yah, errors=errs,
                    calc=calc(item["price"], mer, yah, conf))

    def requery(self, pid, keyword):
        """キーワードを直して相場だけ取り直す。"""
        with self.lock:
            item = self.state["items"].get(pid)
        if not item:
            return None
        item = dict(item, keyword=keyword.strip())
        http = Http(threading.Event(), self.log)
        self._market(http, item, load_conf())
        with self.lock:
            self.state["items"][pid] = item
        return item

    def recalc(self):
        """設定（送料・手数料）を変えたら取り直さずに利益だけ計算し直す。"""
        conf = load_conf()
        with self.lock:
            for it in self.state["items"].values():
                it["calc"] = calc(it["price"], it.get("mercari"),
                                  it.get("yahoo"), conf)

    def csv(self):
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["商品ID", "商品名", "JAN", "ブックオフ価格", "在庫店舗数",
                    "メルカリ中央値", "メルカリ件数", "ヤフオク中央値",
                    "ヤフオク件数", "売り先", "利益", "利益率%", "店舗", "URL"])
        for it in self.snapshot()["items"]:
            m, y, c = it.get("mercari") or {}, it.get("yahoo") or {}, it["calc"]
            w.writerow([it["pid"], it["name"], it.get("jan") or "", it["price"],
                        len(it["shops"]), m.get("median"), m.get("n"),
                        y.get("median"), y.get("n"), c["best"] or "",
                        c["profit"], c["roi"],
                        " / ".join(s["name"] for s in it["shops"]), it["url"]])
        return buf.getvalue().encode("utf-8-sig")


# ═══════════════════════════════════════════════
#  Flask への組み込み
# ═══════════════════════════════════════════════
def register(app, scan_page, parse_shops):
    from flask import Response, jsonify, request

    R = Research(scan_page, parse_shops)

    def body():
        d = request.get_json(silent=True)
        return d if isinstance(d, dict) else {}

    @app.get("/profit")
    def profit_page():
        return Response(PAGE, mimetype="text/html; charset=utf-8")

    @app.get("/api/profit/state")
    def profit_state():
        return jsonify(R.snapshot())

    @app.get("/api/profit/config")
    def profit_conf():
        return jsonify(load_conf())

    @app.post("/api/profit/config")
    def profit_conf_set():
        c = save_conf(body())
        R.recalc()
        return jsonify(c)

    @app.post("/api/profit/start")
    def profit_start():
        ok, msg = R.start(body().get("source", ""))
        return jsonify({"ok": ok, "msg": msg}), (200 if ok else 409)

    @app.post("/api/profit/stop")
    def profit_stop():
        R.stop()
        return jsonify({"ok": True})

    @app.post("/api/profit/requery")
    def profit_requery():
        d = body()
        it = R.requery(str(d.get("pid", "")), str(d.get("keyword", "")))
        return (jsonify(it) if it else (jsonify({"msg": "not found"}), 404))

    @app.get("/api/profit/csv")
    def profit_csv():
        return Response(R.csv(), mimetype="text/csv", headers={
            "Content-Disposition": "attachment; filename=bookoff_profit.csv"})

    @app.get("/profit/debug")
    def profit_debug():
        """各サイトから何が取れているかを確認する（構造が変わったとき用）。

        /profit/debug?bookoff=一覧URLまたは商品ID
        /profit/debug?kw=検索語   （メルカリ・ヤフオク）
        """
        http = Http(threading.Event(), print)
        out = []
        target = request.args.get("bookoff", "").strip()
        kw = request.args.get("kw", "").strip()
        try:
            if target:
                url = (f"{BOOKOFF}/used/{target.zfill(10)}" if target.isdigit()
                       else target if target.startswith("http")
                       else search_url(target))
                r = http.request("bookoff", "GET", url)
                h1, links = scan_page(r.text)
                out += [f"URL: {url}", f"HTTP {r.status_code} / {len(r.text)} bytes",
                        f"h1: {h1}", f"価格: {parse_price(r.text)}",
                        f"JAN: {parse_jan(r.text)}",
                        f"一覧として見た商品ID: {parse_list(r.text)[:40]}",
                        f"在庫店舗: {parse_shops(url, links)}",
                        f"検索キーワード: {clean_keyword(h1 or '')}"]
            if kw:
                for name, fn in (("メルカリ", lambda: mercari_sold(http, R.dpop, kw)),
                                 ("ヤフオク", lambda: yahoo_sold(http, kw))):
                    try:
                        items = fn()
                        s = summarize(items, kw, 30)
                        out.append(f"--- {name}: 取得 {len(items)}件 / 相場 "
                                   f"{s['median']}円 ({s['n']}件)")
                        out += [f"  {i['price']}円 {i['date']} {i['title'][:50]}"
                                for i in items[:20]]
                    except FetchError as e:
                        out.append(f"--- {name}: {e}")
        except FetchError as e:
            out.append(str(e))
        if not out:
            out = ["使い方: /profit/debug?bookoff=商品IDか一覧URL  /profit/debug?kw=検索語"]
        return Response("\n".join(out), mimetype="text/plain; charset=utf-8")

    return R


# ═══════════════════════════════════════════════
#  画面
# ═══════════════════════════════════════════════
PAGE = r"""<!doctype html><html lang="ja"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BOOKOFF 利益リサーチ</title>
<style>
:root{--bg:#16110d;--panel:#1d1712;--line:#2a2620;--ink:#f0e6da;--dim:#a08d78;
--hit:#e0a458;--warn:#c8553d;--ok:#7f9c6d;--chip:#241d16}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font-size:14px;
font-family:"Yu Gothic UI","Meiryo",system-ui,sans-serif;-webkit-text-size-adjust:100%}
.mono{font-variant-numeric:tabular-nums}
header{position:sticky;top:0;z-index:9;background:var(--panel);
border-bottom:1px solid var(--line);padding:10px 12px}
h1{font-size:13px;margin:0 0 8px;letter-spacing:.06em;color:var(--hit)}
h1 a{color:var(--dim);font-weight:400;font-size:11px;margin-left:8px}
.row{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
input{background:#120e0a;color:var(--ink);border:1px solid var(--line);
border-radius:6px;padding:8px;font-size:13px;width:100%}
button{background:var(--line);color:var(--ink);border:1px solid #3a352c;
border-radius:6px;padding:7px 13px;font-size:13px;cursor:pointer}
button.go{background:var(--hit);color:#1b1309;border-color:var(--hit);font-weight:700}
button.stop{background:var(--warn);color:#fff;border-color:var(--warn)}
button:disabled{opacity:.4}
#stat{color:var(--dim);font-size:12px;margin-top:6px}
#bar{height:3px;background:var(--line);margin-top:8px;border-radius:2px;overflow:hidden}
#bar div{height:100%;width:0;background:var(--hit);transition:width .3s}
.sw{display:flex;align-items:center;gap:5px;font-size:11px;color:var(--dim);margin-top:8px}
.sw input{width:auto}
main{padding:10px 12px 40px}
a{color:var(--hit);text-decoration:none}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;
padding:11px 12px;margin:0 0 8px}
.card.good{border-color:#5d7350}
.chead{display:flex;gap:10px;align-items:flex-start}
.ttl{flex:1;font-size:15px;font-weight:700;line-height:1.45;word-break:break-word}
.pf{flex:none;font-size:15px;font-weight:700;border-radius:12px;padding:2px 10px;white-space:nowrap}
.pf.p{color:#16110d;background:var(--ok)}
.pf.m{color:var(--dim);border:1px solid var(--line)}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-top:8px}
.cell{background:var(--chip);border:1px solid var(--line);border-radius:8px;padding:6px 8px}
.cell .k{font-size:10px;color:var(--dim)}
.cell .v{font-size:14px;font-weight:700}
.cell .s{font-size:10px;color:var(--dim)}
.cell.best{border-color:var(--ok)}
.meta{font-size:11px;color:var(--dim);margin-top:7px;line-height:1.7;word-break:break-word}
.err{color:var(--warn);font-size:11px;margin-top:5px}
details{margin-top:6px;font-size:12px}
summary{cursor:pointer;color:var(--dim)}
details li{color:var(--dim);margin:2px 0}
.kw{display:flex;gap:6px;margin-top:8px}
.kw input{padding:5px 8px;font-size:12px}
.kw button{padding:5px 10px;font-size:12px;white-space:nowrap}
.empty{color:var(--dim);text-align:center;padding:40px 14px;line-height:1.9}
#log{font-size:11px;color:var(--dim);white-space:pre-wrap;margin-top:6px;max-height:90px;overflow:auto}
dialog{background:var(--panel);color:var(--ink);border:1px solid var(--line);
border-radius:10px;padding:16px;width:min(480px,94vw)}
dialog::backdrop{background:#000a}
label{display:block;color:var(--dim);font-size:11px;margin:8px 0 3px}
@media(max-width:420px){.grid{grid-template-columns:1fr 1fr}}
</style></head><body>
<header>
<h1>BOOKOFF 利益リサーチ<a href="/">← 在庫チェッカー</a></h1>
<input id="src" placeholder="検索キーワード（例: ドラゴンクエスト PS4）か、ブックオフの一覧ページURL">
<div class="row" style="margin-top:6px">
<button class="go" id="bGo">リサーチ開始</button>
<button class="stop" id="bStop" disabled>中止</button>
<button id="bConf">設定</button>
<button id="bCsv">CSV</button>
</div>
<div id="bar"><div></div></div>
<div id="stat" class="mono">待機中</div>
<label class="sw"><input type="checkbox" id="onlyGood" checked>利益が出るものだけ表示</label>
<div id="log"></div>
</header>
<main id="view"></main>

<dialog id="dConf">
<div style="font-weight:600">設定</div>
<div id="confBox"></div>
<div class="row" style="justify-content:flex-end;margin-top:12px">
<button onclick="dConf.close()">閉じる</button><button class="go" id="confSave">保存</button></div>
</dialog>

<script>
const $=s=>document.querySelector(s);
const esc=s=>String(s==null?'':s).replace(/[&<>"']/g,
  c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const yen=v=>v==null?'—':'¥'+Number(v).toLocaleString();
const post=(u,b)=>fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify(b||{})});
const LABEL={pages:'一覧を巡回するページ数',max_cost:'仕入れ上限（円）',
  min_profit:'利益ありとする最低利益（円）',min_sold:'相場に必要な最低売却件数',
  shipping:'送料（円）',mercari_fee:'メルカリ手数料（%）',yahoo_fee:'ヤフオク手数料（%）',
  sold_limit:'相場に使う直近件数'};
let CONF={}, ITEMS=[], timer=null;

try{$('#src').value=localStorage.getItem('pf_src')||'';
  $('#onlyGood').checked=localStorage.getItem('pf_good')!=='0';}catch(e){}

function mk(name,m,best,calc){
  if(!m) return `<div class="cell"><div class="k">${name}</div><div class="v">—</div>
    <div class="s">取得できず</div></div>`;
  const c=calc.by[best?.key];
  return `<div class="cell${best?' best':''}"><div class="k">${name} 中央値</div>
    <div class="v mono">${yen(m.median)}</div>
    <div class="s mono">${m.n}件 / ${yen(m.min)}〜${yen(m.max)}</div></div>`;
}

function card(it){
  const c=it.calc, good=c.profit!=null&&c.profit>=CONF.min_profit;
  const pf=c.profit==null?'<span class="pf m">相場不足</span>'
    :`<span class="pf ${good?'p':'m'} mono">${c.profit>=0?'+':''}${yen(c.profit).replace('¥-','-¥')}</span>`;
  const sold=(m,l)=>m&&m.items.length?`<details><summary>${l}の売れた商品（${m.n}件）</summary><ul>`
    +m.items.map(i=>`<li><a href="${esc(i.url)}" target="_blank">${yen(i.price)}</a> ${esc(i.date)} ${esc(i.title)}</li>`).join('')
    +'</ul></details>':'';
  return `<div class="card${good?' good':''}">
<div class="chead"><div class="ttl"><a href="${esc(it.url)}" target="_blank" style="color:var(--ink)">${esc(it.name)}</a></div>${pf}</div>
<div class="grid">
<div class="cell"><div class="k">ブックオフ</div><div class="v mono">${yen(it.price)}</div>
<div class="s">在庫 ${it.shops.length}店舗</div></div>
${mk('メルカリ',it.mercari,c.best==='mercari'?{key:'mercari'}:null,c)}
${mk('ヤフオク',it.yahoo,c.best==='yahoo'?{key:'yahoo'}:null,c)}
</div>
<div class="meta">${c.best?`売り先: ${c.best==='mercari'?'メルカリ':'ヤフオク'} / 手取り ${yen(c.by[c.best].net)} / 利益率 ${c.roi??'—'}%<br>`:''}
${it.jan?'JAN '+esc(it.jan)+' / ':''}<a href="${esc(it.mercari_url)}" target="_blank">メルカリで確認</a> ・
<a href="${esc(it.yahoo_url)}" target="_blank">ヤフオクで確認</a></div>
${(it.errors||[]).map(e=>`<div class="err">${esc(e)}</div>`).join('')}
<details><summary>在庫のある店舗（${it.shops.length}）</summary><ul>
${it.shops.map(s=>`<li><a href="${esc(s.url)}" target="_blank">${esc(s.name)}</a></li>`).join('')}</ul></details>
${sold(it.mercari,'メルカリ')}${sold(it.yahoo,'ヤフオク')}
<div class="kw"><input value="${esc(it.keyword)}" data-pid="${esc(it.pid)}">
<button data-rq="${esc(it.pid)}">この語で相場を取り直す</button></div>
</div>`;
}

function render(){
  const only=$('#onlyGood').checked;
  const list=ITEMS.filter(it=>!only||(it.calc.profit!=null&&it.calc.profit>=CONF.min_profit))
    .sort((a,b)=>(b.calc.profit??-1e9)-(a.calc.profit??-1e9));
  // 入力中のキーワード欄を消さないよう、フォーカス中は描き直さない
  if(document.activeElement&&document.activeElement.dataset.pid) return;
  $('#view').innerHTML=list.length?list.map(card).join('')
    :`<div class="empty">${ITEMS.length?'条件に合う商品はまだありません。<br>「利益が出るものだけ表示」を外すと全件見られます。'
      :'キーワードかブックオフの一覧URLを入れて「リサーチ開始」。<br>店舗在庫のある商品だけを、メルカリ・ヤフオクの相場と比べます。'}</div>`;
}

async function poll(){
  const s=await fetch('/api/profit/state').then(r=>r.json());
  ITEMS=s.items;
  const good=ITEMS.filter(it=>it.calc.profit!=null&&it.calc.profit>=CONF.min_profit).length;
  $('#stat').textContent=`${s.phase}　|　一覧 ${s.listed}件・店舗在庫なし ${s.no_stock}・`
    +`価格上限超え ${s.skipped}・相場確認 ${ITEMS.length}・利益あり ${good}`;
  $('#bar div').style.width=(s.total?s.done/s.total*100:0)+'%';
  $('#log').textContent=s.log.slice(-6).join('\n');
  $('#bGo').disabled=s.running; $('#bStop').disabled=!s.running;
  render();
  clearTimeout(timer);
  if(s.running) timer=setTimeout(poll,1500);
}

$('#bGo').onclick=async()=>{
  const v=$('#src').value.trim();
  try{localStorage.setItem('pf_src',v);}catch(e){}
  const r=await post('/api/profit/start',{source:v}).then(r=>r.json());
  if(!r.ok) alert(r.msg);
  poll();
};
$('#bStop').onclick=()=>post('/api/profit/stop').then(poll);
$('#bCsv').onclick=()=>{location.href='/api/profit/csv';};
$('#onlyGood').onchange=()=>{try{localStorage.setItem('pf_good',$('#onlyGood').checked?'1':'0');}catch(e){} render();};
$('#bConf').onclick=()=>{
  $('#confBox').innerHTML=Object.keys(LABEL).map(k=>
    `<label>${LABEL[k]}</label><input type="number" step="any" id="c_${k}" value="${CONF[k]}">`).join('');
  dConf.showModal();
};
$('#confSave').onclick=async()=>{
  const o={}; Object.keys(LABEL).forEach(k=>o[k]=$('#c_'+k).value);
  CONF=await post('/api/profit/config',o).then(r=>r.json());
  dConf.close(); poll();
};
$('#view').addEventListener('click',async e=>{
  const pid=e.target.dataset.rq; if(!pid) return;
  const kw=e.target.parentNode.querySelector('input').value;
  e.target.disabled=true; e.target.textContent='取得中...';
  e.target.parentNode.querySelector('input').blur();
  await post('/api/profit/requery',{pid,keyword:kw});
  poll();
});
fetch('/api/profit/config').then(r=>r.json()).then(c=>{CONF=c; poll();});
</script></body></html>
"""
