#!/usr/bin/env bash
# C 侧 ABI 验收（计划 §3.11 / P6.1 F1）。
#
# 这个脚本证明的是"从 C 真的能调通"，不是"Rust 单测过了"。
# 依赖只有 cargo 和 cc——刻意不引 JSON 库、不引测试框架，
# 这样在有 cc 的任何机器上（含 PRoot）都能跑。
set -euo pipefail

cd "$(dirname "$0")/.."

target_dir="${CARGO_TARGET_DIR:-target}"

echo "== 构建 cdylib =="
cargo build -q -p nmoi-ffi

out="${TMPDIR:-/tmp}/nmoi-abi-smoke"
echo "== 编译 C 验收程序 =="
cc -std=c99 -Wall -Wextra -Werror=implicit-function-declaration -O1 \
   -I crates/ffi/include \
   -o "$out" crates/ffi/tests/c/abi_smoke.c \
   -L "$target_dir/debug" -lnmoi_ffi \
   -Wl,-rpath,"$PWD/$target_dir/debug"

echo "== 跑 =="
"$out"
