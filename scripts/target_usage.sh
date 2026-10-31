#!/usr/bin/env bash
# target/ 的占用报告，必要时整个清掉。
#
# 为什么需要它（不是洁癖）：这台机器的文件层会把每一次写入落成
# `<名字>.0001.0001` 再加一个指向它的名字，于是 rustc 每编一次就在 target/debug 下
# 留一批 `.l2s.*` 临时对象——**一次全量构建约 3500 个**。实拍到的一天：11 天累计
# 14,346 个、target 长到 11 GB（其中约 7.8 GB 是这类残留）。
#
# 为什么清法是 `cargo clean` 而不是删 `.l2s.*`：
# 顶层产物**有时本身就是**指向某个 `.l2s.*` 的符号链接（`cargo build` 之后见过
# `target/debug/libnmoi_core.rlib -> deps/.l2s.libnmoi_core-….rlib0001`）。
# 手删 `.l2s.*` 会留下悬空链接——cargo 会自己重建、不会坏数据，但缓存等于白做，
# 还多出一类"看着在、其实不在"的文件。整目录清掉没有这个问题。
#
# 用法：
#   bash scripts/target_usage.sh            只看
#   bash scripts/target_usage.sh --clean    超过阈值（默认 5 GB）才清
#   bash scripts/target_usage.sh --force    不管多大都清
#
# 阈值可用 NMOI_TARGET_LIMIT_MB 覆盖。清完下一次构建从头编，本机约 2 分半。
set -euo pipefail
cd "$(dirname "$0")/.."

limit=${NMOI_TARGET_LIMIT_MB:-5120}
clean=0
force=0
for arg in "$@"; do
    case "$arg" in
        --clean) clean=1 ;;
        --force) clean=1; force=1 ;;
        -h | --help)
            sed -n '1,20p' "$0"
            exit 0
            ;;
        *)
            echo "不认识的参数：$arg" >&2
            exit 2
            ;;
    esac
done

if [ ! -d target ]; then
    echo "还没有 target/（没在这儿编过）"
    exit 0
fi

# `du` 会因为那种"列得出来、stat 不了"的幽灵条目返回非零，而 set -e 会把它变成静默退出——
# 恰好在最该看这份报告的时候什么都不打印。所以两处都容错，拿不到就写 0。
mb=$(du -sm target 2>/dev/null | cut -f1 || true)
mb=${mb:-0}
l2s=$(find target -name '.l2s.*' 2>/dev/null | wc -l || true)
inc=$(du -sm target/debug/incremental 2>/dev/null | cut -f1 || true)
inc=${inc:-0}
printf 'target        %6s MB\n' "$mb"
printf '.l2s 残留     %6s 个\n' "$l2s"
printf 'incremental   %6s MB\n' "${inc:-0}"
printf '阈值          %6s MB\n' "$limit"

if [ "$clean" -eq 0 ]; then
    if [ "$mb" -gt "$limit" ]; then
        echo "超了：bash scripts/target_usage.sh --clean"
    fi
    exit 0
fi
if [ "$force" -eq 0 ] && [ "$mb" -le "$limit" ]; then
    echo "没超阈值，不清（要清就 --force）"
    exit 0
fi
# 边编边删等于把正在写的产物抽走：这类动作只在没有编译进程时做。
if pgrep -x cargo >/dev/null 2>&1 || pgrep -x rustc >/dev/null 2>&1; then
    echo "还有 cargo / rustc 在跑，等它停了再清" >&2
    exit 1
fi

echo "== cargo clean"
cargo clean
echo "== 清完：$(du -sm target 2>/dev/null | cut -f1 || echo 0) MB（下次构建从头编）"