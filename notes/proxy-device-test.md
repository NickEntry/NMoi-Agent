# 容器内代理：真机验证步骤

> 这一份是**给你按着做**的。代码侧还差最后一段接线（见 §0），接完才轮到 §2。
> 设计在 `notes/proxy.md` §5.5，决策在 D226。

## 0. 还差的那一段（代码，不是真机）

垫片已经写好并进 CI（`crates/netshim` → `libnmoi_netshim.so` 进 jniLibs），但 App 还没做这四件事：

1. **把 .so 复制进 rootfs**：`nativeLibraryDir/libnmoi_netshim.so` → `<rootfs>/usr/local/lib/nmoi-netshim.so`。
2. **写 `/etc/ld.so.preload`**：内容就是上面那个路径。**只在文件存在且 arm64 校验通过时写**，
   写之前先备份原文件（多半不存在）；写坏了容器里所有动态程序都起不来。
3. **写 `/etc/nmoi-net.conf`**：`host/port/user/pass` 四行，来自 `nmoi_proxy_server_start_json` 的 `config` 字段。
4. **开关联动**：代理开 → 起端点 + 写配置；代理关 → 删配置 + 停端点（**不删垫片**，见 §5.6）。

这四步没做完之前，容器里的命令仍然是直连——这是设计好的降级，不是 bug。

## 1. 装包

```bash
adb install -r /workspace/NMoi-Agent-debug.apk
```

## 2. 先验代理本身（不碰容器）

1. 设置 → 工具与工作区 → 网络代理 → 打开开关，类型 SOCKS5，填你本机的代理地址/端口/账号。
2. 点「保存并应用」。
3. 「连接测试」填 `https://www.google.com`，点「测试」。
   **判据**：`成功 · 200 · xxx ms`。失败会写清是"连不上代理"还是目标的问题。
4. 回聊天页发一句话。**判据**：正常回；关掉代理再发一句，也正常回（直连）。

## 3. 验容器里的垫片

1. 设置 → 工具与工作区 → 隔离环境：确认已安装。
2. 打开「容器里的命令」开关（这会触发 §0 的四步）。
3. 让 agent 跑一条命令，或者在容器里手动跑：

```bash
# App 私有目录里
ls -l /etc/ld.so.preload /etc/nmoi-net.conf /usr/local/lib/nmoi-netshim.so
cat /etc/nmoi-net.conf          # 应当有 host/port/user/pass
curl -sS -o /dev/null -w '%{http_code}\n' https://example.com
```

**判据**：

- `ld.so.preload` 里只有一行，指向存在的文件；`ls` 能看到那个 `.so`。
- `curl` 返回 200；同时代理端点的日志里能看到这次 CONNECT（说明真的走了代理）。
- **关掉代理**（设置里那个开关）之后，**不重启容器**再跑一次 `curl`：
  仍然 200（直连），且 `cat /etc/nmoi-net.conf` 报文件不存在。
- 再打开代理，再跑一次：又走代理。**判据是"不用重开容器"**——这就是实时生效。

## 4. 验"不制造校验问题"

```bash
# 容器里
date                                   # 时钟偏差 > 5 分钟，TLS 就会失败
ls /etc/ssl/certs | wc -l              # 0 张 = rootfs 的 ca-certificates 缺了
curl -sv https://registry.npmjs.org/ -o /dev/null 2>&1 | grep -E 'issuer|verify'
npm config get registry
npx --yes cowsay@1.6.0 hi              # 有 node 的话
```

**判据**：

- `issuer` 是公共 CA（Google Trust Services / DigiCert 之类），**不是**本地根——
  说明我们的代理没有做中间人。
- `npx` 能跑出那头牛。跑不出来时先看 §5 的归因表，不要先怀疑垫片。

## 5. 出问题时怎么归因

| 现象 | 先看什么 | 结论 |
|---|---|---|
| 容器里所有命令都起不来 | `cat /etc/ld.so.preload`；`ls` 那个 .so | 垫片写坏了 → 删掉 preload 文件（App 侧要有"移除垫片"入口） |
| `curl` 报证书错误 | `date`；`ls /etc/ssl/certs \| wc -l` | 时钟或 CA 库，跟代理无关 |
| 只有走代理失败 | 端点日志 / 设置页的「测试」 | 上游代理或认证；看错误里点名的是哪一段 |
| 关掉代理后某些工具连不上 | 该工具是否缓存了 DNS | 我们不用假 IP，理论上不该有；有的话记下工具名再查 |
| Go / 静态程序不走代理 | `ldd $(which 程序)` | 没有动态链接器 → 垫片拦不到，预期行为 |

## 6. 收尾

- 关掉代理、关掉容器开关，`cat /etc/ld.so.preload` 应当**仍然有**（垫片常驻是设计），
  但 `/etc/nmoi-net.conf` 应当没了。
- 如果要把垫片彻底移除：App 里那个入口删 preload + .so，然后容器里 `curl` 仍应正常（直连）。
