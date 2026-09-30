#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ebay_lister.py の画面から Android（eBay 出品アプリ）の assets/index.html を生成する。

gen_android_asset.py と同じ考え方で、画面は ebay_lister.py の PAGE の 1 か所だけに置き、
通信部分（fetch で Flask を叩く api()）だけを Kotlin 側の App ブリッジに差し替える。

  python3 tools/gen_ebay_asset.py           生成
  python3 tools/gen_ebay_asset.py --check   生成物が最新か確認（CI 用）
"""
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
SRC = BASE / "ebay_lister.py"
OUT = BASE / "android/ebay/src/main/assets/index.html"

WEB_API = """async function api(path,opt={}){
  const r=await fetch(path,opt);
  let j={};try{j=await r.json();}catch(e){}
  if(!r.ok) throw new Error(j.error||('HTTP '+r.status));
  return j;
}"""

# アプリ版の通信部分。Kotlin の MainActivity.Bridge を叩く。
# 商品特定は 1〜2 分かかるので同期呼び出しにせず、結果は window.__resolve で受け取る。
APP_API = r"""// 通信は Flask ではなく Kotlin 側の App ブリッジ（MainActivity.Bridge）を使う。
// パスと JSON は Web 版と同じ。結果は Kotlin から window.__resolve(id, ok, json) で返る。
const __cb={};let __seq=0;
window.__resolve=(id,ok,txt)=>{
  const c=__cb[id];delete __cb[id];if(!c)return;
  let j={};try{j=JSON.parse(txt);}catch(e){}
  if(ok)c[0](j);else c[1](new Error(j.error||'エラーが発生しました'));
};
function blobB64(b){
  return new Promise((ok,ng)=>{const r=new FileReader();
    r.onload=()=>ok(String(r.result).split(',')[1]);r.onerror=()=>ng(r.error);r.readAsDataURL(b);});
}
async function api(path,opt={}){
  let body=opt.body||'{}';
  if(body instanceof FormData){
    // 画像はブリッジに Blob を渡せないので base64 にして JSON に詰める
    const o={images:[]};
    for(const [k,v] of body.entries()){
      if(v instanceof Blob)o.images.push({mime:v.type||'image/jpeg',data:await blobB64(v)});else o[k]=v;
    }
    body=JSON.stringify(o);
  }
  const id=++__seq;
  return new Promise((ok,ng)=>{__cb[id]=[ok,ng];App.call(id,opt.method||'GET',path,body);});
}
// eBay の同意画面などは外部ブラウザで開く（WebView 内に別窓は作れない）
window.open=(u)=>{
  if(u&&u!=='about:blank'){App.openUrl(String(u));return null;}
  const w={close(){}};
  Object.defineProperty(w,'location',{set(v){App.openUrl(String(v));}});
  return w;
};"""

# アプリでは不要（ホーム画面追加・service worker は Web 版だけの仕組み）
DROP_LINE_MARKERS = ('rel="manifest"', 'rel="icon"', 'rel="apple-touch-icon"',
                     'apple-mobile-web-app', 'name="mobile-web-app-capable"',
                     "navigator.serviceWorker.register")


def literal(src: str, name: str) -> str:
    key = f'{name} = r"""'
    i = src.find(key)
    if i < 0:
        raise SystemExit(f"{SRC.name} に {key} が見つかりません")
    i += len(key)
    j = src.find('"""', i)
    if j < 0:
        raise SystemExit(f"{SRC.name} の {name} が閉じていません")
    return src[i:j]


def build() -> str:
    page = literal(SRC.read_text(encoding="utf-8"), "PAGE")
    if WEB_API not in page:
        raise SystemExit(f"{SRC.name} の api() が想定と違います。{Path(__file__).name} の WEB_API を合わせてください")
    page = page.replace(WEB_API, APP_API, 1)
    lines = [l for l in page.splitlines() if not any(m in l for m in DROP_LINE_MARKERS)]
    return "\n".join(lines) + "\n"


def main() -> int:
    html = build()
    if "--check" in sys.argv:
        cur = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if cur != html:
            print("assets/index.html（eBay）が ebay_lister.py と食い違っています。")
            print("  python3 tools/gen_ebay_asset.py を実行してコミットしてください。")
            return 1
        print("assets/index.html（eBay）は最新です。")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")
    print(f"生成しました: {OUT.relative_to(BASE)}  ({len(html.splitlines())} 行)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
