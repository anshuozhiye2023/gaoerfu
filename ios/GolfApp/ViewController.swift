import UIKit
import WebKit

/// 原生壳：整屏一块 WKWebView，装 H5 原型。
///
/// ## 和 Android 侧的两个关键差异
///
/// 1. **WKWebView 没有 `navigator.geolocation`。**
///    这是 iOS 的长期限制（Safari 有，WKWebView 没有）。好在 H5 的定位逻辑是
///    「先问原生要，原生不行才退回浏览器定位」，而 `Bridge.post('getPosition')`
///    在 iOS 上总能成功（走本文件的 messageHandler），所以页面**不会**走到
///    `navigator.geolocation` 那条分支，不需要额外注入 JS 垫片。
///    但如果你以后改了 H5 的定位顺序，记得这里要补 shim。
///
/// 2. **JS 桥的入口名字不一样。**
///    Android 是 `window.GolfNative.postMessage(字符串)`；
///    iOS 是 `window.webkit.messageHandlers.golf.postMessage(对象)`。
///    H5 的 `Bridge.post()` 两个都认，所以同一份 index.html 两端都能跑。
class ViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate {

    private var webView: WKWebView!
    private var ble: BleBridge!

    /// 换 H5 版本 = 覆盖 Resources/index.html，不用动 Swift
    private let startFile = "index"

    override func viewDidLoad() {
        super.viewDidLoad()

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        // 刻意**不**设置 WKPreferences 的 allowFileAccessFromFileURLs / 
        // allowUniversalAccessFromFileURLs：那两个是非公开 API，KVC 设上去
        // 有被 App Store 拒审的风险，而我们用 loadHTMLString 加载内联 HTML
        // 根本不需要它们（原型也从不 fetch/XHR 本地文件）。

        let wv = WKWebView(frame: view.bounds, configuration: config)
        wv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        wv.navigationDelegate = self
        wv.isOpaque = false
        wv.backgroundColor = UIColor(red: 0.96, green: 0.97, blue: 0.98, alpha: 1)

        // 桥：H5 那边调 window.webkit.messageHandlers.golf.postMessage(...) 进这里
        wv.configuration.userContentController.add(self, name: "golf")

        view.backgroundColor = UIColor(red: 0.96, green: 0.97, blue: 0.98, alpha: 1)
        view.addSubview(wv)
        webView = wv

        ble = BleBridge(webView: wv)

        loadPrototype()
    }

    private func loadPrototype() {
        // 原型连同 js/css 都内联在一个 html 里，直接按文件读进来用 loadHTMLString，
        // 比 loadFileURL 少一层 baseURL 的坑（file:// 下有些相对资源会被拦）。
        guard let path = Bundle.main.path(forResource: startFile, ofType: "html"),
              let html = try? String(contentsOfFile: path, encoding: .utf8) else {
            assertionFailure("Resources/\(startFile).html 没打进包里")
            return
        }
        // baseURL 给 app 目录，让 file:// 的判定和 Android 侧一致
        let base = URL(fileURLWithPath: path).deletingLastPathComponent()
        webView.loadHTMLString(html, baseURL: base)
    }

    // MARK: - 生命周期

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        ble.onHostPause()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        ble.onHostResume()
    }

    deinit {
        ble.release()
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "golf")
    }

    // MARK: - WKScriptMessageHandler：H5 → 原生

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard message.name == "golf" else { return }
        ble.handleIncoming(message.body)
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        // 站内（file:// 或 about:）放行，外链丢给 Safari
        if url.scheme == "file" || url.scheme == "about" {
            decisionHandler(.allow)
            return
        }
        if navigationAction.navigationType == .linkActivated {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }
}
