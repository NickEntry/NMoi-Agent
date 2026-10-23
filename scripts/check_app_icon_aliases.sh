#!/usr/bin/env bash
# 图标 alias 对账：AndroidManifest.xml 里的 activity-alias
# 必须和 scripts/gen_app_icons.py 生成的那一段一致，而且**类名前缀必须与 Kotlin 侧一致**。
#
# 为什么要有这个：alias 名同时出现在三处（manifest / AppIconCatalog.kt 的 aliasFor /
# 偏好里存的枚举名）。2026-10-23 真机踩过一次——manifest 用相对名 `.icon.X` 对着
# namespace（com.nmoi.agent）解析，Kotlin 却照着 applicationId（com.NMoi.agent）拼，
# 差 4 个字母，运行期 "Component class ... does not exist" 直接闪退。
# 编译期完全看不出来，所以这里对账，而且对的是**全名**。
set -euo pipefail

cd "$(dirname "$0")/.."

MANIFEST="android/app/src/main/AndroidManifest.xml"
GENERATED="docs/ui/icons/app/manifest-aliases.xml"
CATALOG="android/app/src/main/kotlin/com/nmoi/agent/ui/AppIconCatalog.kt"

for f in "$MANIFEST" "$GENERATED" "$CATALOG"; do
  [ -f "$f" ] || { echo "找不到 $f" >&2; exit 1; }
done

extract() {
  grep -oE 'android:name="(com\.[A-Za-z0-9_.]*\.icon\.[A-Za-z0-9]+|\.icon\.[A-Za-z0-9]+)"' "$1" \
    | sed 's/android:name="//; s/"$//' \
    | sed 's/^\.icon\./RELATIVE.icon./' \
    | sort
}

in_manifest=$(extract "$MANIFEST")
in_generated=$(extract "$GENERATED")

if [ -z "$in_manifest" ]; then
  echo "$MANIFEST 里一个 icon alias 都没有：启动器图标切换会失效" >&2
  exit 1
fi

if printf '%s\n' "$in_manifest" | grep -q '^RELATIVE\.'; then
  echo "manifest 里还有相对名 .icon.X：" >&2
  echo "  相对名对着 namespace 解析，而 Kotlin 侧用的是全名，两者一旦不一致就是运行期闪退。" >&2
  echo "  请用 scripts/gen_app_icons.py 生成的全名。" >&2
  exit 1
fi

if ! diff -q <(printf '%s\n' "$in_generated") <(printf '%s\n' "$in_manifest") >/dev/null; then
  echo "manifest 的 alias 与生成结果不一致：" >&2
  diff <(printf '%s\n' "$in_generated") <(printf '%s\n' "$in_manifest") | sed 's/^/  /' >&2 || true
  echo "左 = 脚本生成（期望），右 = manifest 现状。改图标请改脚本再重跑。" >&2
  exit 1
fi

# Kotlin 侧拼出来的前缀必须与 manifest 的实际前缀一致（这就是 10-23 那个 bug）
kotlin_prefix=$(grep -oE '"com\.[A-Za-z0-9_.]*\.icon\.' "$CATALOG" | head -1 | tr -d '"' || true)
[ -n "$kotlin_prefix" ] || { echo "$CATALOG 里没找到 alias 前缀" >&2; exit 1; }
manifest_prefix=$(printf '%s\n' "$in_manifest" | head -1 | sed 's/[A-Za-z0-9]*$//')
if [ "$kotlin_prefix" != "$manifest_prefix" ]; then
  echo "类名前缀不一致，运行期会报 Component class ... does not exist：" >&2
  echo "  manifest : $manifest_prefix" >&2
  echo "  Kotlin   : $kotlin_prefix" >&2
  exit 1
fi

# 默认只能开一个，而且必须是 TerminalStarsDark（= DEFAULT_DESIGN + DEFAULT_PLATE）
enabled=$(awk '
  /<activity-alias/ { name = "" }
  /android:name="/ {
    if (match($0, /com\.[A-Za-z0-9_.]*\.icon\.[A-Za-z0-9]+/)) name = substr($0, RSTART, RLENGTH)
  }
  /android:enabled="true"/ { if (name != "") print name }
' "$MANIFEST" | sort -u)
count=$(printf '%s\n' "$enabled" | grep -c . || true)
if [ "$count" -ne 1 ] || [ "$enabled" != "${manifest_prefix}TerminalStarsDark" ]; then
  echo "默认启用的 alias 必须**恰好一个**且是 ${manifest_prefix}TerminalStarsDark，实际：${enabled:-无}" >&2
  exit 1
fi

echo "图标 alias 对账通过：$(printf '%s\n' "$in_manifest" | grep -c .) 个，前缀 $manifest_prefix，默认开 TerminalStarsDark"
