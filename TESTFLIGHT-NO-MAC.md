# 不用 Mac，把包装进 TestFlight / 蒲公英

结论先说：**两条路都能走，但都需要先重新签名——你手上那个 `GolfApp-unsigned.ipa` 两个都用不了。**

---

## 0. 为什么现有的 ipa 用不了

| 问题 | 后果 | 现在状态 |
|---|---|---|
| **未签名**（`CODE_SIGNING_ALLOWED=NO`） | App Store Connect 只收「App Store 分发」签名的包；未签名包连上传的第一步都过不去 | 已在 `release-testflight.yml` 里换成真签名 |
| **Xcode 16.4 构建** | Apple 自 **2026-04-28** 起强制所有上传的包必须由 **Xcode 26 + iOS 26 SDK** 构建，旧 SDK 的包**自动拒收、无宽限期、无人工复核** | 已把 runner 换成 `macos-26`，并加了 `Xcode ≥ 26` 守卫 |
| **没有任何 AppIcon** | 编译完全不报错，上传校验时报 `ITMS-90022 / ITMS-90023 missing required icon file` | 已生成 `icon-1024.png` + `Assets.xcassets`，CI 加了存在性断言 |
| **部署目标 iOS 14.0** | Xcode 26 支持范围是 **iOS 15.0–26.x**，14.0 超出下限 | 已提到 `15.0` |
| **缺出口合规声明** | 每次上传都卡在 `Missing Compliance` 等人去网页答题 | 已加 `ITSAppUsesNonExemptEncryption = false` |

也就是说：**不是「能不能传」的问题，是「得先把包重新做一遍」。** 重新做这件事全程不需要 Mac。

---

## 1. TestFlight 路线（官方、合规，推荐）

### 为什么不需要 Mac

三件事历史上都绑死在 macOS 上，现在至少两件解开了：

| 步骤 | 需要 Mac 吗 | 说明 |
|---|---|---|
| 申请证书 / 描述文件 | **不需要** | Windows 上用 OpenSSL 生成 CSR，浏览器里上传到 Apple 开发者后台 |
| 编译 + 签名 | **不需要** | GitHub Actions 的 `macos-26` runner 干这活 |
| 上传 App Store Connect | **不需要** | WWDC25 新增的官方 Build Upload API（`POST /v1/buildUploads`）任何系统都能调；传统 `Transporter.app` 和 `altool` 确实只有 macOS 版 |
| App 记录、截图、定价、提交审核 | **不需要** | 就是网页 |

> 顺带说一句：Apple 官方在 mid-2026 把 Build Upload API 写进了正式上传文档，这是历史上第一个**零 macOS 依赖**的上传通道。

### 需要准备的 6 个 GitHub Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret。

#### ① ASC_KEY_ID / ASC_ISSUER_ID / ASC_KEY_CONTENT

App Store Connect → **用户和访问** → **集成** → App Store Connect API → **团队密钥** → 生成。
角色选 **App Manager**（Admin 也行）。下载得到的 `AuthKey_XXXXXXXXXX.p8` **只能下载一次**，务必存好。

- `ASC_KEY_ID` = 文件名里那串 `XXXXXXXXXX`
- `ASC_ISSUER_ID` = 页面上方的 Issuer ID
- `ASC_KEY_CONTENT` = `.p8` 文件的 base64：

```powershell
# PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("AuthKey_XXXXXXXXXX.p8")) | Set-Content -NoNewline asc_key.b64
```

```bash
# Git Bash / macOS / Linux
base64 -w0 AuthKey_XXXXXXXXXX.p8 > asc_key.b64
```

> **这个 Key 自己在 `.p8` 里带了 `workflow` scope**，推 `.github/workflows/` 也认（自己手搓 PAT 最容易漏这项）。

#### ② TEAM_ID

developer.apple.com → **Membership details** → Team ID，10 位字符（如 `AB12CD34EF`）。

#### ③ DIST_CERT_P12_BASE64 / DIST_CERT_PASSWORD

**Apple Distribution 证书**。Windows 上全套 OpenSSL 流程：

```bash
# 1) 生成私钥 + CSR（Common Name 用你的名字或团队名，Apple 只认 CN）
openssl genrsa -out distribution.key 2048
openssl req -new -key distribution.key -out distribution.certSigningRequest \
  -subj "/emailAddress=你的邮箱/CN=你的名字/C=CN"

# 2) 浏览器：developer.apple.com → Certificates → + → Apple Distribution
#    上传 distribution.certSigningRequest，下载得到 distribution.cer

# 3) DER 转 PEM，再和私钥打包成 .p12
openssl x509 -inform DER -in distribution.cer -out distribution.pem
openssl pkcs12 -export -inkey distribution.key -in distribution.pem \
  -out distribution.p12 -passout pass:你自己设的密码

# 4) base64（-w0 不能省，Git Bash 的 base64 默认会折行）
base64 -w0 distribution.p12 > cert.b64
```

- `DIST_CERT_P12_BASE64` = `cert.b64` 的内容
- `DIST_CERT_PASSWORD` = 第 3 步设的密码

> ⚠️ **`distribution.key` 一定要自己留好。** Apple 那边只存证书不存私钥，私钥丢了只能吊销重签。别提交进仓库——`.gitignore` 已经挡了 `*.p12` / `*.key` / `*.mobileprovision`。

#### ④ PROVISIONING_PROFILE_BASE64

developer.apple.com → **Profiles** → + → 选 **App Store** 类型（不是 Ad Hoc / Development）→
App ID 选 `com.golfrobot.pickapp` → 关联上面那张证书 → 下载 `.mobileprovision`：

```bash
base64 -w0 distribution.mobileprovision > profile.b64
```

### 触发

仓库 → **Actions** → 左侧 `release-testflight` → **Run workflow**。

构建号默认取 `run_number`，天然单调递增 —— 这点很重要：App Store Connect 里「版本号 + 构建号」是唯一键，撞了会直接报 `already exists`。

### 传完之后

1. App Store Connect → 你的 App → **TestFlight**，构建会先显示「正在处理」（几分钟到十几分钟）
2. 变成「**可供测试**」后：
   - **内部测试员**（最多 100 个，必须是 App Store Connect 里的用户）→ **不需要审核，立刻可用**
   - **外部测试员**（最多 10,000 人）→ 需要过一次 **Beta App Review**（1–3 天）
3. 构建 **90 天**后过期，重新传一个即可

> 想省事就先只加自己 + 同事做内部测试员，完全绕开审核。

---

## 2. 蒲公英路线（另一条赛道，不是 TestFlight 的替代）

蒲公英**不接受 App Store 签名的包**，它走的是两条完全不同的签名通道：

| 签名方式 | 需要什么账号 | 设备限制 | 蒲公英页面显示 |
|---|---|---|---|
| **Ad Hoc** | 个人 / 公司 / 教育（$99） | **100 台/年**，必须先把设备 UDID 加进描述文件 | 内测版 |
| **In-House** | 企业账号（$299） | 无限制 | 企业版 |
| 超级签名（第三方服务） | 服务商自己的个人账号 | 按设备算钱，约 12–18 元/台/年 | — |
| App Store | 个人 / 公司 / 教育 | — | **只能走 App Store 安装，蒲公英分不了** |

### 走蒲公英的话

1. 收集测试设备的 UDID（iPhone 连电脑用爱思助手，或让测试者扫码拿 UDID）
2. 开发者后台 → Devices 添加 UDID → 新建 **Ad Hoc** 描述文件
3. 用这个描述文件签名（`release-testflight.yml` 改两处即可：描述文件类型换成 Ad Hoc，`ExportOptions.plist` 的 `method` 从 `app-store-connect` 改成 `ad-hoc`）
4. 传到蒲公英，生成下载页给测试者

**要不要用蒲公英，取决于你要什么：**

| | TestFlight | 蒲公英（Ad Hoc） |
|---|---|---|
| 测试者数量 | 内部 100 / 外部 10,000 | **硬上限 100 台设备/年** |
| 收集 UDID | 不用 | **必须**，换台手机就要重新签 |
| 审核 | 内部测试员免审；外部要 Beta 审核 | 完全无审核 |
| 包的有效期 | 90 天 | 描述文件 1 年 |
| 崩溃日志 / 反馈收集 | 官方自带 | 靠蒲公英自己的统计 |
| 掉签风险 | 无 | Ad Hoc 无；企业签 / 超级签名有 |

**如果只是「让几个同事装上去用」→ 蒲公英 Ad Hoc 更省事，不用等审核。**
**如果是「正式测试、要长期迭代、要收集反馈」→ 老老实实 TestFlight。**

### 不想用 CI 的话，Windows 上手动传

签名 ipa 有了之后，上传这一步在 Windows 上有三条路：

1. **App Store Connect Build Upload API** —— 官方、免费、任何系统。`POST /v1/buildUploads` 开一个上传会话，拿回一个临时 URL，PUT 把 ipa 传上去，最后 PATCH 标记完成。需要自己写个几十行脚本（JWT 用 ES256 签，`ASC_KEY_ID` + `ASC_ISSUER_ID` + `.p8`）。
2. **iTMSTransporter** —— Apple 官方工具里唯一有 Windows / Linux 版的，Java 写的，能用但配置繁琐。
3. **AppUploader** 之类的第三方 GUI —— 图形界面，最省事，但把证书和账号密码交给第三方工具，自己权衡。

---

## 3. 上架审核的两个风险（提前知道）

### ① ATS 明文放行会被问

`Info.plist` 里 `NSAllowsArbitraryLoads = true` 是为了让用户自填的「内网 http 瓦片源」能用。
**App Store 审核会要求解释用途**。理由写成：

> 用户可自行配置内网/离线地图瓦片服务地址，现场部署的服务常只提供 HTTP。

答辩时强调「用户自定义的内网地址」而不是「我们自己用 http」，通过率会高很多。
如果以后确定只用 https 源，把这个键删掉最干净。

### ② Guideline 4.2「Minimum Functionality」

这 App 本质是 WebView 套壳，**4.2 条是这类应用最常见的拒审理由**（判据是「看起来像个网页而不是 App」）。
好消息是 **TestFlight 内部测试员完全不走审核**，先用起来没问题。
真要上架时，得让原生部分更「重」一些——蓝牙扫描、设备连接、地图围栏这些都算原生能力，答辩时把它们说清楚。

---

## 4. 本轮改了什么

| 文件 | 改动 |
|---|---|
| `.github/workflows/build-ios.yml` | `runs-on: macos-15` → `macos-26`；新增 3 条守卫（Xcode ≥ 26 / 部署目标 ≥ 15 / AppIcon 存在） |
| `.github/workflows/release-testflight.yml` | **新增**：签名 + 归档 + 导出 ipa + 上传 TestFlight，全程云端 |
| `ios/project.yml` | 部署目标 14.0 → 15.0；接回 `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon` |
| `ios/GolfApp/Assets.xcassets/` | **新增**：`AppIcon.appiconset/icon-1024.png`（1024×1024）+ `Contents.json` |
| `ios/GolfApp/Info.plist` | 新增 `ITSAppUsesNonExemptEncryption = false` |
| `tools/make_appicon.py` | **新增**：图标生成脚本（有正式品牌稿时替换同名 png 即可） |
| `tools/check_workflows.py` | **新增**：本地校验 workflow YAML，改完先跑一遍省一轮 CI |

### 本地常用命令

```bash
# 改完 workflow 先校验，别等 CI
python tools/check_workflows.py

# 改完 Swift 先扫跨行字符串字面量（这个坑报的行号是假的）
python tools/swiftstr.py ios

# 重新生成图标
python tools/make_appicon.py
```

---

## 5. 还没做的

- **Gitee 主仓还没配**：`git remote -v` 里只有 `github`，要双推得先 `git remote add gitee <url>`，之后 `./push.sh` 自动双推。
- **仓库仍是 public**：Settings → General → Danger Zone → Change visibility。代价是 Actions 从免费不限量变成约 200 macOS 分钟/月（`macos-26` 和 `macos-15` 都是 10 倍折算）。
- **`release-testflight.yml` 尚未实跑过**：要等 6 个 Secret 配齐才能验证。第一次跑大概率还要调签名细节（`PROVISIONING_PROFILE_SPECIFIER` 用 UUID 还是名称、`method` 的取值等），有日志我就能定位。
