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
## 6. 当前这份 APK 是哪来的（每次拉了新的就更新这一段）

```text
路径      /workspace/NMoi-Agent-debug.apk
字节      19,205,521
sha256   bef63175bb5947cf1202c788c7dda2a5（前 32）
commit   d9201b4（D365）
CI run   44951767806（七 job 全绿）
来源     gh run download <run> -n nmoi-debug-apk  → app-debug.apk
四样核对 classes.dex / assets/notices/LICENSE / assets/notices/LICENSES/Apache-2.0.txt / lib/arm64-v8a/libnmoi_ffi.so

上一份      19,191,201 / 0f053453531ee56f262bb6de148d2647 / 6a09aa7（run 44942347185，D364）
再上一份    19,176,781 / d27a8f46b2a9bb8fcd900fcf8209f439 / 386ea04（run 44934962110，D363）
再上一份    19,173,433 / 3b83425d1fcca93cab8873c909a5a0b4 / 675a907（run 44882728158，D362）
再上一份    19,164,985 / 43fe012652edce76698da3ca0030c0c4 / 98068df（run 44874268634，D361）
再上一份    19,160,349 / d7500dc79eaea769cf5e329da22285b1 / ba16b54（run 44870327200，D360）
再上一份    19,156,813 / 8439fa791669b6267f1ca8094e10669a / 5c8230c（run 44807615247，D359 全批）
再上一份    19,156,997 / 86d56d8edc4b9b081d4c4a7a92ffc3a0 / 1ee4bbc（run 44792601156）
再上一份    19,153,461 / 0862d229226f95c7291a3f335a402b29 / 1764322（run 44782264324）
```

**上一份（19,125,921 / a6e46245… / `56c4d1b`）在 D354 之后一直是旧的**，因为 android job
从 D354 起连续红了三次，红在两个地方：

1. `JNI 符号对账`：Kotlin 加了 `external fun`，`.so` 里没有对应的 `Java_*` 包装
   （`jni` feature 本机编不出来——rustc 1.75 不够，只能靠 CI 发现）。
2. `Kotlin 单测`（其实是编译）：`ScrollableDefaults.flingBehavior()` 是 `@Composable`，
   不能在 `remember { }` 里取。本机 kotlinc 没有 Compose 插件，查不到这一类错；
   仓库里那份 `compose-compiler.jar` 是给旧版编译器用的，也用不上。

**教训**：提交之后要点一次 `gh run list` 看结论。本机全绿 ≠ CI 绿：JNI 与 Compose 上下文
这两类检查只有 CI 有。拿包之前先确认「这一份对应的 run 是绿的」，否则装上去的是旧代码，
所有性能观察都白做。
