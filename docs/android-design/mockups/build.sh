#!/usr/bin/env bash
# 把 board-*.html 渲染成 png/*.png（headless Chrome + CDP，2x 整页截图）。
# 用 CDP 而不是 `--screenshot --window-size`：超高的板面会被 Chrome 降采样并填成灰色。
# 用法： bash build.sh [board-name ...]     不带参数 = 全部
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
NODE="${DSH_NODE:-/Users/cov/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node}"
OUT="$HERE/png"
PORT=9333
mkdir -p "$OUT"

canvas_w() {
  case "$1" in
    01-design-system|06-design-system) echo 2100 ;;
    02-home|03-diary|04-search|05-settings|07-extras|09-gallery) echo 1560 ;;
    08-adaptive) echo 2900 ;;
    08-flows)         echo 2100 ;;
    *) echo 1600 ;;
  esac
}

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  for f in "$HERE"/board-*.html; do
    b="$(basename "$f" .html)"; targets+=("${b#board-}")
  done
fi

PROFILE="$(mktemp -d)"
"$CHROME" --headless --disable-gpu --hide-scrollbars --no-sandbox --no-first-run \
  --remote-debugging-port=$PORT --user-data-dir="$PROFILE" about:blank >/dev/null 2>&1 &
CHROME_PID=$!
trap 'kill $CHROME_PID 2>/dev/null || true; rm -rf "$PROFILE"' EXIT

for _ in $(seq 1 60); do
  curl -s "http://127.0.0.1:$PORT/json/version" >/dev/null 2>&1 && break
  sleep 0.25
done

for name in "${targets[@]}"; do
  file="$HERE/board-$name.html"
  [ -f "$file" ] || { echo "skip: $name"; continue; }
  "$NODE" "$HERE/shoot.mjs" "$PORT" "$OUT/$name.png" "$(canvas_w "$name")" "$file"
done
echo "done."
