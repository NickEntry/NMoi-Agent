# 密钥与容器：威胁模型、隔离设计与凭据代理

> 状态：设计已定，实现未开始（挂 PLAN 的 P7.5）。
> 关联：`docs/sandbox.md`（容器选型）、D25（工作区不依赖容器）、D15（许可）。

---

## 1. 前提：PRoot 不是安全边界

这几条是整套设计的地基，不能绕过：

| 事实 | 后果 |
|---|---|
| PRoot 用 ptrace 伪装 syscall，是**兼容层**不是隔离层 | 容器里的代码与 App **同 UID**，共享文件系统视图 |
| Android 的 App 私有目录只防"别的 App" | 防不住"同一个 App 里的其它进程" |
| 库、工作区、rootfs 都在 App 私有目录下 | 容器里的代码**读得到** |
| Android 的 loopback TCP 是全设备共享的（CERT DRD23-J） | `127.0.0.1:port` 上开的服务，别的 App 也能连 |
| Android Keystore 的密钥不可导出 | 但**同 UID 的代码可以让 App 自己去解密**，密钥保护挡不住"借用" |
| Android 官方指南：本地 IPC 要用**私有路径的 Unix domain socket** | abstract namespace 没有任何权限控制，任何 App 知道名字就能连 |

**所以正确的目标不是"把密钥藏好"，而是：让不可信代码即使拿到它能看到的一切，也拿不到能长期滥用的凭据。**

---

## 2. 威胁模型

| 角色 | 可信度 | 说明 |
|---|---|---|
| 用户 | 可信 | |
| NMoi-Agent 本体（Kotlin 外壳 + Rust core） | 可信（但要防日志/归档泄露） | |
| 容器里执行的代码 | **不可信** | 装依赖、跑 build、执行仓库里的脚本，等于在跑陌生代码 |
| 模型生成的命令 | **不可信** | 可能被提示注入 |
| 同设备其它 App | 不可信 | 只影响 loopback 与剪贴板这类通道 |
| 设备被物理拿走 / 被 root | 部分失效 | 靠 Keystore + 认证兜底 |

---

## 3. 五层设计

### L1 密钥只以密文存在

- Keystore 生成 AES-GCM 主密钥（**StrongBox 优先，TEE 回落**），落盘只有 `vault.bin` 密文。
- 敏感项可以 `setUserAuthenticationRequired(true)` 绑生物识别；常驻 API key 默认**不绑**（否则每次请求都要指纹），但逐项可开。
- 桌面端：系统 keyring；退路是 Argon2id 口令派生。
- 这一层只解决"设备被拿走 / 磁盘被翻"。

### L2 凭据代理（broker）是唯一的持有者

长期令牌**只在 App 进程里**，容器拿不到。容器里的工具通过 UDS 向 broker 请求"用一次"。

- 通道：App 私有目录下的 **Unix domain socket**（`0600`，路径绑定；不用 abstract namespace）。
- 交付方式见 §4。
- broker 负责：查白名单 → 查策略（哪个会话、哪个平台、什么操作）→ 交付 → **记一条审计**。
- 一键吊销：撤销会话 + 从 vault 删除长期令牌。

### L3 容器侧零放置

- 不把 App 私有目录 bind 进 rootfs；只 bind 用户选定的工作区，**默认只读**。
- 密钥**不走 env、不走 argv**（`/proc/<pid>/environ`、`cmdline` 同 UID 可读）。必须传就走 stdin/fd 或已建立的 socket。
  - 例外：`gh` 这类只认环境变量的工具，见 §4 的 B 方案。
- rootfs 里不落 provider 配置，配置里只写占位符 `${SECRET:provider_x}`，替换只发生在 broker/代理层。

### L4 蜜罐告警

在容器"看得见但不该碰"的位置放**诱饵凭据**（无权限、一次性、被使用就打点）。
理由是：我们可以论证"容器碰不到密钥"，但只有**实测过**才算证据。

### L5 硬边界只有 VM

如果威胁模型升级为"容器里是恶意代码，且不允许它碰到 App 的任何文件"，PRoot 无论怎么配都不够。
硬边界只有：**AVF / pKVM 虚拟机**、另一台设备、或云端。
`android:isolatedProcess` 那种独立 UID 的进程没有网络也没有文件权限，只适合纯计算。

---

## 4. 让容器里的 `git` / `gh` 无障碍访问（核心诉求）

目标：在容器里 `git clone/push`、`gh pr create`、`curl` API 都能正常用，**但长期令牌不进容器**。

三种交付方式，可以单独用，也可以叠加：

| 方式 | 原理 | 容器里会出现真实令牌吗 | 覆盖范围 | 成本 |
|---|---|---|---|---|
| **A. git credential helper** | rootfs 里放一个 shim `git-credential-nmoi`，git 需要凭据时由 shim 走 UDS 向 broker 要 | **会**（在 git 进程内存里，不落盘） | 只有 git | 低 |
| **B. 透明 TLS 代理** | 容器设 `https_proxy`，broker 终结 TLS、注入 `Authorization` | **不会** | git / gh / curl / 任何 HTTP 客户端 | 中（要把我们的 CA 装进 rootfs） |
| **C. SSH agent 转发** | 私钥留在 App，容器只拿到签名能力（`SSH_AUTH_SOCK`） | **不会**（只有签名结果） | 走 SSH 的 git | 中 |

### 推荐组合（已按平台定稿）

```text
Android：B 为主（透明代理，容器里连假令牌都能用）+ A 作兜底（绕不过代理的工具）
Linux  ：不用代理。命名空间就是真边界，容器看不见宿主的密钥目录
Windows：不用代理也不用容器。DPAPI 管密钥，原生命令默认关
```

**A 的落地细节（Android 兜底方案）**

- rootfs 里放 `/usr/local/bin/nmoi-credential`（我们自己的 shim，几十行 shell 或静态小二进制）。
- `/etc/gitconfig` 里写：

  ```ini
  [credential]
      helper = nmoi          # 只保留我们这一个
      # 不要加 cache / store：前者会把令牌缓到磁盘，后者直接落盘
  ```

- 交付时由 broker 判定：`protocol=https`、`host` 在白名单、当前会话被允许访问该平台 → 返回 `username` + `password`（真实令牌或平台侧的等效凭据）。
- 它存在的意义是**兜底**：绕不过透明代理的工具（尤其内置 webpki-roots 的 rustls 系 CLI）走这条路。
- **不做磁盘缓存**：`credential.helper` 只挂我们的 shim，不挂 `store`/`cache`；`.git-credentials` 永远不存在。
- git 的 `get` 每次都会问，所以每次交付都能被审计、被拒绝、被记账。

**B 的落地细节（Android 默认方案）**

- 容器内设置 `https_proxy=http://<uds-or-loopback>`、`http_proxy` 同理；把我们的 CA 证书装进 rootfs（`/usr/local/share/ca-certificates/` + `update-ca-certificates`）。
- broker 按 `Host` 头判定目标，只允许白名单域名；**替换/注入** `Authorization`，其余头部原样透传。
- 容器里可以给 `gh` 一个**占位令牌**（无权限的假串），真实令牌由代理替换——这样即使容器里的代码把 `GH_TOKEN` 打印出来也没用。
- 代价：TLS 终结意味着必须信任我们自己的 CA；有证书固定的工具会失败（对 git/gh 不是问题）。

**C 的落地细节**

- App 侧跑一个 SSH agent（私钥放在 App 私有目录、由 Keystore 包裹，或直接用 Keystore 里的密钥）。
- 容器里 `export SSH_AUTH_SOCK=/run/nmoi/agent.sock`，并把该 socket 只 bind 给容器内的 git 用户。
- 容器拿不到私钥，只能请求签名；会话结束 socket 即失效。

### B 的证书信任尾（这是它唯一真正的坑）

MITM 要求容器里的工具**信任我们的 CA** 且**愿意走代理**。实测分布：

| 工具 | 读系统证书库 | 处置 |
|---|---|---|
| git（libcurl / GnuTLS） | ✅ | 直接可用 |
| gh / Go 系 | ✅（也认 `SSL_CERT_FILE`） | 直接可用 |
| curl | ✅ | 直接可用 |
| Python `requests` | ❌ 默认 certifi | 注入 `REQUESTS_CA_BUNDLE` |
| Node / npm | ❌ | 注入 `NODE_EXTRA_CA_CERTS` |
| rustls 系 CLI | ❌ 常内置 webpki-roots | **走 A 兜底，或明确失败** |

rootfs 里统一注入：

```bash
export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
export CURL_CA_BUNDLE="$SSL_CERT_FILE"
export REQUESTS_CA_BUNDLE="$SSL_CERT_FILE"
export GIT_SSL_CAINFO="$SSL_CERT_FILE"
export NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/nmoi.crt
```

原则：**工具失败要说人话**（"这个工具不信任本地代理的证书"），不能只给一个 403 或者 `SSL: CERTIFICATE_VERIFY_FAILED` 让人猜。

### 平台差异（GitHub / Codeberg）

| 平台 | 令牌形态 | 收窄能力 | 备注 |
|---|---|---|---|
| GitHub | fine-grained PAT（可限制到具体仓库 + 具体权限 + 到期日）；也有 classic PAT | 强 | broker 里按"平台 × 仓库 × 权限"记账；`gh` 认 `GH_TOKEN` |
| Codeberg（Forgejo） | access token，scope 是 API 类别（`write:repository` 等），**基本是账号级** | 弱 | Forgejo 15 引入了仓库级 token，Codeberg 上是否开放需要实测；**因此审计与一键吊销是主要补偿手段** |

需要放行的域名白名单（写进 broker 策略，默认只列这些）：

```text
github.com, api.github.com, codeload.github.com,
raw.githubusercontent.com, objects.githubusercontent.com,
gist.github.com, uploads.github.com
codeberg.org, api.codeberg.org
```

### 审计与可见性

- 每次交付写一条：`{session_id, host, username, scope, ttl, at, allowed|denied, reason}`。
- UI 上能看到"这次容器会话用过哪些平台"，并能一键吊销。
- 交付给 git 的令牌**不进日志**，只记指纹（前 6 位 + 长度）与用途。

### 会拒绝交付的情况

| 条件 | 行为 |
|---|---|
| 目标 host 不在白名单 | 拒绝并记审计 |
| 当前会话未获用户授权访问该平台 | 拒绝，UI 弹出授权请求 |
| 容器网络开关是关的 | 直接拒绝（连不上也走不到这一步） |
| 令牌已过期 / 已被吊销 | 拒绝，提示重新授权 |
| 请求来自非预期进程（PID 不在本次会话的进程树里，B/C 方案可用 `SO_PEERCRED` 校验） | 拒绝并告警 |

---

## 5. 实现计划（P7.5）

| 步 | 内容 | 验收 |
|---|---|---|
| 1 | `core::secrets`：`SecretHandle`（Debug/Display 自动脱敏）、`Vault` trait、内存实现 | 打印任何 `SecretHandle` 都不出明文 |
| 2 | Android Keystore 后端（Kotlin 侧）+ 桌面 keyring/Argon2id 退路 | 落盘文件里 `grep` 不到明文；删 Key 后无法解密 |
| 3 | `core::broker`：UDS 服务、协议（长度前缀 JSON）、白名单与策略、审计 | 未授权 host 一律被拒；每次交付有审计记录 |
| 4 | rootfs shim + `/etc/gitconfig`（A 方案） | 容器内 `git clone`/`git push` 可用；容器里搜不到 `.git-credentials` |
| 5 | 透明代理（B 方案，可选开启） | 容器内 `GH_TOKEN` 是假值时 `gh api user` 仍成功；打印 `GH_TOKEN` 无价值 |
| 6 | SSH agent 转发（C 方案，可选） | 容器内 `ssh -T git@github.com` 成功；私钥文件不存在 |
| 7 | 蜜罐（L4） | 诱饵被读取/使用时产生告警 |

**依赖许可**：`zeroize`、`aes-gcm` / `chacha20poly1305`、`argon2` 均为 MIT / Apache-2.0，与 GPL-3.0-only 兼容。

---

## 6. 平台矩阵（定稿）

| | Android | Linux（无桌面） | Windows |
|---|---|---|---|
| 密钥静态保护 | Keystore（StrongBox 优先） | **systemd-creds**（`--with-key=host+tpm2`） | **DPAPI**（`CryptProtectData`） |
| 令牌进容器？ | 不进（透明代理注入） | 本来就不进（容器看不见宿主目录） | 无容器 |
| 是否自研代理 | **是**（PRoot 不是边界，必须由代理兜） | **否**（多余攻击面） | **否** |
| 容器 | PRoot + 自管 rootfs | rootless podman / systemd-nspawn / bubblewrap | 无（只原生命令） |
| 原生命令 | 无（只能进容器） | 可选（等同用户 shell 权限，默认关） | 可选（等同用户权限，默认关） |
| 边界强度 | 弱（同 UID + 伪 chroot） | **强**（真 mount namespace） | 无（同用户） |

**结论**：密钥保护不用自研，三个平台各用系统原语；只有 Android 需要透明的凭据代理，
因为那上面的容器没有真正的文件系统边界。

## 7. 参考

- Android 本地 socket 安全指南（私有路径 UDS、避免 abstract namespace）：<https://developer.android.com/privacy-and-security/risks/unprotected-local-sockets>
- CERT DRD23-J：不要在敏感数据上用 loopback
- Android Keystore（TEE / StrongBox、`setUserAuthenticationRequired`）：<https://developer.android.com/privacy-and-security/keystore>
- git credential helper 协议：`git help credentials`
- Forgejo 仓库级 token（v15 引入）：<https://forgejo.org/docs/latest/user/token-scope/>
