#!/usr/bin/env bash
#
# 把 pi-mobile 的前端同步进 APK 工程，然后编译。
#
#   ./build.sh              # 同步 + 编译 release
#   ./build.sh debug        # 同步 + 编译 debug
#   ./build.sh sync         # 只同步，不编译
#
# 前端唯一来源是 www/app.html（本仓库内维护，入库）。
# 若外部有更新的副本，用 PI_WEB_SRC 指定路径覆盖。
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${PI_WEB_SRC:-$HERE/../pi-mobile/public/index.html}"
MODE="${1:-release}"

# .env 里的签名配置
if [ -f "$HERE/.env" ]; then set -a; . "$HERE/.env"; set +a; fi

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  export JAVA_HOME
fi

sync_web() {
  if [ -f "$SRC" ]; then
    cp -f "$SRC" "$HERE/www/app.html"
    echo "✅ 已同步前端 → www/app.html ($(wc -c < "$HERE/www/app.html") 字节)"
  else
    if [ -f "$HERE/www/app.html" ]; then
      echo "⚠️  找不到 $SRC，沿用现有 www/app.html"
    else
      echo "❌ 缺少 www/app.html，也没有可同步的源文件" >&2
      exit 1
    fi
  fi
}

do_sync() {
  sync_web
  echo "▶️  cap sync…"
  cd "$HERE"
  npx cap sync android >/dev/null
  echo "✅ cap sync 完成"
}

do_build() {
  local task="assembleRelease"
  [ "$MODE" = debug ] && task="assembleDebug"
  cd "$HERE/android"
  echo "▶️  gradlew $task …"
  ./gradlew "$task" --no-daemon -q
  local apk
  if [ "$MODE" = debug ]; then
    apk="$HERE/android/app/build/outputs/apk/debug/app-debug.apk"
  else
    apk="$HERE/android/app/build/outputs/apk/release/app-release.apk"
  fi
  [ -f "$apk" ] || { echo "❌ 未生成 APK" >&2; exit 1; }

  local aapt
  aapt=$(ls -1 "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
  echo
  echo "✅ 编译完成"
  if [ -n "$aapt" ]; then
    "$aapt" dump badging "$apk" 2>/dev/null | head -1
  fi
  ls -lh "$apk"
}

case "$MODE" in
  sync)    do_sync ;;
  debug)   do_sync; do_build ;;
  release) do_sync; do_build ;;
  *) echo "用法: $0 [release|debug|sync]"; exit 1 ;;
esac
