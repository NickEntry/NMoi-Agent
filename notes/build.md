# 构建与这台机器上的 target/ 占用

> 这份文件只讲一件在别的机器上不存在、在这台机器上会一直发生的事：
> `target/` 会自己长到十几 GB，而且是**编译产物的一种形态**，不是普通的缓存膨胀。
>
> 结论放在最前面：**别手删 `target/**/.l2s.*`，要么不动，要么 `bash scripts/target_usage.sh --clean`。**

## 1. 现象（实测数字）

- 2026-10-31：`target/` **11 GB**，`target/debug` 8.1 GB，文件 10,828 个，
  其中 `.l2s.*` **14,346 个**（含符号链接与它们的实体）。
- `cargo clean` 之后从零编一遍（三套测试：core 580 / ffi 36+3 / storage 108，共 727 条）：
  **2 分 32 秒**，`target/` **3.2 GB**，一次全量构建自己就留下 **3,512 个 `.l2s.*`**。
- 清掉之后整个工作区 14 GB → **6.2 GB**。

## 2. 为什么会这样

这台机器的文件层在写入时会把内容落成两个名字：

```
deps/.l2s.nmoi_core-1598e98a891198c7.rlib0001.0001   ← 真正的字节
deps/.l2s.nmoi_core-1598e98a891198c7.rlib0001        ← 指向上面那个
```

rustc 每次编译都把 codegen 单元的对象文件、以及链接产物写到这类临时名上，
编完就不再引用它们；而**这一层不做删除**，于是每编一次攒一批。
`target/debug/incremental/` 里还会出现套娃式名字
（`.l2s..l2s..l2s.query-cache.bin0001.00020001`），连带 rustc 自己的 GC 也删不掉它们，
日志里就出现那串 `failed to garbage collect finalized incremental compilation session directory`。

一个 hello world 也能复现：`cargo new` + `cargo build` → 14 个 `.l2s.*`。
所以这不是本项目的配置问题，`/tmp` 里编也一样（同样是 14 个），换个 `CARGO_TARGET_DIR` 治不了。

## 3. 为什么不能删 `.l2s.*`

**顶层产物有时本身就是指向 `.l2s.*` 的符号链接。** 在 `cargo build` 之后见过：

```
target/debug/libnmoi_core.rlib -> deps/.l2s.libnmoi_core-1598e98a891198c7.rlib0001
target/debug/libnmoi_ffi.so    -> deps/.l2s.libnmoi_ffi.so0001
```

按名字删 `.l2s.*` 就把这些链接的目标抽走了：文件变成悬空链接，`du` 会报
`Operation not permitted`（看着像权限问题，其实是断链）。cargo 会在下一次构建时重建，
**不会坏数据**，但缓存等于白做——和 `cargo clean` 同一个代价，却多了一类"看着在、其实不在"的文件。

（2026-10-31 那次就是这么踩的：先删了 5 GB 的 `.l2s.*`，然后发现顶层产物全是悬空的，
最后照样 `cargo clean`。教训写在这里，别再走一遍。）

## 4. 做法

```bash
bash scripts/target_usage.sh             # 看：target 多大、.l2s 几个、incremental 多大
bash scripts/target_usage.sh --clean     # 超过 5 GB 就 cargo clean（默认阈值）
bash scripts/target_usage.sh --force     # 不管多大都清
```

- 阈值可用 `NMOI_TARGET_LIMIT_MB` 改。
- 有 `cargo` / `rustc` 在跑时脚本**拒绝执行**（边编边删等于把正在写的产物抽走）。
- 清理的代价是"下次构建从头编"，本机约 **2 分半**；不清理的代价是工作区一路涨到十几 GB。

**节奏**：每批收尾（提交、推、CI 绿）之后跑一次不带参数的看一眼；超过 5 GB 就 `--clean`。
它只是一份缓存，清掉不丢任何东西——真正的产物是 APK（`/workspace/NMoi-Agent-debug.apk`）与库里的数据。

## 5. 还有哪些是"真产物、别乱删"

- `target/debug/examples/`（约 700 MB）：`--all-targets` 会把 `provider_smoke` /
  `compress_smoke` / `tool_smoke` 都编出来，冒烟脚本用的就是它们；`cargo clean` 一起清。
- `target/debug/deps/` 里同一个 crate 会有**多个 hash 的副本**（换了 feature 或工具链留下的），
  cargo 不会回收它们。这类也是 `cargo clean` 一并解决，不要按名字挑。