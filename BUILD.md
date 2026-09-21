# 打包成 App（APK / IPA）

**一句话结论**：

| 目标 | 能不能 | 在哪做 | 现状 |
|---|---|---|---|
| **Android APK** | 能，已验证 | **就这台 Windows** | ✅ 已经打出来了，`android/app/build/outputs/apk/debug/app-debug.apk`（60 KB） |
| **iOS IPA** | 能，但**必须换台 Mac** | macOS + Xcode | 壳和桥的 Swift 代码已写好；本机无法编译，Apple 只允许 macOS 出包 |

Android 侧我在本机真跑通了：JDK 21（Android Studio 自带的 `D:\androidstudio\jbr`）+ 本机 Android SDK + Gradle，产出 APK 并验证了包内 `assets/index.html` 与原型的 **md5 完全一致**——也就是说**原型一个字没改就进了包**。

---

## 〇、本轮改的事（你问的那三个 + 安卓实机）

### 1. 「卫星图破碎破旧」——两个原因叠在一起，一个在代码，一个在网络

**代码层（真 bug，已修）**：canvas 是按屏幕像素密度放大的位图（`cv.width = 宽 × DPR`），但取瓦片的层级只按 CSS 像素的 `cam.zoom` 算。结果一张 256px 的源瓦片被拉伸 DPR 倍画出来——在 DPR=2/3 的手机上等于**用三分之一的分辨率显示卫星图**。而界面是矢量画的、照样锐利，一对比就更像一张「破旧照片」。

修法是让「源瓦片像素 ≈ 屏幕物理像素」：取阶层级 = 相机层级 + log2(DPR)。另外把绘制坐标对齐到物理像素网格（`snapPx`），消掉瓦片接缝处的重采样毛边。

设置页的「清晰度」三档控制这个抬升量：

| 档位 | 抬升量 | 效果 |
|---|---|---|
| 高清（默认） | `log2(DPR)` | 源瓦片像素对齐屏幕物理像素，最清楚 |
| 标准 | `max(0, log2(DPR) - 1)` | 省一半流量，轻微变糊 |
| 省流 | `0` | 按相机层级取图，最省流量，放大后明显糊 |

**网络层（外部因素，已兜底）**：实测 Esri 卫星瓦片在当前网络下**时好时坏**——同一台机器上，走代理时曾整片 403，直连时 116ms 就回来了。这是「破碎」的第二个原因：瓦片零星到达，地图自然一块一块的。代码改不了网络，所以做了两个兜底：

- **换源**：设置 → 地图 → 底图源，可在 **Esri / 天地图 / 自定义** 之间切。自定义源支持 `{z} {x} {y}` 和 TMS 的 `{-y}`，填内网瓦片服务或任何镜像都行。天地图要填 Key（免费申请）。
- **健康度提示**：瓦片连续 3 张请求失败且一张都没成功时，地图上方直接给一句能操作的话——「底图加载失败（N 张瓦片请求被拒）· 到「设置 → 底图源」换一个源，或检查网络」，而不是让人对着一张破图猜是 App 坏了还是网断了。

> 这里有个反直觉的取舍值得记一笔：这个提示**故意不用「没有请求在途」当条件**。因为被墙或被黑洞丢包时请求会一直挂着不返回，那就会有请求永远在途、提示永远不出现——用户照样只能干瞪眼。

### 2. 页面流程：入场是设备连接页，连上才进规划页

```
启动 → 设备连接页（唯一入场）
         ├─ 搜索设备（12 秒，边扫边列，列表实时刷新）
         ├─ 点设备 → 连接中（三步进度：建立链路 / 交换标识 / 读取能力）
         ├─ 成功 → 自动跳规划页，状态栏变成可点的「已连接 · GolfBot-01」
         └─ 失败 → 状态环变红，写明原因，可直接重试
```

- 每台设备显示**信号强度格数**，方便在几台里挑近的那台。
- 浏览器里没有 Web Bluetooth，所以自动走**演示模式**（列表出两台假设备），流程照样能走通、能进规划页。
- 规划页状态栏的连接区是**可点的**，点一下回到连接页换设备；设置页里也有「重连 / 断开」。
- 蓝牙由**原生层**实现（WebView 里没有 Web Bluetooth，这点在 iOS 上更绝对）。原生持续扫描并累积设备列表回推给 H5，H5 只负责显示和选择。

### 3. 完整的设置功能（7 组）

| 分组 | 内容 |
|---|---|
| 机器人 | 连接状态、当前设备、重连 / 断开 |
| 机器人 BLE 标识 | 设备名前缀、服务 UUID、写入特征、通知特征 —— **现场联调时直接改，存下来立即生效，不用重新打包** |
| 地图 | 底图源（Esri/天地图/自定义）、天地图 Key、自定义源模板、显示底图、显示网格、清晰度 |
| 坐标标定 | 原点经纬度（可直接填，或「用当前位置」），换球场时区域整体平移、视觉不动 |
| 作业参数 | 作业行距、作业宽度（内缩 = 宽度 / 2） |
| 数据 | 瓦片缓存数、恢复示例地块、已圈区域数、恢复出厂设置 |
| 关于 | 版本、运行环境、上次下发的报文体量 |

所有设置写 `localStorage`（浏览器/WebView 里都能落盘；不可用时降级为本次运行有效，并明确告诉用户）。

### 4. 安卓侧：装进 APK 真跑了一遍，并修掉两个「只有装进 App 才会暴露」的问题

前面三件事都验过，但**全是在浏览器里**验的。装进 App 之后基本是两个世界：WebView 的 DPR、原生桥、真的 BLE 扫描、设置写成 Android 的 SharedPreferences——这些在无头浏览器里一条都测不到。

所以这轮补了 `diag/androide2e.js`：装 debug 包 → 接 WebView 调试端口 → 直接断言 H5 内部状态。**41 条断言全绿**（做法和清单见第八节）。截图在 `diag/shots-android/`。

跑真机跑出两个之前没发现的问题，都修了：

| 问题 | 现象 | 修法 |
|---|---|---|
| 文案说错话 | 在 App 里点「演示模式」时，说明文字写的是「**当前在浏览器里运行**……装进 App 才会真正扫描机器人」——用户会以为装错了包 | 按「有没有原生桥」分开写：浏览器里保留原句；App 里改成「演示模式：用模拟设备把『搜索 → 连接』走一遍，不会真的连机器人」 |
| 进了演示**出不来** | 演示模式下按钮被 `display:none` 藏了，用户点进去之后**再也回不到连真机器人**，只能杀掉 App 重进 | 只要有原生就不隐藏，改成「退出演示，搜索真机器人」；点了清空模拟设备、状态机回 `idle`、按钮复位 |

两个都是「只跑浏览器永远发现不了」的那类——浏览器里没有原生、演示就是唯一路径，所以这两个毛病在 H5 原型上完全看不出来。

真机上的观感结论：**卫星图完全不「破碎」了**。模拟器 DPR=3，深圳那张影像里楼顶瓦片纹理、街道、绿化、水面都清楚；放大到 z19（0.28 m/px）依然清楚，不再出现「放大就没图」。

---

## 一、为什么是这个方案（而不是 Capacitor）

上一轮讨论里我推荐过 Capacitor。这次我把工程真做出来了，方案换成**手写的最小原生壳**，理由如下：

**1. 你的原型本身已经为套壳设计好了。**
`index.html` 里的 `Bridge` 对象已经在探测 `window.GolfNative.postMessage`（Android）和 `window.webkit.messageHandlers.golf`（iOS）。我只要在原生侧把这两个名字提供出来，**H5 一行都不用改**。这是最省事也最不容易出错的路径。

**2. BLE 无论如何都得写原生，Capacitor 帮不上这段忙。**
这是整个项目的关键约束：**WebView 里没有 Web Bluetooth。**
- iOS 的 WKWebView **完全不支持**（Safari 支持，但 WKWebView 没有）
- Android 的 WebView 也不支持（只有 Chrome 浏览器本身支持）

而你的机器人是 BLE 连的，所以这一段代码用哪个框架都要手写。Capacitor 引入的是一整套插件体系，但这里需要的那一个插件它没有现成的，还是得自己写。

**3. 零第三方依赖 = 构建快且不会坏。**
这个壳只用 `android.*` 原生 API，**不引 androidx、不引 Kotlin**。后果是：
- 首次构建只要下 AGP 自己，21 秒出包
- 没有版本冲突的空间
- 换台电脑配好 SDK 就能构建

**什么时候该换回 Capacitor**：如果你后面要加**多页面**、**热更新**、或者要接**支付/推送/文件选择**这类官方插件覆盖得很好的功能，Capacitor 的生态优势才体现出来。到那时把 `assets/index.html` 挪进 Capacitor 的 `www/` 也是一小时的事，不亏。

---

## 二、Android：怎么出包

### 2.1 出 debug 包（可以立刻装手机试）

在 **PowerShell** 里跑（这个写法是实测过的）：

```powershell
$env:JAVA_HOME = 'D:\androidstudio\jbr'          # 必须是 Windows 路径
Set-Location 'GolfAppNative\android'
$g = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
& $g assembleDebug --console=plain
```

> **别在 Git Bash 里用 `cmd //c "gradle.bat ..."`**——会进到 cmd 的交互 shell，命令根本没执行，而退出码还是 0，看着像成功。这是本轮踩到的坑。
>
> 不想记那么长的 gradle 路径的话，也可以直接在 Android Studio 里打开 `GolfAppNative/android` 目录点构建。

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

装到手机（手机开发者选项里开 USB 调试）：

```bash
$ANDROID_HOME/platform-tools/adb.exe install -r \
  GolfAppNative/android/app/build/outputs/apk/debug/app-debug.apk
```

> debug 包的 `applicationId` 是 `com.golfrobot.pickapp.debug`，和正式版**可以共存**，方便对比。

### 2.2 出正式包（要自己的签名）

1. 生成 keystore（**这一步生成的文件和密码丢了就再也更新不了这个 App，务必备份**）：

```bash
JAVA_HOME='D:\androidstudio\jbr' $JAVA_HOME/bin/keytool.exe -genkeypair -v \
  -keystore golf-release.jks -alias golf -keyalg RSA -keysize 2048 -validity 10000
```

2. 在 `android/app/build.gradle` 的 `release` 块里加上签名配置（**不要把密码写进仓库**，用命令行传或本地 `keystore.properties` 并加进 `.gitignore`）：

```groovy
release {
    minifyEnabled false
    signingConfig signingConfigs.release
}

signingConfigs {
    release {
        storeFile file(System.getenv("GOLF_KS") ?: "golf-release.jks")
        storePassword System.getenv("GOLF_KS_PASS")
        keyAlias System.getenv("GOLF_KEY_ALIAS")
        keyPassword System.getenv("GOLF_KEY_PASS")
    }
}
```

3. 构建（PowerShell）：

```powershell
$env:GOLF_KS='golf-release.jks'; $env:GOLF_KS_PASS='xxx'
$env:GOLF_KEY_ALIAS='golf'; $env:GOLF_KEY_PASS='xxx'
$env:JAVA_HOME='D:\androidstudio\jbr'
& $g assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 上 Google Play 现在要 **AAB** 而不是 APK：把任务换成 `bundleRelease`，产物在 `app/build/outputs/bundle/release/`。

### 2.3 环境变量（本机已经踩过的坑）

| 变量 | 值 | 说明 |
|---|---|---|
| `JAVA_HOME` | `D:\androidstudio\jbr` | **必须 Windows 路径**。给 `/d/androidstudio/jbr` 会报 `JAVA_HOME is set to an invalid directory` |
| `sdk.dir` | 写在 `android/local.properties` | 已经填好了，这个文件别提交 git |
| Gradle | `~\.gradle\wrapper\dists\gradle-8.13-bin\...\gradle-8.13\` | 本机已装 8.13，AGP 8.7.2 要求 ≥ 8.9，满足。工程里**没有** gradlew，所以直接调这个 `gradle.bat` |

**如果换机器**：把 `local.properties` 里的 `sdk.dir` 改成新机器的 SDK 路径；`app/build.gradle` 里的 `buildToolsVersion '36.1.0'` 改成 `sdkmanager --list` 里实际装着的版本（不写这一行会报 `Failed to find Build Tools revision 34.0.0`——AGP 8.7 的默认值是 34，很多机器没装）。

### 2.4 仓库地址为什么全指向阿里云

`settings.gradle` 里每个仓库都写了阿里云镜像在前、官方在后。原因是 `dl.google.com`（AGP 和 androidx 的仓库）**在国内直连不通**：

```
https://dl.google.com/dl/android/maven2/          → 连接超时
https://maven.aliyun.com/repository/google/       → 200 ✅
```

如果你的网络环境能直连 Google（比如挂了稳定的代理），把镜像去掉更保险（镜像偶有同步延迟）。

---

## 三、iOS：必须换 Mac

### 3.1 这件事没有绕过的办法

**Apple 只允许在 macOS + Xcode 上编译和签名 iOS 应用。** 不是工具的问题，是签名体系本身：证书、描述文件、`codesign` 全部依赖 macOS 的钥匙串。

所以你需要在以下之一出包：

| 途径 | 说明 |
|---|---|
| 自己的 Mac | 最直接 |
| 借一台 Mac | 装个 Xcode 就能出包，不用长期持有 |
| 云 Mac / CI | Codemagic、Bitrise、GitHub Actions 的 `macos-latest` runner 都能出 IPA；代码推上去，流水线出包 |
| 找外包 | 有专门代打包的服务，但要给他们你的证书 |

**Windows 上唯一能做的**是写完代码（我已经写完了），以及在 Xcode 里用模拟器调试（也得在 Mac 上）。

### 3.2 在 Mac 上出包（三条命令）

```bash
# 0) 前置：Xcode（App Store 装）+ XcodeGen
brew install xcodegen

# 1) 生成 .xcodeproj
cd GolfAppNative/ios
xcodegen generate

# 2) 打开，在 Signing & Capabilities 里选你的 Team（只需点一次）
open GolfApp.xcodeproj
```

之后有两种出包方式：

```bash
# A. 直接出 .ipa（把 TEAM_ID 填进 project.yml 的 DEVELOPMENT_TEAM 后就能无交互）
xcodebuild -project GolfApp.xcodeproj -scheme GolfApp \
  -configuration Release -archivePath build/GolfApp.xcarchive archive
xcodebuild -exportArchive -archivePath build/GolfApp.xcarchive \
  -exportOptionsPlist ExportOptions.plist -exportPath build/ipa
```

```bash
# B. 上传 TestFlight（推荐，先给内测机装）
xcodebuild -project GolfApp.xcodeproj -scheme GolfApp \
  -configuration Release -destination 'generic/platform=iOS' build
# 或用 Xcode 菜单 Product → Archive → Distribute App
```

### 3.3 iOS 侧还有一个平台差异要知道

**WKWebView 没有 `navigator.geolocation`。**
Safari 有，WKWebView 没有——这是 iOS 的长期限制，不是配置问题。

好在你的 H5 定位逻辑是「先问原生要，原生不行才退回浏览器定位」，而 `Bridge.post('getPosition')` 在 iOS 上走 `messageHandlers.golf` 一定能成功，所以页面**不会**走到 `navigator.geolocation` 那条分支。

⚠️ **但如果你以后改了 H5 的定位顺序**（比如把浏览器定位提到前面），iOS 上会静默失败、界面永远定不上位，而且不会有任何报错。改那一段时记得回来看这条。

---

## 三之二、桥的返回值：为什么 `postMessage` 必须有返回

这一条是**在模拟器上真跑时抓出来的 bug**，值得单独说，因为它决定了下发的可信度。

### 问题

原来的写法是「原生对象存在 = 下发成功」：

```js
// 旧 H5：只要 window.GolfNative 在，就当发出去了
if (window.GolfNative && window.GolfNative.postMessage) {
  window.GolfNative.postMessage(JSON.stringify(msg));
  return true;                       // ← 这里就开始骗人了
}
```

后果：**没连机器人时，原生把指令扔了，界面却显示「已下发」、区域边框变实线、开始作业可点。** 操作员会以为区域已经交给机器人了。

### 改法

Android 的 `@JavascriptInterface` 方法**可以同步返回字符串**，所以让原生把受理结果直接回给 H5：

```java
@JavascriptInterface
public String postMessage(String json) {
    // 同步预检：做不了的立刻拒绝
    String why = preflight(type);
    if (why != null) return ack(false, why);      // {"ok":false,"reason":"还没连上机器人"}
    main.post(...);                                // 能做的才排进主线程
    return ack(true, null);                        // {"ok":true}
}
```

H5 侧读这个结果，**受理了才标「已下发」**：

```js
var usedNative = Bridge.post('sendRegion', cmd);
if (Bridge.lastError) {                 // 原生拒了
  state.sent = false;                   // ← 不标已下发
  toast('下发失败：' + Bridge.lastError + '\n区域已保留，处理完再点一次「下发」即可');
  return;
}
state.sent = true;                      // 到这一步才落状态
```

### 两条路都要，缺一不可

| 失败类型 | 能不能同步知道 | 走哪条 |
|---|---|---|
| 还没连上机器人 / 没配 BLE 标识 / 蓝牙没开 / 不认识的指令 | **能** | `postMessage` 的返回值（`preflight`） |
| 扫描 12 秒没找到设备 / BLE 写失败 / 上报丢帧 / 没定位权限 | 不能 | 异步 `onConnection` 的 `detail` |

所以 H5 的 `onConnection` **必须把 `detail` 显示出来**。原来它只在"已连接"时显示 detail，未连接时只显示一句"未连接"——等于把原生给的排查线索扔了：

```js
// 旧：未连接时 detail 被丢掉
txt.textContent = (c && c.connected) ? '已连接 · ' + (c.detail || '') : '未连接';
// 新：未连接也要显示原因
txt.textContent = ok ? ('已连接 · ' + detail) : (detail || '未连接');
```

### iOS 的固有差异

`WKScriptMessageHandler` **没有返回值**，所以 iOS 拿不到同步受理结果，`Bridge.post` 只能按「已交给原生」返回 `true`。失败原因走异步 `onConnection`（状态栏会显示）。

这是平台机制决定的，绕不过去。要做成完全一致，只能改成「Promise + 原生 ack」的异步流程——对当前这个规模不值当。

**H5 里 `Bridge._ack()` 用「解析不了就按受理处理」兜住了这个差异**，所以同一份 `index.html` 两端都能跑。

---

## 四、接机器人之前必须解决的三件事

这三件是「代码写完了但还连不上」的全部原因，按重要性排序：

### 1. BLE 的三个 UUID（最关键）

`BleBridge.java` / `BleBridge.swift` 顶部这四项**现在有编译期默认值、也可以运行时覆盖**：

```java
private volatile String serviceUuid  = "";   // 主服务
private volatile String notifyCharUuid = ""; // 机器人→App 上报
private volatile String writeCharUuid  = ""; // App→机器人 下发
private volatile String namePrefix     = "GolfBot";  // 设备名前缀
```

**推荐做法：不用改代码，直接在 App 的「设置 → 机器人 BLE 标识」里填。** 点「保存到 App」后原生会写进 `SharedPreferences`（iOS 是 `UserDefaults`）并立即生效，**不需要重新打包**。这四项也会随 `scan` 一起下发给原生，所以首次扫描前先填一次即可。

填了「设备名前缀」或「服务 UUID」任一个就能开始扫描。一个都没填时点「连接」会明确提示「未配置机器人 BLE 标识」——**这是刻意设计的**，比「扫描很久然后超时」好排查得多。

### 2. 分片协议要和固件对齐

一次下发多块区域的报文实测 **2015 B**，远超单个 BLE 包的 ATT payload（MTU 247 → 244 B）。所以必分片，我定的帧格式是：

```
byte 0   : 0xA5   帧头
byte 1   : seq    序号 0..255
byte 2   : total  总帧数
byte 3   : flags  0x01=首帧, 0x02=末帧
byte 4.. : UTF-8 正文（最多 240 B）

单帧总长 ≤ 244 B
```

**这个格式固件那边必须照着实现。** 正文是按**字节**切的，会切在多字节汉字中间——这没问题，因为接收端是先拼回完整 `byte[]` 再整体按 UTF-8 解码（两端代码都是这么写的）。

如果固件那边想用别的格式（比如加 CRC、或者用 16 位序号支持超大报文），改这两处即可：
- `BleBridge.java` 的 `sendFramed()` / `onFrame()`
- `BleBridge.swift` 的 `sendFramed()` / `onFrame()`

### 3. 权限

Android 侧已经配好（12+ 的蓝牙三件套 + 定位），但有两个点要留意：

- **`BLUETOOTH_SCAN` 上我加了 `neverForLocation`**，意思是「我不用 BLE 扫描结果推断位置」。**前提是机器人的广播包里不含位置信息**。如果固件在广播里塞了坐标，这就是虚假声明，要去掉这个 flag 并保留定位权限。
- **iOS 的 Info.plist 里三条 usage description 少一条就闪退**。iOS 的机制不是「拒绝调用」，是**进程直接终止**，日志只有一句 `attempted to access privacy-sensitive data without a usage description`。三条都已经写好了。

---

## 五、上架前还要补的东西

| 项 | Android | iOS |
|---|---|---|
| 开发者账号 | Google Play 一次性 $25 | Apple Developer **$99/年** |
| 图标 | 已有矢量图标（`res/drawable/ic_launcher.xml`） | 需要 1024×1024 PNG，拖进 `Assets.xcassets` |
| 隐私声明 | Play Console 的「数据安全」表单 | App Store Connect 的「隐私营养标签」——**这个 App 要声明采集位置数据**，写清用途（场地定位） |
| 打包格式 | AAB（`bundleRelease`） | IPA |
| 审核关注点 | 蓝牙权限用途 | 蓝牙 + 定位权限用途说明写清楚，否则会被拒 |

**关于「上架」的一个建议**：这类「专用硬件配套 App」其实**不需要上应用商店**。它只对买了机器人的客户有意义，走「官网/售后直接发 APK + TestFlight 内测」更省事，也避免因为「功能不完整」被审核拒。等产品成熟了再上架不迟。

---

## 六、这个壳里有什么（代码清单）

```
GolfAppNative/
├── android/                                  ← 已构建通过，出了 APK
│   ├── settings.gradle                        阿里云镜像配置
│   ├── build.gradle                           AGP 8.7.2
│   ├── gradle.properties
│   ├── local.properties                       本机 SDK 路径（勿提交）
│   └── app/
│       ├── build.gradle                       compileSdk 35 / minSdk 24 / buildTools 36.1.0
│       └── src/main/
│           ├── AndroidManifest.xml            蓝牙三件套 + 定位 + BLE feature
│           ├── assets/index.html              ← 原型原封不动放这里
│           ├── java/com/golfrobot/pickapp/
│           │   ├── MainActivity.java          WebView 宿主：注入 GolfNative、授权、地理定位放行
│           │   └── BleBridge.java             BLE + 分片 + 事件回推 + 手机定位
│           └── res/                           图标（矢量）/ 主题 / 文案
└── ios/                                      ← 代码就绪，需 Mac 编译
    ├── project.yml                            XcodeGen 配置（.xcodeproj 由它生成）
    └── GolfApp/
        ├── AppDelegate.swift
        ├── ViewController.swift               WKWebView 宿主 + messageHandlers("golf")
        ├── BleBridge.swift                    CoreBluetooth + 分片 + CLLocationManager
        └── Info.plist                         蓝牙/定位权限文案（缺一条就闪退）
```

**换 H5 版本的方式**：把新的 `index.html` 覆盖 `android/app/src/main/assets/index.html`（iOS 是 `ios/GolfApp/index.html`），重新构建。**不需要动任何原生代码。**

---

## 七、H5 里可选的三个钩子（不写也能跑）

原生侧会探测这几个函数，定义了就会收到额外能力：

```js
// 返回键：返回 true 表示"我处理掉了"，App 不会退出。
// 有未下发的草稿或正在编辑某块区域时，可以在这里弹个确认。
window.GolfOnBack = function () { return false; };

// 权限被拒时收到（Android 会传被拒的权限名列表）
window.GolfPerms = {
  onResult: function (denied) {
    if (denied) toast('缺少权限，请到系统设置里开启');
  }
};

// 下发分片进度："正在发送 3/9 帧"
window.GolfApp.onSendProgress = function (p) {
  console.log(p.type, p.bytes + 'B', p.frames + '帧');
};
```

这些都**没有加进 `index.html`**——原型是经过几百项断言 + 端到端验证的，我不想为了可选功能去动它。需要时你自己加上去。

> 例外：有两轮为了修真问题**确实动了 H5**——
> 1. 修「未连接却显示已下发」（`Bridge.post` 读原生返回值、`onConnection` 显示 detail、下发失败不标 sent）；
> 2. 本轮三件事（连接页入场流程、完整设置页、底图清晰度 + 自定义源 + 健康度提示）。
>
> 每次改完都重跑全部断言、无头 e2e，以及安卓真机 e2e。当前：**`webcheck.js` 240 项全绿，`shot.js` 端到端 `bad=0`，`androide2e.js` 41 项全绿**。
>
> 本轮又动了 H5 一小块——修「App 里误称浏览器」和「进演示出不来」两个缺陷（连接页的按钮文案与演示出口）——同样把三个套件全部重跑过。

---

## 八、这套壳实际验证到了什么程度

不是「编译通过」就算完，下面每一项都是真跑过的：

| 验证项 | 方法 | 结果 |
|---|---|---|
| 原型原封不动进包 | 解包取 `assets/index.html` 与源文件 **md5 对比** | 一致 |
| 包元数据 | `aapt2 dump badging` | 包名 / label「捡球机器人」/ targetSdk 35 / launcher activity 都对 |
| 权限声明 | `aapt2 dump permissions` | 蓝牙三个 + 定位两个，`neverForLocation` flag 在位 |
| **能真的跑起来** | 本机 AVD（Pixel_10_Pro_XL / API 37）安装启动 | ✅ 卫星底图加载、三块示例区域渲染、四种工具都在 |
| **H5 交互在 WebView 里正常** | `adb input swipe` 拖出一个圆 | 半径 43.3 m / 面积 5890.6 m²，几何计算正确 |
| **原生桥收发通了** | 点「下发」后查 logcat | `D GolfBLE: ← sendRegion` → `W GolfBLE: 拒绝 sendRegion：还没连上机器人` |
| **原生 → H5 回推通了** | 同一场景看界面 | toast 正确显示「下发失败：还没连上机器人」，区域保持虚线未下发 |
| H5 逻辑没被改坏 | `diag/webcheck.js` | **240 项断言全绿**（含本轮新增的页面路由 / 连接状态机 / 设置项生效 / 自定义瓦片源 / 底图健康度共 74 项） |
| 浏览器端没被改坏 | `diag/shot.js` 无头 Edge | **`bad=0`**（48 条 e2e 断言），多区域下发报文仍为 3 块 / 3744 m² / pts[4,64,4] |
| **装进 App 后也是对的** | `diag/androide2e.js`：装 debug 包 → 接 WebView 调试端口，直接读 H5 内部状态 | **41 条断言全绿**（含「BLE 配置真的落进 Android SharedPreferences」） |

### 本轮（连接页 + 设置页 + 清晰度）额外验证到的

| 验证项 | 方法 | 结果 |
|---|---|---|
| 入场流程是连接页 | 无头 Edge 打开 → 断言停 `connect` 页、状态文字和按钮引导都在 | ✅ |
| 搜索 → 选设备 → 自动跳规划页 | 点按钮 → 断言 `scanning` + 列出 2 台 → 点设备 → `connecting` → 1.8 s 后 `screen=plan` | ✅ |
| **清晰度的根因修好了** | 无头 Edge 用 DPR=2 跑：断言 `取图层级 = 相机层级 + 1` | ✅ `shift=1` |
| **瓦片真的铺满且不空白** | 相机 z17/18/19/20/21 逐级放大，每级断言 `本层+祖先+拼接 > 0` | ✅ 全部 `miss=0`（20/20 张命中） |
| 设置能改、能落盘 | 设置页切「省流」→ 断言取图层级降 1 级、旧层瓦片被作废 → `reload` → 断言设置还在 | ✅ |
| 设置页改动真的生效 | 切自定义源 → 断言 `CONFIG` 同步且底图换成新源；关底图 → 断言绘制不崩 | ✅ |
| 自定义瓦片源在真浏览器里可用 | 换成 `http://127.0.0.1:<port>/tiles/{z}/{x}/{y}.png` | ✅ 20 张全中 |
| 底图故障时给得出人话 | 单元测试注入「全部瓦片被拒」→ 断言提示文案含「设置 → 底图源」 | ✅ |
| 外网底图现状（信息项） | 无头 Edge 探测 | Esri 直连 **116 ms 可加载**；走代理时曾整片 403 —— **时好时坏，所以必须留换源入口** |
| **装进 APK 后全部走通** | `diag/androide2e.js`：AVD（Pixel_10_Pro_XL / API 37）+ CDP | ✅ **41 条断言全绿**（入场 / DPR / 渲染 / 连接流程 / 设置落盘 / 原生写盘），见下节 |
| 真机（WebView）里 DPR 确实是 3 | 同上，读 canvas 位图尺寸 | ✅ 448 CSS × 3 = 1344 px，取图层级跟着抬 1.585 级 |
| **H5 改设置真的落到 Android 里** | `run-as` 读 `shared_prefs/golf_ble.xml` | ✅ 名字前缀写进去了，不只是「界面看起来改了」 |
| 修掉：App 里误称「在浏览器里运行」 | 同上，断言首屏文案 | ✅ 首屏是「搜索机器人」，文案不含「浏览器」 |
| 修掉：进演示模式后出不来 | 同上，断言按钮文案 + 点它之后的状态 | ✅ 按钮变「退出演示，搜索真机器人」，退回后回 `idle` |

### 测试怎么跑

```bash
# 1) H5 逻辑（把 index.html 的脚本放进桩 DOM 里真执行）
cd diag && node webcheck.js ../GolfAppWeb/index.html      # 期望：240 通过 / 0 失败

# 2) 端到端（无头 Edge，瓦片走本机服务，与外网无关）
NODE_PATH="<node_modules>" node shot.js                    # 期望：bad=0

# 3) 真实底图截图（用 Esri，专门看清晰度；外网不通时会如实说）
NODE_PATH="<node_modules>" node shot-real.js               # 出图到 diag/shots-real/

# 4) 安卓真机 / 模拟器（需要模拟器已在跑；脚本自己装包、自己开调试端口）
node androide2e.js                                         # 期望：端到端全部通过
```

`diag/tileserver.js` 是给 e2e 用的本机瓦片服务（自己编码 PNG，无依赖）。**这是本轮加的最有用的一个测试基建**——它把「外网坏了」和「代码坏了」彻底分开，之前这两种故障长得一模一样，只能看到一片红。

### 安卓端到端（`diag/androide2e.js`）—— 为什么值得单独写一个

无头 Edge 测不了这些：真机 WebView 里的 DPR 到底是不是 3、`window.GolfNative` 有没有注进来、点设备列表会不会真的跳页、**H5 改的设置有没有真的落到 Android 的 SharedPreferences**。这些只能在装进 App 之后测。

做法：debug 包在 `MainActivity.setupWebView()` 里开了 WebView 调试，再
`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`，
用 CDP 直接读页面内部状态（Node 22 自带 WebSocket，不用装包）。
**断言事实，而不是看截图猜**——截图只能证明「画面不是空白」。

它验证到的：

| 项 | 结果 |
|---|---|
| 入场停在连接页、状态机为 idle、三个页面都在 | ✅ |
| 真机首屏主按钮是「搜索机器人」，文案不提「浏览器」 | ✅ |
| `window.GolfNative` 已注入且 `postMessage` 可调 | ✅ |
| WebView 里 DPR=3，canvas 位图 448 CSS × 3 = 1344 px | ✅ |
| 高清档把取图层级抬 log2(3)≈1.585 级；Esri 被自己的 z19 上限挡住 | ✅ |
| 自定义源允许 z20，DPR=3 下真的取到 z20 | ✅ |
| 本地源铺满 88 张瓦片，`miss=0`、`fallback=0`（每张都是本层自绘） | ✅ |
| 放大到 z19 加载稳定后仍是本层自绘、漏画 0（「放大了就没地图」已修） | ✅ |
| 扫描 → 列出 2 台 → 点选 → connecting → 自动跳规划页 | ✅ |
| 演示模式能退出来（按钮变「退出演示」，退回后清空设备、回 idle） | ✅ |
| 设置页 7 组 / 10 个输入框；点「省流」层级 z19→z17，旧层瓦片全作废 | ✅ |
| **BLE 配置真的写进了 `shared_prefs/golf_ble.xml`**（`run-as` 读盘核对） | ✅ |
| H5 设置在 WebView 里持久化（localStorage） | ✅ |
| BLUETOOTH_SCAN / BLUETOOTH_CONNECT / 定位 / INTERNET 权限都在包里 | ✅ |
| 页面无未捕获 JS 异常 | ✅ |

三个关键决定：

- **瓦片用本机服务器（模拟器里走 `10.0.2.2:<port>`），不用 Esri。** Esri 在当前网络时通时不通，「网络不通」和「代码画错了」会长得一模一样。换成可控源之后，测的就只剩渲染管线本身；外网能不能通单独作为一条信息项打印，不参与判定。
- **不读 canvas 像素。** 页面是 `file://`、瓦片是 `http://`，跨源会把 canvas 标记成 tainted，`getImageData` 直接抛 `SecurityError`（这是浏览器安全策略，不是缺陷）。改用绘制统计 `tileDrawStat()`——它比像素更能说明清晰度：`self` 是本层自绘（清晰）、`fallback` 是拿父级拉伸（糊）、`miss` 是没图。
- **等条件，不 `sleep` 固定时长。** 瓦片异步下载、设备列表由定时器一台台报上来；固定 sleep 抓到的往往是中间态（刚放大那几百毫秒画面靠父级瓦片撑着，这是**刻意设计**，避免白屏）。

两个跑之前要知道的坑：

1. 脚本开头会 `localStorage.removeItem(...)` 再 `loadSettings()`。**不这么做就会踩状态残留**——设置是持久的，上一轮把源改成 custom 会留到这一轮，于是 `maxTileZ` 变成 20，「Esri 该被 z19 挡住」那条断言就莫名其妙地红了，看起来像回归。
2. `adb forward --remove` 在第一次跑时会报 `listener not found`（无害，已静音）。

**顺带配好的两件事**（源码里都有注释说明）：

- `MainActivity.setupWebView()`：**只对 debug 包**开 `setWebContentsDebuggingEnabled(true)`，正式包保持关闭（开着等于给本机任意进程一个能读写页面数据的入口）。判据用 `ApplicationInfo.FLAG_DEBUGGABLE` 而不是 `BuildConfig.DEBUG`——AGP 8 起 `buildConfig` 默认 false，`BuildConfig` 类根本不生成，写了会直接编译不过（这个坑实际踩到了）。
- `res/xml/network_security_config.xml`：放行明文 HTTP。因为瓦片源让用户自己填，而现场的自建瓦片服务（树莓派离线地图、机房 GeoServer）基本只提供 http；按 Android 默认禁掉的话，用户填了 http 地址会**静默不出图**，现象和「源填错了」「服务没开」完全一样。代价（明文可被中间人篡改）与上架前的收紧路径都写在文件注释里。


**没有验证到的部分（如实说明）**：
- **BLE 分片没有真机验证过**——分片逻辑要求 `txChar != null` 才执行，没有机器人就进不去。分片的字节级正确性目前只有代码审阅，**联调时这是第一个要盯的地方**。
- **iOS 一行都没编译过**（本机是 Windows）。Swift 代码是按同样的协议写的，但语法/API 错误只有到 Mac 上才会暴露。
- 真机（非模拟器）上的蓝牙权限弹窗、后台保活、不同厂商的 WebView 差异，都没测。

**建议的联调顺序**：先拿一部 Android 真机 + 机器人，把 BLE 三个 UUID 填上 → 点「连接」看能否扫到设备 → 连上后点「下发」看 logcat 里 `→ sendRegion … B / N 帧` 和固件是否收到完整 JSON。**这三步通了，剩下就都是细节。**

