#!/usr/bin/env bash
# JNI 符号对账：Kotlin 声明的 external 方法与 .so 里导出的符号必须一一对上。
#
# 为什么要有这个：第一版把符号名写成 Java_com_nmoi_agent_Native_*（类在 ui 子包、
# 类名是 NativeBridge），.so 进了包、loadLibrary 也成功了，一调用就
# "No implementation found for ..."。这种错编译能过、装包能过，只有真机点了才知道。
# 所以把它变成机械检查。
#
# 用法：
#   ./scripts/check_jni_symbols.sh <libnmoi_ffi.so>   # 对账（CI 用）
#   ./scripts/check_jni_symbols.sh --expected-only    # 只打印期望的符号名（本机没 .so 时）
set -euo pipefail

cd "$(dirname "$0")/.."

KOTLIN="android/app/src/main/kotlin/com/nmoi/agent/ui/NativeBridge.kt"

[ -f "$KOTLIN" ] || { echo "找不到 $KOTLIN" >&2; exit 1; }

# package com.nmoi.agent.ui  →  com_nmoi_agent_ui
pkg=$(sed -nE 's/^package ([A-Za-z0-9_.]+).*/\1/p' "$KOTLIN" | head -1)
[ -n "$pkg" ] || { echo "$KOTLIN 里没有 package 行" >&2; exit 1; }
mangled_pkg=$(printf '%s' "$pkg" | tr '.' '_')

# internal object NativeBridge  →  NativeBridge
cls=$(sed -nE 's/^[a-z ]*object ([A-Za-z0-9_]+).*/\1/p' "$KOTLIN" | head -1)
[ -n "$cls" ] || { echo "$KOTLIN 里没找到 object 声明" >&2; exit 1; }

expected=()
while IFS= read -r fn; do
  [ -n "$fn" ] && expected+=("Java_${mangled_pkg}_${cls}_${fn}")
done < <(sed -nE 's/^[[:space:]]*external fun ([A-Za-z0-9_]+)\(.*/\1/p' "$KOTLIN")

[ "${#expected[@]}" -gt 0 ] || { echo "$KOTLIN 里没有 external fun" >&2; exit 1; }

echo "Kotlin：package=$pkg object=$cls，声明了 ${#expected[@]} 个 external 方法"
for sym in "${expected[@]}"; do
  echo "  期望 $sym"
done

if [ "${1:-}" = "--expected-only" ]; then
  exit 0
fi

SO="${1:-}"
[ -n "$SO" ] || { echo "用法：$0 <libnmoi_ffi.so> | --expected-only" >&2; exit 2; }
[ -f "$SO" ] || { echo "找不到 $SO" >&2; exit 1; }

# nm 可能在 NDK 里；宿主机的 GNU nm 也能读 aarch64 ELF，先试宿主的。
actual=$(nm -D --defined-only "$SO" 2>/dev/null | awk '{print $NF}' | grep -E '^Java_[A-Za-z0-9_]+$' || true)

missing=0
for sym in "${expected[@]}"; do
  if printf '%s\n' "$actual" | grep -qx "$sym"; then
    echo "  ok      $sym"
  else
    echo "  MISSING $sym" >&2
    missing=$((missing + 1))
  fi
done

if [ -n "$actual" ]; then
  echo "--- .so 里实际导出的 Java_* 符号"
  printf '%s\n' "$actual" | sed 's/^/    /'
else
  echo "--- .so 里一个 Java_* 符号都没有（忘了 --features jni？）" >&2
  missing=$((missing + 1))
fi

if [ "$missing" -gt 0 ]; then
  echo "对账失败：$missing 处对不上。JVM 找不到的符号在运行期只会报 No implementation found。" >&2
  exit 1
fi

echo "对账通过：Kotlin 声明的 ${#expected[@]} 个方法在 .so 里都有。"
