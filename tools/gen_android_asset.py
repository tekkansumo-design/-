#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bookoff_web.py の画面から Android の assets/index.html を生成する。

Web 版とアプリ版で画面を二重管理すると必ず食い違うので、マークアップと表示
ロジック（UI_HTML / UI_JS）は bookoff_web.py の 1 か所だけに置き、通信部分
だけをここで差し替える。

  python3 tools/gen_android_asset.py          生成
  python3 tools/gen_android_asset.py --check   生成物が最新か確認（CI 用）
"""
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE))

import bookoff_web as W  # noqa: E402

OUT = BASE / "android/app/src/main/assets/index.html"

# アプリ版の通信部分。Kotlin の MainActivity.Bridge を叩く。
APP_JS = r"""
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
  start:async()=>App.start()
};
// Kotlin (Checker) からのイベント受け口
window.__native=(kind,data)=>__event(kind,data);
"""


def build():
    return (W.UI_HTML + "\n<script>\n" + APP_JS + "\n" + W.UI_JS
            + "\n</script></body></html>\n")


def main():
    html = build()
    if "--check" in sys.argv:
        cur = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if cur != html:
            print("assets/index.html が bookoff_web.py と食い違っています。")
            print("  python3 tools/gen_android_asset.py を実行してコミットしてください。")
            return 1
        print("assets/index.html は最新です。")
        return 0
    OUT.write_text(html, encoding="utf-8")
    print(f"生成しました: {OUT.relative_to(BASE)}  ({len(html.splitlines())} 行)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
