#!/usr/bin/env bash
# 7z 打包 C ABI 的端到端验收（D425）。
#
# `scripts/check_sevenz.sh` 验的是 `nmoi_core::sevenz` 写出来的字节；
# 这一份验的是**界面真正会走的那条路**：`nmoi_sevenz_write_json` → 盘上的包 → 7z 解开。
#
# 覆盖四件事：
#   ① 四类内容（重复文本 / 二进制 / 空文件 / 中文路径）都能打进去；
#   ② `7z t` 全过；
#   ③ 解出来逐字节等于原文；
#   ④ **连跑两遍安全**：第二遍覆盖第一遍的包，结果一样。
#
# 用法：./scripts/check_sevenz_abi.sh [--keep]
set -euo pipefail

cd "$(dirname "$0")/.."

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

if ! command -v 7z >/dev/null 2>&1; then
  echo "跳过：没装 7z（apt install p7zip-full）"
  exit 0
fi
command -v gcc >/dev/null 2>&1 || { echo "跳过：没有 gcc"; exit 0; }

WORK=$(mktemp -d)
cleanup() {
  if [ "$KEEP" -eq 1 ]; then echo "产物保留在 $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

echo "== 编 cdylib"
cargo build -q -p nmoi-ffi
SO=$(find target/debug -maxdepth 1 -name 'libnmoi_ffi.so' | head -1)
[ -n "$SO" ] || { echo "找不到 libnmoi_ffi.so" >&2; exit 1; }
SODIR=$(cd "$(dirname "$SO")" && pwd)

echo "== 造样本"
mkdir -p "$WORK/src/sub" "$WORK/src/中文"
python3 - "$WORK/src" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
(root / "a.txt").write_bytes(("hello world\n" * 2000).encode())
(root / "sub" / "b.bin").write_bytes(bytes(i % 256 for i in range(5000)))
(root / "empty.txt").write_bytes(b"")
(root / "中文" / "报告.md").write_bytes("# 标题\n正文\n".encode())
PY

cat >"$WORK/driver.c" <<'EOF'
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern char *nmoi_sevenz_write_json(const char *spec);
extern void nmoi_string_free(char *ptr);

/* 读一个 spec 文件、调一次、把 JSON 打到 stdout。成功返回 0。 */
int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("spec"); return 2; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc((size_t)n + 1);
    if (!buf) return 2;
    if (fread(buf, 1, (size_t)n, f) != (size_t)n) { fclose(f); return 2; }
    buf[n] = 0;
    fclose(f);

    char *out = nmoi_sevenz_write_json(buf);
    printf("%s\n", out);
    int ok = strncmp(out, "{\"ok\":true", 10) == 0;
    nmoi_string_free(out);
    free(buf);
    return ok ? 0 : 1;
}
EOF

gcc -o "$WORK/driver" "$WORK/driver.c" -L"$SODIR" -lnmoi_ffi -Wl,-rpath,"$SODIR"
echo "   驱动已编好"

pack() {
  local dest="$1"
  python3 - "$WORK/src" "$dest" >"$WORK/spec.json" <<'PY'
import json, sys, pathlib
root = pathlib.Path(sys.argv[1])
dest = sys.argv[2]
names = ["a.txt", "sub/b.bin", "empty.txt", "中文/报告.md"]
print(json.dumps({
    "dest": dest,
    "entries": [{"name": n, "path": str(root / n)} for n in names],
}, ensure_ascii=False))
PY
  "$WORK/driver" "$WORK/spec.json"
}

fail=0
note() { echo "   ✗ $1"; fail=$((fail + 1)); }

verify() {
  local archive="$1"
  local label="$2"

  7z t "$archive" >"$WORK/t-$label.log" 2>&1 || { note "[$label] 7z t 失败"; return; }
  echo "   ✓ [$label] Everything is Ok"

  rm -rf "$WORK/out-$label"
  mkdir -p "$WORK/out-$label"
  7z x -y -o"$WORK/out-$label" "$archive" >/dev/null 2>&1 \
    || { note "[$label] 7z x 失败"; return; }

  python3 - "$WORK/src" "$WORK/out-$label" "$label" <<'PY' || fail=$((fail + 1))
import sys, pathlib
src, out, label = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
bad = 0
for rel in ["a.txt", "sub/b.bin", "empty.txt", "中文/报告.md"]:
    want = (src / rel).read_bytes()
    path = out / rel
    if not path.is_file():
        print(f"   ✗ [{label}] 缺 {rel}")
        bad += 1
        continue
    got = path.read_bytes()
    if got != want:
        print(f"   ✗ [{label}] {rel}：期望 {len(want)} 字节，实际 {len(got)}")
        bad += 1
    else:
        print(f"   ✓ [{label}] {rel}（{len(got)} 字节）")
sys.exit(1 if bad else 0)
PY
}

echo "== ① 第一遍"
ARCHIVE="$WORK/pack.7z"
if OUT=$(pack "$ARCHIVE"); then
  echo "   $OUT"
  verify "$ARCHIVE" "第一次"
else
  note "第一遍打包失败：$OUT"
fi
FIRST_SUM=$(sha256sum "$ARCHIVE" | cut -d' ' -f1)
FIRST_SIZE=$(stat -c %s "$ARCHIVE")

echo "== ② 第二遍（覆盖同一个包）"
if OUT=$(pack "$ARCHIVE"); then
  echo "   $OUT"
  verify "$ARCHIVE" "第二次"
else
  note "第二遍打包失败：$OUT"
fi
SECOND_SUM=$(sha256sum "$ARCHIVE" | cut -d' ' -f1)
SECOND_SIZE=$(stat -c %s "$ARCHIVE")

echo "== ③ 两遍结果一致"
if [ "$FIRST_SUM" = "$SECOND_SUM" ] && [ "$FIRST_SIZE" = "$SECOND_SIZE" ]; then
  echo "   ✓ $SECOND_SUM（$SECOND_SIZE 字节）"
else
  note "两遍不一致：$FIRST_SIZE/$FIRST_SUM vs $SECOND_SIZE/$SECOND_SUM"
fi

echo "== ④ 坏输入要被拒绝"
python3 - >"$WORK/bad.json" <<'PY'
import json
print(json.dumps({"dest": "/tmp/should-not-exist.7z",
                  "entries": [{"name": "../escape.txt", "path": "/etc/hostname"}]}))
PY
if "$WORK/driver" "$WORK/bad.json" >"$WORK/bad.log" 2>&1; then
  note "越界名字该被拒绝，却成功了"
else
  if grep -q 'UnsafeName\|不安全' "$WORK/bad.log"; then
    echo "   ✓ 越界名字被拒绝"
  else
    note "拒绝了，但理由不是 UnsafeName：$(head -c 200 "$WORK/bad.log")"
  fi
fi
if [ -e /tmp/should-not-exist.7z ]; then
  note "被拒绝的那一次仍然留下了文件"
  rm -f /tmp/should-not-exist.7z
fi

if [ "$fail" -ne 0 ]; then
  echo "== 有 $fail 条不过"
  exit 1
fi
echo "== 全过"
