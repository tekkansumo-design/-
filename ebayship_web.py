#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
eBay 発送管理 PC 版

eBay の Awaiting shipment を取ってきて、国際郵便マイページで送り状を作り、
二次元コードのメールを拾い、発送後は eBay に追跡番号を戻すまでを一本にする。
Android 版（ebayship/）と同じ流れを、打ち込みが楽な PC でやるためのもの。

使い方:
  pip install flask requests
  python ebayship_web.py
  → ブラウザで http://localhost:5001 を開く

国際郵便マイページの自動入力まで任せたいときは、追加で:
  pip install playwright
  playwright install chromium

入れなくても動く。その場合はマイページを既定のブラウザで開き、
入れる値を画面から選んでコピーできるようにする。
"""

import base64
import email
import email.header
import email.utils
import html as html_mod
import imaplib
import json
import queue
import re
import threading
import webbrowser
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

import requests
from flask import Flask, Response, jsonify, request

# ═══════════════════════════════════════════════ 置き場所
BASE_DIR = Path(__file__).resolve().parent
CONF_FILE = BASE_DIR / "ebayship_config.json"
ORDERS_FILE = BASE_DIR / "ebayship_orders.json"
PROFILE_FILE = BASE_DIR / "ebayship_jp_profile.json"
# マイページのログイン状態を残しておく場所（Playwright を使うときだけ）
BROWSER_DIR = BASE_DIR / "ebayship_browser"
# 入力欄を当てる JS。Android 版と同じものを読む
JS_FILE = BASE_DIR / "ebayship" / "app" / "src" / "main" / "assets" / "jp_fill.js"

PORT = 5001

SCOPE = "https://api.ebay.com/oauth/api_scope/sell.fulfillment"

DEFAULTS = {
    "ebayClientId": "",
    "ebayClientSecret": "",
    "ebayRuName": "",
    "ebayRefreshToken": "",
    "ebayAccessToken": "",
    "ebayAccessExpiresAt": 0,
    "carrierCode": "JapanPost",

    "jpLoginId": "",
    "jpPassword": "",
    "jpStartUrl": "https://www.int-mypage.post.japanpost.jp/smart/MSC1000",

    "mailHost": "imap.gmail.com",
    "mailPort": 993,
    "mailUser": "",
    "mailPassword": "",
    "mailFolder": "INBOX",
    "mailSubject": "二次元コード",

    "fromName": "",
    "fromPostal": "",
    "fromAddress": "",
    "fromPhone": "",

    "defHsCode": "",
    "defOrigin": "JP",
    "defWeight": "",
}

SECRET_KEYS = {"ebayClientSecret", "jpPassword", "mailPassword"}
MASK = "****"

# 項目名 -> 画面での呼び名。Android 版の FIELD_LABELS と同じ並び
FIELD_LABELS = [
    ("toName", "お届け先 氏名"),
    ("toPostal", "お届け先 郵便番号"),
    ("toAddress", "お届け先 住所"),
    ("toCity", "お届け先 都市"),
    ("toState", "お届け先 州・県"),
    ("toCountry", "お届け先 国"),
    ("toPhone", "お届け先 電話番号"),
    ("toEmail", "お届け先 メール"),
    ("fromName", "ご依頼主 氏名"),
    ("fromPostal", "ご依頼主 郵便番号"),
    ("fromAddress", "ご依頼主 住所"),
    ("fromPhone", "ご依頼主 電話番号"),
    ("content", "内容品の品名"),
    ("quantity", "内容品の個数"),
    ("weight", "内容品の重量"),
    ("value", "内容品の価格"),
    ("hsCode", "HS コード"),
    ("origin", "原産国"),
]

# eBay は国を 2 文字コードで返すが、マイページのあて先国は日本語名の
# プルダウン。よく出る国だけ持っておき、無ければコードのまま渡す。
COUNTRY_JA = {
    "US": "アメリカ合衆国", "CA": "カナダ", "GB": "英国", "AU": "オーストラリア",
    "NZ": "ニュージーランド", "DE": "ドイツ", "FR": "フランス", "IT": "イタリア",
    "ES": "スペイン", "NL": "オランダ", "BE": "ベルギー", "CH": "スイス",
    "AT": "オーストリア", "SE": "スウェーデン", "NO": "ノルウェー", "DK": "デンマーク",
    "FI": "フィンランド", "IE": "アイルランド", "PT": "ポルトガル", "PL": "ポーランド",
    "CZ": "チェコ", "GR": "ギリシャ", "HU": "ハンガリー", "RO": "ルーマニア",
    "SG": "シンガポール", "HK": "香港", "TW": "台湾", "KR": "韓国", "CN": "中国",
    "TH": "タイ", "MY": "マレーシア", "PH": "フィリピン", "ID": "インドネシア",
    "VN": "ベトナム", "IN": "インド", "AE": "アラブ首長国連邦", "IL": "イスラエル",
    "MX": "メキシコ", "BR": "ブラジル", "CL": "チリ", "AR": "アルゼンチン",
    "ZA": "南アフリカ共和国", "JP": "日本",
}


def country_candidates(code):
    """プルダウン用。日本語名とコードの両方を渡し、当たったほうを選ばせる。"""
    c = (code or "").upper()
    name = COUNTRY_JA.get(c)
    return f"{name}||{c}" if name else c


# ═══════════════════════════════════════════════ 保存

_lock = threading.Lock()


def _read_json(path, fallback):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return fallback


def _write_json(path, data):
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def load_conf():
    conf = dict(DEFAULTS)
    conf.update(_read_json(CONF_FILE, {}))
    return conf


def save_conf(patch):
    """MASK のまま返ってきた項目は既存値を保つ。"""
    with _lock:
        conf = load_conf()
        for k, v in patch.items():
            if k not in DEFAULTS and k not in ("ebayRefreshToken", "ebayAccessToken",
                                               "ebayAccessExpiresAt"):
                continue
            if isinstance(v, str) and v == MASK:
                continue
            conf[k] = v
        _write_json(CONF_FILE, conf)
        return conf


def conf_for_ui():
    conf = load_conf()
    out = {k: v for k, v in conf.items()}
    for k in SECRET_KEYS:
        if out.get(k):
            out[k] = MASK
    out["ebayLinked"] = bool(conf.get("ebayRefreshToken"))
    for k in ("ebayRefreshToken", "ebayAccessToken", "ebayAccessExpiresAt"):
        out.pop(k, None)
    return out


def load_orders():
    return _read_json(ORDERS_FILE, {})


def save_orders(orders):
    _write_json(ORDERS_FILE, orders)


def order_list():
    """注文日の新しい順。"""
    orders = load_orders()
    return sorted(orders.values(),
                  key=lambda o: o.get("creationDate", ""), reverse=True)


def patch_order(order_id, patch):
    with _lock:
        orders = load_orders()
        o = orders.get(order_id)
        if o is None:
            return None
        o.update(patch)
        orders[order_id] = o
        save_orders(orders)
        return o


def merge_orders(fetched):
    """
    取り直した一覧を保存する。入力欄の中身や二次元コードのリンクなど、
    こちらで足した情報は消さずに残す。
    """
    keep = ("form", "state", "submittedAt", "qrUrl", "qrReceivedAt",
            "trackingNo", "shippedAt", "note")
    with _lock:
        orders = load_orders()
        for f in fetched:
            oid = f["orderId"]
            old = orders.get(oid)
            if old:
                for k in keep:
                    if k in old:
                        f[k] = old[k]
            f.setdefault("state", "new")
            orders[oid] = f
        save_orders(orders)
    return order_list()


def load_profile():
    return _read_json(PROFILE_FILE, {})


def save_profile(page_key, field, selector):
    with _lock:
        p = load_profile()
        p.setdefault(page_key, {})[field] = selector
        _write_json(PROFILE_FILE, p)


# ═══════════════════════════════════════════════ eBay


class EbayError(Exception):
    pass


def is_sandbox(conf=None):
    """
    App ID は -SBX- か -PRD- を含んでいて、それ自体がどちらの鍵かを表している。
    設定と食い違っていると認可画面が "OAuth client was not found" を返すだけなので、
    鍵の側を正として扱う。
    """
    conf = conf or load_conf()
    cid = (conf.get("ebayClientId") or "").upper()
    if "-SBX-" in cid:
        return True
    if "-PRD-" in cid:
        return False
    return False


def api_base(conf=None):
    return ("https://api.sandbox.ebay.com" if is_sandbox(conf)
            else "https://api.ebay.com")


def auth_base(conf=None):
    return ("https://auth.sandbox.ebay.com" if is_sandbox(conf)
            else "https://auth.ebay.com")


def env_name(conf=None):
    return "サンドボックス" if is_sandbox(conf) else "本番"


def preflight():
    """連携を始める前に、明らかにおかしい設定を見つける。無ければ None。"""
    conf = load_conf()
    if not conf.get("ebayClientId"):
        return "App ID (Client ID) が未入力です"
    if not conf.get("ebayClientSecret"):
        return "Cert ID (Client Secret) が未入力です"
    if not conf.get("ebayRuName"):
        return "RuName が未入力です"

    ru = conf["ebayRuName"].upper()
    if "-SBX-" in ru or "-PRD-" in ru:
        return ("RuName の欄に App ID が入っているようです。RuName は SBX / PRD を"
                "含まない別の文字列で、Application Keys の User Tokens から確認できます。")

    cid = conf["ebayClientId"].upper()
    secret = conf["ebayClientSecret"].upper()
    id_sbx, sec_sbx = "-SBX-" in cid, secret.startswith("SBX-")
    id_known = "-SBX-" in cid or "-PRD-" in cid
    sec_known = secret.startswith("SBX-") or secret.startswith("PRD-")
    if id_known and sec_known and id_sbx != sec_sbx:
        return ("App ID と Cert ID の環境が揃っていません。"
                f"App ID は{'サンドボックス' if id_sbx else '本番'}、"
                f"Cert ID は{'サンドボックス' if sec_sbx else '本番'}のものです。")
    return None


def consent_url():
    conf = load_conf()
    return (
        f"{auth_base(conf)}/oauth2/authorize"
        f"?client_id={quote(conf['ebayClientId'], safe='')}"
        f"&response_type=code"
        f"&redirect_uri={quote(conf['ebayRuName'], safe='')}"
        f"&scope={quote(SCOPE, safe='')}"
        f"&prompt=login"
    )


def _basic(conf):
    raw = f"{conf['ebayClientId']}:{conf['ebayClientSecret']}".encode()
    return "Basic " + base64.b64encode(raw).decode()


def _post_token(data):
    conf = load_conf()
    r = requests.post(
        f"{api_base(conf)}/identity/v1/oauth2/token",
        headers={"Authorization": _basic(conf),
                 "Content-Type": "application/x-www-form-urlencoded"},
        data=data, timeout=30,
    )
    if r.status_code != 200:
        raise EbayError(f"認証に失敗しました ({r.status_code}) {r.text}")
    return r.json()


def extract_code(pasted):
    """
    同意のあと飛ばされた URL をまるごと貼ってもらう。
    URL でなく code の値だけ貼られても通す。
    """
    pasted = (pasted or "").strip()
    if not pasted:
        return ""
    if pasted.startswith("http"):
        q = parse_qs(urlparse(pasted).query)
        return (q.get("code") or [""])[0]
    if "code=" in pasted:
        return parse_qs(pasted.split("code=", 1)[1] and "code=" +
                        pasted.split("code=", 1)[1]).get("code", [""])[0]
    return pasted


def token_from_code(code):
    conf = load_conf()
    res = _post_token({
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": conf["ebayRuName"],
    })
    if not res.get("refresh_token"):
        raise EbayError("refresh token が返ってきませんでした")
    save_conf({
        "ebayRefreshToken": res["refresh_token"],
        "ebayAccessToken": res.get("access_token", ""),
        "ebayAccessExpiresAt": now_ms() + int(res.get("expires_in", 7200)) * 1000,
    })


def now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def access_token():
    """有効なアクセストークン。切れていれば refresh token で取り直す。"""
    conf = load_conf()
    cached = conf.get("ebayAccessToken")
    expires = int(conf.get("ebayAccessExpiresAt") or 0)
    # 期限ぎりぎりで使うと通信中に切れるので 2 分の余裕を見る
    if cached and expires - 120_000 > now_ms():
        return cached

    if not conf.get("ebayRefreshToken"):
        raise EbayError("eBay と未連携です。設定から連携してください")

    res = _post_token({
        "grant_type": "refresh_token",
        "refresh_token": conf["ebayRefreshToken"],
        "scope": SCOPE,
    })
    token = res.get("access_token")
    if not token:
        raise EbayError("アクセストークンを取得できませんでした")
    save_conf({
        "ebayAccessToken": token,
        "ebayAccessExpiresAt": now_ms() + int(res.get("expires_in", 7200)) * 1000,
    })
    return token


def simplify_order(o):
    """API の返事から画面と送り状で使うぶんだけ取り出す。"""
    items, title, qty = [], "", 0
    for it in o.get("lineItems") or []:
        if not title:
            title = it.get("title", "")
        qty += int(it.get("quantity") or 1)
        cost = it.get("lineItemCost") or {}
        items.append({
            "lineItemId": it.get("lineItemId", ""),
            "title": it.get("title", ""),
            "quantity": int(it.get("quantity") or 1),
            "sku": it.get("sku", ""),
            "price": cost.get("value", ""),
            "currency": cost.get("currency", ""),
        })
    if len(items) > 1:
        title = f"{title} ほか{len(items) - 1}点"

    step = ((o.get("fulfillmentStartInstructions") or [{}])[0]
            .get("shippingStep") or {})
    to = step.get("shipTo") or {}
    addr = to.get("contactAddress") or {}
    total = (o.get("pricingSummary") or {}).get("total") or {}

    return {
        "orderId": o.get("orderId", ""),
        "legacyOrderId": o.get("legacyOrderId", ""),
        "creationDate": o.get("creationDate", ""),
        "fulfillmentStatus": o.get("orderFulfillmentStatus", ""),
        "buyer": (o.get("buyer") or {}).get("username", ""),
        "title": title,
        "quantity": qty,
        "lineItems": items,
        "address": {
            "name": to.get("fullName", ""),
            "line1": addr.get("addressLine1", ""),
            "line2": addr.get("addressLine2", ""),
            "city": addr.get("city", ""),
            "state": addr.get("stateOrProvince", ""),
            "postal": addr.get("postalCode", ""),
            "country": addr.get("countryCode", ""),
            "phone": (to.get("primaryPhone") or {}).get("phoneNumber", ""),
            "email": to.get("email", ""),
        },
        "totalValue": total.get("value", ""),
        "totalCurrency": total.get("currency", ""),
    }


def fetch_awaiting_shipment():
    """
    発送待ちの注文。eBay の Awaiting shipment は
    orderfulfillmentstatus が NOT_STARTED か IN_PROGRESS のもの。
    """
    token = access_token()
    conf = load_conf()
    flt = "orderfulfillmentstatus:%7BNOT_STARTED%7CIN_PROGRESS%7D"
    out, offset = [], 0
    while True:
        url = (f"{api_base(conf)}/sell/fulfillment/v1/order"
               f"?limit=50&offset={offset}&filter={flt}")
        r = requests.get(url, headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "X-EBAY-C-MARKETPLACE-ID": "EBAY_US",
        }, timeout=60)
        if r.status_code != 200:
            raise EbayError(f"注文の取得に失敗しました ({r.status_code}) {r.text}")
        body = r.json()
        orders = body.get("orders") or []
        out.extend(simplify_order(o) for o in orders)
        offset += len(orders)
        if not orders or offset >= int(body.get("total") or len(out)):
            break
    return out


def create_fulfillment(order, tracking_no, carrier):
    """追跡番号を eBay に登録する（Awaiting shipment から外れる）。"""
    token = access_token()
    conf = load_conf()
    payload = {
        "lineItems": [{"lineItemId": it["lineItemId"], "quantity": it["quantity"]}
                      for it in order.get("lineItems") or []],
        "shippedDate": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        "shippingCarrierCode": carrier,
        "trackingNumber": tracking_no,
    }
    r = requests.post(
        f"{api_base(conf)}/sell/fulfillment/v1/order/{order['orderId']}"
        f"/shipping_fulfillment",
        headers={"Authorization": f"Bearer {token}",
                 "Content-Type": "application/json",
                 "Accept": "application/json"},
        json=payload, timeout=60,
    )
    if r.status_code not in (200, 201):
        raise EbayError(f"発送登録に失敗しました ({r.status_code}) {r.text}")
    return (r.headers.get("Location") or "").rsplit("/", 1)[-1]


# ═══════════════════════════════════════════════ 二次元コードのメール

RE_URL = re.compile(r"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+")
RE_TRACK = re.compile(r"\b[A-Z]{2}\d{9}JP\b")
RE_PRINT = re.compile(r"印刷用番号[^0-9A-Za-z]{0,8}([0-9A-Za-z\-]{6,})")
RE_MARK = re.compile(r"[▼▽■・]?\s*二次元コード(の)?表示")

SCAN = 40


def strip_html(text):
    text = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", text)
    text = re.sub(r"(?i)<br\s*/?>", "\n", text)
    text = re.sub(r"(?i)</p>", "\n", text)
    # href の中身は本文に出てこないことがあるので拾っておく
    text = re.sub(r"""(?i)<a[^>]+href=["']([^"']+)["'][^>]*>""",
                  lambda m: " " + m.group(1) + " ", text)
    text = re.sub(r"<[^>]+>", " ", text)
    for a, b in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                 ("&quot;", '"'), ("&#39;", "'"), ("&nbsp;", " ")):
        text = text.replace(a, b)
    return text


def _decode(part):
    payload = part.get_payload(decode=True)
    if payload is None:
        return ""
    charset = part.get_content_charset() or "utf-8"
    try:
        return payload.decode(charset, errors="replace")
    except LookupError:
        return payload.decode("utf-8", errors="replace")


def message_text(msg):
    """マルチパートでも HTML でも、とにかく文字にする。"""
    if not msg.is_multipart():
        text = _decode(msg)
        return strip_html(text) if msg.get_content_type() == "text/html" else text
    plain, htm = [], []
    for part in msg.walk():
        if part.is_multipart():
            continue
        ctype = part.get_content_type()
        if ctype == "text/plain":
            plain.append(_decode(part))
        elif ctype == "text/html":
            htm.append(strip_html(_decode(part)))
    return "\n".join(plain) if any(p.strip() for p in plain) else "\n".join(htm)


def decode_subject(raw):
    if not raw:
        return ""
    out = []
    for text, enc in email.header.decode_header(raw):
        if isinstance(text, bytes):
            out.append(text.decode(enc or "utf-8", errors="replace"))
        else:
            out.append(text)
    return "".join(out)


def trim_url(u):
    return u.rstrip(".,)>」、。")


def parse_mail(subject, received_at, body_raw):
    """本文から必要なものを抜く。"""
    body = (body_raw or "").replace("\r\n", "\n")
    urls = [trim_url(m.group(0)) for m in RE_URL.finditer(body)]

    qr = ""
    mark = RE_MARK.search(body)
    if mark:
        after = body[mark.end():]
        m = RE_URL.search(after)
        if m:
            qr = trim_url(m.group(0))
    if not qr:
        jp = [u for u in urls if "post.japanpost.jp" in u]
        qr = next((u for u in jp if "?" in u), None) or (jp[0] if jp else "") \
            or (urls[0] if urls else "")

    track = RE_TRACK.search(body)
    printno = RE_PRINT.search(body)
    return {
        "subject": subject,
        "receivedAt": received_at,
        "qrUrl": qr,
        "trackingNo": track.group(0) if track else "",
        "printNo": printno.group(1) if printno else "",
        "snippet": body.replace("\n", " ").strip()[:160],
        "urls": list(dict.fromkeys(urls)),
    }


def fetch_mails(since_ms):
    """
    直近のメールから二次元コードの通知だけ拾う。新しい順。
    件名は日本語なのでサーバ側の検索に頼らず、こちらで絞る。
    """
    conf = load_conf()
    if not (conf["mailHost"] and conf["mailUser"] and conf["mailPassword"]):
        raise EbayError("メール設定（IMAP）が未入力です")

    keyword = conf.get("mailSubject") or "二次元コード"
    out = []
    box = imaplib.IMAP4_SSL(conf["mailHost"], int(conf["mailPort"] or 993))
    try:
        box.login(conf["mailUser"], conf["mailPassword"])
        box.select(conf.get("mailFolder") or "INBOX", readonly=True)
        typ, data = box.search(None, "ALL")
        if typ != "OK":
            return []
        ids = data[0].split()[-SCAN:]
        for num in reversed(ids):
            typ, raw = box.fetch(num, "(RFC822)")
            if typ != "OK" or not raw or not raw[0]:
                continue
            msg = email.message_from_bytes(raw[0][1])
            subject = decode_subject(msg.get("Subject"))
            if keyword not in subject:
                continue
            received = 0
            try:
                dt = email.utils.parsedate_to_datetime(msg.get("Date"))
                if dt:
                    received = int(dt.timestamp() * 1000)
            except Exception:
                pass
            if since_ms and received and received < since_ms:
                continue
            out.append(parse_mail(subject, received, message_text(msg)))
    finally:
        try:
            box.logout()
        except Exception:
            pass
    out.sort(key=lambda m: m.get("receivedAt", 0), reverse=True)
    return out


def attach_qr_mails(order_id=""):
    """
    登録の早い注文から、届いた順にメールを当てていく。
    戻り値は結び付けられた件数。
    """
    waiting = [o for o in order_list()
               if o.get("state") == "submitted" and not o.get("qrUrl")
               and (not order_id or o["orderId"] == order_id)]
    waiting.sort(key=lambda o: o.get("submittedAt") or 0)
    if not waiting:
        raise EbayError("二次元コード待ちの注文がありません")

    earliest = min((o.get("submittedAt") or 0) for o in waiting)
    since = earliest - 10 * 60_000 if earliest else 0

    used = {o["qrUrl"] for o in order_list() if o.get("qrUrl")}
    fresh = [m for m in fetch_mails(since)
             if m["qrUrl"] and m["qrUrl"] not in used]
    fresh.sort(key=lambda m: m.get("receivedAt", 0))

    mi = n = 0
    for o in waiting:
        assigned = False
        while mi < len(fresh):
            m = fresh[mi]
            rec, sub = m.get("receivedAt", 0), o.get("submittedAt") or 0
            # 登録より前に届いたメールはこの注文のものではない
            if rec and sub and rec < sub - 10 * 60_000:
                mi += 1
                continue
            patch = {"state": "qr", "qrUrl": m["qrUrl"], "qrReceivedAt": rec}
            if m["trackingNo"]:
                patch["trackingNo"] = m["trackingNo"]
            patch_order(o["orderId"], patch)
            mi += 1
            n += 1
            assigned = True
            break
        if not assigned:
            break
    return n


# ═══════════════════════════════════════════════ eBay の管理画面から読み取る

# 鍵は形が決まっているので、画面のどこにあっても文字の形で見分けられる。
# 相手の DOM に頼ると作りが変わるたびに壊れるので、文字だけを見る。
RE_APP_ID = re.compile(r"\b[A-Za-z0-9]+-[A-Za-z0-9]+-(?:SBX|PRD)-[0-9a-f]{6,}-[0-9a-f]{6,}\b")
RE_CERT_ID = re.compile(
    r"\b(?:SBX|PRD)-[0-9a-f]{8,}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4,}\b")
RE_DEV_ID = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
# RuName は Bob_Smith-BobSmit-Test-abcdefg のような形。下線を含むのが目印。
# 区切りの長さは決まっていない（2 文字のこともある）ので幅を持たせる。
RE_RUNAME = re.compile(
    r"\b[A-Za-z0-9]+_[A-Za-z0-9]+(?:-[A-Za-z0-9]{1,16}){2,4}\b")

EBAY_KEYS_URL = "https://developer.ebay.com/my/keys"


def scrape_keys(text):
    """
    developer.ebay.com の画面から拾える文字を見て、鍵らしきものを取り出す。
    見つからなかったものは入れない。
    """
    found = {}
    m = RE_APP_ID.search(text)
    if m:
        found["ebayClientId"] = m.group(0)
    m = RE_CERT_ID.search(text)
    if m:
        found["ebayClientSecret"] = m.group(0)
    for m in RE_RUNAME.finditer(text):
        # App ID も似た形なので、そちらに当たるものは除く
        if not RE_APP_ID.match(m.group(0)):
            found["ebayRuName"] = m.group(0)
            break
    return found


def page_all_text(page):
    """本文の文字と、入力欄に入っている値の両方を集める。"""
    parts = []
    try:
        parts.append(page.inner_text("body"))
    except Exception:
        pass
    try:
        parts.extend(page.eval_on_selector_all(
            "input,textarea",
            "els => els.map(e => (e.value || '') + ' ' + (e.placeholder || ''))"))
    except Exception:
        pass
    return "\n".join(p for p in parts if p)


# ═══════════════════════════════════════════════ 国際郵便マイページ


def shipment_data(order):
    """注文と設定から、送り状に入れる値を組み立てる。"""
    conf = load_conf()
    a = order.get("address") or {}
    f = order.get("form") or {}
    line = " ".join(x for x in (a.get("line1"), a.get("line2")) if x)
    return {
        "toName": a.get("name", ""),
        "toPostal": a.get("postal", ""),
        "toAddress": line,
        "toCity": a.get("city", ""),
        "toState": a.get("state", ""),
        "toCountry": country_candidates(a.get("country", "")),
        "toPhone": a.get("phone", ""),
        "toEmail": a.get("email", ""),
        "fromName": conf.get("fromName", ""),
        "fromPostal": conf.get("fromPostal", ""),
        "fromAddress": conf.get("fromAddress", ""),
        "fromPhone": conf.get("fromPhone", ""),
        "content": f.get("content", ""),
        "quantity": f.get("quantity", ""),
        "weight": f.get("weight", ""),
        "value": f.get("value", ""),
        "hsCode": f.get("hsCode", ""),
        "origin": country_candidates(f.get("origin") or "JP"),
    }


def load_fill_js():
    """
    入力欄を当てる JS。Android 版と同じファイルを読む。
    リポジトリごと置いていないと見つからないので、そのときははっきり言う。
    """
    try:
        return JS_FILE.read_text(encoding="utf-8")
    except Exception as e:
        raise EbayError(
            f"{JS_FILE} が読めません（{e}）。"
            "このファイルはリポジトリの ebayship/ と同じ場所に置いてください。"
        )


def page_key(url):
    """画面ごとに覚え書きを分けるためのキー。クエリは無視してパスだけ使う。"""
    try:
        u = urlparse(url)
        return (u.netloc or "") + (u.path or "")
    except Exception:
        return url or "?"


class Browser:
    """
    Playwright があればマイページを開いて自動入力する。
    無ければ何もしない（呼び出し側が既定のブラウザに逃がす）。

    画面は見せたまま入力だけ肩代わりする。相手のサイトの作りが変わっても
    手で続けられるし、押すと戻せない「登録」までは踏み込まない。
    """

    def __init__(self):
        self._pw = None
        self._ctx = None
        self._page = None
        self._lock = threading.Lock()

    @staticmethod
    def available():
        try:
            import playwright.sync_api  # noqa: F401
            return True
        except Exception:
            return False

    def open(self):
        """開いていなければ開く。ログイン状態はプロファイルに残る。"""
        from playwright.sync_api import sync_playwright
        if self._page and not self._page.is_closed():
            return self._page
        BROWSER_DIR.mkdir(exist_ok=True)
        self._pw = sync_playwright().start()
        self._ctx = self._pw.chromium.launch_persistent_context(
            str(BROWSER_DIR), headless=False, viewport={"width": 1180, "height": 900}
        )
        self._page = self._ctx.pages[0] if self._ctx.pages else self._ctx.new_page()
        return self._page

    def close(self):
        for closer in (getattr(self._ctx, "close", None),
                       getattr(self._pw, "stop", None)):
            try:
                if closer:
                    closer()
            except Exception:
                pass
        self._pw = self._ctx = self._page = None

    def goto(self, url):
        page = self.open()
        page.goto(url, wait_until="domcontentloaded")
        return page

    def fill(self, data, do_login=False):
        """
        入れた結果を返す。当たった欄は次回のために控えておく。
        戻り値は {"filled": [...], "missing": [...], "url": ...}
        """
        with self._lock:
            page = self.open()
            page.evaluate(load_fill_js())
            key = page_key(page.url)
            profile = load_profile().get(key, {})

            if do_login:
                conf = load_conf()
                if conf.get("jpLoginId") and conf.get("jpPassword"):
                    data = dict(data)
                    data["loginId"] = conf["jpLoginId"]
                    data["loginPw"] = conf["jpPassword"]

            raw = page.evaluate(
                "([d, p]) => window.__jp.fill(d, p)", [data, profile]
            )
            report = json.loads(raw)
            for f in report.get("filled", []):
                if not f.get("learned") and f.get("selector"):
                    save_profile(key, f["field"], f["selector"])
            report["pageKey"] = key
            return report

    def read_text(self):
        """いま開いている画面の文字を集める。鍵を読み取るのに使う。"""
        with self._lock:
            return page_all_text(self.open())

    def describe(self):
        with self._lock:
            page = self.open()
            page.evaluate(load_fill_js())
            return json.loads(page.evaluate("() => window.__jp.describe()"))

    def press_next(self, include_submit):
        """
        「次へ」にあたるボタンを文言で探して押す。
        押すと取り消せない「登録」「送信」は、頼まれたときだけ触る。
        """
        words = (["次へ", "確認", "内容確認", "進む", "登録", "送信", "この内容で"]
                 if include_submit else ["次へ", "内容確認", "確認", "進む"])
        with self._lock:
            page = self.open()
            page.evaluate(load_fill_js())
            return page.evaluate("(w) => window.__jp.press(w)", words)


BROWSER = Browser()


class BrowserThread(threading.Thread):
    """
    Playwright の同期 API は作ったスレッドからしか触れない。
    Flask は要求ごとに別スレッドで走るので、ブラウザの操作は
    このスレッド 1 本に集めて順番に流す。
    """

    def __init__(self):
        super().__init__(daemon=True)
        self.jobs = queue.Queue()

    def run(self):
        while True:
            fn, box = self.jobs.get()
            try:
                box["result"] = fn()
            except Exception as exc:      # ブラウザ側の失敗で止まらせない
                box["error"] = exc
            finally:
                box["done"].set()

    def call(self, fn, timeout=300):
        box = {"done": threading.Event()}
        self.jobs.put((fn, box))
        if not box["done"].wait(timeout):
            raise EbayError("ブラウザの操作が終わりませんでした")
        if "error" in box:
            raise box["error"]
        return box.get("result")


BROWSER_THREAD = BrowserThread()
BROWSER_THREAD.start()


# ═══════════════════════════════════════════════ 設定のまとめ取り込み

ALIASES = {
    "ebayclientid": "ebayClientId", "appid": "ebayClientId",
    "clientid": "ebayClientId", "applicationid": "ebayClientId",
    "ebayclientsecret": "ebayClientSecret", "certid": "ebayClientSecret",
    "clientsecret": "ebayClientSecret",
    "ebayruname": "ebayRuName", "runame": "ebayRuName",
    "redirecturi": "ebayRuName", "redirecturl": "ebayRuName",
    "carriercode": "carrierCode", "carrier": "carrierCode",
    "jploginid": "jpLoginId", "jpid": "jpLoginId", "jplogin": "jpLoginId",
    "jppassword": "jpPassword", "jppw": "jpPassword",
    "jpstarturl": "jpStartUrl", "jpurl": "jpStartUrl",
    "mailhost": "mailHost", "imaphost": "mailHost",
    "mailport": "mailPort", "imapport": "mailPort",
    "mailuser": "mailUser", "imapuser": "mailUser", "mailaddress": "mailUser",
    "mailpassword": "mailPassword", "imappassword": "mailPassword",
    "apppassword": "mailPassword",
    "mailfolder": "mailFolder", "mailsubject": "mailSubject",
    "fromname": "fromName", "frompostal": "fromPostal", "fromzip": "fromPostal",
    "fromaddress": "fromAddress", "fromphone": "fromPhone", "fromtel": "fromPhone",
    "defhscode": "defHsCode", "hscode": "defHsCode",
    "deforigin": "defOrigin", "origin": "defOrigin",
    "defweight": "defWeight", "weight": "defWeight",
}


def alias(raw_key):
    """貼り付けた見出しを設定の項目名に読み替える。Dev ID などは捨てる。"""
    k = raw_key.strip().strip("\"'").lower()
    k = k.replace(" ", "").replace("_", "").replace("-", "")
    return ALIASES.get(k)


def import_text(text):
    """
    まとめて貼り付けた文字を設定として取り込む。
    JSON でも「App ID = xxxx」のような行の並びでも受け取る。
    """
    text = (text or "").strip()
    patch = {}

    if text.startswith("{"):
        try:
            for k, v in json.loads(text).items():
                key = alias(k)
                if key:
                    patch[key] = str(v)
        except Exception:
            pass

    if not patch:
        for raw in text.split("\n"):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            at = min((line.find(c) for c in "=:\t" if line.find(c) > 0),
                     default=-1)
            if at <= 0:
                continue
            key = alias(line[:at])
            if not key:
                continue
            value = line[at + 1:].strip().strip("\"',")
            if value:
                patch[key] = value

    if patch:
        save_conf(patch)
    return sorted(patch.keys())


# ═══════════════════════════════════════════════ Web

app = Flask(__name__)


def ok(**kw):
    out = {"ok": True}
    out.update(kw)
    return jsonify(out)


def ng(msg):
    return jsonify({"ok": False, "error": str(msg)})


def body():
    return request.get_json(silent=True) or {}


@app.route("/")
def index():
    return Response(PAGE, mimetype="text/html; charset=utf-8")


@app.route("/api/state")
def api_state():
    return ok(orders=order_list(), conf=conf_for_ui(),
              env=env_name(), browser=Browser.available(),
              fields=[{"key": k, "label": v} for k, v in FIELD_LABELS])


@app.route("/api/refresh", methods=["POST"])
def api_refresh():
    try:
        merged = merge_orders(fetch_awaiting_shipment())
        return ok(orders=merged, msg=f"{len(merged)}件")
    except Exception as e:
        return ng(e)


@app.route("/api/conf", methods=["POST"])
def api_conf():
    save_conf(body())
    return ok(conf=conf_for_ui(), env=env_name(), msg="設定を保存しました")


@app.route("/api/conf/import", methods=["POST"])
def api_conf_import():
    got = import_text(body().get("text", ""))
    if not got:
        return ng("読み取れる設定がありませんでした")
    return ok(conf=conf_for_ui(), env=env_name(),
              msg=f"{len(got)}項目を取り込みました")


@app.route("/api/ebay/open", methods=["POST"])
def api_ebay_open():
    """eBay の管理画面をブラウザで開く。ログインは本人がする。"""
    if not Browser.available():
        webbrowser.open(EBAY_KEYS_URL)
        return ok(manual=True,
                  msg="eBay の画面を開きました。読み取りには Playwright が必要です")
    try:
        BROWSER_THREAD.call(lambda: BROWSER.goto(EBAY_KEYS_URL))
        return ok(msg="eBay にログインし、鍵や RuName が見えている画面まで進んでから"
                      "「この画面から読み取る」を押してください")
    except Exception as e:
        webbrowser.open(EBAY_KEYS_URL)
        return ng(f"ブラウザを開けませんでした（{e}）")


@app.route("/api/ebay/grab", methods=["POST"])
def api_ebay_grab():
    """
    いま開いている eBay の画面から鍵と RuName を読み取って設定に入れる。
    相手の作りに合わせず、文字の形だけで見分ける。
    """
    if not Browser.available():
        return ng("Playwright が入っていないので読み取れません")
    try:
        text = BROWSER_THREAD.call(lambda: BROWSER.read_text())
    except Exception as e:
        return ng(e)

    found = scrape_keys(text)
    if not found:
        return ng("この画面からは見つかりませんでした。"
                  "Application Keys の画面か、User Tokens の画面を開いてから押してください"
                  "（Cert ID は伏せ字のことがあるので、表示してから押してください）")
    save_conf(found)
    labels = {"ebayClientId": "App ID", "ebayClientSecret": "Cert ID",
              "ebayRuName": "RuName"}
    got = "、".join(labels[k] for k in found)
    return ok(conf=conf_for_ui(), env=env_name(), found=list(found.keys()),
              msg=f"{got} を読み取りました")


@app.route("/api/consent")
def api_consent():
    bad = preflight()
    if bad:
        return ng(bad)
    return ok(url=consent_url(), env=env_name())


@app.route("/api/link", methods=["POST"])
def api_link():
    code = extract_code(body().get("pasted", ""))
    if not code:
        return ng("認可コードが見つかりません。飛ばされた先の URL をまるごと貼ってください")
    try:
        token_from_code(code)
        return ok(conf=conf_for_ui(), msg="eBay と連携しました")
    except Exception as e:
        return ng(e)


@app.route("/api/unlink", methods=["POST"])
def api_unlink():
    save_conf({"ebayRefreshToken": "", "ebayAccessToken": "",
               "ebayAccessExpiresAt": 0})
    return ok(conf=conf_for_ui(), msg="連携を解除しました")


@app.route("/api/order/<order_id>/form", methods=["POST"])
def api_form(order_id):
    if patch_order(order_id, {"form": body().get("form") or {}}) is None:
        return ng("注文が見つかりません")
    return ok()


@app.route("/api/order/<order_id>/submit", methods=["POST"])
def api_submit(order_id):
    """決定。入力を保存してマイページを開き、入るところまで入れる。"""
    order = patch_order(order_id, {"form": body().get("form") or {}})
    if order is None:
        return ng("注文が見つかりません")

    conf = load_conf()
    data = shipment_data(order)
    if not Browser.available():
        webbrowser.open(conf["jpStartUrl"])
        return ok(orders=order_list(), data=data, manual=True,
                  msg="マイページを開きました。下の値をコピーして入れてください")
    try:
        BROWSER_THREAD.call(lambda: BROWSER.goto(conf["jpStartUrl"]))
        report = BROWSER_THREAD.call(lambda: BROWSER.fill(data, do_login=True))
        return ok(orders=order_list(), data=data, report=report,
                  msg=fill_message(report))
    except Exception as e:
        webbrowser.open(conf["jpStartUrl"])
        return ok(orders=order_list(), data=data, manual=True,
                  msg=f"自動入力を使えませんでした（{e}）。手で入れてください")


def fill_message(report):
    labels = dict(FIELD_LABELS)
    filled = len(report.get("filled") or [])
    missing = [labels.get(f, f) for f in report.get("missing") or []]
    head = f"{filled}項目を入力しました"
    if not missing:
        return f"{head}。内容を確かめて次へ進んでください"
    return f"{head} / 入らなかった欄: " + "、".join(missing)


@app.route("/api/jp/fill", methods=["POST"])
def api_jp_fill():
    order = load_orders().get(body().get("orderId", ""))
    if order is None:
        return ng("注文が見つかりません")
    try:
        report = BROWSER_THREAD.call(
            lambda: BROWSER.fill(shipment_data(order), do_login=True))
        return ok(report=report, msg=fill_message(report))
    except Exception as e:
        return ng(e)


@app.route("/api/jp/next", methods=["POST"])
def api_jp_next():
    include = bool(body().get("includeSubmit"))
    try:
        hit = BROWSER_THREAD.call(lambda: BROWSER.press_next(include))
        return ok(msg="次の画面へ進みます" if hit else "押せるボタンが見つかりませんでした")
    except Exception as e:
        return ng(e)


@app.route("/api/jp/describe", methods=["POST"])
def api_jp_describe():
    try:
        return ok(**BROWSER_THREAD.call(lambda: BROWSER.describe()))
    except Exception as e:
        return ng(e)


@app.route("/api/jp/learn", methods=["POST"])
def api_jp_learn():
    d = body()
    if not (d.get("pageKey") and d.get("field") and d.get("selector")):
        return ng("項目と欄の指定が足りません")
    save_profile(d["pageKey"], d["field"], d["selector"])
    return ok(msg=f"{dict(FIELD_LABELS).get(d['field'], d['field'])} として覚えました")


@app.route("/api/order/<order_id>/done", methods=["POST"])
def api_done(order_id):
    if patch_order(order_id, {"state": "submitted",
                              "submittedAt": now_ms()}) is None:
        return ng("注文が見つかりません")
    return ok(orders=order_list(), msg="登録済みにしました。メールが届いたら確認を押してください")


@app.route("/api/mail/check", methods=["POST"])
def api_mail_check():
    try:
        n = attach_qr_mails(body().get("orderId", ""))
        return ok(orders=order_list(),
                  msg=(f"{n}件に二次元コードを結び付けました" if n
                       else "新しいメールはまだ届いていません"))
    except Exception as e:
        return ng(e)


@app.route("/api/order/<order_id>/ship", methods=["POST"])
def api_ship(order_id):
    tracking = (body().get("trackingNo") or "").strip()
    if not tracking:
        return ng("追跡番号（お問い合わせ番号）を入れてください")
    order = load_orders().get(order_id)
    if order is None:
        return ng("注文が見つかりません")
    try:
        carrier = load_conf().get("carrierCode") or "JapanPost"
        fid = create_fulfillment(order, tracking, carrier)
        patch_order(order_id, {"state": "shipped", "trackingNo": tracking,
                               "shippedAt": now_ms(), "fulfillmentId": fid})
        return ok(orders=order_list(), msg="発送済みにしました")
    except Exception as e:
        return ng(e)


@app.route("/api/orders/clear-shipped", methods=["POST"])
def api_clear_shipped():
    with _lock:
        orders = load_orders()
        gone = [k for k, v in orders.items() if v.get("state") == "shipped"]
        for k in gone:
            del orders[k]
        save_orders(orders)
    return ok(orders=order_list(), msg=f"{len(gone)}件を片付けました")


@app.route("/api/order/<order_id>/state", methods=["POST"])
def api_state_set(order_id):
    patch_order(order_id, {"state": body().get("state") or "new"})
    return ok(orders=order_list())


@app.route("/api/profile/clear", methods=["POST"])
def api_profile_clear():
    _write_json(PROFILE_FILE, {})
    return ok(msg="入力欄の記録を消しました")


def esc(s):
    return html_mod.escape("" if s is None else str(s))


# ═══════════════════════════════════════════════ 画面

PAGE = r"""<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<title>eBay 発送管理</title>
<style>
  :root{
    --bg:#16110d; --panel:#1d1710; --panel2:#241d15; --line:#2f2619;
    --text:#f0e6da; --dim:#a08d78; --accent:#e0a458; --accent2:#c8862f;
    --ok:#6fbf73; --bad:#e06c5f; --wait:#7aa7d8;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--text);
       font-family:-apple-system,"Hiragino Kaku Gothic ProN","Noto Sans JP",
                   "Yu Gothic UI",sans-serif;
       font-size:14px;line-height:1.6}
  header{position:sticky;top:0;z-index:20;background:var(--panel);
         border-bottom:1px solid var(--line);padding:12px 20px;
         display:flex;align-items:center;gap:10px}
  header h1{font-size:15px;margin:0;flex:1;color:var(--accent);font-weight:700}
  .env{font-size:11px;color:var(--dim)}
  button{font-family:inherit;font-size:13px;border-radius:8px;
         border:1px solid var(--line);background:var(--panel2);color:var(--text);
         padding:8px 14px;cursor:pointer}
  button:hover{border-color:var(--accent2)}
  .primary{background:var(--accent);color:#231703;border-color:var(--accent);font-weight:700}
  .ghost{background:transparent}
  .bar{padding:9px 20px;font-size:12px;color:var(--dim);background:var(--panel);
       border-bottom:1px solid var(--line);display:none;white-space:pre-wrap}
  .bar.on{display:block}
  .bar.bad{color:var(--bad)}

  .wrap{max-width:1000px;margin:0 auto;padding:16px 20px 60px}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:12px;
        padding:14px 16px;margin-bottom:10px}
  .head{display:flex;gap:12px;align-items:flex-start;cursor:pointer}
  .head > div{flex:1;min-width:0}
  .date{font-size:11px;color:var(--dim)}
  .title{font-size:14px;font-weight:700;margin-top:2px}
  .meta{font-size:11px;color:var(--dim);margin-top:3px}
  .badge{font-size:10px;padding:3px 9px;border-radius:999px;white-space:nowrap;
         border:1px solid var(--line);color:var(--dim)}
  .badge.new{color:var(--accent);border-color:var(--accent2)}
  .badge.submitted{color:var(--wait);border-color:#3a4a5e}
  .badge.qr{color:var(--ok);border-color:#3a5a3c}
  .row{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;align-items:center}

  input,select,textarea{background:var(--panel2);color:var(--text);width:100%;
        border:1px solid var(--line);border-radius:8px;padding:9px 12px;
        font-size:14px;font-family:inherit}
  textarea{resize:vertical}
  label{display:block;font-size:11px;color:var(--dim);margin:12px 0 4px}
  .hint{font-size:11px;color:var(--dim);margin-top:5px}
  .sect{font-size:12px;color:var(--accent);font-weight:700;margin:22px 0 2px;
        border-bottom:1px solid var(--line);padding-bottom:6px}
  .addr{background:var(--panel);border:1px solid var(--line);border-radius:10px;
        padding:12px 14px;font-size:13px;white-space:pre-wrap}
  .empty{text-align:center;color:var(--dim);padding:60px 20px}
  .cols{display:flex;gap:12px}
  .cols > div{flex:1}
  .modal{position:fixed;inset:0;background:rgba(0,0,0,.75);z-index:40;
         display:none;overflow-y:auto;padding:30px 16px}
  .modal.on{display:block}
  .sheet{max-width:760px;margin:0 auto;background:var(--bg);
         border:1px solid var(--line);border-radius:14px;padding:20px 24px 28px}
  .sheet h2{font-size:15px;color:var(--accent);margin:0 0 4px}
  .close{float:right}
  pre{background:var(--panel2);border:1px solid var(--line);border-radius:8px;
      padding:12px;overflow-x:auto;font-size:12px;white-space:pre-wrap;
      word-break:break-all}
</style>
</head>
<body>

<header>
  <h1>eBay 発送管理</h1>
  <span class="env" id="env"></span>
  <button class="ghost" onclick="openSettings()">設定</button>
  <button class="primary" onclick="refresh()">更新</button>
</header>
<div id="bar" class="bar"></div>
<div class="wrap"><div id="list"></div></div>

<!-- 明細 -->
<div id="detail" class="modal"><div class="sheet">
  <button class="close ghost" onclick="closeModal('detail')">閉じる</button>
  <h2>発送情報の入力</h2>
  <div class="sect">商品</div>
  <div class="addr" id="dTitle"></div>
  <div class="sect">お届け先</div>
  <div class="addr" id="dAddr"></div>
  <div class="sect">内容品</div>
  <label>商品内容（内容品の品名・英語で）</label>
  <input id="fContent" placeholder="Used camera lens">
  <div class="cols">
    <div><label>重量（g）</label><input id="fWeight" placeholder="500"></div>
    <div><label>個数</label><input id="fQty" placeholder="1"></div>
    <div><label>HS コード</label><input id="fHs" placeholder="9002.11"></div>
    <div><label>原産国</label><input id="fOrigin" placeholder="JP"></div>
  </div>
  <label>商品金額</label>
  <input id="fValue" placeholder="8000">
  <div class="hint" id="dPriceHint"></div>
  <div class="row" style="margin-top:20px">
    <button class="primary" onclick="submitDetail()">決定（マイページを開いて入力）</button>
  </div>
</div></div>

<!-- マイページ操作 -->
<div id="jp" class="modal"><div class="sheet">
  <button class="close ghost" onclick="closeModal('jp')">閉じる</button>
  <h2>国際郵便マイページ</h2>
  <div class="hint" id="jpMsg"></div>
  <div class="row">
    <button onclick="jpFill()">もう一度入力</button>
    <button onclick="jpNext(false)">次へ</button>
    <button onclick="jpNext(true)">登録まで押す</button>
    <button onclick="jpDescribe()">項目一覧</button>
  </div>
  <div class="hint">「次へ」は確認画面まで。「登録まで押す」は取り消せないので、
    内容を確かめてから押してください。</div>
  <div id="jpManual"></div>
  <div class="row" style="margin-top:20px">
    <button class="primary" onclick="jpDone()">登録できた</button>
  </div>
</div></div>

<!-- 項目一覧と学習 -->
<div id="fields" class="modal"><div class="sheet">
  <button class="close ghost" onclick="closeModal('fields')">閉じる</button>
  <h2>この画面の入力欄</h2>
  <div class="hint">当てたい項目を選んで「覚える」を押すと、次からその欄に入ります。</div>
  <div id="fieldList"></div>
</div></div>

<!-- 設定 -->
<div id="settings" class="modal"><div class="sheet">
  <button class="close ghost" onclick="closeModal('settings')">閉じる</button>
  <h2>設定</h2>

  <div class="sect">まとめて貼り付け</div>
  <div class="hint">「App ID = xxxx」のような行を並べて貼ると一度に入ります。JSON でも読みます。</div>
  <label>貼り付け欄</label>
  <textarea id="sBulk" rows="5" placeholder="App ID = ...
Cert ID = ...
RuName = ..."></textarea>
  <div class="row"><button onclick="importBulk()">取り込む</button></div>

  <div class="sect">eBay</div>
  <div class="hint">developer.ebay.com を開いて、画面に出ている鍵をそのまま読み取れます。
    ログインはあなた自身が行い、打ち込んだものはこの PC の外には出ません。</div>
  <div class="row">
    <button onclick="post('/api/ebay/open',{}).then(function(r){bar(r.msg||r.error,!r.ok)})">
      eBay の管理画面を開く</button>
    <button onclick="grabKeys()">この画面から読み取る</button>
  </div>
  <label>App ID (Client ID)</label><input id="sClientId">
  <label>Cert ID (Client Secret)</label><input id="sClientSecret" type="password">
  <label>RuName (redirect_uri)</label><input id="sRuName">
  <div class="hint" id="sEnvHint"></div>
  <div class="row">
    <button onclick="startLink()">eBay と連携する</button>
    <button onclick="post('/api/unlink',{}).then(afterConf)">連携を解除</button>
  </div>
  <div class="hint" id="sLinked"></div>
  <div id="linkBox" style="display:none">
    <div class="hint">下のリンクが開かなかったときは、これを押してください。</div>
    <div class="row">
      <a id="consentLink" href="#" target="_blank" rel="noopener">
        <button class="primary">eBay の許可画面を開く</button></a>
    </div>
    <label>許可したあと、飛ばされた先の URL をまるごと貼ってください</label>
    <div class="hint">表示できないページでも構いません。
      アドレス欄の文字をそのままコピーしてください。</div>
    <textarea id="sCode" rows="3"
      placeholder="https://.../?code=v%5E1.1%23i%5E1%23..."></textarea>
    <div class="row"><button class="primary" onclick="finishLink()">連携を完了する</button></div>
  </div>
  <label>配送業者コード</label><input id="sCarrier">

  <div class="sect">国際郵便マイページ</div>
  <label>ログイン ID</label><input id="sJpId">
  <label>パスワード</label><input id="sJpPw" type="password">
  <label>開始 URL</label><input id="sJpUrl">
  <div class="row">
    <button onclick="post('/api/profile/clear',{}).then(function(r){bar(r.msg)})">
      覚えた入力欄を消す</button>
  </div>

  <div class="sect">二次元コードのメール（IMAP）</div>
  <div class="cols">
    <div><label>サーバ</label><input id="sMailHost"></div>
    <div><label>ポート</label><input id="sMailPort"></div>
  </div>
  <label>ユーザ（メールアドレス）</label><input id="sMailUser">
  <label>パスワード（Gmail はアプリパスワード）</label><input id="sMailPw" type="password">
  <div class="cols">
    <div><label>フォルダ</label><input id="sMailFolder"></div>
    <div><label>件名に含まれる語</label><input id="sMailSubject"></div>
  </div>

  <div class="sect">ご依頼主（送り状の差出人）</div>
  <label>氏名</label><input id="sFromName">
  <div class="cols">
    <div><label>郵便番号</label><input id="sFromPostal"></div>
    <div><label>電話番号</label><input id="sFromPhone"></div>
  </div>
  <label>住所</label><input id="sFromAddress">

  <div class="sect">内容品の初期値</div>
  <div class="cols">
    <div><label>HS コード</label><input id="sDefHs"></div>
    <div><label>原産国</label><input id="sDefOrigin"></div>
    <div><label>重量（g）</label><input id="sDefWeight"></div>
  </div>

  <div class="row" style="margin-top:20px">
    <button class="primary" onclick="saveSettings()">保存</button>
  </div>
</div></div>

<script>
var orders = [], conf = {}, fields = [], current = null, hasBrowser = false;
var lastPageKey = "";

function esc(s){
  return String(s == null ? "" : s).replace(/&/g,"&amp;").replace(/</g,"&lt;")
    .replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;");
}
function jst(iso){
  if(!iso) return "";
  var d = new Date(iso);
  if(isNaN(d.getTime())) return iso;
  return d.toLocaleString("ja-JP",{timeZone:"Asia/Tokyo",year:"numeric",
    month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit"});
}
function stamp(ms){ return ms ? jst(new Date(Number(ms)).toISOString()) : ""; }

function bar(msg, bad){
  var b = document.getElementById("bar");
  if(!msg){ b.className = "bar"; b.textContent = ""; return; }
  b.className = "bar on" + (bad ? " bad" : "");
  b.textContent = msg;
}

function post(url, data){
  bar("処理中…");
  return fetch(url, {method:"POST", headers:{"Content-Type":"application/json"},
                     body: JSON.stringify(data || {})})
    .then(function(r){ return r.json(); })
    .then(function(r){
      if(r.orders) { orders = r.orders; render(); }
      if(r.conf) conf = r.conf;
      if(r.env) document.getElementById("env").textContent = r.env;
      bar(r.ok ? (r.msg || "") : r.error, !r.ok);
      return r;
    })
    .catch(function(e){ bar(String(e), true); return {ok:false, error:String(e)}; });
}

function openModal(id){ document.getElementById(id).classList.add("on"); }
function closeModal(id){ document.getElementById(id).classList.remove("on"); }

var BADGE = {
  "new":["未入力","new"], "submitted":["登録済・コード待ち","submitted"],
  "qr":["二次元コードあり","qr"], "shipped":["発送済み","shipped"]
};

function render(){
  var el = document.getElementById("list");
  if(!orders.length){
    el.innerHTML = '<div class="empty">発送待ちの注文はありません。<br>' +
      '出てこないときは設定で eBay と連携してから「更新」を押してください。</div>';
    return;
  }
  var h = "";
  for(var i=0;i<orders.length;i++){
    var o = orders[i], st = o.state || "new", b = BADGE[st] || BADGE["new"];
    var a = o.address || {};
    h += '<div class="card"><div class="head" onclick="openDetail(\'' + esc(o.orderId) + '\')">' +
         '<div><div class="date">' + esc(jst(o.creationDate)) + '</div>' +
         '<div class="title">' + esc(o.title) + '</div>' +
         '<div class="meta">' + esc(a.name || "") + ' / ' + esc(a.country || "") +
         (o.buyer ? ' / ' + esc(o.buyer) : '') + '</div></div>' +
         '<span class="badge ' + b[1] + '">' + b[0] + '</span></div>';

    if(st === "submitted"){
      h += '<div class="row"><button onclick="checkMail(\'' + esc(o.orderId) + '\')">' +
           'メールを確認</button><button class="ghost" onclick="setState(\'' +
           esc(o.orderId) + '\',\'new\')">やり直す</button></div>';
    }
    if((st === "qr" || st === "shipped") && o.qrUrl){
      h += '<div class="row"><a href="' + esc(o.qrUrl) + '" target="_blank">' +
           '<button class="primary">二次元コードを表示</button></a></div>';
    }
    if(st === "qr"){
      h += '<div class="row"><input style="flex:1" id="tr_' + esc(o.orderId) +
           '" placeholder="お問い合わせ番号" value="' + esc(o.trackingNo || "") + '">' +
           '<button class="primary" onclick="ship(\'' + esc(o.orderId) + '\')">' +
           '発送済み</button></div>';
    }
    if(st === "shipped"){
      h += '<div class="meta">' + esc(o.trackingNo || "") + ' / ' +
           esc(stamp(o.shippedAt)) + '</div>';
    }
    h += '</div>';
  }
  if(orders.some(function(o){ return o.state === "shipped"; })){
    h += '<div class="row"><button onclick="post(\'/api/orders/clear-shipped\',{})">' +
         '発送済みを一覧から消す</button></div>';
  }
  el.innerHTML = h;
}

function find(id){
  for(var i=0;i<orders.length;i++) if(orders[i].orderId === id) return orders[i];
  return null;
}

function openDetail(id){
  var o = find(id);
  if(!o) return;
  current = o;
  var a = o.address || {}, f = o.form || {};
  document.getElementById("dTitle").textContent = o.title || "";
  var lines = [];
  if(a.name) lines.push(a.name);
  if(a.line1) lines.push(a.line1);
  if(a.line2) lines.push(a.line2);
  var city = [a.city, a.state, a.postal].filter(Boolean).join(" ");
  if(city) lines.push(city);
  if(a.country) lines.push(a.country);
  if(a.phone) lines.push("TEL " + a.phone);
  if(a.email) lines.push(a.email);
  document.getElementById("dAddr").textContent = lines.join("\n");

  document.getElementById("fContent").value = f.content || o.title || "";
  document.getElementById("fWeight").value = f.weight || conf.defWeight || "";
  document.getElementById("fQty").value = f.quantity || o.quantity || 1;
  document.getElementById("fHs").value = f.hsCode || conf.defHsCode || "";
  document.getElementById("fOrigin").value = f.origin || conf.defOrigin || "JP";
  document.getElementById("fValue").value = f.value || "";
  document.getElementById("dPriceHint").textContent =
    o.totalValue ? ("eBay の受注額 " + o.totalValue + " " + (o.totalCurrency || "")) : "";
  openModal("detail");
}

function formValues(){
  return {
    content: document.getElementById("fContent").value.trim(),
    weight: document.getElementById("fWeight").value.trim(),
    quantity: document.getElementById("fQty").value.trim(),
    hsCode: document.getElementById("fHs").value.trim(),
    origin: document.getElementById("fOrigin").value.trim(),
    value: document.getElementById("fValue").value.trim()
  };
}

function submitDetail(){
  if(!current) return;
  var f = formValues();
  if(!f.content){ bar("商品内容を入れてください", true); return; }
  closeModal("detail");
  post("/api/order/" + encodeURIComponent(current.orderId) + "/submit", {form:f})
    .then(function(r){
      document.getElementById("jpMsg").textContent = r.msg || "";
      var manual = document.getElementById("jpManual");
      if(r.manual && r.data){
        var t = "";
        for(var i=0;i<fields.length;i++){
          var v = r.data[fields[i].key];
          if(v) t += fields[i].label + "\t" + String(v).split("||")[0] + "\n";
        }
        manual.innerHTML = '<div class="hint">自動入力は使えないので、' +
          'ここからコピーして入れてください。</div><pre>' + esc(t) + '</pre>';
      } else {
        manual.innerHTML = "";
      }
      openModal("jp");
    });
}

function jpFill(){
  if(!current) return;
  post("/api/jp/fill", {orderId: current.orderId}).then(function(r){
    document.getElementById("jpMsg").textContent = r.msg || r.error || "";
    if(r.report) lastPageKey = r.report.pageKey || "";
  });
}
function jpNext(includeSubmit){
  if(includeSubmit &&
     !confirm("「登録」「送信」も押します。取り消せません。続けますか？")) return;
  post("/api/jp/next", {includeSubmit: !!includeSubmit}).then(function(r){
    document.getElementById("jpMsg").textContent = r.msg || r.error || "";
  });
}
function jpDone(){
  if(!current) return;
  closeModal("jp");
  post("/api/order/" + encodeURIComponent(current.orderId) + "/done", {});
  current = null;
}

function jpDescribe(){
  post("/api/jp/describe", {}).then(function(r){
    if(!r.ok) return;
    lastPageKey = r.url ? "" : lastPageKey;
    var list = r.fields || [], h = "";
    var opts = fields.map(function(f){
      return '<option value="' + esc(f.key) + '">' + esc(f.label) + '</option>';
    }).join("");
    for(var i=0;i<list.length;i++){
      var f = list[i];
      h += '<div class="card"><div class="meta">' + esc(f.tag) + '/' + esc(f.type) +
           '</div><div>' + esc(f.label || "(説明文なし)") + '</div>' +
           '<div class="meta">' + esc(f.selector) + '</div>' +
           '<div class="row"><select id="pick_' + i + '">' + opts + '</select>' +
           '<button onclick="learn(' + i + ',\'' + esc(f.selector) + '\')">' +
           '覚える</button></div></div>';
    }
    document.getElementById("fieldList").innerHTML =
      h || '<div class="empty">入力欄が見つかりませんでした</div>';
    openModal("fields");
  });
}

function learn(i, selector){
  var field = document.getElementById("pick_" + i).value;
  post("/api/jp/learn", {pageKey: lastPageKey, field: field, selector: selector});
}

function checkMail(id){ post("/api/mail/check", {orderId: id || ""}); }
function setState(id, st){
  post("/api/order/" + encodeURIComponent(id) + "/state", {state: st});
}
function ship(id){
  var el = document.getElementById("tr_" + id);
  var t = el ? el.value.trim() : "";
  if(!confirm("追跡番号 " + t + " を eBay に登録します。買い手に発送通知が飛びます。"))
    return;
  post("/api/order/" + encodeURIComponent(id) + "/ship", {trackingNo: t});
}
function refresh(){ post("/api/refresh", {}); }

var S = [
  ["sClientId","ebayClientId"], ["sClientSecret","ebayClientSecret"],
  ["sRuName","ebayRuName"], ["sCarrier","carrierCode"],
  ["sJpId","jpLoginId"], ["sJpPw","jpPassword"], ["sJpUrl","jpStartUrl"],
  ["sMailHost","mailHost"], ["sMailPort","mailPort"], ["sMailUser","mailUser"],
  ["sMailPw","mailPassword"], ["sMailFolder","mailFolder"],
  ["sMailSubject","mailSubject"],
  ["sFromName","fromName"], ["sFromPostal","fromPostal"],
  ["sFromAddress","fromAddress"], ["sFromPhone","fromPhone"],
  ["sDefHs","defHsCode"], ["sDefOrigin","defOrigin"], ["sDefWeight","defWeight"]
];

function fillSettings(){
  for(var i=0;i<S.length;i++){
    var el = document.getElementById(S[i][0]);
    if(el) el.value = conf[S[i][1]] == null ? "" : conf[S[i][1]];
  }
  document.getElementById("sLinked").textContent =
    conf.ebayLinked ? "連携済みです" : "まだ連携していません";
  var id = String(conf.ebayClientId || "").toUpperCase();
  var hint = document.getElementById("sEnvHint");
  if(id.indexOf("-SBX-") >= 0) hint.textContent =
    "App ID がサンドボックスのものなので、サンドボックスとして扱います。";
  else if(id.indexOf("-PRD-") >= 0) hint.textContent =
    "App ID が本番のものなので、本番として扱います。";
  else hint.textContent = "App ID から環境を読み取ります。";
}
function afterConf(r){ if(r && r.conf){ conf = r.conf; fillSettings(); } return r; }
function openSettings(){ fillSettings(); openModal("settings"); }
function collectSettings(){
  var out = {};
  for(var i=0;i<S.length;i++){
    var el = document.getElementById(S[i][0]);
    if(el) out[S[i][1]] = el.value.trim();
  }
  return out;
}
function saveSettings(){
  return post("/api/conf", collectSettings()).then(afterConf);
}
function importBulk(){
  var el = document.getElementById("sBulk");
  var t = el.value.trim();
  if(!t){ bar("貼り付け欄が空です", true); return; }
  post("/api/conf/import", {text: t}).then(function(r){
    afterConf(r);
    if(r.ok) el.value = "";
  });
}

function grabKeys(){
  post("/api/ebay/grab", {}).then(function(r){
    afterConf(r);
    if(r.ok) bar(r.msg + "。足りないものは eBay 側で該当の画面を開いてから" +
                 "もう一度押してください");
  });
}

function startLink(){
  // 先に保存を終わらせる。待たずに進むと、いま打った値が反映されないまま
  // 認可 URL を組んでしまう
  post("/api/conf", collectSettings()).then(function(r){
    afterConf(r);
    return fetch("/api/consent").then(function(x){ return x.json(); });
  }).then(function(r){
    if(!r.ok){ bar(r.error, true); return; }
    // ポップアップは塞がれることがあるので、押せるリンクとしても出しておく
    var box = document.getElementById("linkBox");
    box.style.display = "";
    document.getElementById("consentLink").href = r.url;
    // 見落とされると「何も起きない」に見えるので、確実に目に入れる
    try { box.scrollIntoView({behavior:"smooth", block:"center"}); } catch(e){}
    var w = null;
    try { w = window.open(r.url, "_blank"); } catch(e){ w = null; }
    bar(r.env + "の eBay にログインして許可してください。" +
        (w ? "" : "別のタブが開かなかったので、下のボタンを押してください。") +
        "\n許可したあと、飛ばされた先の URL をまるごと下に貼ります。");
  });
}
function finishLink(){
  post("/api/link", {pasted: document.getElementById("sCode").value}).then(function(r){
    afterConf(r);
    if(r.ok){
      document.getElementById("linkBox").style.display = "none";
      document.getElementById("sCode").value = "";
      refresh();
    }
  });
}

fetch("/api/state").then(function(r){ return r.json(); }).then(function(r){
  orders = r.orders || []; conf = r.conf || {}; fields = r.fields || [];
  hasBrowser = r.browser;
  document.getElementById("env").textContent = r.env || "";
  render();
  if(!hasBrowser){
    bar("Playwright が入っていないので、マイページの自動入力は使えません" +
        "（pip install playwright && playwright install chromium）");
  }
  refresh();
});
</script>
</body>
</html>
"""


def main():
    url = f"http://localhost:{PORT}"
    print(f"eBay 発送管理  →  {url}")
    if not Browser.available():
        print("マイページの自動入力を使うには: pip install playwright && "
              "playwright install chromium")
    threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    app.run(host="127.0.0.1", port=PORT, threaded=True)


if __name__ == "__main__":
    main()
