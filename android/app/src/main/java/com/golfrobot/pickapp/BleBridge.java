package com.golfrobot.pickapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * H5 ↔ 原生 的桥。H5 侧把整个协议收在一个对象里：
 *
 *     window.GolfNative.postMessage('{"type":"sendRegion","payload":{...}}')
 *
 * 所以这里只有一个入口 {@link #postMessage(String)}，按 type 分派。
 * 反方向用 {@link #emitJs} 去调 window.GolfApp.onXxx(...)。
 *
 * <h3>为什么 BLE 必须在原生做</h3>
 * WebView 里**没有** Web Bluetooth：
 *  - iOS 的 WKWebView 完全不支持（Safari 支持，但 WKWebView 没有）；
 *  - Android 的 WebView 也不支持（只有 Chrome 浏览器本身支持）。
 * 而机器人是 BLE 连的，所以这一段无论如何都得写原生。这也是"套壳"方案里
 * 唯一不可外包给 H5 的部分。
 *
 * <h3>分片为什么必须做</h3>
 * 一次下发的多区域报文实测 2015 B，远超单个 BLE 包的 ATT payload（MTU 247 → 244 B）。
 * 不分片直接 write，Android 会静默截断，机器人收到半个 JSON 解析失败。
 */
public class BleBridge {

    private static final String TAG = "GolfBLE";

    // ============================================================
    // 机器人侧的 BLE 参数 —— 这几个必须和固件对一致，是接入时第一件要对的事
    //
    // 编译期给默认值，但**现场可以直接从 App 的「设置 → 机器人 BLE 标识」改**，
    // 改完存进 SharedPreferences 立即生效、下次启动仍然有效。
    // 把 UUID 写死在代码里的做法，意味着每换一台机器人就要重新打包一次，
    // 联调阶段根本没法用——所以这几个是实例字段，不是 static final。
    // ============================================================

    private static final String PREFS = "golf_ble";

    /** 机器人主服务的 UUID。**留空 = 不过滤**，靠设备名前缀找。 */
    private volatile String serviceUuid = "";

    /** 用于接收机器人上报（notify）的特征值 UUID */
    private volatile String notifyCharUuid = "";

    /** 用于下发指令（write）的特征值 UUID */
    private volatile String writeCharUuid = "";

    /** 设备名前缀，用来在扫描结果里认出你们自己的机器人 */
    private volatile String namePrefix = "GolfBot";

    /** 一次扫描持续多久（毫秒）。到点自动停，别让蓝牙一直开着耗电。 */
    private static final long SCAN_MS = 12000;

    // ============================================================
    // 分片帧格式（固件必须按这个解）
    // ============================================================
    //
    //   byte 0 : 0xA5  帧头
    //   byte 1 : seq   序号 0..255
    //   byte 2 : total 总帧数
    //   byte 3 : flags 0x01 = 首帧, 0x02 = 末帧
    //   byte 4.. : UTF-8 正文
    //
    // 单帧总长固定 ≤ 244 B（BLE ATT payload 上限），所以正文最多 240 B。
    // 正文按 **字节** 切，不按字符切——切在多字节汉字中间没关系，因为接收端
    // 是先把所有帧拼回一个 byte[] 再整体按 UTF-8 解码。

    private static final byte FRAME_HEAD = (byte) 0xA5;
    private static final int FRAME_HEADER = 4;
    private static final int ATT_PAYLOAD = 244;
    private static final int MAX_CHUNK = ATT_PAYLOAD - FRAME_HEADER;   // 240

    /** 客户端特征配置描述符（开启 notify 用），BLE 规范固定值，别改 */
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ============================================================

    private final Activity activity;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- BLE 状态 ----
    // 这几个字段会被 JS 线程读（postMessage 里的预检）、主线程写，
    // 所以加 volatile，别指望"碰巧能看见"。
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic txChar;   // write（下发）
    private volatile BluetoothGattCharacteristic rxChar;   // notify（上报）

    private final Deque<byte[]> txQueue = new ArrayDeque<>();
    private boolean writing = false;

    // 扫描期间累积发现的设备（地址 → {id,name,rssi}）。
    // 每发现一台就回推一次**全量**列表，H5 直接整表替换——增量同步在这里
    // 只会带来「漏了 / 重复了」的 bug，列表撑死几十条，全量最省心。
    private final Map<String, JSONObject> found = new LinkedHashMap<>();
    private boolean scanning = false;

    private final ByteArrayOutputStream rxBuf = new ByteArrayOutputStream();
    private int rxExpectTotal = 0;
    private int rxGot = 0;

    // ---- 定位 ----
    private LocationManager lm;
    private LocationListener locListener;
    private boolean wantLocation = false;
    private boolean hostForeground = true;

    public BleBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        BluetoothManager bm = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            adapter = bm.getAdapter();
            if (adapter != null) scanner = adapter.getBluetoothLeScanner();
        }
        loadBleConfig();
    }

    /** 读上次在设置页填过的 BLE 标识（没有就用编译期默认值） */
    private void loadBleConfig() {
        try {
            SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            serviceUuid = sp.getString("serviceUuid", serviceUuid);
            notifyCharUuid = sp.getString("notifyCharUuid", notifyCharUuid);
            writeCharUuid = sp.getString("writeCharUuid", writeCharUuid);
            namePrefix = sp.getString("namePrefix", namePrefix);
        } catch (Exception e) {
            Log.w(TAG, "读 BLE 配置失败，用默认值", e);
        }
    }

    // ============================================================
    // H5 → 原生：唯一入口
    // ============================================================

    /**
     * @JavascriptInterface 必须加，否则这个方法不会暴露给 JS（安全机制）。
     * 返回值只能是基本类型/String——这是 JS 接口的硬限制。
     *
     * <p><b>返回值是有意义的</b>：`{"ok":true}` 表示这条指令已经被受理，
     * `{"ok":false,"reason":"…"}` 表示收到了但做不了（最常见的是没连上机器人）。
     * H5 的 `Bridge.post` 会读这个结果——**以前它只判断"原生对象存在"就当成功**，
     * 结果没连机器人时界面照样显示「已下发」、区域边框变成实线，是错的。
     *
     * <p>注意：这里只能做**同步**预检。像"扫描 12 秒后没找到设备"这种
     * 只能异步知道的结论，仍然走 {@link #emitError} → H5 的 onConnection。
     * 两条路都要有，H5 那边也都要显示（见 index.html 的 onConnection）。
     */
    @JavascriptInterface
    public String postMessage(String json) {
        String type;
        JSONObject payload;
        try {
            JSONObject msg = new JSONObject(json);
            type = msg.optString("type");
            payload = msg.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
        } catch (Exception e) {
            Log.e(TAG, "报文不是合法 JSON: " + json, e);
            return ack(false, "报文不是合法 JSON");
        }
        Log.d(TAG, "← " + type);

        // 预检：同步能判定做不了的，立刻拒绝，别让 H5 以为发成功了
        String why = preflight(type);
        if (why != null) {
            Log.w(TAG, "拒绝 " + type + "：" + why);
            return ack(false, why);
        }

        // BLE / 定位的回调都在别的线程，统一切到主线程处理，
        // 后面 evaluateJavascript 也必须在主线程，省得来回切。
        final String t = type;
        final JSONObject p = payload;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(t, p);
                } catch (Exception e) {
                    Log.e(TAG, "dispatch " + t + " failed", e);
                    emitError(t, e.getMessage());
                }
            }
        });
        return ack(true, null);
    }

    /**
     * 同步预检：返回 null 表示可以继续，否则返回给用户看的原因。
     * 这个方法在 JS 线程上跑，所以只读 volatile 字段、不碰 BLE API 之外的东西。
     */
    private String preflight(String type) {
        switch (type) {
            case "sendRegion":
            case "startTask":
            case "pauseTask":
            case "stopTask":
            case "taskDone":
            case "setOrigin":
            case "manualDrive":
            case "setWifi":
                // 下发类指令的唯一硬前提就是"已经连上并且拿到可写特征值"
                if (gatt == null || txChar == null) return "还没连上机器人";
                return null;

            case "scan":
            case "connect":
                if (serviceUuid.isEmpty() && namePrefix.isEmpty()) {
                    return "未配置机器人 BLE 标识（服务 UUID / 设备名前缀），可在设置页填写";
                }
                if (adapter == null) return "本机没有蓝牙适配器";
                try {
                    if (!adapter.isEnabled()) return "手机蓝牙未开启";
                } catch (SecurityException e) {
                    return "缺少蓝牙权限";
                }
                return null;

            case "stopScan":
            case "setBleConfig":
                return null;

            case "disconnect":
            case "getPosition":
                // 定位失败/没权限在 startLocation 里异步报，这里先放行
                return null;

            default:
                return "不认识的指令：" + type;
        }
    }

    private static String ack(boolean ok, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            if (!ok && reason != null) o.put("reason", reason);
            return o.toString();
        } catch (Exception e) {
            return ok ? "{\"ok\":true}" : "{\"ok\":false}";
        }
    }

    private void dispatch(String type, JSONObject p) throws Exception {
        switch (type) {
            // 连接流程：先搜出设备列表让用户选，再连指定的那台。
            // 老 H5 的 connect（不带 deviceId）依然能用，会自动退回"搜到就连"。
            case "scan":         startScan();                                   break;
            case "stopScan":     stopScan();                                    break;
            case "connect":      connectCmd(p);                                 break;
            case "disconnect":   userDisconnect();                              break;
            case "setBleConfig": applyBleConfig(p);                             break;

            // 下发类：全部走分片
            case "sendRegion":
            case "startTask":
            case "pauseTask":
            case "stopTask":
            case "taskDone":
            case "setOrigin":
            case "manualDrive":
            case "setWifi":
                sendFramed(type, p);
                break;

            case "getPosition":
                wantLocation = p.optBoolean("continuous", true);
                startLocation();
                break;

            default:
                Log.w(TAG, "unknown type: " + type);
        }
    }

    // ============================================================
    // 下发：分片
    // ============================================================

    /**
     * 把一条指令按 {@link #MAX_CHUNK} 切帧推入发送队列。
     *
     * 为什么用队列而不是循环里连着 write：BLE 的 write 是异步的，
     * 必须等上一个 onCharacteristicWrite 回调回来才能发下一个。
     * 连续 write 而不等回调，后面的会被系统丢掉（表现为"偶尔少发一截"，
     * 极难复现）。所以这里靠 {@link #pump()} 一帧一帧推进。
     */
    private void sendFramed(String type, JSONObject payload) {
        if (txChar == null || gatt == null) {
            emitError(type, "还没连上机器人");
            return;
        }
        JSONObject env = new JSONObject();
        try {
            env.put("type", type);
            env.put("payload", payload);
            env.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {
        }

        byte[] body = env.toString().getBytes(StandardCharsets.UTF_8);
        int total = (body.length + MAX_CHUNK - 1) / MAX_CHUNK;
        if (total == 0) total = 1;
        if (total > 255) {
            emitError(type, "报文过大（" + body.length + " B），超过 255 帧上限");
            return;
        }

        for (int i = 0; i < total; i++) {
            int off = i * MAX_CHUNK;
            int len = Math.min(MAX_CHUNK, body.length - off);
            byte[] frame = new byte[FRAME_HEADER + len];
            frame[0] = FRAME_HEAD;
            frame[1] = (byte) i;
            frame[2] = (byte) total;
            byte flags = 0;
            if (i == 0) flags |= 0x01;
            if (i == total - 1) flags |= 0x02;
            frame[3] = flags;
            System.arraycopy(body, off, frame, FRAME_HEADER, len);
            txQueue.add(frame);
        }
        Log.d(TAG, "→ " + type + " " + body.length + " B / " + total + " 帧");
        emitProgress(type, body.length, total);
        pump();
    }

    private void pump() {
        if (writing) return;
        final byte[] frame = txQueue.peek();
        if (frame == null) return;
        if (gatt == null || txChar == null) {
            txQueue.clear();
            return;
        }
        writing = true;
        if (!writeChunk(frame)) {
            // 写入调用就失败了，别再挂在队列上
            writing = false;
            txQueue.poll();
            emitError("write", "写入特征值失败");
        }
    }

    @SuppressLint("MissingPermission")
    private boolean writeChunk(byte[] frame) {
        if (Build.VERSION.SDK_INT >= 33) {
            // API 33+ 的新签名：值作为参数传，不再改特征值对象的状态
            return gatt.writeCharacteristic(txChar, frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothGatt.GATT_SUCCESS;
        }
        // 老 API：先 setValue 再 write
        txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        txChar.setValue(frame);
        return gatt.writeCharacteristic(txChar);
    }

    // ============================================================
    // 接收：重组
    // ============================================================

    private void onFrame(byte[] frame) {
        if (frame.length < FRAME_HEADER || frame[0] != FRAME_HEAD) {
            Log.w(TAG, "丢弃非法帧 len=" + frame.length);
            return;
        }
        int seq = frame[1] & 0xFF;
        int total = frame[2] & 0xFF;
        byte flags = frame[3];

        if (seq == 0) {
            rxBuf.reset();
            rxExpectTotal = total;
            rxGot = 0;
        } else if (seq != rxGot) {
            // 丢帧了 —— 与其拼出半个 JSON 让业务拿错数据，不如整条丢弃并明确报错
            Log.w(TAG, "帧序断裂 期望 " + rxGot + " 实际 " + seq);
            emitError("rx", "上报报文丢帧，已丢弃这一条");
            rxBuf.reset();
            rxExpectTotal = 0;
            return;
        }

        rxBuf.write(frame, FRAME_HEADER, frame.length - FRAME_HEADER);
        rxGot = seq + 1;

        if ((flags & 0x02) != 0) {
            // 末帧到了。再核一次总数：中间丢帧会让 rxGot < total，
            // 这种半个 JSON 直接解会拿到错数据，宁可整条丢掉。
            if (rxGot != rxExpectTotal) {
                Log.w(TAG, "末帧收到但只凑齐 " + rxGot + "/" + rxExpectTotal + " 帧，丢弃");
                emitError("rx", "上报报文不完整（" + rxGot + "/" + rxExpectTotal + " 帧），已丢弃");
            } else {
                String json = new String(rxBuf.toByteArray(), StandardCharsets.UTF_8);
                handleReport(json);
            }
            rxBuf.reset();
            rxExpectTotal = 0;
            rxGot = 0;
        }
    }

    /** 机器人上报的报文，形如 {"type":"telemetry","payload":{...}} */
    private void handleReport(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("type");
            JSONObject p = o.optJSONObject("payload");
            if (p == null) p = o;
            Log.d(TAG, "→ 上报 " + t);

            switch (t) {
                case "telemetry":    emitToApp("onTelemetry(" + p + ")");    break;
                case "position":     emitToApp("onPosition(" + p + ")");     break;
                case "connection":   emitToApp("onConnection(" + p + ")");   break;
                case "taskState":    emitToApp("onTaskState(" + p + ")");    break;
                case "coveragePath": emitToApp("onCoveragePath(" + p + ")"); break;
                case "origin":       emitToApp("onOrigin(" + p + ")");       break;
                default:             Log.w(TAG, "未知上报类型: " + t);
            }
        } catch (Exception e) {
            Log.e(TAG, "上报解析失败: " + json, e);
        }
    }

    // ============================================================
    // 扫描 / 连接
    // ============================================================

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            emitConnection("error", "手机蓝牙未开启");
            return;
        }
        if (!hasBlePerm()) {
            emitConnection("error", "缺少蓝牙权限");
            return;
        }
        if (serviceUuid.isEmpty() && namePrefix.isEmpty()) {
            // 不猜：直接说缺什么，比"扫描很慢然后超时"好排查得多
            emitConnection("error", "未配置机器人 BLE 标识（服务 UUID / 设备名前缀），可在设置页填写");
            return;
        }
        if (scanning) return;

        found.clear();
        emitScanList(true);                  // 先清空连接页上的旧列表

        List<ScanFilter> filters = new ArrayList<>();
        if (!serviceUuid.isEmpty()) {
            try {
                filters.add(new ScanFilter.Builder()
                        .setServiceUuid(android.os.ParcelUuid.fromString(serviceUuid))
                        .build());
            } catch (Exception e) {
                emitConnection("error", "服务 UUID 格式不对：" + serviceUuid);
                return;
            }
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        scanner.startScan(filters, settings, scanCb);
        emitConnection("scanning", "正在搜索机器人…");
        main.postDelayed(stopScanTask, SCAN_MS);     // 到点自动停，别让蓝牙一直开着
    }

    /** H5 点了「停止搜索」 */
    private void stopScan() {
        boolean had = !found.isEmpty();
        stopScanInternal();
        emitToApp("onScanStopped({detail:" + JSONObject.quote(
                had ? "已停止搜索，共发现 " + found.size() + " 台" : "已停止搜索") + "})");
    }

    /** 纯关闭扫描，不回推任何东西（内部用，避免各种路径互相打架） */
    @SuppressLint("MissingPermission")
    private void stopScanInternal() {
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {
        }
        main.removeCallbacks(stopScanTask);
    }

    private final Runnable stopScanTask = new Runnable() {
        @Override
        public void run() {
            stopScanInternal();
            if (gatt == null) {
                emitToApp("onScanStopped({detail:" + JSONObject.quote(
                        found.isEmpty() ? "没找到机器人，请确认它已开机并靠近手机"
                                        : "搜索结束，发现 " + found.size() + " 台设备") + "})");
            }
        }
    };

    /** 把当前发现的设备整表推给连接页（列表撑死几十条，全量最省心） */
    private void emitScanList(boolean force) {
        long now = System.currentTimeMillis();
        // 同一批扫到的设备会连着触发好几次回调，500ms 内只推最后一次。
        // 新设备出现时必须立刻推（force），否则界面要等半秒才显示。
        if (!force && now - lastListEmit < 500) return;
        lastListEmit = now;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : found.values()) arr.put(o);
            JSONObject o = new JSONObject();
            o.put("devices", arr);
            emitToApp("onScanResult(" + o + ")");
        } catch (Exception ignored) {
        }
    }

    private long lastListEmit = 0;

    private final ScanCallback scanCb = new ScanCallback() {
        // 一次扫描可能连着吐一串结果，回调线程也不保证就是主线程，
        // 所以统一 post 回主线程处理——这样 found 这张表任何时候只有一个线程在动。
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            main.post(new Runnable() {
                @Override
                public void run() { handleFound(result); }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopScanInternal();
                    emitConnection("error", "蓝牙扫描失败 code=" + errorCode);
                }
            });
        }
    };

    @SuppressLint("MissingPermission")
    private void handleFound(ScanResult result) {
        BluetoothDevice d = result.getDevice();
        String name = d.getName();
        if (name == null) name = "";
        boolean hit = !namePrefix.isEmpty() && name.startsWith(namePrefix);
        // 配了服务 UUID 时过滤器已经筛过一轮，这里再按名字确认一次
        if (serviceUuid.isEmpty() && !hit) return;
        if (!serviceUuid.isEmpty() && !namePrefix.isEmpty() && !hit) return;

        String addr = d.getAddress();
        boolean isNew = !found.containsKey(addr);
        try {
            JSONObject o = found.get(addr);
            if (o == null) {
                // 不再"扫到谁就自动连谁"：现场可能同时开着好几台机器人，
                // 让操作员在列表里点选——连错机器的代价比多点一下大得多。
                o = new JSONObject();
                o.put("id", addr);
                o.put("name", name.isEmpty() ? "未命名设备" : name);
                o.put("rssi", result.getRssi());
                found.put(addr, o);
                Log.d(TAG, "发现 " + name + " / " + addr + " rssi=" + result.getRssi());
            } else {
                o.put("rssi", result.getRssi());     // 已发现过：只更新信号强度
            }
        } catch (Exception ignored) {
        }
        emitScanList(isNew);
    }

    /** H5 在设备列表里选了某一台 */
    @SuppressLint("MissingPermission")
    private void connectCmd(JSONObject p) {
        String id = (p == null) ? "" : p.optString("deviceId", "");
        if (id.isEmpty()) {
            startScan();                 // 没指定设备 → 退回"搜到就连"，兼容老版 H5
            return;
        }
        stopScanInternal();
        try {
            BluetoothDevice d = adapter.getRemoteDevice(id);
            if (d != null) {
                Log.d(TAG, "按选择连接 " + id);
                connectGatt(d);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "取设备失败 " + id, e);
        }
        emitConnection("error", "找不到设备 " + id + "，请重新搜索");
    }

    /** H5 主动断开（不是掉线），状态回 idle，界面不该弹「连接失败」 */
    private void userDisconnect() {
        stopScanInternal();
        closeGatt();
        emitConnection("idle", "已断开机器人连接");
    }

    /**
     * 现场改 BLE 标识，存 SharedPreferences，下次启动仍然有效。
     * 这是联调必需的口子——UUID 写死在代码里意味着每改一次都要重新打包。
     */
    private void applyBleConfig(JSONObject p) {
        if (p == null) return;
        serviceUuid = p.optString("serviceUuid", "").trim();
        notifyCharUuid = p.optString("notifyUuid", "").trim();
        writeCharUuid = p.optString("writeUuid", "").trim();
        namePrefix = p.optString("namePrefix", "").trim();
        try {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("serviceUuid", serviceUuid)
                    .putString("notifyCharUuid", notifyCharUuid)
                    .putString("writeCharUuid", writeCharUuid)
                    .putString("namePrefix", namePrefix)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "保存 BLE 配置失败", e);
        }
        Log.d(TAG, "BLE 标识已更新：prefix=" + namePrefix + " svc=" + serviceUuid);
        // 标识变了，当前这条连接大概率已经对不上，断开重来更干净
        stopScanInternal();
        closeGatt();
        emitConnection("idle", "机器人标识已更新，请重新搜索连接");
    }

    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice d) {
        emitConnection("connecting", "正在连接 " + d.getAddress() + "…");
        gatt = d.connectGatt(activity, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "已连接，发现服务");
                emitConnection("connecting", "已建立连接，正在匹配串口服务…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "断开 status=" + status);
                closeGatt();
                // status==0 是本地主动断开 → idle（界面不该弹"连接失败"）；
                // 非 0 是异常掉线 → error，要让操作员看见
                emitConnection(status == 0 ? "idle" : "error",
                        status == 0 ? "与机器人断开" : "连接异常断开 code=" + status);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            txChar = null;
            rxChar = null;
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    String u = c.getUuid().toString();
                    if (!writeCharUuid.isEmpty() && u.equalsIgnoreCase(writeCharUuid)) txChar = c;
                    if (!notifyCharUuid.isEmpty() && u.equalsIgnoreCase(notifyCharUuid)) rxChar = c;
                }
            }
            if (txChar == null || rxChar == null) {
                emitConnection("error", "机器人服务不匹配：请确认「写入 / 通知特征」UUID，" +
                        "可在设置 → 机器人 BLE 标识里改");
                return;
            }
            // 开 notify
            g.setCharacteristicNotification(rxChar, true);
            BluetoothGattDescriptor cccd = rxChar.getDescriptor(CCCD);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            }
            emitConnection("connected", "已连接机器人");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            // 上一帧发完了 → 出队 → 发下一帧
            writing = false;
            txQueue.poll();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "写失败 status=" + status + "，剩余 " + txQueue.size() + " 帧已丢弃");
                txQueue.clear();
                emitError("write", "蓝牙写入失败 code=" + status);
                return;
            }
            pump();
        }

        // API 33+ 的新回调
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onFrame(value);
        }

        // API < 33 的旧回调
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onFrame(c.getValue());
        }
    };

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        stopScan();
        txQueue.clear();
        writing = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        txChar = null;
        rxChar = null;
    }

    private boolean hasBlePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ============================================================
    // 定位（手机自身 GPS；真机上通常用机器人 RTK 覆盖）
    // ============================================================

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            emitError("getPosition", "缺少定位权限");
            return;
        }
        if (lm == null) lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            emitError("getPosition", "本机不支持定位");
            return;
        }

        locListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                emitPosition(l);
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle b) {
            }
        };

        // GPS + 网络融合：只开 GPS 在室内/树荫下会长时间定不上
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locListener, Looper.getMainLooper());
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0f, locListener, Looper.getMainLooper());
        } catch (Exception e) {
            Log.w(TAG, "requestLocationUpdates: " + e.getMessage());
        }

        // 先给一个最后已知位置，界面不用空着等
        Location last = null;
        try {
            last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
        }
        if (last != null) emitPosition(last);
    }

    private void emitPosition(Location l) {
        try {
            JSONObject p = new JSONObject();
            p.put("lon", l.getLongitude());
            p.put("lat", l.getLatitude());
            p.put("accuracy", l.hasAccuracy() ? (double) l.getAccuracy() : 0d);
            p.put("heading", l.hasBearing() ? (double) l.getBearing() : 0d);
            p.put("src", "phone");
            emitToApp("onPosition(" + p + ")");
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // 生命周期（由 MainActivity 调）
    // ============================================================

    public void onHostPause() {
        hostForeground = false;
        stopLocationUpdates();
    }

    public void onHostResume() {
        hostForeground = true;
        if (wantLocation && hostForeground) startLocation();
    }

    private void stopLocationUpdates() {
        if (lm != null && locListener != null) {
            try {
                lm.removeUpdates(locListener);
            } catch (Exception ignored) {
            }
        }
    }

    public void release() {
        stopLocationUpdates();
        closeGatt();
        main.removeCallbacksAndMessages(null);
    }

    // ============================================================
    // 原生 → H5
    // ============================================================

    /**
     * 把一段 JS 丢进页面执行。通用入口，不假设目标是 window.GolfApp。
     *
     * 注意两点：
     *  1. 必须回主线程（BLE 回调跑在 binder 线程）；
     *  2. 页面可能还在加载、window.GolfApp 还没建好，所以整段包在 try/catch 里，
     *     并且调用方自己要写 `window.GolfApp && ...` 的存在性判断（见 emitToApp）。
     */
    void emitJs(final String js) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                String guarded = "(function(){try{" + js
                        + "}catch(e){console.warn('[bridge:error]',e)}})()";
                web.evaluateJavascript(guarded, null);
            }
        });
    }

    /** 调 window.GolfApp.onXxx(...)，带存在性判断 */
    private void emitToApp(String call) {
        emitJs("window.GolfApp && window.GolfApp." + call);
    }

    /**
     * 连接状态回推。phase 取值：
     * <pre>idle / scanning / connecting / connected / error</pre>
     *
     * H5 连接页的整个界面都靠它驱动（状态图标、按钮文案、进度三步、设备列表）。
     * 同时保留 connected 布尔——老版本 H5 只读那个字段也能正常工作。
     *
     * <p>为什么要有 phase：以前只用 connected 布尔表达，于是「正在搜索」
     * 「正在连接」这些**进度**和「连接失败」这个**错误**在 H5 侧长得一模一样，
     * 界面只能一律显示"未连接"，用户根本不知道它到底在干活还是已经挂了。
     */
    private void emitConnection(String phase, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", phase);
            o.put("connected", "connected".equals(phase));
            o.put("detail", detail == null ? "" : detail);
            emitToApp("onConnection(" + o + ")");
        } catch (Exception ignored) {
        }
    }

    /** 各类异步错误：都算连接层面的问题，交给连接页显示具体原因 */
    private void emitError(String type, String msg) {
        Log.w(TAG, "错误[" + type + "] " + msg);
        try {
            JSONObject o = new JSONObject();
            o.put("phase", "error");
            o.put("connected", false);
            o.put("detail", msg == null ? "" : msg);
            o.put("from", type);
            emitToApp("onConnection(" + o + ")");
        } catch (Exception ignored) {
        }
    }

    /** 下发进度回推，H5 可以拿它显示"正在发送 3/9 帧" */
    private void emitProgress(String type, int bytes, int frames) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("bytes", bytes);
            o.put("frames", frames);
            emitToApp("onSendProgress && window.GolfApp.onSendProgress(" + o + ")");
        } catch (Exception ignored) {
        }
    }
}
