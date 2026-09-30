#!/data/data/com.termux/files/usr/bin/bash
# Termux:Widget 用。ホーム画面のウィジェットから押すと
# サーバーが止まっていれば起動し、ブラウザで画面を開く。
#
#   mkdir -p ~/.shortcuts
#   cp ebay_lister_termux.sh ~/.shortcuts/eBay出品
#   chmod +x ~/.shortcuts/eBay出品
#   （このスクリプトを置いた場所ではなく、下の DIR が ebay_lister.py の場所を指すこと）
DIR="${EBAY_LISTER_DIR:-$HOME/-}"
PORT="${EBAY_LISTER_PORT:-5001}"
URL="http://127.0.0.1:$PORT/"

if ! curl -s -o /dev/null "$URL"; then
  cd "$DIR" || { echo "ebay_lister.py の場所が見つかりません: $DIR"; exit 1; }
  nohup python ebay_lister.py > "$DIR/ebay_lister.log" 2>&1 &
  for _ in $(seq 1 30); do
    curl -s -o /dev/null "$URL" && break
    sleep 0.5
  done
fi
termux-open-url "$URL"
