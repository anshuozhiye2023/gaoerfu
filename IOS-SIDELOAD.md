# iOS：从 push 代码到装上 iPhone（全程不需要 Mac）

## 〇、总览

```
你 push 代码 → 云端 Mac 自动编译 → 下载「未签名 ipa」
    → Windows 上用 Sideloadly 签上你的 Apple ID → 数据线装进 iPhone
```

编译环节完全自动化；签名环节目前必须在你自己的 Windows 上做一次（几分钟），
原因见 §四「为什么签名不放进 CI」。

**只有 GitHub 提供免费的 macOS 构建环境**，这一点查过国内三家的官方文档：

| 平台 | 托管构建环境 | 能不能编 iOS |
|---|---|---|
| **GitHub Actions** | Linux / Windows / **macOS**（免费额度内） | **能** |
| Gitee Go | 只有 Linux 容器（可自建 Windows/macOS 执行器） | ✗（官方无 macOS） |
| 腾讯云 CODING | 官方云主机只有 Linux；macOS 需自接节点，且 **2025-01-01 起已停止新增自定义节点接入** | ✗ |
| 阿里云效 Flow | macOS 只存在于「私有构建集群」＝你自己得有一台 Mac 接进去 | ✗ |

所以想免 Mac，编译这一步只能落在 GitHub 上。但**仓库可以放 Gitee**——见下面的 B 方案。

## 一、把仓库推上去（一次性）

### A. 只用 GitHub（最省事）

```bash
cd GolfAppNative
git init                                  # 本机已经执行过，重复执行无害

# 在 github.com 上新建一个**私有**空仓库（不要勾初始化 README），然后：
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git branch -M main
git push -u origin main
```

> **务必建私有仓库**——里面是产品源码。
> 另外提醒额度：私有仓库的 **macOS 构建按 10 倍折算**（跑 4 分钟计 40 分钟），
> 免费 2000 分钟/月 ≈ **200 分钟 macOS**，按每次构建 4–6 分钟算，够每月三四十次。
> 公开仓库虽然不限量，但代码就公开了，不建议。

### B. Gitee 当主仓 + GitHub 只当编译机（国内推送快，推荐给你）

GitHub 在国内 push/pull 慢是老问题；但**编译在云端跑，跟你的网络无关**，
只影响你 push 那一下。所以让两边各司其职：

```bash
cd GolfAppNative

# Gitee 建私有仓库，当日常主仓（pull/push 都是国内线路，快）
git remote add gitee git@gitee.com:<你的用户名>/<仓库名>.git

# GitHub 建私有空仓库，只负责编译
git remote add github https://github.com/<你的用户名>/<仓库名>.git

git push -u gitee main
git push -u github main

# 以后一条命令双推（在 Bash/Git Bash 里执行）
./push.sh "改了什么"
```

`push.sh` 已经放在仓库根目录了，它做的事就是「add → commit → 同时推 gitee 和 github」。
不想用脚本，也可以配置一个 git 别名：

```bash
git config alias.pushall '!git push gitee main && git push github main'
# 之后：git pushall
```

**想更省事（Gitee 会员/企业版）**：Gitee 仓库的「管理 → 仓库镜像管理」能把代码
**自动推送到 GitHub**，连上面那条双推命令都可以省掉。个人版是否开放这项要看当前套餐，
没有就继续用双推，一样自动化。

**GitHub 直连慢的话**，给这个仓库单独走你的代理（本机 2336）：

```bash
git config http.https://github.com.proxy http://127.0.0.1:2336
# SSH 方式则在 ~/.ssh/config 里给 github.com 配 ProxyCommand
```

### C. 当前这个项目用的仓库（已配好）

```
https://github.com/anshuozhiye2023/gaoerfu.git      ← public，空仓
```

本机已经做完的：
- `git remote add github https://github.com/anshuozhiye2023/gaoerfu.git`（origin 没配）
- 该仓库单独走代理：`git config http.https://github.com.proxy http://127.0.0.1:2336`
- 浏览器授权已跑通（Git Credential Manager，账号 `anshuozhiye2023`，即仓库属主）
- `main` 分支已推送，日常改动跑 `./push.sh "改了什么"` 即可

**公开还是私有，这个仓库目前是 public，代价与收益摆一起：**

| | 公开 | 私有 |
|---|---|---|
| Actions 额度 | **免费不限量**，macOS 也不计费 | 2000 分钟/月，且 macOS 按 10 倍折算 ≈ 200 分钟 |
| 源码 | **全网可见、可被搜索、可被 fork** | 只有你可见 |

换成私有：仓库 Settings → General → 最下面 Danger Zone → Change visibility（推代码之前换最干净）。
不管哪种，**别把 keystore / p12 / 描述文件 / 任何 token 提交进仓库**——`.gitignore` 已经挡了签名文件，
但 token 得靠自己别写进代码。

**认证（2026-09-22 实测，浏览器授权这条路已经跑通，不需要手动生成 PAT）：**

```bash
cd GolfAppNative
export HTTPS_PROXY=http://127.0.0.1:2336    # ← 必须，原因见下表
git push github main
```

Git Credential Manager 会拉起浏览器；浏览器已登录 GitHub 的话点一下 Authorize 就完事，
token 自动存进 Windows 凭据管理器，之后 push 不再打扰你。

它拿到的 OAuth token 自带 `repo gist workflow` 三个 scope —— **`workflow` 是必需的**，
否则 GitHub 会拒绝推送 `.github/workflows/` 下的文件；自己生成 PAT 时最容易漏勾这一项。

**三个坑，都踩过一次了：**

| 现象 | 原因 | 处理 |
|---|---|---|
| push 挂住几分钟不动，`tasklist` 里堆着 `git-credential-helper-sel` | PortableGit 的 **system 级** `credential.helper = helper-selector` 会弹 GUI 选择框，非交互 shell 里没人点就永久挂住。而且 `credential.helper` 是**多值**配置，命令行再写 `-c credential.helper=manager` 属于**追加**，selector 照样会跑一遍 | 用空值重置整个列表：<br>`git config --global credential.helper ""`<br>`git config --global --add credential.helper manager`<br>（本机已改，push 从 7 分钟变 3 秒） |
| 浏览器授权页转圈 / GCM 连不上 GitHub | GCM 是**独立进程，不继承 git 的 `http.proxy`** | push 前 `export HTTPS_PROXY=http://127.0.0.1:2336` |
| 换台机器后第一次 push 弹窗问选哪个凭据管理器 | 同第一条，selector 首次运行会询问并记录选择 | 同上，或在下拉里手动选 Git Credential Manager |

> 另外两条路仍可用：**Deploy key**（仓库 Settings → Deploy keys，粘贴公钥并勾 Allow write access，
> 作用域最小、只限这一个仓库）和 **PAT**（Settings → Developer settings → Personal access tokens，
> 经典版勾 `repo` + `workflow`，或细粒度勾本仓库 Contents: Read and write）。
> 本机 SSH 的 22 端口对 GitHub 时通时断，走 SSH 要有心理准备。


## 二、看编译结果

push 之后：

1. 仓库页面 → **Actions** 标签 → 点最新一次 run；
2. 全绿后页面右侧 **Artifacts** → 下载 `GolfApp-unsigned-ipa-<run号>`；
3. 解压得到 `GolfApp-unsigned.ipa`。

> ⚠ **第一次推送不会自动跑。** 如果 `build-ios.yml` 本身是这次 push 新增的文件，
> GitHub 判定触发条件时看的是「push **之前**的默认分支」，那时默认分支上还没有这个
> workflow，于是这一 push 静默不触发（Actions 页面看起来像没反应）。
> **随便再推一次改动**，或者在 Actions 页面右上角点 **Run workflow**，就正常了。
> 之后只要改 `ios/` 下的东西并 push 都会自动出包。

编译失败先看日志的第一红行（点进失败的 job，展开红色那一步）。常见三种：

| 日志里的关键行 | 原因 | 处理 |
|---|---|---|
| `xcodegen generate` 报 yml 语法错 | `project.yml` 写错了 | 本地装 XcodeGen 复现一遍再修 |
| `xcodebuild: error: Unable to read project ... future Xcode project file format (77)` | XcodeGen 生成的工程格式新过 runner 上的 Xcode。注意这个错是 **xcodebuild 读工程时**才炸，xcodegen 那一步会显示 `Created project at ...` 一切正常，极易误判 | `project.yml` 里 `options.projectFormat: xcode15_3` 已经锁死，**别删这一行** |
| `unterminated string literal` + `cannot find ')' to match opening '(' in string interpolation` | Swift 的普通 `"..."` **不能跨行**（要跨行必须用三引号），而代码里把多行字典直接塞进了 `\(...)`。**报错行号指向字符串起点，不是真正出错的那几行**，照行号去看容易改错地方 | 先把文案/字典算成变量，再拼单行 JS。改完本地跑 `python tools/swiftstr.py ios` 扫一遍，归零再 push |
| 其他 Swift 编译错（带文件名与行号） | 代码问题 | 按行号改完 push 即可 |

## 三、把 ipa 装进 iPhone（免费 Apple ID 路线）

1. Windows 上装 **Sideloadly**（https://sideloadly.io ，免费）；
2. iPhone 用数据线连电脑，装好 iTunes 相关驱动（Sideloadly 首次会提示）；
3. Sideloadly 里：拖入 `GolfApp-unsigned.ipa` → 填你的 Apple ID → Start；
4. iPhone 上：设置 → 通用 → VPN与设备管理 → 信任你的开发者证书；
5. 桌面出现「捡球机器人」。

**限制（苹果对免费账号的硬性规定，谁都绕不开）：**

| 限制 | 说明 |
|---|---|
| **7 天有效期** | 第 7 天后 App 打不开，数据线重签一次即可（设置和圈地数据不受影响，存在 App 沙盒里） |
| 每账号最多 3 个自签 App | 只装这一个就无所谓 |
| 不能分发给别人的手机 | 想给别人装 → 见 §五 |

**不想总插线的替代**：用 **AltStore**（altstore.io），在 iPhone 上装个
AltStore 客户端后，手机和电脑同一 Wi-Fi 时会**自动后台重签**，基本可以忘了 7 天这回事。
SideStore（AltStore 的去电脑化分支）连电脑都可以省，但配网较折腾。

## 四、为什么签名不放进 CI

把签名放进 CI 需要：p12 证书 + 描述文件 + 存进 GitHub Secrets。能做，但：

- 免费 Apple ID 的证书**每 7 天过期**，CI 签出来的包存多久都没意义；
- 真正值得进 CI 的签名是付费账号的（见 §五），那一步目前还没有账号支撑；
- Sideloadly 在本地签名就 2 分钟，先把流程跑通，别为还没买的东西建基础设施。

## 五、买了开发者账号（¥688/年）之后的分发升级

| 想给谁装 | 用什么 | 要不要 UDID | 有效期 |
|---|---|---|---|
| 自己 + 少数固定设备 | Ad-hoc（CI 里真签名） | 要，每人报 UDID，年 100 台 | 1 年 |
| 十人以上内测 | **TestFlight（推荐）** | 不要，对方装 TestFlight 点链接 | 90 天/版 |
| App Store 公开上架 | 走审核 | — | 常驻 |

到那时把 `project.yml` 的 `DEVELOPMENT_TEAM` 填上 Team ID，工作流里
把 `CODE_SIGNING_ALLOWED=NO` 换成证书解密 + `xcodebuild -exportArchive`
即可——需要时说一声，我再把签名版工作流写上。

## 六、iOS 侧已经替你配好的东西

- **XcodeGen 工程描述**（`ios/project.yml`）：CI 上先 `xcodegen generate` 再编译，
  仓库里永远没有 .xcodeproj；
- **权限描述**（`Info.plist`）：蓝牙/定位三条 usage 缺一条 iOS 直接崩，已写好中文说明；
- **明文 http 放行**：底图源可自填，内网 http 瓦片服务不再被静默拦截
  （与 Android 的 `network_security_config.xml` 行为一致）；
- **部署目标 iOS 14**：WKWebView + CoreBluetooth 够用，老机型也能装；
- **工程格式锁在 15.3**（`options.projectFormat`）：不让 XcodeGen 的默认值随版本
  往前漂，CI 和本地都不会被「工程格式太新」卡住；
- **`project.yml` 里绝不能写 `info:` 段**：XcodeGen 里那个键的语义是「生成一份
  plist 写到该路径」，会把 `Info.plist` 里手写的蓝牙权限、ATS 明文放行全部覆盖掉，
  而编译**依然成功**，只有装到真机才暴露（蓝牙一调就闪退 / http 瓦片源被静默拦）。
  CI 上有一条断言专门拦这个回归；
- 工作流里有一条**包内 H5 校验**：解包 ipa 对比 `index.html` 的 SHA-256 与仓库一致，
  防止哪天同步断链、编出一个旧版界面；
- artifact 名带 run 号、打包步骤设了 `if-no-files-found: error`，
  避免出现「流水线绿了但没产出包」这种最难查的状态。
- 工作流里有一条**包内 H5 校验**：解包 ipa 对比 `index.html` 的 SHA-256 与仓库一致，
  防止哪天同步断链、编出一个旧版界面。
