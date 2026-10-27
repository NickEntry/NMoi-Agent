# 签名密钥与 CI 注入（P9.1 / D65）

> 结论先说：**CI 里的 secret 只是副本，主密钥必须离线保存两份。**
> Android 的包名绑死签名，keystore 丢了，已发出去的应用就再也更新不了——
> 这不是"重新生成一个"能解决的问题。

本文件只管"怎么存、怎么注入、怎么擦"。材料本身（`.jks` / `.pfx` / GPG 私钥）不进仓库、
不进 issue、不进聊天记录。

## 0. 包名与当前阶段（D102）

- **`applicationId = com.nmoi.agent`**。Kotlin 的 `namespace` 另用 `com.nmoi.agent`，
  两者不必相同——前者是安装身份，后者是代码包名。
- **1.0 之前，keystore 丢了就换新的**：项目还没对外宣传，卸载重装、数据从零再来一次，
  成本是零。**但现在这把钥匙已经有了（D170）**，而且已经灌进两边的 secret——
  真丢了就是"已装的包再也覆盖不上"，所以从这一刻起请按第 1 节备份。"离线两份"那套等 1.0 再严格执行。
- 但**同一个阶段内必须固定同一套 keystore**：CI runner 每次都是全新虚拟机，Android 的
  debug keystore 由构建机自动生成——签出来的包签名各不相同，第二个包就装不上去，只能先卸载
  （会话库、设置全丢）。所以从第一个 APK 起就走本节这套 secret 注入，而不是靠 debug 签名。

## 1. 要签什么，存哪儿

| 用途 | 材料 | 存在的变量名（两边一致） |
|---|---|---|
| Android 发版签名 | `release.jks`（base64） | `ANDROID_KEYSTORE_B64` |
| | store 口令 | `ANDROID_KEYSTORE_PASSWORD` |
| | key 别名 | `ANDROID_KEY_ALIAS` |
| | key 口令 | `ANDROID_KEY_PASSWORD` |
| Windows 代码签名 | `.pfx`（base64） | `WINDOWS_PFX_B64` |
| | pfx 口令 | `WINDOWS_PFX_PASSWORD` |
| Linux 包签名（deb/rpm） | GPG 私钥（base64） | `LINUX_GPG_KEY_B64` |
| | 私钥口令 | `LINUX_GPG_PASSPHRASE` |
| | GPG key id | `LINUX_GPG_KEY_ID` |

**主存**：离线两份（U 盘 + 可信的离线介质），口令与文件分开存放。
**CI 副本**：Codeberg 为主（主仓在那儿），GitHub 只在确实要发 GitHub Release 时才有。

## 2. 两边怎么配

### 现在的实际做法（D170，2026-10-27）

`ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
四个 secret 已经分别放进 **GitHub 仓库级 secret** 与 **Codeberg 仓库级 secret**，
`.github/workflows/ci.yml` 的 `android` job 在打包前把它们解到 `$RUNNER_TEMP/nmoi-release.jks`，
再以 `NMOI_*` 环境变量交给 Gradle（`android/app/build.gradle.kts` 的 `signingConfigs`）。
打包之后那一步 `验签` 会拿证书指纹跟仓库里写死的**公开**指纹比对，指纹不对就红灯。

**为什么这里是仓库级、而不是下面说的 environment secret**：这个 job 就是每天跑的 CI，
挂了 `environment: release` 每次都要人工点一次批准，等于把日常构建变慢。等真要做
"打 tag 才出正式包"的发布流程时，再把它挪到带 `environment` 的独立 workflow 里。

### （原计划）GitHub：用 environment secret，不用仓库级 secret

```
仓库 → Settings → Environments → New environment: release
  → Environment secrets → 添加上面那些变量
  → Deployment protection rules → Required reviewers: 你自己
```

仓库级 secret 任何 workflow 都能读，被改坏一次就是全泄；environment secret 只有在
`environment: release` 的 job 里、并且过了人工批准才拿得到。

### Codeberg / Woodpecker

```
Repo → Settings → Secrets → Add secret（名字同上，逐条加）
```

pipeline 里只有**声明了 `secrets:` 的步骤**才拿得到，且默认不注入来自 fork 的 PR。

## 3. 早先的可粘贴片段（**已经被 ci.yml 采纳**，留作记录）

下面这段当初没放进 `workflows/`（理由是"没有材料，红久了没人看"）。材料到位后，
它被并进了 `.github/workflows/ci.yml` 的 `android` job（差别：不是独立 workflow、
不是 `environment`，见上一节），所以**不要**再把它另存一份——
两份就会漂移，而漂移的那一份通常是没人跑的那一份。

```yaml
name: release
on:
  push:
    tags: ["v*"]
  workflow_dispatch:

jobs:
  android:
    runs-on: ubuntu-latest
    environment: release                 # 需要人工批准
    env:
      KEYSTORE_B64: ${{ secrets.ANDROID_KEYSTORE_B64 }}
      KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
      KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
      KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
    steps:
      - uses: actions/checkout@v4

      # 解到 runner 的临时目录：不进工作树、不进 artifact。
      # 注意：secrets 不能用在 if 条件里，所以先映射成 env 再判空。
      - name: 解出 keystore
        if: env.KEYSTORE_B64 != ''
        run: |
          set -euo pipefail
          umask 077
          out="$RUNNER_TEMP/release.jks"
          printf '%s' "$KEYSTORE_B64" | base64 -d > "$out"
          echo "KEYSTORE_PATH=$out" >> "$GITHUB_ENV"

      - name: 构建（签名参数全部从 env 读，仓库里不写任何口令）
        run: ./gradlew assembleRelease
        # build.gradle 里这样读，缺了就明确失败，不要静默出一个未签名包：
        #   storeFile file(System.getenv("KEYSTORE_PATH") ?: error("没有 KEYSTORE_PATH"))
        #   storePassword System.getenv("KEYSTORE_PASSWORD")
        #   keyAlias System.getenv("KEY_ALIAS")
        #   keyPassword System.getenv("KEY_PASSWORD")

      - name: 擦掉材料（失败也要跑）
        if: always()
        run: rm -f "$RUNNER_TEMP/release.jks"
```

Woodpecker 版形状一样，差别是 secret 由步骤声明：

```yaml
when:
  - event: tag
steps:
  - name: android
    image: ghcr.io/your/android-build:latest
    secrets: [android_keystore_b64, android_keystore_password, android_key_alias, android_key_password]
    commands:
      - set -eu; umask 077
      - printf '%s' "$ANDROID_KEYSTORE_B64" | base64 -d > "$CI_WORKSPACE/../release.jks"
      - ./gradlew assembleRelease
      - rm -f "$CI_WORKSPACE/../release.jks"
```

## 4. 禁令清单（每一条都对应一次真实事故）

- **不要 `echo` / `cat` / `env` 打印 secret**，也不要 `set -x`——追踪输出会把值打出来。
  两边的日志 mask 只挡"整段匹配"，拼过、截过、base64 解过的值它就不认了。
- **不要写进仓库**（哪怕随后 `rm`）。`keystore.properties` 已在 `.gitignore` 里，别绕过它。
- **不要放进 artifact**：上传 APK 可以，上传 `*.jks` 等于把密钥发给所有下游。
- **不要在 PR 触发的 job 里注入**：fork 的 PR 能跑 workflow，绝不能让它有机会看到材料。
  GitHub 默认不给 fork 的 PR 发 secret，但**不要靠默认**——`event: tag` / `environment` 是明写出来的。
- **不要 `curl … | sh` 装签名工具**：那些引导脚本后面还会装什么你看不见。

## 5. 轮换与丢失

| 情况 | 后果 | 处理 |
|---|---|---|
| CI 里那份泄露 | 别人能签出"看起来是你发的"包 | 立刻换新 keystore，旧 app 发最后一次更新（带新签名不可行——Android 不允许换签名），改用**新包名**发新应用并在旧版里提示迁移 |
| 离线主密钥还在、CI 丢了 | 没事 | 重新灌一次 secret |
| 离线主密钥也丢了 | 已安装用户永远收不到更新 | 这就是"必须离线两份"的全部理由 |
| GPG 私钥泄露 | 别人能签出你的包 | 换 key、发吊销证书（`gpg --gen-revoke`）并公告 |

## 6. 现在的状态

- `.gitignore` 已挡住 `*.jks` / `*.keystore` / `*.p12` / `*.pfx` / `signing/` / `keystore.properties`。
- **已有一把固定钥匙**（D170，2026-10-27 生成，RSA 4096，有效期到 2056-10-19）：
  证书指纹 `SHA-256: 1A:29:73:7E:02:41:CB:A1:5F:2E:89:D1:77:31:D2:2D:17:6E:CC:32:EF:42:50:B7:7E:AF:D2:49:F8:0D:AA:A4`
  （指纹是公钥摘要，不是秘密，所以写进仓库当闸用）。
- **文件在哪**：本机 `/workspace/keystore/nmoi-release.jks`（口令在同目录 `release.env`，
  600 权限，**不在仓库里**，仓库外）。**请自己再离线存两份**——手机上的这一份不算备份。
- **两边的 secret 都灌好了**：GitHub 仓库级 4 个、Codeberg 仓库级 4 个（同名）。
- **签名流程：已实测**（run `40513941179`，2026-10-27）。
  `解出签名材料` 打出"已就位：…（4292 字节）"→ `打包` → `验签` 打出
  `签名指纹：1a29737e…f80daaa4` 与 `固定签名 ✓`；本机再用 `apksigner verify --print-certs`
  复核过同一个 APK：`CN=NMoi Agent, O=NickEntry, C=CN`，摘要一致。
  那个包的 sha256 前 32 = `77e3f9d03dfaa7db27e19055cfa4801b`（16,522,374 字节）。
- **Codeberg 只有 secret，没有 Android 构建**：那儿是 Forgejo Actions（仓库已开），
  但托管 runner 没有 Android SDK / NDK，装一套要几个 GB，不适合跑在共享 runner 上。
  要比包的话，把 secret 用上就行（同一个 job 定义搬过去），或者来自托管 runner。
