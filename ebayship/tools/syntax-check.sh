#!/usr/bin/env bash
# Kotlin の構文だけを手元で見る。
#
# Android SDK が無い環境でも、括弧の閉じ忘れや壊れた文字リテラルのような
# 「読めていない」類の間違いは kotlinc だけで見つかる。
# 型の解決は android.jar が要るので通らない。そちらは CI に任せる。
#
#   ./tools/syntax-check.sh
#
# kotlinc は PATH か KOTLIN_HOME/bin に置いておく。
set -u

here="$(cd "$(dirname "$0")/.." && pwd)"
src="$here/app/src/main/java/com/tekkansumo/ebayship"

kc="$(command -v kotlinc || true)"
if [ -z "$kc" ] && [ -n "${KOTLIN_HOME:-}" ]; then
  kc="$KOTLIN_HOME/bin/kotlinc"
fi
if [ -z "$kc" ] || [ ! -x "$kc" ]; then
  echo "kotlinc が見つかりません。PATH か KOTLIN_HOME を設定してください。" >&2
  exit 2
fi

out="$("$kc" "$src"/*.kt -d "$(mktemp -d)" 2>&1 |
  grep -E "syntax error|incorrect character literal|incorrect string literal|Expecting|unclosed|illegal escape")"

if [ -n "$out" ]; then
  echo "$out"
  echo "--- 構文の誤りが見つかりました ---" >&2
  exit 1
fi
echo "構文の誤りはありません（型の確認は CI で行われます）"
