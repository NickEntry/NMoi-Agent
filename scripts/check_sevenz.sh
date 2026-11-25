#!/usr/bin/env bash
# 7z 写入器的外部验收（D425）。
#
# 为什么要外部工具：`crates/core/src/sevenz.rs` 的单测只能证明"我们自己能解回来"。
# 用户拿到导出包是拿别的解压器解（7-Zip / Keka / p7zip / Windows 自带），
# 那份包必须**别人**认得。所以这里用 `7z` 命令行逐条核对：
#   ① 头能打开（`7z t`）；② 条目清单与文件名一致（含中文、含空文件）；
#   ③ 解出来的字节逐字节等于原文。
#
# 用法：
#   ./scripts/check_sevenz.sh          # 造样本 → 写包 → 7z 验 → 清理
#   ./scripts/check_sevenz.sh --keep   # 保留产物（排查时用）
#
# 退出码：0 = 全过；1 = 有一条不过（日志里点名是哪一条）。
# 没装 `7z` 时**跳过而不是失败**：本机与 CI 的 runner 可能没有它，
# 但"没有解压器"不等于"我们的包坏了"。跳过会打一行说明。
set -euo pipefail

cd "$(dirname "$0")/.."

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

if ! command -v 7z >/dev/null 2>&1; then
  echo "跳过：没装 7z（apt install p7zip-full / brew install p7zip）"
  exit 0
fi

WORK=$(mktemp -d)
cleanup() {
  if [ "$KEEP" -eq 1 ]; then
    echo "产物保留在 $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

echo "== 编一个写包的小程序"
# 用 examples 里的那个：它造四份有代表性的内容（重复文本 / 二进制 / 空文件 / 中文路径）。
cargo build -q -p nmoi-core --example dump7z

ARCHIVE="$WORK/sample.7z"
./target/debug/examples/dump7z "$ARCHIVE" >/dev/null
SIZE=$(stat -c %s "$ARCHIVE")
echo "   包大小 $SIZE 字节"

fail=0
note() { echo "   ✗ $1"; fail=$((fail + 1)); }

echo "== ① 7z 能打开并校验"
if 7z t "$ARCHIVE" >"$WORK/t.log" 2>&1; then
  echo "   ✓ Everything is Ok"
else
  note "7z t 失败"
  sed 's/^/     /' "$WORK/t.log" | tail -20
fi

echo "== ② 条目清单"
LIST="$WORK/list.txt"
# 用 `-slt`（技术列表）取 `Path = `：`-ba` 的列宽是按内容对齐的，
# 空日期那几列全是空格，用 awk 砍列会把文件名一起砍掉。
7z l -slt "$ARCHIVE" 2>/dev/null | sed -n 's/^Path = //p' | sort >"$LIST"
for want in "a.txt" "dir/b.bin" "empty.txt" "中文/报告.md"; do
  if grep -qxF "$want" "$LIST"; then
    echo "   ✓ $want"
  else
    note "清单里没有 $want"
  fi
done

echo "== ③ 解出来逐字节核对"
mkdir -p "$WORK/out"
if 7z x -y -o"$WORK/out" "$ARCHIVE" >/dev/null 2>&1; then
  python3 - "$WORK/out" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])
expected = {
    "a.txt": ("hello world\n" * 2000).encode(),
    "dir/b.bin": bytes(i % 256 for i in range(5000)),
    "empty.txt": b"",
    "中文/报告.md": "# 标题\n正文\n".encode(),
}
bad = 0
for name, body in expected.items():
    path = root / name
    if not path.is_file():
        print(f"   ✗ 缺文件 {name}")
        bad += 1
        continue
    got = path.read_bytes()
    if got == body:
        print(f"   ✓ {name}（{len(got)} 字节）")
    else:
        print(f"   ✗ {name}：期望 {len(body)} 字节，实际 {len(got)} 字节")
        bad += 1
sys.exit(1 if bad else 0)
PY
else
  note "7z x 解包失败"
fi

if [ "$fail" -ne 0 ]; then
  echo "== 有 $fail 条不过"
  exit 1
fi
echo "== 全过"
