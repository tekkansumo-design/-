#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
eBay 出品アシスタント

  商品写真 → Claude が商品を特定し、Web 検索で型番・仕様を調べる
           → eBay のカテゴリ・Item Specifics を埋めて英語の出品文を作る
           → 落札相場（Sold）と現在の出品価格を調べて値付け
           → eBay に下書き登録 / 出品

使い方:
  pip install flask requests anthropic
  python ebay_lister.py
  → ブラウザで http://127.0.0.1:5001 を開き、「設定」タブから
    Anthropic API キーと eBay の開発者キーを登録する

必要なもの:
  - Anthropic API キー（https://console.anthropic.com/）
    環境変数 ANTHROPIC_API_KEY でも可
  - eBay 開発者アカウントのキー（https://developer.ebay.com/my/keys）
    App ID (Client ID) / Cert ID (Client Secret) / RuName
  - eBay セラーアカウントでビジネスポリシー（送料・支払・返品）を有効化済みであること

アイコンで起動する:
  - スマホ: 画面を開いてブラウザのメニューから「ホーム画面に追加」（iPhone は共有 →
    「ホーム画面に追加」）。以後はアイコンからアプリのように全画面で開く。
    Termux でサーバーごと起動したい場合は ebay_lister_termux.sh を Termux:Widget に置く
  - パソコン: Chrome / Edge のアドレスバー右の「インストール」ボタン
  どちらもサーバー（このスクリプト）が動いている必要がある。

設定ファイル ebay_lister_config.json には API キーとトークンが平文で入る。
他人に渡さないこと（.gitignore 済み）。既定では 127.0.0.1 でだけ待ち受ける。
スマホから LAN 越しに使う場合は EBAY_LISTER_HOST=0.0.0.0 を付けて起動する。
"""

import base64
import functools
import json
import math
import os
import re
import secrets
import statistics
import struct
import threading
import time
import traceback
import zlib
from datetime import datetime
from html import unescape
from pathlib import Path
from urllib.parse import parse_qs, quote, urlencode, urlparse

import requests
from flask import Flask, Response, abort, jsonify, request

# ═══════════════════════════════════════════════
BASE_DIR = Path(__file__).resolve().parent
CONF_FILE = BASE_DIR / "ebay_lister_config.json"
PORT = int(os.environ.get("EBAY_LISTER_PORT", "5001"))
HOST = os.environ.get("EBAY_LISTER_HOST", "127.0.0.1")

MODEL = "claude-opus-5-5"
# 安全分類器が断ったときに別モデルで自動再実行させる（server-side fallback）
FALLBACK_BETA = "server-side-fallback-2026-07-01"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")

# marketplace -> (通貨, Content-Language, 表サイト)
MARKETPLACES = {
    "EBAY_US": ("USD", "en-US", "www.ebay.com"),
    "EBAY_GB": ("GBP", "en-GB", "www.ebay.co.uk"),
    "EBAY_AU": ("AUD", "en-AU", "www.ebay.com.au"),
    "EBAY_CA": ("CAD", "en-CA", "www.ebay.ca"),
    "EBAY_DE": ("EUR", "de-DE", "www.ebay.de"),
}

CONDITIONS = [
    ("NEW", "新品"),
    ("NEW_OTHER", "新品（箱なし・開封済み）"),
    ("NEW_WITH_DEFECTS", "新品（傷あり）"),
    ("CERTIFIED_REFURBISHED", "メーカー整備済み"),
    ("SELLER_REFURBISHED", "セラー整備済み"),
    ("LIKE_NEW", "未使用に近い"),
    ("USED_EXCELLENT", "中古 - 非常に良い"),
    ("USED_VERY_GOOD", "中古 - 良い"),
    ("USED_GOOD", "中古 - 可"),
    ("USED_ACCEPTABLE", "中古 - 難あり"),
    ("FOR_PARTS_OR_NOT_WORKING", "ジャンク"),
]
CONDITION_KEYS = [k for k, _ in CONDITIONS]

API_SCOPE = "https://api.ebay.com/oauth/api_scope"
INSIGHTS_SCOPE = "https://api.ebay.com/oauth/api_scope/buy.marketplace.insights"
USER_SCOPES = [
    API_SCOPE,
    "https://api.ebay.com/oauth/api_scope/sell.inventory",
    "https://api.ebay.com/oauth/api_scope/sell.account",
]

DEFAULT_CONF = {
    "anthropic_api_key": "",
    "ebay_env": "production",          # production / sandbox
    "client_id": "",
    "client_secret": "",
    "ru_name": "",
    "refresh_token": "",
    "refresh_token_expires": "",
    "marketplace_id": "EBAY_US",
    "fulfillment_policy_id": "",
    "payment_policy_id": "",
    "return_policy_id": "",
    "merchant_location_key": "",
    "usd_jpy": 150.0,                  # 相場の円換算用（手入力）
    "scrape_sold": True,               # Insights API が使えないとき Sold 検索ページを読む
    "auto_draft": True,                # 商品特定のあと相場・項目を埋めて eBay に下書き登録まで進める
}
SECRET_KEYS = ("anthropic_api_key", "client_secret", "refresh_token")

CONF_LOCK = threading.Lock()


def load_conf():
    d = dict(DEFAULT_CONF)
    if CONF_FILE.exists():
        try:
            saved = json.loads(CONF_FILE.read_text(encoding="utf-8"))
            if isinstance(saved, dict):
                d.update({k: v for k, v in saved.items() if k in DEFAULT_CONF})
        except (OSError, ValueError):
            pass
    return d


def save_conf(d):
    with CONF_LOCK:
        CONF_FILE.write_text(json.dumps(d, ensure_ascii=False, indent=2),
                             encoding="utf-8")
        try:
            os.chmod(CONF_FILE, 0o600)
        except OSError:
            pass


def public_conf(d):
    """画面に返す設定。秘密の値は伏せて「登録済みか」だけ返す。"""
    out = {k: v for k, v in d.items() if k not in SECRET_KEYS}
    for k in SECRET_KEYS:
        out["has_" + k] = bool(d.get(k))
    out["has_anthropic_env"] = bool(os.environ.get("ANTHROPIC_API_KEY"))
    return out


def body_json():
    d = request.get_json(silent=True)
    if not isinstance(d, dict):
        abort(400, description="JSON オブジェクトが必要です")
    return d


class AppError(Exception):
    """画面にそのまま見せてよいエラー。"""


# ═══════════════════════════════════════════════
#  eBay API
# ═══════════════════════════════════════════════
class EbayError(AppError):
    def __init__(self, status, errors, text=""):
        self.status = status
        self.errors = errors or []
        msgs = []
        for e in self.errors:
            m = e.get("longMessage") or e.get("message") or ""
            if e.get("errorId"):
                m = f"[{e['errorId']}] {m}"
            msgs.append(m)
        super().__init__(f"eBay API {status}: " + (" / ".join(msgs) or text[:300]))

    def param(self, name):
        for e in self.errors:
            for p in e.get("parameters") or []:
                if p.get("name") == name:
                    return p.get("value")
        return None

    def has_id(self, error_id):
        return any(e.get("errorId") == error_id for e in self.errors)


class Ebay:
    """REST API の薄いラッパー。アプリトークンとユーザートークンを内部でキャッシュする。"""

    def __init__(self):
        self._tokens = {}          # kind -> (token, expires_at, conf_sig)
        self._tree = {}            # marketplace -> category_tree_id
        self._lock = threading.Lock()

    # ── ホスト ──
    @staticmethod
    def _sandbox(conf):
        return conf.get("ebay_env") == "sandbox"

    def api_host(self, conf):
        return "https://api.sandbox.ebay.com" if self._sandbox(conf) else "https://api.ebay.com"

    def auth_host(self, conf):
        return "https://auth.sandbox.ebay.com" if self._sandbox(conf) else "https://auth.ebay.com"

    def apim_host(self, conf):
        return "https://apim.sandbox.ebay.com" if self._sandbox(conf) else "https://apim.ebay.com"

    def web_host(self, conf):
        if self._sandbox(conf):
            return "https://sandbox.ebay.com"
        return "https://" + MARKETPLACES.get(conf["marketplace_id"], MARKETPLACES["EBAY_US"])[2]

    # ── OAuth ──
    def _basic(self, conf):
        if not conf.get("client_id") or not conf.get("client_secret"):
            raise AppError("eBay の Client ID / Client Secret が未設定です（設定タブ）")
        raw = f"{conf['client_id']}:{conf['client_secret']}".encode()
        return "Basic " + base64.b64encode(raw).decode()

    def _token_request(self, conf, data):
        r = requests.post(
            self.api_host(conf) + "/identity/v1/oauth2/token",
            headers={"Authorization": self._basic(conf),
                     "Content-Type": "application/x-www-form-urlencoded"},
            data=data, timeout=30)
        try:
            j = r.json()
        except ValueError:
            j = {}
        if r.status_code != 200:
            desc = j.get("error_description") or j.get("error") or r.text[:200]
            raise AppError(f"eBay トークン取得に失敗しました ({r.status_code}): {desc}")
        return j

    def token(self, conf, kind):
        """kind: app / insights / user"""
        sig = (conf.get("ebay_env"), conf.get("client_id"), conf.get("client_secret"),
               conf.get("refresh_token"))
        with self._lock:
            hit = self._tokens.get(kind)
            if hit and hit[2] == sig and hit[1] > time.time() + 60:
                return hit[0]
        if kind == "user":
            if not conf.get("refresh_token"):
                raise AppError("eBay アカウントと未連携です（設定タブの「eBay と連携」）")
            j = self._token_request(conf, {
                "grant_type": "refresh_token",
                "refresh_token": conf["refresh_token"],
                "scope": " ".join(USER_SCOPES)})
        else:
            scope = INSIGHTS_SCOPE if kind == "insights" else API_SCOPE
            j = self._token_request(conf, {"grant_type": "client_credentials",
                                           "scope": scope})
        tok = j["access_token"]
        with self._lock:
            self._tokens[kind] = (tok, time.time() + int(j.get("expires_in", 3600)), sig)
        return tok

    def auth_url(self, conf, state):
        if not conf.get("client_id") or not conf.get("ru_name"):
            raise AppError("Client ID と RuName を先に保存してください")
        q = urlencode({"client_id": conf["client_id"],
                       "redirect_uri": conf["ru_name"],
                       "response_type": "code",
                       "scope": " ".join(USER_SCOPES),
                       "state": state})
        return self.auth_host(conf) + "/oauth2/authorize?" + q

    def exchange_code(self, conf, code):
        j = self._token_request(conf, {"grant_type": "authorization_code",
                                       "code": code,
                                       "redirect_uri": conf["ru_name"]})
        with self._lock:
            self._tokens.pop("user", None)
        return j

    # ── 汎用リクエスト ──
    def call(self, conf, method, path, kind="app", host=None, headers=None,
             ok=(200, 201, 204), **kw):
        h = {"Authorization": "Bearer " + self.token(conf, kind),
             "Accept": "application/json",
             "X-EBAY-C-MARKETPLACE-ID": conf["marketplace_id"]}
        if "json" in kw:
            h["Content-Type"] = "application/json"
            h["Content-Language"] = MARKETPLACES[conf["marketplace_id"]][1]
        h.update(headers or {})
        url = (host or self.api_host(conf)) + path
        r = requests.request(method, url, headers=h, timeout=60, **kw)
        if r.status_code not in ok:
            try:
                errs = r.json().get("errors")
            except ValueError:
                errs = None
            raise EbayError(r.status_code, errs, r.text)
        if not r.content:
            return {}, r
        try:
            return r.json(), r
        except ValueError:
            return {}, r

    # ── Taxonomy ──
    def tree_id(self, conf):
        mp = conf["marketplace_id"]
        if mp not in self._tree:
            j, _ = self.call(conf, "GET",
                             "/commerce/taxonomy/v1/get_default_category_tree_id",
                             params={"marketplace_id": mp})
            self._tree[mp] = j["categoryTreeId"]
        return self._tree[mp]

    def category_suggestions(self, conf, q):
        tid = self.tree_id(conf)
        j, _ = self.call(conf, "GET",
                         f"/commerce/taxonomy/v1/category_tree/{tid}/get_category_suggestions",
                         params={"q": q[:350]})
        out = []
        for s in j.get("categorySuggestions") or []:
            c = s.get("category") or {}
            path = [a.get("categoryName", "") for a in reversed(s.get("categoryTreeNodeAncestors") or [])]
            out.append({"id": c.get("categoryId"), "name": c.get("categoryName"),
                        "path": " > ".join(path + [c.get("categoryName", "")])})
        return out

    def item_aspects(self, conf, category_id):
        tid = self.tree_id(conf)
        j, _ = self.call(conf, "GET",
                         f"/commerce/taxonomy/v1/category_tree/{tid}/get_item_aspects_for_category",
                         params={"category_id": category_id})
        out = []
        for a in j.get("aspects") or []:
            c = a.get("aspectConstraint") or {}
            out.append({
                "name": a.get("localizedAspectName"),
                "required": bool(c.get("aspectRequired")),
                "usage": c.get("aspectUsage"),               # RECOMMENDED / OPTIONAL
                "mode": c.get("aspectMode"),                 # FREE_TEXT / SELECTION_ONLY
                "multi": c.get("itemToAspectCardinality") == "MULTI",
                "values": [v.get("localizedValue") for v in a.get("aspectValues") or []],
            })
        return out

    # ── Browse（現在の出品）──
    def search_active(self, conf, q, limit=50):
        j, _ = self.call(conf, "GET", "/buy/browse/v1/item_summary/search",
                         params={"q": q, "limit": limit})
        out = []
        for it in j.get("itemSummaries") or []:
            p = it.get("price") or {}
            try:
                price = float(p.get("value"))
            except (TypeError, ValueError):
                continue
            out.append({"title": it.get("title"), "price": price,
                        "currency": p.get("currency"),
                        "condition": it.get("condition"),
                        "url": it.get("itemWebUrl"),
                        "image": (it.get("image") or {}).get("imageUrl")})
        return out

    def search_by_image(self, conf, jpeg_bytes, limit=8):
        j, _ = self.call(conf, "POST", "/buy/browse/v1/item_summary/search_by_image",
                         params={"limit": limit},
                         json={"image": base64.b64encode(jpeg_bytes).decode()})
        return [it.get("title") for it in j.get("itemSummaries") or [] if it.get("title")]

    # ── Marketplace Insights（落札履歴。利用には eBay の個別承認が必要）──
    def search_sold_api(self, conf, q, limit=50):
        j, _ = self.call(conf, "GET",
                         "/buy/marketplace_insights/v1_beta/item_sales/search",
                         kind="insights", params={"q": q, "limit": limit})
        out = []
        for it in j.get("itemSales") or []:
            p = it.get("lastSoldPrice") or {}
            try:
                price = float(p.get("value"))
            except (TypeError, ValueError):
                continue
            out.append({"title": it.get("title"), "price": price,
                        "currency": p.get("currency"),
                        "date": (it.get("lastSoldDate") or "")[:10],
                        "qty": it.get("totalSoldQuantity"),
                        "condition": it.get("condition"),
                        "url": it.get("itemWebUrl")})
        return out

    # ── Account（ビジネスポリシー・発送元）──
    def policies(self, conf):
        mp = conf["marketplace_id"]
        out = {}
        for kind, key in (("fulfillment", "fulfillmentPolicies"),
                          ("payment", "paymentPolicies"),
                          ("return", "returnPolicies")):
            j, _ = self.call(conf, "GET", f"/sell/account/v1/{kind}_policy",
                             kind="user", params={"marketplace_id": mp})
            out[kind] = [{"id": p.get(f"{kind}PolicyId"), "name": p.get("name")}
                         for p in j.get(key) or []]
        j, _ = self.call(conf, "GET", "/sell/inventory/v1/location",
                         kind="user", params={"limit": 100})
        out["location"] = [{"id": l.get("merchantLocationKey"),
                            "name": l.get("name") or l.get("merchantLocationKey")}
                           for l in j.get("locations") or []]
        return out

    def opted_in_programs(self, conf):
        j, _ = self.call(conf, "GET", "/sell/account/v1/program/get_opted_in_programs", kind="user")
        return [p.get("programType") for p in j.get("programs") or []]

    def opt_in(self, conf, program):
        self.call(conf, "POST", "/sell/account/v1/program/opt_in", kind="user",
                  json={"programType": program})

    def create_policy(self, conf, kind, body):
        """kind: payment / return。車両以外の全カテゴリ向けに作る。"""
        body = dict(body, marketplaceId=conf["marketplace_id"],
                    categoryTypes=[{"name": "ALL_EXCLUDING_MOTORS_VEHICLES"}])
        j, _ = self.call(conf, "POST", f"/sell/account/v1/{kind}_policy", kind="user", json=body)
        return j.get(f"{kind}PolicyId")

    def create_location(self, conf, key, addr):
        body = {"location": {"address": addr},
                "locationTypes": ["WAREHOUSE"],
                "name": key,
                "merchantLocationStatus": "ENABLED"}
        self.call(conf, "POST", f"/sell/inventory/v1/location/{quote(key, safe='')}",
                  kind="user", json=body)

    # ── 出品 ──
    def upload_image(self, conf, data, mime="image/jpeg"):
        j, r = self.call(conf, "POST", "/commerce/media/v1_beta/image/create_image_from_file",
                         kind="user", host=self.apim_host(conf),
                         files={"image": ("photo.jpg", data, mime)})
        if j.get("imageUrl"):
            return j["imageUrl"]
        loc = r.headers.get("Location")
        if loc:
            j2, _ = self.call(conf, "GET", urlparse(loc).path, kind="user",
                              host=self.apim_host(conf))
            if j2.get("imageUrl"):
                return j2["imageUrl"]
        raise AppError("画像のアップロード結果に imageUrl がありません")

    def put_inventory_item(self, conf, sku, body):
        self.call(conf, "PUT", f"/sell/inventory/v1/inventory_item/{quote(sku, safe='')}",
                  kind="user", json=body)

    def upsert_offer(self, conf, body):
        try:
            j, _ = self.call(conf, "POST", "/sell/inventory/v1/offer",
                             kind="user", json=body)
            return j["offerId"]
        except EbayError as e:
            # 同じ SKU の offer が既にある → それを更新する
            oid = e.param("offerId") if e.has_id(25002) else None
            if not oid:
                raise
        upd = {k: v for k, v in body.items() if k not in ("sku", "marketplaceId", "format")}
        self.call(conf, "PUT", f"/sell/inventory/v1/offer/{oid}", kind="user", json=upd)
        return oid

    def publish_offer(self, conf, offer_id):
        j, _ = self.call(conf, "POST", f"/sell/inventory/v1/offer/{offer_id}/publish",
                         kind="user")
        return j.get("listingId")


EBAY = Ebay()


# ═══════════════════════════════════════════════
#  落札相場
# ═══════════════════════════════════════════════
def _strip(s):
    return re.sub(r"\s+", " ", unescape(re.sub(r"<[^>]+>", " ", s or ""))).strip()


PRICE_RE = re.compile(r"(?:US\s*)?(?:\$|£|€|AU\s*\$|C\s*\$)\s*([\d,]+(?:\.\d{1,2})?)"
                      r"|([\d.,]+)\s*€|EUR\s*([\d.,]+)")
SOLD_DATE_RE = re.compile(r"(?:Sold|Verkauft)\s+([A-Z][a-z]{2}\s+\d{1,2},?\s+\d{4}|\d{1,2}\.?\s+\w+\.?\s+\d{4})")
ITEM_URL_RE = re.compile(r'href="(https?://[^"]*?/itm/(?:[^"/]*/)?(\d{9,15})[^"]*)"')


def _price(text):
    m = PRICE_RE.search(text or "")
    if not m:
        return None
    raw = next(g for g in m.groups() if g)
    if m.group(1):                      # $1,234.56
        raw = raw.replace(",", "")
    else:                               # 1.234,56 €
        raw = raw.replace(".", "").replace(",", ".")
    try:
        return float(raw)
    except ValueError:
        return None


def parse_sold_page(html_text):
    """eBay の Sold 検索結果ページから (タイトル, 価格, 日付, URL) を抜く。

    旧マークアップ（li.s-item）と新マークアップ（li.s-card）の両方を見る。
    ページ構造は予告なく変わるので、取れなかったら空を返して呼び出し側で案内する。
    """
    out, seen = [], set()
    chunks = re.split(r"<li\b(?=[^>]*class=\"[^\"]*\bs-(?:item|card)\b)", html_text)
    for ch in chunks[1:]:
        ch = ch[:20000]
        m = ITEM_URL_RE.search(ch)
        if not m or m.group(2) in seen:
            continue
        tm = (re.search(r'class="[^"]*s-(?:item|card)__title[^"]*"[^>]*>(.*?)</(?:div|h3)>', ch, re.S)
              or re.search(r'<h3[^>]*>(.*?)</h3>', ch, re.S))
        title = _strip(tm.group(1)) if tm else ""
        title = re.sub(r"^(New Listing|Neues Angebot)\s*", "", title)
        title = re.sub(r"\s*Opens in a new window or tab$", "", title)
        if not title or title.lower().startswith("shop on ebay"):
            continue
        pm = re.search(r'class="[^"]*s-(?:item|card)__price[^"]*"[^>]*>(.*?)</span>', ch, re.S)
        price = _price(_strip(pm.group(1))) if pm else None
        if price is None:
            continue
        dm = SOLD_DATE_RE.search(_strip(ch))
        date = ""
        if dm:
            for fmt in ("%b %d, %Y", "%b %d %Y"):
                try:
                    date = datetime.strptime(dm.group(1), fmt).strftime("%Y-%m-%d")
                    break
                except ValueError:
                    pass
            date = date or dm.group(1)
        seen.add(m.group(2))
        out.append({"title": title, "price": price, "date": date,
                    "url": m.group(1).split("?")[0]})
    return out


def sold_search_url(conf, q):
    return (EBAY.web_host(conf) + "/sch/i.html?" +
            urlencode({"_nkw": q, "LH_Sold": "1", "LH_Complete": "1", "_ipg": "120"}))


def search_sold_scrape(conf, q):
    r = requests.get(sold_search_url(conf, q) + "&rt=nc",
                     headers={"User-Agent": UA,
                              "Accept": "text/html,application/xhtml+xml",
                              "Accept-Language": "en-US,en;q=0.9"},
                     timeout=30)
    if r.status_code != 200:
        raise AppError(f"Sold 検索ページの取得に失敗しました ({r.status_code})")
    items = parse_sold_page(r.text)
    if not items and ("captcha" in r.text.lower() or "splashui" in r.text.lower()):
        raise AppError("eBay にロボット判定されました。時間をおくか、下のリンクから直接確認してください")
    return items


def price_stats(items):
    prices = sorted(i["price"] for i in items if i.get("price") is not None)
    if not prices:
        return None
    st = {"count": len(prices), "min": prices[0], "max": prices[-1],
          "mean": round(statistics.fmean(prices), 2),
          "median": round(statistics.median(prices), 2)}
    if len(prices) >= 4:
        q1, _, q3 = statistics.quantiles(prices, n=4)
        st["p25"], st["p75"] = round(q1, 2), round(q3, 2)
    return st


def research_prices(conf, q):
    res = {"query": q,
           "currency": MARKETPLACES[conf["marketplace_id"]][0],
           "usd_jpy": conf.get("usd_jpy") or 0,
           "links": {"sold": sold_search_url(conf, q),
                     "terapeak": "https://www.ebay.com/sh/research?" +
                                 urlencode({"marketplace": conf["marketplace_id"].replace("EBAY_", "EBAY-"),
                                            "keywords": q, "tabName": "SOLD"})}}
    sold = {"source": None, "items": [], "error": None}
    try:
        sold["items"] = EBAY.search_sold_api(conf, q)
        sold["source"] = "Marketplace Insights API"
    except AppError as e:
        api_err = str(e)
        if conf.get("scrape_sold"):
            try:
                sold["items"] = search_sold_scrape(conf, q)
                sold["source"] = "eBay Sold 検索ページ"
            except (AppError, requests.RequestException) as e2:
                sold["error"] = f"{e2}（Insights API: {api_err}）"
        else:
            sold["error"] = f"Insights API が使えません: {api_err}"
    except requests.RequestException as e:
        sold["error"] = f"通信エラー: {e}"
    sold["stats"] = price_stats(sold["items"])
    res["sold"] = sold

    active = {"items": [], "error": None}
    try:
        active["items"] = EBAY.search_active(conf, q)
    except (AppError, requests.RequestException) as e:
        active["error"] = str(e)
    active["stats"] = price_stats(active["items"])
    res["active"] = active

    # 推奨価格: 落札の中央値。落札が取れなければ出品中の下位 25% あたり
    s, a = sold["stats"], active["stats"]
    if s:
        res["suggested"] = s["median"]
        res["suggested_basis"] = f"落札 {s['count']} 件の中央値"
    elif a:
        res["suggested"] = a.get("p25", a["median"])
        res["suggested_basis"] = f"出品中 {a['count']} 件の下位 25%（落札データなし）"
    return res


# ═══════════════════════════════════════════════
#  Claude（商品特定・調査・出品文）
# ═══════════════════════════════════════════════
def claude_client(conf):
    try:
        import anthropic
    except ImportError:
        raise AppError("anthropic パッケージが必要です: pip install anthropic")
    key = conf.get("anthropic_api_key") or None     # 未設定なら環境変数を使う
    if not key and not os.environ.get("ANTHROPIC_API_KEY"):
        raise AppError("Anthropic API キーが未設定です（設定タブ）")
    return anthropic.Anthropic(api_key=key, max_retries=3)


def check_anthropic_key(key=None):
    """キーが使えるかを確かめる。models.retrieve はトークンを使わないので料金はかからない。"""
    try:
        import anthropic
    except ImportError:
        raise AppError("anthropic パッケージが必要です: pip install anthropic")
    try:
        anthropic.Anthropic(api_key=key or None, max_retries=1, timeout=20).models.retrieve(MODEL)
    except anthropic.AuthenticationError:
        raise AppError("キーが正しくありません（コピー漏れか、削除されたキーの可能性があります）")
    except anthropic.PermissionDeniedError:
        raise AppError("このキーには使う権限がありません（組織やワークスペースの設定を確認してください）")
    except anthropic.NotFoundError:
        raise AppError(f"このキーでは {MODEL} を使えません")
    except anthropic.APIConnectionError:
        raise AppError("Anthropic に接続できません。インターネット接続を確認してください")
    except anthropic.APIStatusError as e:
        if e.status_code != 429:            # 混雑しているだけならキー自体は有効
            raise AppError(f"確認できませんでした ({e.status_code}): {e.message}")
    except anthropic.AnthropicError as e:
        raise AppError(f"確認できませんでした: {e}")


def claude_create(client, **kw):
    import anthropic
    try:
        resp = client.beta.messages.create(model=MODEL, betas=[FALLBACK_BETA],
                                           fallbacks="default", **kw)
    except anthropic.AuthenticationError:
        raise AppError("Anthropic API キーが正しくありません")
    except anthropic.RateLimitError:
        raise AppError("Anthropic API の利用上限に達しました。しばらく待ってください")
    except anthropic.BadRequestError as e:
        if "credit balance" in str(e.message).lower():
            raise AppError("Anthropic のクレジット残高が足りません。設定タブの「Anthropic API かんたん登録」②からクレジットを購入してください")
        raise AppError(f"Anthropic API エラー ({e.status_code}): {e.message}")
    except anthropic.APIStatusError as e:
        raise AppError(f"Anthropic API エラー ({e.status_code}): {e.message}")
    except anthropic.APIConnectionError:
        raise AppError("Anthropic API に接続できません")
    if resp.stop_reason == "refusal":
        raise AppError("AI がこの画像の処理を断りました")
    return resp


def image_blocks(images):
    return [{"type": "image",
             "source": {"type": "base64", "media_type": m,
                        "data": base64.standard_b64encode(b).decode()}}
            for b, m in images]


RESEARCH_SYSTEM = """\
You are a product researcher for a Japanese seller who exports second-hand and new goods to eBay.
Given product photos, identify the exact product and research it on the web.

- Read every clue in the photos: logos, model numbers, JAN/EAN/UPC barcodes, serial plates, packaging text (often Japanese).
- Use web search to confirm the exact model/variant and collect specs: brand, official product name (Japanese and English),
  model/part number, JAN/UPC, release year, color, size, material, compatibility, what is included, country of manufacture.
  Prefer manufacturer pages, then major retailers (Amazon, Rakuten, Yodobashi, etc.).
- Also note whether it is a Japan-only / Japanese-version item (a selling point on eBay), and what buyers search for in English.
- If you cannot pin down the exact variant, say which candidates remain and why.
- Describe the visible condition of the item in the photos (scratches, wear, missing parts, box condition).

Finish with a concise research summary: confirmed facts (with source), uncertain points, and visible condition."""

LISTING_SCHEMA = {
    "type": "object",
    "properties": {
        "identified": {"type": "boolean"},
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
        "product_name_ja": {"type": "string"},
        "product_name_en": {"type": "string"},
        "brand": {"type": "string"},
        "model_number": {"type": "string"},
        "jan_code": {"type": "string"},
        "search_query": {"type": "string"},
        "ebay_title": {"type": "string"},
        "description_html": {"type": "string"},
        "item_specifics": {"type": "array", "items": {
            "type": "object",
            "properties": {"name": {"type": "string"}, "value": {"type": "string"}},
            "required": ["name", "value"], "additionalProperties": False}},
        "condition": {"type": "string", "enum": CONDITION_KEYS},
        "condition_notes": {"type": "string"},
        "notes_ja": {"type": "string"},
    },
    "required": ["identified", "confidence", "product_name_ja", "product_name_en", "brand",
                 "model_number", "jan_code", "search_query", "ebay_title", "description_html",
                 "item_specifics", "condition", "condition_notes", "notes_ja"],
    "additionalProperties": False,
}

LISTING_INSTRUCTIONS = """\
Turn the research notes below into an eBay listing draft for marketplace {mp}. Use the photos to judge condition.

Field rules:
- search_query: 3-8 English keywords a buyer would type to find this exact item on eBay (brand + model + key variant). No condition words.
- ebay_title: English, at most 80 characters, keyword-rich: brand, product name, model number, key specs, "Japan" when it is a Japanese version.
  No emoji, no ALL CAPS words except model numbers and acronyms, no "L@@K"/"wow".
- description_html: English. Simple HTML (<h3>, <p>, <ul><li>, <br>) with sections: overview, specifications, condition
  (honest, based on the photos), what's included, shipping from Japan. No external links, no scripts, no images.
- item_specifics: eBay-style names (Brand, Model, MPN, Type, Color, Series, Country/Region of Manufacture, ...). Only facts you are confident in.
- condition: the eBay condition enum that best matches the photos; condition_notes: one or two English sentences.
- jan_code / model_number: empty string when unknown.
- notes_ja: 日本語で、出品者向けのメモ（確度が低い点、確認すべき点、付属品の確認など）。
- If the item could not be identified, set identified=false, confidence=low and still fill a best-effort draft.
{hint}
<research_notes>
{notes}
</research_notes>"""

ASPECTS_SCHEMA = {
    "type": "object",
    "properties": {"aspects": {"type": "array", "items": {
        "type": "object",
        "properties": {"name": {"type": "string"},
                       "values": {"type": "array", "items": {"type": "string"}}},
        "required": ["name", "values"], "additionalProperties": False}}},
    "required": ["aspects"], "additionalProperties": False,
}


def first_json(resp):
    for b in resp.content:
        if b.type == "text":
            try:
                return json.loads(b.text)
            except ValueError:
                break
    if resp.stop_reason == "max_tokens":
        raise AppError("AI の出力が途中で切れました。もう一度試してください")
    raise AppError("AI の応答を解釈できませんでした")


def research_product(conf, images, hint, ebay_hints):
    client = claude_client(conf)
    text = "Identify this product and research it."
    if hint:
        text += f"\nSeller's note (may be Japanese): {hint}"
    if ebay_hints:
        text += ("\neBay image search returned visually similar listings (may be wrong or a different variant):\n- "
                 + "\n- ".join(ebay_hints))
    user = {"role": "user", "content": image_blocks(images) + [{"type": "text", "text": text}]}
    messages = [user]
    tools = [{"type": "web_search_20260209", "name": "web_search", "max_uses": 8}]
    notes, sources, seen = [], [], set()
    for _ in range(5):
        resp = claude_create(client, max_tokens=16000, system=RESEARCH_SYSTEM,
                             messages=messages, tools=tools,
                             output_config={"effort": "medium"})
        for b in resp.content:
            if b.type == "text":
                notes.append(b.text)
            elif b.type == "web_search_tool_result" and isinstance(b.content, list):
                for r in b.content:
                    url = getattr(r, "url", None)
                    if url and url not in seen:
                        seen.add(url)
                        sources.append({"title": getattr(r, "title", "") or url, "url": url})
        if resp.stop_reason != "pause_turn":
            break
        # サーバー側の検索ループが上限で止まった。続きから再開させる
        messages.append({"role": "assistant", "content": resp.content})
    return "".join(notes).strip(), sources[:15]


def draft_listing(conf, images, notes, hint):
    client = claude_client(conf)
    prompt = LISTING_INSTRUCTIONS.format(
        mp=conf["marketplace_id"], notes=notes or "(no notes)",
        hint=f"\nSeller's note: {hint}\n" if hint else "")
    resp = claude_create(client, max_tokens=16000,
                         messages=[{"role": "user", "content":
                                    image_blocks(images) + [{"type": "text", "text": prompt}]}],
                         output_config={"effort": "low",
                                        "format": {"type": "json_schema", "schema": LISTING_SCHEMA}})
    d = first_json(resp)
    d["ebay_title"] = d.get("ebay_title", "")[:80]
    return d


def fill_aspects(conf, product, notes, aspects):
    """カテゴリの Item Specifics 定義に合わせて値を埋める。選択式は許可値だけ残す。"""
    client = claude_client(conf)
    lines = []
    for a in aspects:
        tag = "REQUIRED" if a["required"] else (a.get("usage") or "OPTIONAL")
        line = f"- {a['name']} [{tag}{', multiple values ok' if a['multi'] else ''}]"
        if a["values"]:
            vals = a["values"][:80]
            kind = "choose only from" if a["mode"] == "SELECTION_ONLY" else "suggested values"
            line += f" {kind}: " + " | ".join(vals) + (" | ..." if len(a["values"]) > 80 else "")
        lines.append(line)
    prompt = (
        "Fill eBay item specifics for this product. Use only facts supported by the product data or research notes.\n"
        "Include every REQUIRED aspect (best supported guess if needed; use \"Unbranded\" for Brand only when there is no brand, "
        "and \"Does Not Apply\" only when the aspect genuinely does not apply). Include RECOMMENDED/OPTIONAL aspects only when known. "
        "Use the exact aspect names given. For 'choose only from' aspects, copy a listed value exactly.\n\n"
        "<aspects>\n" + "\n".join(lines) + "\n</aspects>\n\n"
        f"<product>\n{json.dumps(product, ensure_ascii=False)}\n</product>\n\n"
        f"<research_notes>\n{notes}\n</research_notes>")
    resp = claude_create(client, max_tokens=8000,
                         messages=[{"role": "user", "content": prompt}],
                         output_config={"effort": "low",
                                        "format": {"type": "json_schema", "schema": ASPECTS_SCHEMA}})
    got = first_json(resp).get("aspects") or []
    defs = {a["name"].lower(): a for a in aspects}
    out = []
    for g in got:
        a = defs.get((g.get("name") or "").lower())
        if not a:
            continue
        vals = [v.strip() for v in g.get("values") or [] if v and v.strip()]
        if a["mode"] == "SELECTION_ONLY" and a["values"]:
            allowed = {v.lower(): v for v in a["values"]}
            vals = [allowed[v.lower()] for v in vals if v.lower() in allowed]
        if not a["multi"]:
            vals = vals[:1]
        if vals:
            out.append({"name": a["name"], "value": ", ".join(vals) if a["multi"] else vals[0],
                        "required": a["required"]})
    have = {o["name"] for o in out}
    missing = [a["name"] for a in aspects if a["required"] and a["name"] not in have]
    return out, missing


# ═══════════════════════════════════════════════
#  下書き（撮影画像と調査結果をサーバー側で保持）
# ═══════════════════════════════════════════════
DRAFTS = {}
DRAFT_LOCK = threading.Lock()
MAX_DRAFTS = 20
MAX_IMAGES = 12
MAX_IMAGE_BYTES = 5 * 1024 * 1024      # Claude の画像 1 枚あたりの上限
IMAGE_MIMES = {"image/jpeg", "image/png", "image/webp", "image/gif"}


def put_draft(d):
    did = secrets.token_hex(8)
    with DRAFT_LOCK:
        if len(DRAFTS) >= MAX_DRAFTS:
            oldest = min(DRAFTS, key=lambda k: DRAFTS[k]["created"])
            DRAFTS.pop(oldest)
        d["created"] = time.time()
        DRAFTS[did] = d
    return did


def get_draft(did):
    with DRAFT_LOCK:
        d = DRAFTS.get(did or "")
    if not d:
        raise AppError("下書きが見つかりません（サーバーを再起動した場合は撮り直してください）")
    return d


def read_images():
    files = request.files.getlist("images")
    if not files:
        raise AppError("画像を選んでください")
    if len(files) > MAX_IMAGES:
        raise AppError(f"画像は {MAX_IMAGES} 枚までです")
    out = []
    for f in files:
        data = f.read()
        mime = (f.mimetype or "").lower()
        if mime not in IMAGE_MIMES:
            raise AppError(f"対応していない画像形式です: {f.filename} ({mime})")
        if len(data) > MAX_IMAGE_BYTES:
            raise AppError(f"画像が大きすぎます: {f.filename}（5MB まで）")
        out.append((data, mime))
    return out


# ═══════════════════════════════════════════════
#  Flask
# ═══════════════════════════════════════════════
app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 80 * 1024 * 1024
OAUTH_STATE = {"value": None}


@app.errorhandler(AppError)
def on_app_error(e):
    return jsonify({"error": str(e)}), 400


@app.errorhandler(requests.RequestException)
def on_net_error(e):
    return jsonify({"error": f"通信エラー: {e}"}), 502


@app.errorhandler(Exception)
def on_error(e):
    if hasattr(e, "code") and hasattr(e, "description"):     # werkzeug の HTTPException
        return jsonify({"error": e.description}), e.code
    traceback.print_exc()
    return jsonify({"error": f"内部エラー: {type(e).__name__}: {e}"}), 500


@app.get("/")
def index():
    return Response(PAGE, mimetype="text/html")


# ═══════════════════════════════════════════════
#  アイコン（ホーム画面に追加できるように）
#  Pillow なしで動かしたいので、値札の形を距離関数で直接 PNG に描く
# ═══════════════════════════════════════════════
ICON_BG = (11, 99, 206)
APP_NAME = "eBay 出品アシスタント"
APP_SHORT = "eBay出品"
# 値札（左向き）。回転前の座標で、中心 (0,0)・アイコン全体を 1 とした大きさ
TAG_POLY = [(-0.30, 0.0), (-0.13, -0.17), (0.27, -0.17), (0.27, 0.17), (-0.13, 0.17)]
TAG_ROUND = 0.03
TAG_HOLE = (-0.115, 0.0, 0.045)
TAG_ANGLE = -45
TAG_SHIFT = (-0.047, 0.047)             # 回転後の見た目の重心を中央に寄せる


def _sd_poly(x, y, pts):
    d = (x - pts[0][0]) ** 2 + (y - pts[0][1]) ** 2
    inside = False
    for i in range(len(pts)):
        ax, ay = pts[i - 1]
        bx, by = pts[i]
        ex, ey, wx, wy = bx - ax, by - ay, x - ax, y - ay
        t = max(0.0, min(1.0, (wx * ex + wy * ey) / (ex * ex + ey * ey)))
        qx, qy = wx - ex * t, wy - ey * t
        d = min(d, qx * qx + qy * qy)
        if (ay > y) != (by > y) and x < ax + (y - ay) * ex / ey:
            inside = not inside
    return -math.sqrt(d) if inside else math.sqrt(d)


def _png(w, h, rows):
    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data))
    raw = b"".join(b"\x00" + r for r in rows)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


@functools.lru_cache(maxsize=8)
def icon_png(size, rounded=True):
    """rounded=False は全面塗り（maskable / iOS 用。角丸は OS 側が付ける）。"""
    a = math.radians(TAG_ANGLE)
    ca, sa = math.cos(a), math.sin(a)
    hx, hy, hr = TAG_HOLE
    corner = 0.22
    lim = 0.36                          # 値札が収まる範囲。外は距離計算を省く
    rows = []
    for py in range(size):
        row = bytearray()
        y = (py + 0.5) / size - 0.5
        for px in range(size):
            x = (px + 0.5) / size - 0.5
            # 背景（角丸の四角）
            if rounded:
                qx, qy = abs(x) - (0.5 - corner), abs(y) - (0.5 - corner)
                db = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - corner
                alpha = max(0.0, min(1.0, 0.5 - db * size))
            else:
                alpha = 1.0
            cov = 0.0
            tx, ty = x - TAG_SHIFT[0], y - TAG_SHIFT[1]
            if alpha > 0 and abs(tx) < lim and abs(ty) < lim:
                # 値札の座標系へ戻す（回転の逆）
                lx, ly = tx * ca + ty * sa, -tx * sa + ty * ca
                dt = _sd_poly(lx, ly, TAG_POLY) - TAG_ROUND
                dh = hr - math.hypot(lx - hx, ly - hy)      # 穴は差し引く
                cov = max(0.0, min(1.0, 0.5 - max(dt, dh) * size))
            r, g, b = (round(c + (255 - c) * cov) for c in ICON_BG)
            row += bytes((r, g, b, round(alpha * 255)))
        rows.append(bytes(row))
    return _png(size, size, rows)


def icon_svg():
    pts = " ".join(f"{50 + x * 100:.1f},{50 + y * 100:.1f}" for x, y in TAG_POLY)
    hx, hy, hr = TAG_HOLE
    bg = "#%02x%02x%02x" % ICON_BG
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
            f'<rect width="100" height="100" rx="22" fill="{bg}"/>'
            f'<g transform="translate({TAG_SHIFT[0] * 100:.1f} {TAG_SHIFT[1] * 100:.1f}) rotate({TAG_ANGLE} 50 50)">'
            f'<polygon points="{pts}" fill="#fff" stroke="#fff" stroke-width="{TAG_ROUND * 200:.0f}" stroke-linejoin="round"/>'
            f'<circle cx="{50 + hx * 100:.1f}" cy="{50 + hy * 100:.1f}" r="{hr * 100:.1f}" fill="{bg}"/>'
            f'</g></svg>')


MANIFEST = {
    "name": APP_NAME,
    "short_name": APP_SHORT,
    "start_url": "/",
    "scope": "/",
    "display": "standalone",
    "background_color": "#f5f6f8",
    "theme_color": "#%02x%02x%02x" % ICON_BG,
    "lang": "ja",
    "icons": [
        {"src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
        {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
        {"src": "/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
        {"src": "/favicon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any"},
    ],
}

# 通信は素通しするだけ。古い Chrome が「インストール」を出す条件を満たすために置く
SERVICE_WORKER = "self.addEventListener('install',()=>self.skipWaiting());\n" \
                 "self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));\n" \
                 "self.addEventListener('fetch',()=>{});\n"


def _cached(body, mimetype):
    r = Response(body, mimetype=mimetype)
    r.headers["Cache-Control"] = "public, max-age=86400"
    return r


@app.get("/manifest.webmanifest")
def manifest():
    return _cached(json.dumps(MANIFEST, ensure_ascii=False), "application/manifest+json")


@app.get("/favicon.svg")
def favicon_svg():
    return _cached(icon_svg(), "image/svg+xml")


@app.get("/favicon.ico")
def favicon_ico():
    return _cached(icon_png(48), "image/png")


@app.get("/icon-<int:size>.png")
def icon(size):
    if size not in (192, 512):
        abort(404)
    return _cached(icon_png(size), "image/png")


@app.get("/icon-maskable-512.png")
def icon_maskable():
    return _cached(icon_png(512, rounded=False), "image/png")


@app.get("/apple-touch-icon.png")
def apple_touch_icon():
    return _cached(icon_png(180, rounded=False), "image/png")


@app.get("/sw.js")
def service_worker():
    r = Response(SERVICE_WORKER, mimetype="text/javascript")
    r.headers["Cache-Control"] = "no-cache"
    return r


@app.get("/api/meta")
def api_meta():
    return jsonify({"conditions": CONDITIONS, "marketplaces": list(MARKETPLACES)})


@app.get("/api/config")
def api_config():
    return jsonify(public_conf(load_conf()))


@app.post("/api/config")
def api_config_save():
    d = body_json()
    conf = load_conf()
    for k, default in DEFAULT_CONF.items():
        if k not in d or k in ("refresh_token", "refresh_token_expires"):
            continue
        v = d[k]
        if k in SECRET_KEYS and v in ("", None):
            continue                         # 空欄は「変更しない」
        if isinstance(default, bool):
            v = bool(v)
        elif isinstance(default, float):
            try:
                v = float(v)
            except (TypeError, ValueError):
                raise AppError(f"{k} は数値で入力してください")
        else:
            v = str(v).strip()
        conf[k] = v
    if conf["marketplace_id"] not in MARKETPLACES:
        raise AppError("未対応のマーケットプレイスです")
    if conf["ebay_env"] not in ("production", "sandbox"):
        raise AppError("ebay_env は production か sandbox です")
    save_conf(conf)
    return jsonify(public_conf(conf))


@app.post("/api/config/clear_secret")
def api_clear_secret():
    k = body_json().get("key")
    if k not in SECRET_KEYS:
        raise AppError("不明な項目です")
    conf = load_conf()
    conf[k] = ""
    if k == "refresh_token":
        conf["refresh_token_expires"] = ""
    save_conf(conf)
    return jsonify(public_conf(conf))


@app.get("/api/ebay/auth_url")
def api_auth_url():
    OAUTH_STATE["value"] = secrets.token_urlsafe(16)
    return jsonify({"url": EBAY.auth_url(load_conf(), OAUTH_STATE["value"])})


@app.post("/api/ebay/auth_code")
def api_auth_code():
    """同意後にリダイレクトされた URL（または code だけ）を受け取ってトークンに交換する。"""
    raw = (body_json().get("code") or "").strip()
    if not raw:
        raise AppError("リダイレクト後の URL か code を貼り付けてください")
    code = raw
    if "://" in raw or "code=" in raw:
        q = parse_qs(urlparse(raw).query or raw.split("?", 1)[-1])
        code = (q.get("code") or [""])[0]
        state = (q.get("state") or [""])[0]
        if state and OAUTH_STATE["value"] and state != OAUTH_STATE["value"]:
            raise AppError("state が一致しません。もう一度「eBay と連携」からやり直してください")
    if not code:
        raise AppError("URL に code が含まれていません")
    conf = load_conf()
    j = EBAY.exchange_code(conf, code)
    conf["refresh_token"] = j.get("refresh_token", "")
    exp = j.get("refresh_token_expires_in")
    conf["refresh_token_expires"] = (
        datetime.fromtimestamp(time.time() + int(exp)).strftime("%Y-%m-%d") if exp else "")
    save_conf(conf)
    OAUTH_STATE["value"] = None
    return jsonify(public_conf(conf))


def explain_ebay_error(msg):
    """よくある失敗に日本語の対処を添える。"""
    low = msg.lower()
    if "invalid_client" in low or "client authentication failed" in low:
        return msg + "\n→ App ID / Cert ID が違うか、本番と Sandbox の取り違えです。" \
                     "本番キーは「アカウント削除通知」の設定（手順②）が済むまで使えません"
    if "invalid_grant" in low:
        return msg + "\n→ 連携の有効期限切れか取り消しです。手順⑤の「eBay と連携」をやり直してください"
    if "invalid_scope" in low:
        return msg + "\n→ このキーには出品の権限がありません。キーの環境（本番/Sandbox）を確認してください"
    if "20403" in low or "not eligible for business policy" in low or "business polic" in low:
        return msg + "\n→ ビジネスポリシーが有効になっていません（手順⑥）"
    return msg


ANTHROPIC_KEY_RE = re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}")


@app.post("/api/anthropic/key")
def api_anthropic_key():
    """貼り付けられた文字からキーを拾い、使えることを確かめてから保存する。"""
    m = ANTHROPIC_KEY_RE.search(body_json().get("key") or "")
    if not m:
        raise AppError("キーが見つかりません。「sk-ant-」で始まる文字列をまるごと貼ってください")
    check_anthropic_key(m.group(0))
    conf = load_conf()
    conf["anthropic_api_key"] = m.group(0)
    save_conf(conf)
    return jsonify(public_conf(conf))


@app.post("/api/ebay/test")
def api_ebay_test():
    """設定を順に確かめ、どこで止まっているかを返す。"""
    conf = load_conf()
    out = []

    def step(label, fn):
        try:
            out.append({"ok": True, "label": label, "detail": fn() or ""})
            return True
        except AppError as e:
            out.append({"ok": False, "label": label, "detail": explain_ebay_error(str(e))})
            return False
        except requests.RequestException:
            out.append({"ok": False, "label": label,
                        "detail": "eBay に接続できません。インターネット接続を確認してください"})
            return False

    def anthropic_key():
        if not (conf.get("anthropic_api_key") or os.environ.get("ANTHROPIC_API_KEY")):
            raise AppError("未設定です（「Anthropic API かんたん登録」）")
        check_anthropic_key(conf.get("anthropic_api_key"))
        return "有効です"

    def app_keys():
        EBAY.token(conf, "app")
        return "App ID / Cert ID は有効です（%s）" % ("Sandbox" if conf["ebay_env"] == "sandbox" else "本番")

    def ru_name():
        if not conf.get("ru_name"):
            raise AppError("RuName が未設定です（手順④）")
        return conf["ru_name"]

    def policies():
        p = EBAY.policies(conf)
        counts = {k: len(p[k]) for k in ("fulfillment", "payment", "return", "location")}
        missing = [n for k, n in (("fulfillment", "送料"), ("payment", "支払"), ("return", "返品"),
                                  ("location", "発送元")) if not counts[k]]
        if missing:
            raise AppError("eBay 側に %s がまだありません" % "・".join(missing))
        return "送料 %(fulfillment)d・支払 %(payment)d・返品 %(return)d・発送元 %(location)d 件" % counts

    def selected():
        miss = [n for k, n in (("fulfillment_policy_id", "送料"), ("payment_policy_id", "支払"),
                               ("return_policy_id", "返品"), ("merchant_location_key", "発送元"))
                if not conf.get(k)]
        if miss:
            raise AppError("%s を選んで保存してください（出品ポリシーと発送元の欄）" % "・".join(miss))
        return "選択済み"

    step("Anthropic API キー", anthropic_key)
    if step("eBay のキー（App ID / Cert ID）", app_keys):
        step("RuName", ru_name)
        if step("eBay アカウント連携", lambda: (EBAY.token(conf, "user"), "連携済み")[1]):
            if step("ビジネスポリシーと発送元", policies):
                step("出品に使うポリシーの選択", selected)
    return jsonify({"steps": out, "ok": all(s["ok"] for s in out) and len(out) == 6})


SHIPPING_POLICY_URL = "https://www.bizpolicy.ebay.com/businesspolicy/manage"


@app.post("/api/ebay/auto_setup")
def api_auto_setup():
    """連携後の出品準備をまとめて行う。何度呼んでもよい（あるものは作らず選ぶだけ）。

    送料ポリシーだけは送料の決定が要るので作らず、あれば選ぶ。
    """
    d = body_json()
    conf = load_conf()
    steps = []
    try:
        programs = EBAY.opted_in_programs(conf)
    except EbayError:
        programs = []
    if "SELLING_POLICY_MANAGEMENT" not in programs:
        try:
            EBAY.opt_in(conf, "SELLING_POLICY_MANAGEMENT")
            steps.append("ビジネスポリシーを有効化しました")
        except EbayError as e:
            if "already" not in str(e).lower():
                raise AppError(explain_ebay_error(str(e)))
    try:
        pol = EBAY.policies(conf)
    except EbayError as e:
        raise AppError(explain_ebay_error(str(e)) +
                       "\n（有効化した直後は反映まで数分かかることがあります。少し待ってもう一度お試しください）")
    if not pol["payment"]:
        pid = EBAY.create_policy(conf, "payment", {"name": "AI Lister Payment", "immediatePay": True})
        pol["payment"] = [{"id": pid, "name": "AI Lister Payment"}]
        steps.append("支払ポリシーを作成しました（即時支払い）")
    if not pol["return"]:
        rid = EBAY.create_policy(conf, "return", {
            "name": "AI Lister Returns 30 days", "returnsAccepted": True,
            "returnPeriod": {"value": 30, "unit": "DAY"}, "returnShippingCostPayer": "BUYER"})
        pol["return"] = [{"id": rid, "name": "AI Lister Returns 30 days"}]
        steps.append("返品ポリシーを作成しました（30 日以内・返送料は購入者負担）")
    zip_code = re.sub(r"[^0-9-]", "", str(d.get("postal_code") or ""))
    if not pol["location"] and zip_code:
        EBAY.create_location(conf, "JP-HOME", {"postalCode": zip_code, "country": "JP"})
        pol["location"] = [{"id": "JP-HOME", "name": "JP-HOME"}]
        steps.append(f"発送元（〒{zip_code}）を登録しました")
    for key, items in (("fulfillment_policy_id", pol["fulfillment"]), ("payment_policy_id", pol["payment"]),
                       ("return_policy_id", pol["return"]), ("merchant_location_key", pol["location"])):
        ids = [i["id"] for i in items]
        if conf.get(key) not in ids:
            conf[key] = ids[0] if ids else ""
    save_conf(conf)
    return jsonify({"steps": steps, "policies": pol, "config": public_conf(conf),
                    "need_shipping": not pol["fulfillment"], "need_postal": not pol["location"],
                    "shipping_url": SHIPPING_POLICY_URL})


@app.get("/api/ebay/policies")
def api_policies():
    return jsonify(EBAY.policies(load_conf()))


@app.post("/api/ebay/location")
def api_location():
    d = body_json()
    key = re.sub(r"[^A-Za-z0-9_-]", "", d.get("key") or "")[:36]
    if not key:
        raise AppError("発送元キーは英数字で入力してください")
    addr = {k: str(d[k]).strip() for k in ("postalCode", "city", "stateOrProvince", "addressLine1")
            if d.get(k)}
    addr["country"] = (d.get("country") or "JP").upper()
    if not (addr.get("postalCode") or addr.get("city")):
        raise AppError("郵便番号か市区町村を入力してください")
    conf = load_conf()
    EBAY.create_location(conf, key, addr)
    conf["merchant_location_key"] = key
    save_conf(conf)
    return jsonify({"ok": True, "key": key})


@app.post("/api/identify")
def api_identify():
    conf = load_conf()
    images = read_images()
    hint = (request.form.get("hint") or "").strip()[:500]
    ebay_hints = []
    if conf.get("client_id") and conf.get("client_secret") and images[0][1] == "image/jpeg":
        try:
            ebay_hints = EBAY.search_by_image(conf, images[0][0])
        except (AppError, requests.RequestException):
            pass                              # 画像検索は補助。使えなくても続ける
    notes, sources = research_product(conf, images, hint, ebay_hints)
    listing = draft_listing(conf, images, notes, hint)
    did = put_draft({"images": images, "notes": notes, "sources": sources,
                     "listing": listing, "hint": hint})
    cats = []
    if conf.get("client_id") and conf.get("client_secret"):
        try:
            cats = EBAY.category_suggestions(conf, listing.get("search_query") or listing["ebay_title"])
        except (AppError, requests.RequestException) as e:
            cats = []
            listing["notes_ja"] = (listing.get("notes_ja", "") + f"\n（カテゴリ候補を取得できませんでした: {e}）").strip()
    return jsonify({"draft_id": did, "listing": listing, "notes": notes,
                    "sources": sources, "ebay_hints": ebay_hints, "categories": cats,
                    "sku": "AI-" + datetime.now().strftime("%y%m%d%H%M%S")})


@app.post("/api/categories")
def api_categories():
    q = (body_json().get("q") or "").strip()
    if not q:
        raise AppError("キーワードを入力してください")
    return jsonify(EBAY.category_suggestions(load_conf(), q))


@app.post("/api/aspects")
def api_aspects():
    d = body_json()
    cid = str(d.get("category_id") or "").strip()
    if not cid.isdigit():
        raise AppError("カテゴリ ID を指定してください")
    conf = load_conf()
    aspects = EBAY.item_aspects(conf, cid)
    filled, missing = [], []
    if d.get("draft_id"):
        dr = get_draft(d["draft_id"])
        product = dict(dr["listing"])
        product.pop("description_html", None)
        filled, missing = fill_aspects(conf, product, dr["notes"], aspects)
    return jsonify({"aspects": [{k: a[k] for k in ("name", "required", "mode", "multi")}
                                | {"values": a["values"][:200]} for a in aspects],
                    "filled": filled, "missing": missing})


@app.post("/api/research")
def api_research():
    q = (body_json().get("q") or "").strip()
    if not q:
        raise AppError("キーワードを入力してください")
    return jsonify(research_prices(load_conf(), q[:200]))


def _clean_aspects(rows):
    out = {}
    for r in rows or []:
        name = str(r.get("name") or "").strip()[:65]
        val = str(r.get("value") or "").strip()
        if not name or not val:
            continue
        vals = [v.strip()[:65] for v in val.split(",") if v.strip()] if r.get("multi") else [val[:65]]
        out.setdefault(name, []).extend(vals)
    return out


@app.post("/api/list")
def api_list():
    d = body_json()
    conf = load_conf()
    dr = get_draft(d.get("draft_id"))
    sku = re.sub(r"[^A-Za-z0-9_-]", "", d.get("sku") or "")[:50]
    title = (d.get("title") or "").strip()
    desc = (d.get("description") or "").strip()
    cond = d.get("condition")
    cid = str(d.get("category_id") or "").strip()
    try:
        price = round(float(d.get("price")), 2)
        qty = int(d.get("quantity") or 1)
    except (TypeError, ValueError):
        raise AppError("価格と数量を数値で入力してください")
    problems = []
    if not sku:
        problems.append("SKU")
    if not title or len(title) > 80:
        problems.append("タイトル（1〜80 文字）")
    if not desc:
        problems.append("説明")
    if cond not in CONDITION_KEYS:
        problems.append("状態")
    if not cid.isdigit():
        problems.append("カテゴリ")
    if price <= 0 or qty <= 0:
        problems.append("価格・数量")
    for k, label in (("fulfillment_policy_id", "送料ポリシー"), ("payment_policy_id", "支払ポリシー"),
                     ("return_policy_id", "返品ポリシー"), ("merchant_location_key", "発送元")):
        if not conf.get(k):
            problems.append(label + "（設定タブ）")
    if problems:
        raise AppError("入力が足りません: " + "、".join(problems))

    idx = d.get("image_indexes")
    imgs = dr["images"] if not isinstance(idx, list) else \
        [dr["images"][i] for i in idx if isinstance(i, int) and 0 <= i < len(dr["images"])]
    if not imgs:
        raise AppError("出品に使う画像を 1 枚以上選んでください")
    steps = []
    if dr.get("uploaded_sig") == [len(b) for b, _ in imgs] and dr.get("image_urls"):
        urls = dr["image_urls"]                 # 再送信時は上げ直さない
    else:
        urls = [EBAY.upload_image(conf, b, m) for b, m in imgs]
        dr["image_urls"], dr["uploaded_sig"] = urls, [len(b) for b, _ in imgs]
    steps.append(f"画像 {len(urls)} 枚をアップロード")

    product = {"title": title, "description": desc, "imageUrls": urls,
               "aspects": _clean_aspects(d.get("aspects"))}
    listing = dr["listing"]
    if listing.get("brand"):
        product["brand"] = listing["brand"][:65]
    if listing.get("model_number"):
        product["mpn"] = listing["model_number"][:65]
    jan = re.sub(r"\D", "", listing.get("jan_code") or "")
    if len(jan) == 13:
        product["ean"] = [jan]
    elif len(jan) == 12:
        product["upc"] = [jan]
    item = {"product": product, "condition": cond,
            "availability": {"shipToLocationAvailability": {"quantity": qty}}}
    note = (d.get("condition_notes") or "").strip()
    if note and not cond.startswith("NEW"):
        item["conditionDescription"] = note[:1000]
    EBAY.put_inventory_item(conf, sku, item)
    steps.append(f"在庫アイテム {sku} を登録")

    offer = {"sku": sku, "marketplaceId": conf["marketplace_id"], "format": "FIXED_PRICE",
             "availableQuantity": qty, "categoryId": cid, "listingDescription": desc,
             "merchantLocationKey": conf["merchant_location_key"],
             "listingPolicies": {"fulfillmentPolicyId": conf["fulfillment_policy_id"],
                                 "paymentPolicyId": conf["payment_policy_id"],
                                 "returnPolicyId": conf["return_policy_id"]},
             "pricingSummary": {"price": {"value": f"{price:.2f}",
                                          "currency": MARKETPLACES[conf["marketplace_id"]][0]}}}
    offer_id = EBAY.upsert_offer(conf, offer)
    steps.append(f"オファー {offer_id} を作成（未公開）")
    out = {"sku": sku, "offer_id": offer_id, "steps": steps,
           "seller_hub": EBAY.web_host(conf) + "/sh/lst/drafts"}
    if d.get("publish"):
        lid = EBAY.publish_offer(conf, offer_id)
        steps.append(f"出品しました（Item ID {lid}）")
        out["listing_id"] = lid
        out["url"] = f"{EBAY.web_host(conf)}/itm/{lid}"
    return jsonify(out)


# ═══════════════════════════════════════════════
#  画面
# ═══════════════════════════════════════════════
PAGE = r"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>eBay 出品アシスタント</title>
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<meta name="theme-color" content="#0b63ce">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="eBay出品">
<style>
:root{--bg:#f5f6f8;--card:#fff;--fg:#1d2330;--sub:#667085;--line:#e3e6eb;--acc:#0b63ce;--ok:#15803d;--ng:#c62828;--warn:#b45309}
@media (prefers-color-scheme:dark){:root{--bg:#12151b;--card:#1b2029;--fg:#e6e9ef;--sub:#98a2b3;--line:#2c3340;--acc:#5aa2ff;--ok:#4ade80;--ng:#f87171;--warn:#fbbf24}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.6 -apple-system,"Hiragino Sans","Noto Sans JP",sans-serif}
header{position:sticky;top:0;z-index:5;background:var(--card);border-bottom:1px solid var(--line)}
header h1{font-size:16px;margin:0;padding:10px 16px 0}
nav{display:flex;gap:4px;padding:6px 12px 0}
nav button{flex:1;border:0;background:none;color:var(--sub);padding:8px;border-bottom:3px solid transparent;font-size:15px}
nav button.on{color:var(--acc);border-bottom-color:var(--acc);font-weight:600}
main{max-width:860px;margin:0 auto;padding:12px 16px 80px}
section{display:none}section.on{display:block}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px;margin:0 0 12px}
.card h2{font-size:15px;margin:0 0 10px}
label{display:block;font-size:13px;color:var(--sub);margin:10px 0 3px}
input,select,textarea{width:100%;padding:8px 10px;border:1px solid var(--line);border-radius:7px;background:var(--bg);color:var(--fg);font:inherit}
textarea{min-height:90px}
a.b{text-decoration:none;display:inline-block}
.b{border:0;border-radius:8px;padding:10px 14px;background:var(--acc);color:#fff;font:inherit;font-weight:600;cursor:pointer}
.b.sub{background:var(--line);color:var(--fg)}
.b.ok{background:var(--ok)}
.b:disabled{opacity:.5;cursor:default}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:flex-end}.row>*{flex:1;min-width:120px}
.row>.shrink{flex:0 0 auto}
.muted{color:var(--sub);font-size:13px}
.err{color:var(--ng);white-space:pre-wrap}.okt{color:var(--ok)}
.thumbs{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
.thumbs label{margin:0;position:relative}
.thumbs img{width:84px;height:84px;object-fit:cover;border-radius:8px;border:2px solid var(--line);display:block}
.thumbs input{position:absolute;top:4px;left:4px;width:auto}
.pill{display:inline-block;padding:1px 8px;border-radius:99px;font-size:12px;background:var(--line)}
.pill.high{background:#dcfce7;color:#166534}.pill.medium{background:#fef3c7;color:#92400e}.pill.low{background:#fee2e2;color:#991b1b}
table{width:100%;border-collapse:collapse;font-size:14px}
td,th{border-bottom:1px solid var(--line);padding:5px 4px;text-align:left;vertical-align:top}
td input{padding:5px 7px}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:8px;margin:8px 0}
.stat{background:var(--bg);border-radius:8px;padding:8px}.stat b{display:block;font-size:18px}
.stat small{color:var(--sub)}
.spin{display:inline-block;width:14px;height:14px;border:2px solid var(--line);border-top-color:var(--acc);border-radius:50%;animation:s 1s linear infinite;vertical-align:-2px;margin-right:6px}
@keyframes s{to{transform:rotate(360deg)}}
details summary{cursor:pointer;color:var(--acc)}
pre{white-space:pre-wrap;font-size:13px;background:var(--bg);padding:8px;border-radius:7px;max-height:300px;overflow:auto}
a{color:var(--acc)}
.req{color:var(--ng);font-weight:700}
.steps{padding-left:0;list-style:none;counter-reset:st;margin:10px 0}
.steps>li{counter-increment:st;position:relative;padding:10px 0 10px 40px;border-top:1px solid var(--line)}
.steps>li::before{content:counter(st);position:absolute;left:0;top:10px;width:28px;height:28px;border-radius:50%;background:var(--line);text-align:center;line-height:28px;font-weight:700}
.steps>li.done::before{content:"✅";background:none}
.steps .lnk{display:inline-block;margin-top:6px;text-decoration:none;padding:7px 12px}
.copyrow{display:flex;gap:8px;align-items:center;margin:6px 0}.copyrow code{background:var(--bg);padding:4px 8px;border-radius:6px}
.copyrow .cp{padding:4px 10px}
.tres{list-style:none;padding:0;margin:0}.tres li{padding:4px 0;white-space:pre-wrap}
</style></head><body>
<header><h1>eBay 出品アシスタント</h1>
<nav><button data-tab="list" class="on">出品</button><button data-tab="research">相場リサーチ</button><button data-tab="settings">設定</button></nav>
</header>
<main>

<section id="tab-list" class="on">
  <div class="card">
    <h2>1. 商品写真</h2>
    <input type="file" id="files" accept="image/*" multiple>
    <div class="muted">正面・型番ラベル・バーコード・付属品・傷の写真があると精度が上がります（最大 12 枚。1 枚目がメイン画像）</div>
    <div class="thumbs" id="thumbs"></div>
    <label>ヒント（任意）</label>
    <input id="hint" placeholder="例: 箱なし、動作確認済み / ポケモンカード 1996年版">
    <div style="margin-top:10px"><button class="b" id="btnIdentify">商品を特定して出品文を作る</button>
    <span id="idStat" class="muted"></span></div>
  </div>

  <div id="draft" style="display:none">
    <div class="card">
      <h2>2. 特定結果 <span id="conf" class="pill"></span></h2>
      <div id="prod"></div>
      <div id="notesJa" class="muted" style="white-space:pre-wrap;margin-top:6px"></div>
      <details style="margin-top:8px"><summary>調査メモと情報源</summary>
        <pre id="notes"></pre><ul id="sources"></ul></details>
    </div>

    <div class="card">
      <h2>3. 出品内容</h2>
      <label>タイトル <span id="tlen" class="muted"></span></label>
      <input id="title" maxlength="80">
      <div class="row">
        <div><label>状態</label><select id="condition"></select></div>
        <div><label>SKU</label><input id="sku"></div>
      </div>
      <label>状態の説明（中古のみ eBay に送信）</label>
      <input id="condNotes">
      <label>説明（HTML）</label>
      <textarea id="desc" style="min-height:180px"></textarea>
      <details><summary>説明のプレビュー</summary><div id="descPrev" class="card" style="margin-top:6px"></div></details>
    </div>

    <div class="card">
      <h2>4. カテゴリと Item Specifics</h2>
      <div class="row">
        <div><label>カテゴリ候補</label><select id="catSel"></select></div>
        <div class="shrink"><label>&nbsp;</label><button class="b sub" id="btnCatSearch">再検索</button></div>
      </div>
      <label>カテゴリ ID</label><input id="catId" inputmode="numeric">
      <div style="margin-top:10px"><button class="b sub" id="btnAspects">このカテゴリの項目を AI で埋める</button>
        <span id="aspStat" class="muted"></span></div>
      <table style="margin-top:10px"><thead><tr><th style="width:40%">項目</th><th>値</th><th></th></tr></thead>
        <tbody id="aspects"></tbody></table>
      <button class="b sub" id="btnAddAsp" style="margin-top:6px">＋ 行を追加</button>
    </div>

    <div class="card">
      <h2>5. 価格</h2>
      <div class="row">
        <div><label>価格（<span class="cur">USD</span>）</label><input id="price" inputmode="decimal"></div>
        <div><label>数量</label><input id="qty" value="1" inputmode="numeric"></div>
        <div class="shrink"><label>&nbsp;</label><button class="b sub" id="btnPrice">相場を調べる</button></div>
      </div>
      <div id="priceBox"></div>
    </div>

    <div class="card">
      <h2>6. eBay へ送信</h2>
      <div class="muted">「下書き登録」は非公開のオファーを作るだけです。内容を確認してから「出品する」を押してください。</div>
      <div class="row" style="margin-top:10px">
        <button class="b sub" id="btnDraft">下書き登録（非公開）</button>
        <button class="b ok" id="btnPublish">出品する</button>
      </div>
      <div id="listResult" style="margin-top:10px"></div>
    </div>
  </div>
</section>

<section id="tab-research">
  <div class="card">
    <h2>落札相場を調べる</h2>
    <div class="row"><input id="rq" placeholder="英語キーワード（例: Nintendo Switch OLED Japan）">
      <button class="b shrink" id="btnResearch">調べる</button></div>
  </div>
  <div id="researchBox"></div>
</section>

<section id="tab-settings">
  <div class="card">
    <h2>Anthropic API かんたん登録 <span id="akState" class="muted"></span></h2>
    <div class="muted">写真から商品を調べて出品文を作る AI（Claude）の鍵です。Claude.ai の有料プランとは別の契約で、使った分だけ料金がかかります。</div>
    <ol class="steps">
      <li id="as1"><b>Anthropic のアカウントを作る</b>
        <div class="muted">メールアドレスか Google アカウントで登録します。</div>
        <a class="b sub lnk" href="https://console.anthropic.com/" target="_blank" rel="noopener">登録ページを開く</a></li>
      <li id="as2"><b>クレジットを買う</b>
        <div class="muted">前払いです。まずは少額で十分です。自動チャージはオフのままにしておくと使いすぎを防げます。</div>
        <a class="b sub lnk" href="https://console.anthropic.com/settings/billing" target="_blank" rel="noopener">支払いページを開く</a></li>
      <li id="as3"><b>API キーを作る</b>
        <div class="muted">「Create Key」を押し、名前（例: ebay）を付けて作ります。表示されたキーは<b>一度しか見られない</b>ので、すぐコピーしてください。</div>
        <a class="b sub lnk" href="https://console.anthropic.com/settings/keys" target="_blank" rel="noopener">キーのページを開く</a></li>
      <li id="as4"><b>キーを貼って確認する</b>
        <div class="muted">貼ったキーが使えるかを Anthropic に問い合わせてから保存します（確認に料金はかかりません）。</div>
        <div class="row" style="margin-top:6px"><input id="anthropic_api_key" type="password" placeholder="sk-ant-api03-..." autocomplete="off">
          <button class="b sub shrink" id="btnAkSave">確認して保存</button></div>
        <span id="akStat" class="muted"></span></li>
    </ol>
    <div class="muted">料金の目安: 1 商品の調査で数十円程度（写真の枚数と Web 検索の回数で変わります）。使った額は
      <a href="https://console.anthropic.com/usage" target="_blank" rel="noopener">利用状況のページ</a>で確認できます。</div>
  </div>
  <div class="card" id="wizard">
    <h2>eBay API かんたん登録</h2>
    <div class="muted">上から順に進めてください。終わった手順には ✅ が付きます。英語のページは ブラウザの翻訳を使うと楽です。</div>
    <ol class="steps">
      <li id="st1"><b>eBay 開発者アカウントを作る</b>
        <div class="muted">いつもの eBay アカウントとは別の登録です（同じメールアドレスで可）。承認まで最大 1 営業日かかることがあります。</div>
        <a class="b sub lnk" href="https://developer.ebay.com/signin?tab=register" target="_blank" rel="noopener">登録ページを開く</a></li>
      <li id="st2"><b>「アカウント削除通知」を免除にする</b>
        <div class="muted">これをしないと本番のキーが使えません。開いたページの <i>Marketplace Account Deletion</i> で
          「Exempted from Marketplace Account Deletion」をオンにし、理由に「I do not persist eBay data」を選んで保存します。</div>
        <a class="b sub lnk" href="https://developer.ebay.com/my/push/" target="_blank" rel="noopener">通知設定を開く</a></li>
      <li id="st3"><b>キーを作って貼り付ける</b>
        <div class="muted">開いたページの <i>Production</i> で「Create a keyset」を押し、表示されたキーの欄を<b>まとめてコピー</b>して下に貼ります。App ID と Cert ID を自動で見つけます。</div>
        <a class="b sub lnk" href="https://developer.ebay.com/my/keys" target="_blank" rel="noopener">キーのページを開く</a>
        <textarea id="keyPaste" placeholder="App ID (Client ID)  Tekkan-ebaylist-PRD-1a2b3c4d5-6e7f8a9b&#10;Dev ID  ...&#10;Cert ID (Client Secret)  PRD-1a2b3c4d5e6f-..." style="min-height:70px;margin-top:6px"></textarea>
        <button class="b sub" id="btnKeyPaste" style="margin-top:6px">読み取って保存</button> <span id="keyStat" class="muted"></span></li>
      <li id="st4"><b>RuName（戻り先の名前）を作る</b>
        <div class="muted">開いたページで「Get a Token from eBay via Your Application」→「Add eBay Redirect URL」を押し、次の 3 か所に同じ URL を入れて保存します。</div>
        <div class="copyrow"><code>https://example.com/</code><button class="b sub cp" data-copy="https://example.com/">コピー</button></div>
        <div class="muted">（Your privacy policy URL / Your auth accepted URL / Your auth declined URL）<br>
          保存後に表示される <i>RuName (eBay Redirect URL name)</i> の値をコピーして貼ります。</div>
        <a class="b sub lnk" href="https://developer.ebay.com/my/auth/?env=production&amp;index=0" target="_blank" rel="noopener">User Tokens ページを開く</a>
        <div class="row" style="margin-top:6px"><input id="ruPaste" placeholder="例: Tekkan_Sumo-TekkanSu-ebayli-abcde">
          <button class="b sub shrink" id="btnRuPaste">保存</button></div> <span id="ruStat" class="muted"></span></li>
      <li id="st5"><b>eBay アカウントと連携する</b>
        <div class="muted">下の「eBay アカウント連携」で「eBay と連携」を押し、同意後に移動した example.com のページの URL を丸ごと貼ります。</div></li>
      <li id="st6"><b>出品の準備を自動で行う</b>
        <div class="muted">ビジネスポリシーの有効化、支払・返品ポリシーの作成、発送元の登録、選択までまとめて行います。
          発送元の郵便番号だけ入れてください。送料ポリシーは送料を決める必要があるので、eBay で 1 つ作ってください（あれば自動で選びます）。</div>
        <div class="row" style="margin-top:6px"><input id="autoZip" placeholder="発送元の郵便番号（例: 150-0001）" inputmode="numeric">
          <button class="b shrink" id="btnAutoSetup2">自動で設定する</button></div>
        <div id="autoStat" class="muted"></div></li>
    </ol>
    <button class="b ok" id="btnAutoSetup" style="display:none;margin-bottom:8px">アプリ内で自動セットアップ（ログインだけで登録）</button>
    <button class="b" id="btnTest">接続テスト</button>
    <div id="testBox" style="margin-top:8px"></div>
  </div>
  <div class="card">
    <h2>eBay 開発者キー</h2>
    <div class="muted">「かんたん登録」で貼り付けると自動で入ります。手で直す場合はここで。</div>
    <div class="row">
      <div><label>環境</label><select id="ebay_env"><option value="production">本番</option><option value="sandbox">Sandbox（テスト）</option></select></div>
      <div><label>マーケットプレイス</label><select id="marketplace_id"></select></div>
    </div>
    <label>App ID (Client ID)</label><input id="client_id" autocomplete="off">
    <label>Cert ID (Client Secret) <span id="csState" class="muted"></span></label>
    <input id="client_secret" type="password" placeholder="空欄なら変更しない" autocomplete="off">
    <label>RuName（eBay Redirect URL name）</label><input id="ru_name" autocomplete="off">
    <div style="margin-top:10px"><button class="b" id="btnSave">保存</button> <span id="saveStat" class="muted"></span></div>
  </div>
  <div class="card">
    <h2>eBay アカウント連携</h2>
    <div id="linkState" class="muted"></div>
    <ol class="muted" style="padding-left:18px;margin:6px 0">
      <li>「eBay と連携」で開いた画面でログインし、同意する</li>
      <li>移動先ページのアドレスバーの URL を丸ごとコピーして下に貼る</li>
    </ol>
    <div class="row"><button class="b shrink" id="btnAuth">eBay と連携</button></div>
    <label>リダイレクト後の URL</label><input id="authCode" placeholder="https://...?code=v%5E1.1...&state=...">
    <div style="margin-top:8px"><button class="b sub" id="btnCode">連携を完了</button> <span id="authStat" class="muted"></span></div>
  </div>
  <div class="card">
    <h2>出品ポリシーと発送元</h2>
    <div class="muted">eBay のビジネスポリシー（送料・支払・返品）を事前に Seller Hub で作成してください。</div>
    <div style="margin:8px 0"><button class="b sub" id="btnPol">eBay から読み込む</button> <span id="polStat" class="muted"></span></div>
    <label>送料ポリシー</label><select id="fulfillment_policy_id"></select>
    <label>支払ポリシー</label><select id="payment_policy_id"></select>
    <label>返品ポリシー</label><select id="return_policy_id"></select>
    <label>発送元（Inventory Location）</label><select id="merchant_location_key"></select>
    <details style="margin-top:8px"><summary>発送元を新規作成</summary>
      <div class="row">
        <div><label>キー（英数字）</label><input id="locKey" value="JP-HOME"></div>
        <div><label>国</label><input id="locCountry" value="JP"></div>
      </div>
      <div class="row">
        <div><label>郵便番号</label><input id="locZip" placeholder="150-0001"></div>
        <div><label>都道府県（英字）</label><input id="locState" placeholder="Tokyo"></div>
        <div><label>市区町村（英字）</label><input id="locCity" placeholder="Shibuya-ku"></div>
      </div>
      <div style="margin-top:8px"><button class="b sub" id="btnLoc">作成</button> <span id="locStat" class="muted"></span></div>
    </details>
    <div style="margin-top:10px"><button class="b" id="btnSave2">保存</button> <span id="saveStat2" class="muted"></span></div>
  </div>
  <div class="card">
    <h2>相場</h2>
    <label>円換算レート（1 USD = ? 円）</label><input id="usd_jpy" inputmode="decimal">
    <label style="display:flex;gap:8px;align-items:center"><input type="checkbox" id="auto_draft" style="width:auto">
      商品を特定したら、項目の入力・相場調査・eBay への下書き登録（非公開）まで自動で進める</label>
    <label style="display:flex;gap:8px;align-items:center"><input type="checkbox" id="scrape_sold" style="width:auto">
      Insights API が使えないときは eBay の Sold 検索ページから読み取る</label>
    <div class="muted">落札履歴の公式 API（Marketplace Insights）は eBay の個別承認が必要です。ページ読み取りは eBay の利用規約上グレーで、構造変更やロボット判定で取れなくなることがあります。</div>
    <div style="margin-top:10px"><button class="b" id="btnSave3">保存</button> <span id="saveStat3" class="muted"></span></div>
  </div>
</section>
</main>

<script>
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let CONF={},META={},DRAFT=null,ASPDEF={},PHOTOS=[];

async function api(path,opt={}){
  const r=await fetch(path,opt);
  let j={};try{j=await r.json();}catch(e){}
  if(!r.ok) throw new Error(j.error||('HTTP '+r.status));
  return j;
}
const post=(p,b)=>api(p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});
function busy(btn,on,label){
  if(on){btn.dataset.l=btn.innerHTML;btn.innerHTML='<span class="spin"></span>'+(label||'処理中...');btn.disabled=true;}
  else{btn.innerHTML=btn.dataset.l||btn.innerHTML;btn.disabled=false;}
}
async function run(btn,fn,label,statEl){
  busy(btn,true,label);if(statEl)statEl.innerHTML='';
  try{return await fn();}
  catch(e){if(statEl)statEl.innerHTML='<span class="err">'+esc(e.message)+'</span>';else alert(e.message);}
  finally{busy(btn,false);}
}

document.querySelectorAll('nav button').forEach(b=>b.onclick=()=>{
  document.querySelectorAll('nav button').forEach(x=>x.classList.toggle('on',x===b));
  document.querySelectorAll('section').forEach(s=>s.classList.toggle('on',s.id==='tab-'+b.dataset.tab));
});

// ── 画像: ブラウザ側で長辺 1600px の JPEG に縮めてから送る（5MB 制限と通信量対策）──
function shrink(file){
  return new Promise((ok,ng)=>{
    const url=URL.createObjectURL(file),img=new Image();
    img.onload=()=>{
      const k=Math.min(1,1600/Math.max(img.width,img.height));
      const c=document.createElement('canvas');c.width=Math.round(img.width*k);c.height=Math.round(img.height*k);
      c.getContext('2d').drawImage(img,0,0,c.width,c.height);
      URL.revokeObjectURL(url);
      c.toBlob(b=>b?ok(b):ng(new Error('画像を変換できません')),'image/jpeg',0.9);
    };
    img.onerror=()=>{URL.revokeObjectURL(url);ng(new Error(file.name+' を読み込めません'));};
    img.src=url;
  });
}
$('#files').onchange=async e=>{
  const fs=[...e.target.files].slice(0,12);
  PHOTOS=[];$('#thumbs').innerHTML='';
  for(const f of fs){
    try{
      const b=await shrink(f);PHOTOS.push(b);
      const i=PHOTOS.length-1;
      $('#thumbs').insertAdjacentHTML('beforeend',
        `<label title="出品に使う"><input type="checkbox" class="useimg" data-i="${i}" checked><img src="${URL.createObjectURL(b)}"></label>`);
    }catch(err){alert(err.message);}
  }
};

$('#btnIdentify').onclick=()=>{
  if(!PHOTOS.length){alert('画像を選んでください');return;}
  const fd=new FormData();
  PHOTOS.forEach((b,i)=>fd.append('images',b,`photo${i+1}.jpg`));
  fd.append('hint',$('#hint').value);
  const t0=Date.now(),tick=setInterval(()=>{$('#idStat').textContent=` 画像を読み取り、Web で調べています... ${Math.round((Date.now()-t0)/1000)}秒`;},1000);
  run($('#btnIdentify'),async()=>{
    const j=await api('/api/identify',{method:'POST',body:fd});
    showDraft(j);$('#idStat').textContent='';
    return true;
  },'調査中...',$('#idStat')).finally(()=>clearInterval(tick)).then(ok=>{if(ok)autoChain();});
};

// 特定のあと、項目の入力 → 相場 → 下書き登録（非公開）まで自動で進める。公開だけは手で押す
async function autoChain(){
  if(!CONF.auto_draft)return;
  if($('#catId').value&&!await doAspects())return;
  if(!await doPrice())return;
  const ready=CONF.fulfillment_policy_id&&CONF.payment_policy_id&&CONF.return_policy_id&&CONF.merchant_location_key;
  if(!ready){$('#listResult').innerHTML='<span class="muted">設定タブで出品の準備を済ませると、ここで自動的に下書き登録します。</span>';return;}
  if(!$('#price').value){$('#listResult').innerHTML='<span class="muted">相場が取れなかったので価格を入れて「下書き登録」を押してください。</span>';return;}
  await sendListing(false);
  $('#btnPublish').scrollIntoView({behavior:'smooth',block:'center'});
}

function showDraft(j){
  DRAFT=j;const L=j.listing;
  $('#draft').style.display='';
  $('#conf').textContent={high:'確度 高',medium:'確度 中',low:'確度 低'}[L.confidence]||'';
  $('#conf').className='pill '+L.confidence;
  $('#prod').innerHTML=`<b>${esc(L.product_name_ja||L.product_name_en)}</b><br>
    <span class="muted">${esc(L.product_name_en)}</span><br>
    ブランド: ${esc(L.brand||'-')} / 型番: ${esc(L.model_number||'-')} / JAN: ${esc(L.jan_code||'-')}`
    +(L.identified?'':'<div class="err">商品を特定しきれませんでした。内容をよく確認してください。</div>');
  $('#notesJa').textContent=L.notes_ja||'';
  $('#notes').textContent=j.notes||'';
  $('#sources').innerHTML=(j.sources||[]).map(s=>`<li><a href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.title)}</a></li>`).join('')
    +((j.ebay_hints||[]).length?'<li class="muted">eBay 画像検索の類似: '+j.ebay_hints.map(esc).join(' / ')+'</li>':'');
  $('#title').value=L.ebay_title;updLen();
  $('#condition').value=L.condition;
  $('#condNotes').value=L.condition_notes||'';
  $('#desc').value=L.description_html;updPrev();
  $('#sku').value=j.sku;
  $('#rq').value=L.search_query;
  setCats(j.categories||[]);
  ASPDEF={};
  $('#aspects').innerHTML='';
  (L.item_specifics||[]).forEach(a=>addAsp(a.name,a.value));
  $('#priceBox').innerHTML='';$('#listResult').innerHTML='';$('#price').value='';
  $('#draft').scrollIntoView({behavior:'smooth'});
}
function updLen(){const n=$('#title').value.length;$('#tlen').textContent=n+' / 80';$('#tlen').className=n>80?'err':'muted';}
$('#title').oninput=updLen;
// プレビューは script を動かさないよう sandbox iframe に入れる
function updPrev(){
  $('#descPrev').innerHTML='';
  const f=document.createElement('iframe');f.setAttribute('sandbox','');f.style.cssText='width:100%;height:320px;border:0;background:#fff';
  f.srcdoc='<meta charset=utf-8><body style="font-family:sans-serif;font-size:14px;color:#111">'+$('#desc').value;
  $('#descPrev').appendChild(f);
}
$('#desc').oninput=updPrev;

function setCats(cats){
  $('#catSel').innerHTML=cats.length?cats.map(c=>`<option value="${esc(c.id)}">${esc(c.path)} (${esc(c.id)})</option>`).join('')
    :'<option value="">候補なし（ID を直接入力）</option>';
  $('#catId').value=cats[0]?cats[0].id:'';
}
$('#catSel').onchange=()=>{$('#catId').value=$('#catSel').value;};
$('#btnCatSearch').onclick=()=>{
  const q=prompt('カテゴリ検索キーワード（英語）',DRAFT?DRAFT.listing.search_query:'');
  if(!q)return;
  run($('#btnCatSearch'),async()=>setCats(await post('/api/categories',{q})),'検索中');
};

function addAsp(name='',value='',req=false){
  const tr=document.createElement('tr');
  const def=ASPDEF[name]||{};
  const listId=def.values&&def.values.length?'dl'+Math.random().toString(36).slice(2):'';
  tr.innerHTML=`<td>${req?'<span class="req">*</span> ':''}<input class="an" value="${esc(name)}"></td>
    <td><input class="av" value="${esc(value)}" ${listId?`list="${listId}"`:''}>
    ${listId?`<datalist id="${listId}">${def.values.slice(0,200).map(v=>`<option value="${esc(v)}">`).join('')}</datalist>`:''}
    ${def.multi?'<div class="muted">複数はカンマ区切り</div>':''}</td>
    <td style="width:30px"><button class="b sub" style="padding:4px 9px">×</button></td>`;
  tr.querySelector('button').onclick=()=>tr.remove();
  $('#aspects').appendChild(tr);
}
$('#btnAddAsp').onclick=()=>addAsp();
function collectAsp(){
  return [...$('#aspects').querySelectorAll('tr')].map(tr=>{
    const name=tr.querySelector('.an').value.trim();
    return {name,value:tr.querySelector('.av').value.trim(),multi:!!(ASPDEF[name]&&ASPDEF[name].multi)};
  }).filter(a=>a.name&&a.value);
}
$('#btnAspects').onclick=()=>doAspects();
function doAspects(){
  const cid=$('#catId').value.trim();
  if(!cid){alert('カテゴリ ID を入れてください');return Promise.resolve(false);}
  return run($('#btnAspects'),async()=>{
    const j=await post('/api/aspects',{category_id:cid,draft_id:DRAFT&&DRAFT.draft_id});
    ASPDEF={};j.aspects.forEach(a=>ASPDEF[a.name]=a);
    const cur={};collectAsp().forEach(a=>cur[a.name]=a.value);
    const got={};j.filled.forEach(f=>got[f.name]=f.value);
    $('#aspects').innerHTML='';
    // 必須 → AI が埋めたもの → 手入力の残り の順に並べる
    const done=new Set();
    j.aspects.filter(a=>a.required).forEach(a=>{addAsp(a.name,got[a.name]||cur[a.name]||'',true);done.add(a.name);});
    j.filled.forEach(f=>{if(!done.has(f.name)){addAsp(f.name,f.value);done.add(f.name);}});
    Object.entries(cur).forEach(([k,v])=>{if(!done.has(k)&&!ASPDEF[k])addAsp(k,v);});
    $('#aspStat').innerHTML=j.missing.length?'<span class="err">必須で未入力: '+j.missing.map(esc).join(', ')+'</span>'
      :'<span class="okt">必須項目はすべて入力済み</span>';
    return true;
  },'AI が入力中...',$('#aspStat'));
}

// ── 相場 ──
function money(v,cur){return v==null?'-':(cur==='USD'?'$':cur+' ')+Number(v).toFixed(2);}
function yen(v,j){return (j.currency==='USD'&&j.usd_jpy)?' <small>≈'+Math.round(v*j.usd_jpy).toLocaleString()+'円</small>':'';}
function statsHtml(s,j){
  if(!s)return '<div class="muted">データなし</div>';
  const cell=(k,l)=>s[k]==null?'':`<div class="stat"><small>${l}</small><b>${money(s[k],j.currency)}</b>${yen(s[k],j)}</div>`;
  return `<div class="stats"><div class="stat"><small>件数</small><b>${s.count}</b></div>${cell('median','中央値')}${cell('mean','平均')}
    ${cell('p25','下位25%')}${cell('p75','上位25%')}${cell('min','最安')}${cell('max','最高')}</div>`;
}
function researchHtml(j,compact){
  const S=j.sold,A=j.active;
  const rows=(items,sold)=>items.slice(0,compact?8:60).map(i=>`<tr><td>${sold?esc(i.date||''):esc(i.condition||'')}</td>
    <td style="white-space:nowrap">${money(i.price,i.currency||j.currency)}</td>
    <td><a href="${esc(i.url)}" target="_blank" rel="noopener">${esc(i.title)}</a></td></tr>`).join('');
  return `<div class="card"><h2>落札（Sold）${S.source?'<span class="muted"> - '+esc(S.source)+'</span>':''}</h2>
    ${S.error?'<div class="err">'+esc(S.error)+'</div>':''}${statsHtml(S.stats,j)}
    ${S.items.length?`<details ${compact?'':'open'}><summary>落札一覧 (${S.items.length})</summary><table>${rows(S.items,true)}</table></details>`:''}
    <div class="muted" style="margin-top:6px"><a href="${esc(j.links.sold)}" target="_blank" rel="noopener">eBay で Sold を見る</a> ・
    <a href="${esc(j.links.terapeak)}" target="_blank" rel="noopener">Terapeak（Seller Hub）</a></div></div>
    <div class="card"><h2>現在の出品</h2>${A.error?'<div class="err">'+esc(A.error)+'</div>':''}${statsHtml(A.stats,j)}
    ${A.items.length?`<details><summary>出品一覧 (${A.items.length})</summary><table>${rows(A.items,false)}</table></details>`:''}</div>`;
}
$('#btnResearch').onclick=()=>{
  const q=$('#rq').value.trim();if(!q)return;
  run($('#btnResearch'),async()=>{$('#researchBox').innerHTML=researchHtml(await post('/api/research',{q}),false);},'調査中',$('#researchBox'));
};
$('#rq').onkeydown=e=>{if(e.key==='Enter')$('#btnResearch').click();};
$('#btnPrice').onclick=()=>doPrice();
function doPrice(){
  const q=DRAFT&&DRAFT.listing.search_query||$('#title').value;
  return run($('#btnPrice'),async()=>{
    const j=await post('/api/research',{q});
    $('#priceBox').innerHTML=(j.suggested?`<p>推奨価格: <b>${money(j.suggested,j.currency)}</b>${yen(j.suggested,j)} <span class="muted">(${esc(j.suggested_basis)})</span>
      <button class="b sub" id="usePrice" style="padding:4px 10px">この価格にする</button></p>`:'')
      +`<div class="muted">検索語: ${esc(q)}（相場リサーチタブで変えて調べ直せます）</div>`+researchHtml(j,true);
    if(j.suggested)$('#usePrice').onclick=()=>{$('#price').value=j.suggested.toFixed(2);};
    if(j.suggested&&!$('#price').value)$('#price').value=j.suggested.toFixed(2);
    return true;
  },'調査中',$('#priceBox'));
}

// ── 送信 ──
function sendListing(publish){
  if(!DRAFT)return Promise.resolve(false);
  if(publish&&!confirm('eBay に公開出品します。よろしいですか？'))return Promise.resolve(false);
  const idx=[...document.querySelectorAll('.useimg')].filter(c=>c.checked).map(c=>+c.dataset.i);
  const body={draft_id:DRAFT.draft_id,publish,sku:$('#sku').value,title:$('#title').value,description:$('#desc').value,
    condition:$('#condition').value,condition_notes:$('#condNotes').value,category_id:$('#catId').value,
    price:$('#price').value,quantity:$('#qty').value,aspects:collectAsp(),image_indexes:idx};
  const btn=publish?$('#btnPublish'):$('#btnDraft');
  return run(btn,async()=>{
    const j=await post('/api/list',body);
    $('#listResult').innerHTML='<ul>'+j.steps.map(s=>'<li class="okt">'+esc(s)+'</li>').join('')+'</ul>'
      +(j.url?`<a href="${esc(j.url)}" target="_blank" rel="noopener"><b>出品ページを開く</b></a>`
             :`<span class="muted">未公開です。内容を確認して「出品する」で公開できます。</span>`);
    return true;
  },'送信中...',$('#listResult'));
}
$('#btnDraft').onclick=()=>sendListing(false);
$('#btnPublish').onclick=()=>sendListing(true);

// ── 設定 ──
const FIELDS=['ebay_env','marketplace_id','client_id','ru_name','fulfillment_policy_id','payment_policy_id','return_policy_id','merchant_location_key','usd_jpy'];
function ensureOpt(sel,val,label){
  if(val&&![...sel.options].some(o=>o.value===val))sel.insertAdjacentHTML('beforeend',`<option value="${esc(val)}">${esc(label||val)}</option>`);
}
function fillConf(c){
  CONF=c;
  ['fulfillment_policy_id','payment_policy_id','return_policy_id','merchant_location_key'].forEach(k=>{
    const s=$('#'+k);if(!s.options.length)s.innerHTML='<option value="">（未選択）</option>';ensureOpt(s,c[k]);});
  FIELDS.forEach(k=>{$('#'+k).value=c[k]??'';});
  $('#scrape_sold').checked=!!c.scrape_sold;
  $('#auto_draft').checked=!!c.auto_draft;
  $('#akState').textContent=c.has_anthropic_api_key?'（登録済み）':c.has_anthropic_env?'（環境変数を使用中）':'（未登録）';
  ['as1','as2','as3','as4'].forEach(k=>$('#'+k).classList.toggle('done',!!(c.has_anthropic_api_key||c.has_anthropic_env)));
  $('#csState').textContent=c.has_client_secret?'（登録済み）':'（未登録）';
  $('#linkState').innerHTML=c.has_refresh_token?'<span class="okt">連携済み</span>'+(c.refresh_token_expires?'（'+esc(c.refresh_token_expires)+' まで有効）':'')
    :'未連携';
  const done={st3:c.client_id&&c.has_client_secret,st4:!!c.ru_name,st5:c.has_refresh_token,
    st6:c.fulfillment_policy_id&&c.payment_policy_id&&c.return_policy_id&&c.merchant_location_key};
  if(done.st3){done.st1=done.st2=true;}
  Object.entries(done).forEach(([k,v])=>$('#'+k).classList.toggle('done',!!v));
  document.querySelectorAll('.cur').forEach(e=>e.textContent={EBAY_US:'USD',EBAY_GB:'GBP',EBAY_AU:'AUD',EBAY_CA:'CAD',EBAY_DE:'EUR'}[c.marketplace_id]||'');
}
async function saveConf(stat){
  const b={};FIELDS.forEach(k=>b[k]=$('#'+k).value);
  b.scrape_sold=$('#scrape_sold').checked;
  b.auto_draft=$('#auto_draft').checked;
  if($('#client_secret').value)b.client_secret=$('#client_secret').value;
  try{fillConf(await post('/api/config',b));$('#client_secret').value='';
    stat.innerHTML='<span class="okt">保存しました</span>';}
  catch(e){stat.innerHTML='<span class="err">'+esc(e.message)+'</span>';}
}
$('#btnSave').onclick=()=>saveConf($('#saveStat'));
// キー画面をまるごと貼ると App ID / Cert ID / RuName を拾う
function parseKeys(t){
  const app=t.match(/\b[A-Za-z0-9_]+-[A-Za-z0-9_]+-(PRD|SBX)-[0-9a-z]{6,}-[0-9a-z]{6,}\b/);
  const cert=t.match(/\b(PRD|SBX)-[0-9a-f]{8,}(?:-[0-9a-f]{4}){3,4}(?![0-9a-z-])/i);
  const ru=t.match(/RuName[^A-Za-z0-9]*(?:\([^)]*\))?[^A-Za-z0-9]*([A-Za-z0-9_]+(?:-[A-Za-z0-9_]+){3,})/i);
  return {app:app&&app[0],cert:cert&&cert[0],env:(app||cert)&&((app||cert)[0].includes('SBX')?'sandbox':'production'),ru:ru&&ru[1]};
}
$('#btnAkSave').onclick=()=>run($('#btnAkSave'),async()=>{
  fillConf(await post('/api/anthropic/key',{key:$('#anthropic_api_key').value}));
  $('#anthropic_api_key').value='';$('#akStat').innerHTML='<span class="okt">使えることを確認して保存しました</span>';
},'確認中',$('#akStat'));
$('#btnKeyPaste').onclick=()=>run($('#btnKeyPaste'),async()=>{
  const k=parseKeys($('#keyPaste').value);
  if(!k.app&&!k.cert)throw new Error('App ID / Cert ID が見つかりません。キーの欄をまとめてコピーして貼ってください');
  const b={};if(k.app)b.client_id=k.app;if(k.cert)b.client_secret=k.cert;if(k.env)b.ebay_env=k.env;if(k.ru)b.ru_name=k.ru;
  fillConf(await post('/api/config',b));$('#keyPaste').value='';
  $('#keyStat').innerHTML='<span class="okt">保存しました: '+[k.app&&'App ID',k.cert&&'Cert ID',k.ru&&'RuName'].filter(Boolean).join('・')
    +'（'+(k.env==='sandbox'?'Sandbox':'本番')+'）</span>'+(!k.app||!k.cert?'<br><span class="err">'+(!k.app?'App ID':'Cert ID')+' が見つかりませんでした</span>':'');
},'読み取り中',$('#keyStat'));
$('#btnRuPaste').onclick=()=>run($('#btnRuPaste'),async()=>{
  const v=$('#ruPaste').value.trim();const k=parseKeys(v);
  const ru=k.ru||v.split(/\s+/).find(w=>/^[A-Za-z0-9_]+(-[A-Za-z0-9_]+){3,}$/.test(w)&&!/-(PRD|SBX)-/.test(w));
  if(!ru)throw new Error('RuName の形になっていません（例: Tekkan_Sumo-TekkanSu-ebayli-abcde）');
  fillConf(await post('/api/config',{ru_name:ru}));$('#ruPaste').value='';
  $('#ruStat').innerHTML='<span class="okt">保存しました: '+esc(ru)+'</span>';
},'保存中',$('#ruStat'));
document.querySelectorAll('.cp').forEach(b=>b.onclick=async()=>{
  try{await navigator.clipboard.writeText(b.dataset.copy);b.textContent='コピー済み';}
  catch(e){prompt('コピーしてください',b.dataset.copy);}
  setTimeout(()=>b.textContent='コピー',1500);
});
$('#btnTest').onclick=()=>run($('#btnTest'),async()=>{
  const j=await post('/api/ebay/test',{});
  $('#testBox').innerHTML='<ul class="tres">'+j.steps.map(s=>`<li>${s.ok?'✅':'❌'} <b>${esc(s.label)}</b> ${esc(s.detail)}</li>`).join('')+'</ul>'
    +(j.ok?'<div class="okt"><b>すべて OK です。出品できます。</b></div>':'');
},'確認中',$('#testBox'));
$('#btnSave2').onclick=()=>saveConf($('#saveStat2'));
$('#btnSave3').onclick=()=>saveConf($('#saveStat3'));
$('#btnAuth').onclick=()=>{
  // ポップアップブロックを避けるため、クリック直後に窓だけ開いておく
  const w=window.open('about:blank','_blank');
  run($('#btnAuth'),async()=>{
    await saveConf($('#authStat'));
    const j=await api('/api/ebay/auth_url');
    if(w)w.location=j.url;else location.href=j.url;
    return true;
  },'',$('#authStat')).then(ok=>{if(!ok&&w)w.close();});
};
$('#btnCode').onclick=()=>run($('#btnCode'),async()=>{
  fillConf(await post('/api/ebay/auth_code',{code:$('#authCode').value}));
  $('#authCode').value='';$('#authStat').innerHTML='<span class="okt">連携しました</span>';
},'交換中',$('#authStat'));
function fillPolicies(p){
  const fill=(k,list)=>{const s=$('#'+k);
    s.innerHTML='<option value="">（未選択）</option>'+list.map(x=>`<option value="${esc(x.id)}">${esc(x.name)}</option>`).join('');
    ensureOpt(s,CONF[k]);s.value=CONF[k]||(list.length===1?list[0].id:'');};
  fill('fulfillment_policy_id',p.fulfillment);fill('payment_policy_id',p.payment);
  fill('return_policy_id',p.return);fill('merchant_location_key',p.location);
}
$('#btnPol').onclick=()=>run($('#btnPol'),async()=>{
  fillPolicies(await api('/api/ebay/policies'));
  $('#polStat').innerHTML='<span class="okt">読み込みました。選んで「保存」してください</span>';
},'読込中',$('#polStat'));
$('#btnAutoSetup2').onclick=()=>run($('#btnAutoSetup2'),async()=>{
  const j=await post('/api/ebay/auto_setup',{postal_code:$('#autoZip').value});
  fillConf(j.config);fillPolicies(j.policies);
  $('#autoStat').innerHTML=(j.steps.length?'<ul class="tres">'+j.steps.map(s=>'<li class="okt">✅ '+esc(s)+'</li>').join('')+'</ul>':'')
    +(j.need_postal?'<div class="err">発送元がありません。郵便番号を入れてもう一度押してください</div>':'')
    +(j.need_shipping?`<div class="err">送料ポリシーがまだありません。<a href="${esc(j.shipping_url)}" target="_blank" rel="noopener">eBay で送料ポリシーを作る</a>と、もう一度押したときに自動で選びます</div>`:'')
    +(!j.need_postal&&!j.need_shipping?'<div class="okt"><b>出品の準備ができました</b></div>':'');
},'設定中',$('#autoStat'));
// アプリ版だけ: ログインだけで登録できるアプリ内セットアップ
if(window.App&&App.openSetup){$('#btnAutoSetup').style.display='';$('#btnAutoSetup').onclick=()=>App.openSetup();}
$('#btnLoc').onclick=()=>run($('#btnLoc'),async()=>{
  const j=await post('/api/ebay/location',{key:$('#locKey').value,country:$('#locCountry').value,
    postalCode:$('#locZip').value,stateOrProvince:$('#locState').value,city:$('#locCity').value});
  CONF.merchant_location_key=j.key;ensureOpt($('#merchant_location_key'),j.key);$('#merchant_location_key').value=j.key;
  $('#locStat').innerHTML='<span class="okt">作成しました</span>';
},'作成中',$('#locStat'));

if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
(async()=>{
  META=await api('/api/meta');
  $('#condition').innerHTML=META.conditions.map(([k,l])=>`<option value="${k}">${esc(l)}（${k}）</option>`).join('');
  $('#marketplace_id').innerHTML=META.marketplaces.map(m=>`<option>${m}</option>`).join('');
  fillConf(await api('/api/config'));
  if(!CONF.has_anthropic_api_key&&!CONF.has_anthropic_env||!CONF.client_id)
    document.querySelector('nav button[data-tab=settings]').click();
})();
</script></body></html>
"""

if __name__ == "__main__":
    print(f"→ http://127.0.0.1:{PORT}   設定: {CONF_FILE}")
    app.run(host=HOST, port=PORT, threaded=True, debug=False)
