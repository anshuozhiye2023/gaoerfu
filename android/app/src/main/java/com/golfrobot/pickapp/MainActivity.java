package com.golfrobot.pickapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生壳：整屏一块 WebView，装 H5 原型。
 *
 * <p>这个壳刻意做到「零第三方依赖」——只用 android.* 原生 API，不引 androidx。
 * 好处是首次构建只需要下载 AGP 自己，不碰 androidx 那几百兆，
 * 构建快、版本冲突的可能性也降到最低。地图像素读取、fetch/XHR 这类
 * 需要 https 源的场景本来就不存在（原型只用 {@code new Image()} 加载瓦片），
 * 所以 file:// 直载完全够用。
 */
public class MainActivity extends Activity {

    /**
     * H5 原型就是一个自包含的 HTML，放在 {@code app/src/main/assets/index.html}。
     * <b>换版本 = 覆盖这个文件后重新构建</b>，不需要动任何 Java 代码。
     */
    private static final String START_URL = "file:///android_asset/index.html";

    private static final int REQ_PERMS = 0x6401;

    private WebView web;
    private BleBridge ble;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 圈地 / 作业过程中屏幕必须常亮：熄屏会让 WebView 停掉 requestAnimationFrame，
        // 地图不刷新，用户以为卡死了。现场作业类 App 的常识性设置。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        setupWebView();

        // 注入成 window.GolfNative —— H5 的 Bridge.post() 认的就是这个名字。
        // 方法必须带 @JavascriptInterface 才会被暴露（见 BleBridge.postMessage）。
        ble = new BleBridge(this, web);
        web.addJavascriptInterface(ble, "GolfNative");

        ensurePermissions();

        web.loadUrl(START_URL);
    }

    private void setupWebView() {
        // 仅 debug 包开启 WebView 远程调试（chrome://inspect，或 adb forward 到 9222 用 CDP）。
        // 正式包必须保持关闭 —— 开着等于给本机任意进程一个可以直接读写页面数据的入口。
        // 打开它才做得到「在真机/模拟器里断言 H5 的内部状态」，而不只是看截图猜。
        //
        // 判据用 FLAG_DEBUGGABLE 而不是 BuildConfig.DEBUG：AGP 8 起 buildConfig 默认为
        // false，BuildConfig 类根本不生成，写 BuildConfig.DEBUG 会直接编译不过。
        boolean debuggable =
            (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings s = web.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);

        // 从 assets 加载需要 file 访问权限；但只给「读」，
        // 不给 file:// 页面跨源读别的本地文件（默认就该是关的，这里写明确）。
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        // 双指缩放交给 H5 自己处理（那是地图手势），WebView 层不要再插一脚，
        // 否则两套缩放手势会打架。
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        // 页面是 file://、瓦片是 https，某些机型不加这句会拦掉瓦片。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);

        // 跟随系统字体缩放会让 H5 的固定像素布局错位，锁成 100%
        s.setTextZoom(100);

        // 加个标识，方便服务端 / 抓包区分「App 内」和「浏览器里」
        s.setUserAgentString(s.getUserAgentString() + " GolfPickRobot/1.0");

        web.setBackgroundColor(0xFFF5F7FA);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("file://")) return false;   // 站内，放行
                // 外链（比如用户点到了地图版权链接）交给系统浏览器，别在 WebView 里开
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Log.w("GolfH5", "无法打开外链 " + url);
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback cb) {
                // 关键：默认实现是「直接拒绝、且连提示都不弹」，
                // 结果 navigator.geolocation.watchPosition 静默失败——
                // 原型里「定位」按钮点下去毫无反应，就是这个原因。
                cb.invoke(origin, true, true);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                // 把 H5 的 [bridge:*] 日志转进 logcat，真机联调时
                // 不用连 chrome://inspect 也能看到桥的收发情况。
                Log.d("GolfH5", m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
    }

    /** 一次性把该要的权限都要了：蓝牙 + 定位。 */
    private void ensurePermissions() {
        List<String> need = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) 起蓝牙权限拆成三个，必须运行时申请
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        // 定位：地图上显示「我的位置」要用；
        // 另外 Android 11 及以下的 BLE 扫描也要求这一项，所以不能省。
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        // 注：BLUETOOTH / BLUETOOTH_ADMIN 是安装即授予的普通权限，不需要运行时申请。

        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;

        // 把结果回推给 H5，让界面能给出「去设置里开权限」的明确指引，
        // 而不是用户点了「连接」之后一直转圈。
        // 需要在 H5 里定义 window.GolfPerms.onResult(deniedList) 才能收到（可选钩子）。
        StringBuilder denied = new StringBuilder();
        for (int i = 0; i < perms.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                if (denied.length() > 0) denied.append(',');
                denied.append(perms[i]);
            }
        }
        String js = denied.length() == 0 ? "null" : "'" + denied + "'";
        ble.emitJs("window.GolfPerms && window.GolfPerms.onResult(" + js + ")");
    }

    @Override
    public void onBackPressed() {
        // H5 可能有「正在编辑某块区域 / 手上有未下发的草稿」这类中间态，
        // 先给它一次机会消费返回键（可选钩子 window.GolfOnBack，返回 true = 已处理）。
        //
        // evaluateJavascript 是异步的，所以这里先直接返回；回调里再决定要不要退出。
        // 返回 true 时什么都不做，正是「H5 消费掉了」的语义。
        web.evaluateJavascript(
                "(function(){try{return !!(window.GolfOnBack && window.GolfOnBack())}catch(e){return false}})()",
                value -> {
                    if ("true".equals(value)) return;      // H5 已处理
                    if (web.canGoBack()) {
                        web.goBack();
                    } else {
                        finish();
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ble != null) ble.onHostPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (ble != null) ble.onHostResume();
    }

    @Override
    protected void onDestroy() {
        if (ble != null) ble.release();
        if (web != null) {
            web.removeJavascriptInterface("GolfNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
