#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Python 版の画面から Android の assets/index.html を生成する。

Web 版とアプリ版で画面を二重管理すると必ず食い違うので、マークアップと表示
ロジック（UI_HTML / UI_JS）は Python 側の 1 か所だけに置き、通信部分だけを
ここで差し替える。

Python 側は Pydroid 3 に 1 ファイルで置けることを保ちたいので分割せず、
かといって import すると flask が要る（CI に web フレームワークを入れるのは
筋が悪い）。そのためソースから文字列リテラルを取り出す。

  python3 tools/gen_android_asset.py           生成
  python3 tools/gen_android_asset.py --check   生成物が最新か確認（CI 用）
"""
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent

# 在庫店舗チェッカーの通信部分。Kotlin の MainActivity.Bridge を叩く。
CHECKER_APP_JS = r"""
// 通信は Flask ではなく Kotlin 側の App ブリッジ（MainActivity.Bridge）を使う。
// @JavascriptInterface は同期呼び出しなので Promise に包んで形を揃える。
const BOOKMARKLET='';   // アプリでは「取込」画面を使うので不要
const T={
  state:async()=>JSON.parse(App.getState()),
  ids:async()=>JSON.parse(App.getIds()),
  saveIds:async l=>JSON.parse(App.saveIds(JSON.stringify(l))),
  reset:async()=>App.reset(),
  cancel:async()=>App.cancel(),
  prefectures:async()=>JSON.parse(App.getPrefectures()),
  mailConfig:async()=>JSON.parse(App.getMailConfig()),
  saveMailConfig:async o=>App.saveMailConfig(JSON.stringify(o)),
  mailPreview:async p=>App.mailPreview(JSON.stringify(p)),
  sendMail:async p=>App.sendMail(JSON.stringify(p)),   // 結果は __native('mail') で届く
  csv:()=>App.exportCsv(),
  openImport:()=>App.openImport(),
  openDiagnose:()=>App.openDiagnose(),
  start:async()=>App.start()
};
// Kotlin (Checker) からのイベント受け口
window.__native=(kind,data)=>__event(kind,data);
"""


# CD リストの通信部分。時間のかかる問い合わせは返事を待たずに投げ、
# Kotlin から __native('reply') で返してもらって Promise を解く。
CDLIST_APP_JS = r"""
// 通信は Flask ではなく Kotlin 側の App ブリッジ（MainActivity.Bridge）を使う。
// @JavascriptInterface は同期呼び出しなので、通信を伴うものをそのまま待つと
// 画面が固まる。id を振って投げ、返事は window.__native('reply') で受ける。
let RQ=0; const PEND={};
const ask=(fn,arg)=>new Promise(r=>{ const id=++RQ; PEND[id]=r; App[fn](id,arg); });
const T={
  artists:q=>ask('artists',q),
  disco:m=>ask('disco',m),
  explore:u=>ask('explore',u),
  setOwned:async(id,owned,meta)=>JSON.parse(App.setOwned(id,owned,JSON.stringify(meta))),
  plan:async o=>JSON.parse(App.plan(JSON.stringify(o))),
  start:()=>App.start(),
  stopStream(){},          // アプリでは張りっぱなしの接続が無い
  cancel:async()=>App.cancel(),
  diag:kw=>App.diag(kw),
  csv:(text,name)=>App.csv(text,name)
};
// Kotlin (Checker) からのイベント受け口
window.__native=(kind,data)=>{
  if(kind==='reply'){
    const f=PEND[data.rid]; delete PEND[data.rid]; if(f) f(data.body); return;
  }
  __event(kind,data);
};
"""

# (Python 側の画面, 生成先, アプリ版の通信部分)
TARGETS = [
    ("bookoff_web.py", "android/app/src/main/assets/index.html", CHECKER_APP_JS),
    ("artist_cd_web.py", "android/cdlist/src/main/assets/index.html", CDLIST_APP_JS),
]


def literal(src: str, name: str) -> str:
    """`NAME = r\"\"\"...\"\"\"` の中身を取り出す。"""
    key = f'{name} = r"""'
    i = src.find(key)
    if i < 0:
        raise SystemExit(f"{key} が見つかりません")
    i += len(key)
    j = src.find('"""', i)
    if j < 0:
        raise SystemExit(f"{name} が閉じていません")
    return src[i:j]


def build(src_path: Path, app_js: str) -> str:
    src = src_path.read_text(encoding="utf-8")
    ui_html = literal(src, "UI_HTML")
    ui_js = literal(src, "UI_JS")
    return ui_html + "\n<script>\n" + app_js + "\n" + ui_js + "\n</script></body></html>\n"


def main() -> int:
    check = "--check" in sys.argv
    bad = 0
    for src_name, out_name, app_js in TARGETS:
        src, out = BASE / src_name, BASE / out_name
        html = build(src, app_js)
        if check:
            cur = out.read_text(encoding="utf-8") if out.exists() else ""
            if cur != html:
                print(f"{out_name} が {src_name} と食い違っています。")
                print("  python3 tools/gen_android_asset.py を実行してコミットしてください。")
                bad += 1
            else:
                print(f"{out_name} は最新です。")
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(html, encoding="utf-8")
        print(f"生成しました: {out_name}  ({len(html.splitlines())} 行)")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
