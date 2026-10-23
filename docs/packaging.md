# 分发与目录约定

管的是"发出去的那份东西里必须有什么、放在哪"。签名与密钥见 `docs/signing.md`，
第三方声明的内容见 `THIRD-PARTY-NOTICES.md`。

## 1. 一条硬规则：声明随每份副本

MIT / ISC 要求版权与许可声明"出现在**每一份副本**里"。这句话的推论很直接：

| 产物 | 声明在哪 |
|---|---|
| 源码仓库 | 根目录 `THIRD-PARTY-NOTICES.md` + `LICENSES/` |
| APK | `assets/notices/THIRD-PARTY-NOTICES.md` + `assets/notices/LICENSES/`，应用内可查看 |
| 桌面安装包（msi / deb / rpm / dmg） | 安装目录 `licenses/`，装完即存在 |
| 便携包（zip / tar.gz） | **压缩包根目录**内，解压即见 |
| 图标包（导出或导入的 zip） | 包内根目录 `NOTICE.txt` |

**不做"只在网页上挂一份"**。那不算随副本走：拿到文件的人看不到网页。

占用可以忽略：这份文件和两段许可全文加起来不到 10 KB。

## 2. 目录布局

### 安装目录（只读语义，但不藏起来）

```
<install>/
  nmoi-agent(.exe)            主程序
  licenses/
    THIRD-PARTY-NOTICES.md
    LICENSES/
      ISC-lucide.txt
      MIT-lobe-icons.txt
  icon-packs/
    original/                 随安装包分发的原版图标包（PNG + manifest.json + NOTICE.txt）
```

- **不要装到隐藏目录、不要只留在安装缓存里**。用户要能直接打开、直接看、直接换。
- 安装器不得在卸载时删 `icon-packs/original/` 之外的用户导入包（那些在用户数据目录，见下）。

### 用户数据目录（可写）

```
<user-data>/
  nmoi.db                     会话库（WAL）
  icon-packs/
    imported/<pack-id>/       用户导入的图标包
```

**"安装后能解压覆盖到安装目录"** 这条具体要求：

1. 导出/导入的图标包就是一个普通 zip，目录结构与 `original/` 一致。
2. 用户可以用任何解压工具把它**覆盖到 `<install>/icon-packs/original/`**
   （或者解压到 `<user-data>/icon-packs/imported/<pack-id>/` 后从应用里切换）。
3. 应用打开时按 `manifest.json` 校验：`schema_version` 认得、每个条目文件在、PNG 魔数对、
   总大小不超上限。校验不过就**退回内置默认**并把原因写在设置页，不崩、不静默。
4. 所以安装目录里的路径与文件命名是**对外契约**：改名等于破坏用户已经解压好的包。

## 3. APK 的特殊处

- `assets/` 是只读的，所以原版包在 `assets/icon-packs/original/`，用户**不能**覆盖它。
  想换图标只有两条路：装成"导入包"（应用私有目录），或在设置里切换。
- 「关于 → 开源许可」读的就是 `assets/notices/`，不联网、不需要额外权限。
- 首装与升级都要保证 `assets/notices/` 里那份和仓库根的文件是同一次提交生成的。

## 4. 便携版

- 一切路径相对可执行文件所在目录解析，不写注册表、不写 `~/.config`。
- 解压即用：`licenses/` 与 `icon-packs/original/` 必须就在压缩包根目录里，
  不能塞进二级目录再靠启动脚本搬。
- zip 用 store（PNG 已经压缩过，再压只会变慢），文件名与版本号对齐发行 tag。

## 5. 发版前逐条过

1. `THIRD-PARTY-NOTICES.md` 里的版本号/取样日期与实际引入的一致。
2. 每个产物里都能找到 `THIRD-PARTY-NOTICES.md` 与 `LICENSES/` 两份全文。
3. APK：装到真机，进「关于 → 开源许可」，两段许可正文都能滚到底。
4. 便携包：解压到空目录，双击能起，`licenses/` 在根目录。
5. 图标包：导出 → 解压 → 覆盖 `icon-packs/original/` → 应用能起来且图标生效；
   再故意删掉一个 PNG，应用应回退并给出提示而不是白屏。
6. `LICENSES/` 里的文件与上游逐字一致（改过就说明改了，别改许可正文）。

## 6. 还没做的

- **Rust 依赖清单**：`Cargo.toml` 里几十个 crate 尚未汇总进 `THIRD-PARTY-NOTICES.md`。
  1.0 之前必须补，用 `cargo metadata` 或 `cargo-about` 生成，挂进打包流程。
- **字体**：内置字体全是 OFL-1.1（D68），OFL 也要求随分发带许可与保留字体名，
  打包时同样进 `licenses/`。目前字体文件还没进仓库（8.3MB），这一步等子集化一起做。
