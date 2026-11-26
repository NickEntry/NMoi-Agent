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
字节      19,906,801
sha256   5f3c7c6527339c9dfe5f160bc1effaaa（前 32）
          全：5f3c7c6527339c9dfe5f160bc1effaaaf0a07c4b63d7be1fde715ac16e09d0ba
commit   231cd3a（D434/D435：存储三种形状、内置册强制生效、思考强度跟模型、启动对账、系统提示词输入框）
CI run   45901738649（七 job 全绿）
schema   v21（未动）
JNI      76 个（+listAllConversationsJson：存储页「会话文件」要不过滤助手的列表）
四样      字节 / sha256 / arm64 .so 里 76 个 Java_ 符号（含新的那个）/ assets/presets 五份在

上一份      19,812,997 / 8cec218912837baf1cb0efb048beef29 / e06c6fe（run 45736109901，D433）
再上一份    19,812,845 / b2f531d9a458c696bebaea8d0ce09572 / 127edfb（run 45727619573，D431/D432）
再上一份    19,794,861 / 1416d24636bec4c44bdcd6fd4dab3342 / e3f8db7（run 45716550375，D430）
再上一份    19,789,233 / e95d40096c9805f0c4487e87826e5195 / 4e98ac5（run 45703171369，D429）
再上一份    19,797,165 / cfd5f6d9440e7904394c40d4c671523d / 4e32258（run 45664890099，D428）
再上一份    19,774,525 / 0f7c9bdd146de604c29ff8b22f1cf416 / aeabcb2（run 45629683436，D423–D426）
再上一份    19,772,933 / 27bf2e9d88daa660c5f9c0468e3e0d69 / 95b20a8（run 45626061239，D424 备份入口）
再上一份    19,768,233 / f531de5660572a9662b0c456e6360d43 / 7f59512（run 45620273261，D423–D426）
再上一份    19,665,493 / 224ff143f9c7cf210612bbdf9d4fa551 / 143659a（run 45560116753，D422）
再上一份    19,665,601 / 47cbc22f775d71d5d6714abfd96ceccb / d67a67e（run 45539544352，D421）
再上一份    19,665,821 / b79d8f20f0255d7f6c9553e248f3d3db / 5107bee（run 45528055281，D420）
再上一份    19,681,989 / d68e472343bd10468019936be36482e5 / 6991172（run 45506940667，D417/D418/D419）
再上一份    19,680,805 / 9ed72baf5b0e42cb3753d070545b8bae / 2ce5273（run 45496159428，D417/D418）
再上一份    19,672,761 / 6f547b6f8fc60c188bdf77e64aad215f / 417a499（run 45371865622，D416）
再上一份    19,672,481 / beb9934c34a67763aa4a77d38b260cf1 / edb6581（run 45366390755，D415）
再上一份    19,670,093 / fa08e013d3790a200e63b3c193161415 / 4cf39cd（run 45356829714，D411）
再上一份    19,670,385 / 863a6360d5fd1179f8d17a8475375c2f / bc2450d（run 45346009629，D410）
再上一份    19,670,133 / 3d8a951d53f8d537915e9f68aed32d7e / c917b59（run 45343676341，D409）
再上一份    19,670,165 / 0b0ad6e187a5e91ce196265d564d0c0a / adf8617（run 45341037756，D408）
再上一份    19,669,741 / 53b7bcfd84ca7ccf2aa05bb3d4628763 / e445113（run 45333724943，D407）
再上一份    19,670,021 / 3ef10ab12c4c82a30cdfa34210e0ff74 / 1d2ecc5（run 45326852953，D406）
再上一份    19,669,601 / 6ee830932716c247dade77bf2956b0c5 / b1bd7dc（run 45323662780，D405）
再上一份    19,664,385 / 7569908c7559201f0583e56c92c50132 / 3227e4f（run 45300385738，D402–D404）
再上一份    19,629,033 / a723b55a2a1ffd6e75b2f640b043f816 / 19a5713（run 45250938086，D397–D401）
再上一份    19,558,425 / 96b463cd9f5710d324033e34ce2f26c0 / 158ff0a（run 45140398864，D396）
再上一份    19,459,459 / 8003e6c69845c540d5a7c9a8607daca2 / 46d0716（run 45121958783，D392）
再上一份    19,311,241 / 37aacd999c73bd36b126375e331f9b48 / 154eabb（run 45072570525，D372）
再上一份    19,214,597 / 3f17f5ee27ad0de147cfc5b779822146 / 4fc38f8（run 45016559411，D377）
再上一份    19,214,221 / 80d4c8357c2fed4355da9576d41a3b29 / 65f999e（run 45014651018，D376）
再上一份    19,215,233 / 602225ab5e4ba3d57d18c2347f60f1b1 / 82631fe（run 45009513814，D375）
来源     gh run download <run> -n nmoi-debug-apk  → app-debug.apk
四样核对 classes.dex / assets/notices/LICENSE / assets/notices/LICENSES/Apache-2.0.txt / lib/arm64-v8a/libnmoi_ffi.so

上一份      19,213,329 / f93eb1e8df8fca7702fbb954153a8100 / 3cb892d（run 44963540391，D367）
再上一份    19,209,361 / cb8f9ced2bc3feeb10673daf76a2c402 / 9058665（run 44954775890，D366）
再上一份    19,205,521 / bef63175bb5947cf1202c788c7dda2a5 / d9201b4（run 44951767806，D365）
再上一份    19,191,201 / 0f053453531ee56f262bb6de148d2647 / 6a09aa7（run 44942347185，D364）
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
