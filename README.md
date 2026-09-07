# BOOKOFF まわりの道具

Python 1ファイルずつで動く小さな Web アプリ。Pydroid 3 でもそのまま置ける。

```
pip install flask requests
```

## bookoff_web.py — 在庫店舗チェッカー

商品ID の一覧を入れておくと、BOOKOFF のどの店舗に在庫があるかをまとめて調べる。
結果はカードで並び、都道府県で絞ってメール送信、CSV 書き出しもできる。

```
python bookoff_web.py     → http://127.0.0.1:5000
```

Android アプリ版も同じ画面（`android/`）。画面のマークアップと表示ロジックは
`bookoff_web.py` の `UI_HTML` / `UI_JS` だけに置き、`tools/gen_android_asset.py`
が `android/app/src/main/assets/index.html` を生成する。画面を直したら

```
python3 tools/gen_android_asset.py
```

を実行してコミットする（CI が食い違いを検出する）。

## artist_cd_web.py — アーティストCDリスト × BOOKOFF オンライン

アーティスト名から CD の一覧を作り、表にして見せる。

```
python artist_cd_web.py   → http://127.0.0.1:5001
```

- 作品一覧は [MusicBrainz](https://musicbrainz.org/)（無料・鍵不要）から取る。
  1秒1リクエストの約束があるので、アプリ側で間隔を空けている。
- **アルバム／シングル／EP／その他**で絞り込める。所有・未所有、在庫あり、
  タイトルの部分一致でも絞れる。見出しをタップすると並べ替わる。
- 持っている CD はチェックを入れるとその場で `artist_cd_owned.json` に保存される
  （ボタンを押す必要はない）。次に同じアーティストを開くと復元される。
- 「BOOKOFF在庫チェック」で、表示中の作品を BOOKOFF オンラインで自動検索し、
  **在庫の有無と価格**を各行に埋める。相手のサイトに負担をかけないよう、
  403/503 が返ったら並列数を落として待つ。中止ボタンでいつでも止められる。
- 集計行に「未所有で在庫あり」の件数と合計金額が出る。CSV も書き出せる。
- 検索語はアーティスト名の日本語表記を使う（英語名で選んでも自動で入れ替える）。
  うまく当たらないときは検索名の欄を直す。
- 在庫が取れなくなったら「診断」。どの検索URLの形が生きているか、商品リンクを
  拾えているか、生の HTML までそのまま出す。

保存ファイル（`bookoff_ids.json` / `artist_cd_owned.json` など）は
`.gitignore` 済み。
