import Foundation
import CoreBluetooth
import CoreLocation
import WebKit

/// H5 ↔ 原生 的桥（iOS 侧）。
///
/// ## 为什么 iOS 的 BLE 也必须在原生做
/// WKWebView **完全不支持 Web Bluetooth**（Safari 支持，但 WKWebView 没有）。
/// 所以和 Android 一样，机器人通信只能走 CoreBluetooth。
///
/// ## 收发格式
/// 入口是 `window.webkit.messageHandlers.golf.postMessage(obj)`，
/// Swift 侧拿到的是 `[String: Any]`。注意 iOS 传的是**对象**，
/// Android 传的是**字符串**——所以 `handleIncoming` 两种都要收。
///
/// 反方向用 `evaluateJavaScript` 调 `window.GolfApp.onXxx(...)`。
final class BleBridge: NSObject {

    // ============================================================
    // 机器人侧的 BLE 参数 —— 必须和固件、以及 Android 侧那一份保持一致
    //
    // 编译期给默认值，但**现场可以直接从 App 的「设置 → 机器人 BLE 标识」改**，
    // 改完存进 UserDefaults 立即生效、下次启动仍然有效。
    // ============================================================

    /// 机器人主服务 UUID，留空 = 不过滤，靠设备名前缀认
    private var serviceUUID = ""
    /// 接收机器人上报（notify）
    private var notifyCharUUID = ""
    /// 给机器人下发（write）
    private var writeCharUUID = ""
    /// 设备名前缀
    private var deviceNamePrefix = "GolfBot"

    /// 一次扫描持续多久（秒）。到点自动停，别让蓝牙一直开着耗电。
    private let scanSeconds: TimeInterval = 12

    // ============================================================
    // 分片帧格式（必须和 Android 侧、固件三方一致）
    // ============================================================
    //
    //   byte 0 : 0xA5  帧头
    //   byte 1 : seq   序号
    //   byte 2 : total 总帧数
    //   byte 3 : flags 0x01 首帧 / 0x02 末帧
    //   byte 4.. : UTF-8 正文
    //
    // 单帧 ≤ 244 B（ATT payload 上限），正文最多 240 B。
    // iOS 上 writeValue 的长度上限由 peripheral.maximumWriteValueLength(for:) 给出，
    // 这里取 244 与 Android 对齐；如果固件协商出更小 MTU，要按协商值下调。

    private let frameHead: UInt8 = 0xA5
    private let frameHeader = 4
    private let maxChunk = 240

    private weak var webView: WKWebView?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var txChar: CBCharacteristic?
    private var rxChar: CBCharacteristic?

    private var txQueue: [Data] = []
    private var writing = false

    private var rxBuf = Data()
    private var rxExpectTotal = 0
    private var rxGot = 0

    private let loc = CLLocationManager()
    private var wantLocation = false

    init(webView: WKWebView) {
        self.webView = webView
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        loc.delegate = self
        loadBleConfig()
    }

    /// 读上次在设置页填过的 BLE 标识（没有就用编译期默认值）
    private func loadBleConfig() {
        let d = UserDefaults.standard
        if let v = d.string(forKey: "serviceUuid") { serviceUUID = v }
        if let v = d.string(forKey: "notifyCharUuid") { notifyCharUUID = v }
        if let v = d.string(forKey: "writeCharUuid") { writeCharUUID = v }
        if let v = d.string(forKey: "namePrefix") { deviceNamePrefix = v }
    }

    // MARK: - H5 → 原生

    /// message.body 在 iOS 是 [String: Any]，但也兼容字符串（防两端混用）
    func handleIncoming(_ body: Any) {
        var type = ""
        var payload: [String: Any] = [:]

        if let dict = body as? [String: Any] {
            type = dict["type"] as? String ?? ""
            payload = dict["payload"] as? [String: Any] ?? [:]
        } else if let s = body as? String, let d = s.data(using: .utf8),
                  let dict = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
            type = dict["type"] as? String ?? ""
            payload = dict["payload"] as? [String: Any] ?? [:]
        }

        print("[GolfBLE] ← \(type)")

        switch type {
        // 连接流程：先搜出设备列表让操作员选，再连指定的那台。
        // 老 H5 的 connect（不带 deviceId）依然能用，会自动退回"搜到就连"。
        case "scan":
            startScan()
        case "stopScan":
            stopScan(announce: true)
        case "connect":
            connectCmd(payload)
        case "disconnect":
            stopScan(announce: false)
            closePeripheral()
            emitConnection("idle", "已断开机器人连接")
        case "setBleConfig":
            applyBleConfig(payload)

        case "sendRegion", "startTask", "pauseTask", "stopTask",
             "taskDone", "setOrigin", "manualDrive", "setWifi":
            sendFramed(type: type, payload: payload)

        case "getPosition":
            wantLocation = (payload["continuous"] as? Bool) ?? true
            startLocation()

        default:
            // 和 Android 侧的 preflight 对等：不认识的指令要给明确原因，
            // 不能只是吞掉。
            print("[GolfBLE] 未知指令 \(type)")
            emitError(type: type, msg: "不认识的指令：\(type)")
        }
    }

    // MARK: - 下发：分片

    /// 把一条指令切帧入队。
    ///
    /// 用队列 + 回调推进，而不是连着 writeValue：CoreBluetooth 的写是异步的，
    /// 不等 `didWriteValueFor` 就发下一帧，后面的会被丢（表现为"偶尔少一截"）。
    private func sendFramed(type: String, payload: [String: Any]) {
        guard let p = peripheral, let ch = txChar else {
            emitError(type: type, msg: "还没连上机器人")
            return
        }
        let env: [String: Any] = [
            "type": type,
            "payload": payload,
            "ts": Int(Date().timeIntervalSince1970 * 1000)
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: env, options: []) else {
            emitError(type: type, msg: "报文序列化失败")
            return
        }

        let total = max(1, (body.count + maxChunk - 1) / maxChunk)
        guard total <= 255 else {
            emitError(type: type, msg: "报文过大（\(body.count) B），超过 255 帧上限")
            return
        }

        for i in 0..<total {
            let off = i * maxChunk
            let len = min(maxChunk, body.count - off)
            var frame = Data()
            frame.append(frameHead)
            frame.append(UInt8(i))
            frame.append(UInt8(total))
            var flags: UInt8 = 0
            if i == 0 { flags |= 0x01 }
            if i == total - 1 { flags |= 0x02 }
            frame.append(flags)
            frame.append(body.subdata(in: off..<(off + len)))
            txQueue.append(frame)
        }
        print("[GolfBLE] → \(type) \(body.count) B / \(total) 帧")
        emitToApp("onSendProgress && window.GolfApp.onSendProgress(\(json(["type": type, "bytes": body.count, "frames": total])))")
        pump()
        _ = p
        _ = ch
    }

    private func pump() {
        guard !writing, let p = peripheral, let ch = txChar else { return }
        guard let frame = txQueue.first else { return }
        writing = true
        p.writeValue(frame, for: ch, type: .withResponse)
    }

    // MARK: - 接收：重组

    private func onFrame(_ data: Data) {
        guard data.count >= frameHeader, data[0] == frameHead else {
            print("[GolfBLE] 丢弃非法帧 len=\(data.count)")
            return
        }
        let seq = Int(data[1])
        let total = Int(data[2])
        let flags = data[3]

        if seq == 0 {
            rxBuf = Data()
            rxExpectTotal = total
            rxGot = 0
        } else if seq != rxGot {
            print("[GolfBLE] 帧序断裂 期望 \(rxGot) 实际 \(seq)")
            emitError(type: "rx", msg: "上报报文丢帧，已丢弃这一条")
            rxBuf = Data()
            rxExpectTotal = 0
            return
        }

        rxBuf.append(data.subdata(in: frameHeader..<data.count))
        rxGot = seq + 1

        if flags & 0x02 != 0 {
            if rxGot != rxExpectTotal {
                emitError(type: "rx", msg: "上报报文不完整（\(rxGot)/\(rxExpectTotal) 帧），已丢弃")
            } else if let s = String(data: rxBuf, encoding: .utf8) {
                handleReport(s)
            }
            rxBuf = Data()
            rxExpectTotal = 0
            rxGot = 0
        }
    }

    private func handleReport(_ jsonText: String) {
        guard let d = jsonText.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else {
            print("[GolfBLE] 上报不是合法 JSON: \(jsonText)")
            return
        }
        let t = root["type"] as? String ?? ""
        let p = (root["payload"] as? [String: Any]) ?? root
        let payloadJson = json(p)
        print("[GolfBLE] → 上报 \(t)")

        switch t {
        case "telemetry":    emitToApp("onTelemetry(\(payloadJson))")
        case "position":     emitToApp("onPosition(\(payloadJson))")
        case "connection":   emitToApp("onConnection(\(payloadJson))")
        case "taskState":    emitToApp("onTaskState(\(payloadJson))")
        case "coveragePath": emitToApp("onCoveragePath(\(payloadJson))")
        case "origin":       emitToApp("onOrigin(\(payloadJson))")
        default:             print("[GolfBLE] 未知上报类型 \(t)")
        }
    }

    // MARK: - 扫描 / 连接

    /// 扫描期间累积发现的设备（identifier → {id,name,rssi}）
    /// 每发现一台就回推一次**全量**列表，H5 整表替换——
    /// 增量同步在这里只会带来「漏了 / 重复了」的 bug，列表撑死几十条。
    private var found: [String: [String: Any]] = [:]
    private var scanTimer: DispatchWorkItem?

    /// 扫描到的外围设备引用（identifier → CBPeripheral）。
    /// iOS 拿不到 MAC 地址，identifier 是系统给的 UUID 字符串，
    /// 连接时要靠这张表把点选的 id 还原成 CBPeripheral 对象。
    private var discovered: [String: CBPeripheral] = [:]

    private func startScan() {
        guard central.state == .poweredOn else {
            emitConnection("error", "手机蓝牙未开启")
            return
        }
        guard !(serviceUUID.isEmpty && deviceNamePrefix.isEmpty) else {
            emitConnection("error", "未配置机器人 BLE 标识（服务 UUID / 设备名前缀），可在设置页填写")
            return
        }
        if scanTimer != nil { return }        // 已经在扫了

        found.removeAll()
        emitScanList(force: true)             // 先清空连接页上的旧列表

        var services: [CBUUID]? = nil
        if !serviceUUID.isEmpty { services = [CBUUID(string: serviceUUID)] }

        central.scanForPeripherals(withServices: services,
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        emitConnection("scanning", "正在搜索机器人…")

        // 到点自动停，别一直开着蓝牙耗电
        let task = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.stopScan(announce: false)
            if self.peripheral == nil {
                self.emitToApp("onScanStopped(\(self.json([
                    "detail": self.found.isEmpty
                        ? "没找到机器人，请确认它已开机并靠近手机"
                        : "搜索结束，发现 \(self.found.count) 台设备"
                ])))")
            }
        }
        scanTimer = task
        DispatchQueue.main.asyncAfter(deadline: .now() + scanSeconds, execute: task)
    }

    /// announce=true 表示是用户点了「停止搜索」，要回一条结果给界面
    private func stopScan(announce: Bool) {
        scanTimer?.cancel()
        scanTimer = nil
        central.stopScan()
        if announce {
            emitToApp("onScanStopped(\(json([
                "detail": found.isEmpty ? "已停止搜索" : "已停止搜索，共发现 \(found.count) 台"
            ])))")
        }
    }

    private func emitScanList(force: Bool) {
        var arr: [[String: Any]] = []
        for (_, v) in found { arr.append(v) }
        emitToApp("onScanResult(\(json(["devices": arr])))")
    }

    /// H5 在设备列表里选了某一台
    private func connectCmd(_ p: [String: Any]) {
        guard let id = p["deviceId"] as? String, !id.isEmpty else {
            startScan()                        // 没指定 → 退回"搜到就连"，兼容老版 H5
            return
        }
        stopScan(announce: false)
        // iOS 拿不到 MAC 地址，identifier 是系统给的 UUID 字符串。
        // 扫描时发现过的外围设备都留着引用，这里按 identifier 找回来。
        if let hit = discovered[id] {
            connect(hit)
        } else {
            emitConnection("error", "找不到设备，请重新搜索")
        }
    }

    /// 现场改 BLE 标识，存 UserDefaults，下次启动仍然有效。
    /// 这是联调必需的口子——UUID 写死在代码里意味着每改一次都要重新打包。
    private func applyBleConfig(_ p: [String: Any]) {
        serviceUUID = (p["serviceUuid"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        notifyCharUUID = (p["notifyUuid"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        writeCharUUID = (p["writeUuid"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        deviceNamePrefix = (p["namePrefix"] as? String ?? "").trimmingCharacters(in: .whitespaces)

        let d = UserDefaults.standard
        d.set(serviceUUID, forKey: "serviceUuid")
        d.set(notifyCharUUID, forKey: "notifyCharUuid")
        d.set(writeCharUUID, forKey: "writeCharUuid")
        d.set(deviceNamePrefix, forKey: "namePrefix")

        print("[GolfBLE] BLE 标识已更新：prefix=\(deviceNamePrefix) svc=\(serviceUUID)")
        // 标识变了，当前这条连接大概率已经对不上，断开重来更干净
        stopScan(announce: false)
        closePeripheral()
        emitConnection("idle", "机器人标识已更新，请重新搜索连接")
    }

    private func connect(_ p: CBPeripheral) {
        emitConnection("connecting", "正在连接 \(p.name ?? p.identifier.uuidString)…")
        peripheral = p
        p.delegate = self
        central.connect(p, options: nil)
    }

    private func closePeripheral() {
        scanTimer?.cancel()
        scanTimer = nil
        central.stopScan()
        txQueue.removeAll()
        writing = false
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        txChar = nil
        rxChar = nil
    }

    // MARK: - 定位

    private func startLocation() {
        switch loc.authorizationStatus {
        case .notDetermined:
            loc.requestWhenInUseAuthorization()
        case .denied, .restricted:
            emitError(type: "getPosition", msg: "缺少定位权限")
            return
        default:
            break
        }
        loc.desiredAccuracy = kCLLocationAccuracyBest
        loc.startUpdatingLocation()
        if let last = loc.location { emitPosition(last) }
    }

    private func emitPosition(_ l: CLLocation) {
        var d: [String: Any] = [
            "lon": l.coordinate.longitude,
            "lat": l.coordinate.latitude,
            "accuracy": l.horizontalAccuracy,
            "src": "phone"
        ]
        if l.course >= 0 { d["heading"] = l.course }
        emitToApp("onPosition(\(json(d)))")
    }

    // MARK: - 生命周期

    func onHostPause() { loc.stopUpdatingLocation() }

    func onHostResume() { if wantLocation { startLocation() } }

    func release() {
        loc.stopUpdatingLocation()
        closePeripheral()
    }

    // MARK: - 原生 → H5

    private func emitToApp(_ call: String) {
        guard let wv = webView else { return }
        let js = "(function(){try{window.GolfApp && window.GolfApp.\(call)}catch(e){console.warn('[bridge:error]',e)}})()"
        DispatchQueue.main.async {
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    /// 连接状态回推。phase 取值：idle / scanning / connecting / connected / error
    ///
    /// H5 连接页的整个界面都靠它驱动（状态图标、按钮文案、进度三步、设备列表）。
    /// 同时保留 connected 布尔——老版本 H5 只读那个字段也能正常工作。
    ///
    /// 为什么要有 phase：以前只用 connected 布尔表达，于是「正在搜索」「正在连接」
    /// 这些**进度**和「连接失败」这个**错误**在 H5 侧长得一模一样，界面只能一律
    /// 显示"未连接"，用户根本不知道它到底在干活还是已经挂了。
    private func emitConnection(_ phase: String, _ detail: String) {
        emitToApp("onConnection(\(json(["phase": phase,
                                        "connected": phase == "connected",
                                        "detail": detail])))")
    }

    private func emitError(type: String, msg: String) {
        print("[GolfBLE] 错误[\(type)] \(msg)")
        emitToApp("onConnection(\(json(["phase": "error", "connected": false,
                                        "detail": msg, "from": type])))")
    }

    private func json(_ o: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: o, options: []),
              let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }
}

// MARK: - CBCentralManagerDelegate

extension BleBridge: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn {
            emitConnection("error", "蓝牙不可用（state=\(central.state.rawValue)）")
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
        // 配了 serviceUUID 时系统已经筛过一轮，这里再按名字确认一次
        if !serviceUUID.isEmpty && !deviceNamePrefix.isEmpty && !name.hasPrefix(deviceNamePrefix) { return }
        if serviceUUID.isEmpty && !name.hasPrefix(deviceNamePrefix) { return }

        // 不再"扫到谁就自动连谁"：现场可能同时开着好几台机器人，
        // 让操作员在列表里点选——连错机器的代价比多点一下大得多。
        let id = peripheral.identifier.uuidString
        let isNew = found[id] == nil
        discovered[id] = peripheral
        found[id] = [
            "id": id,
            "name": name.isEmpty ? "未命名设备" : name,
            "rssi": RSSI.intValue
        ]
        if isNew {
            print("[GolfBLE] 发现 \(name) / \(id) rssi=\(RSSI.intValue)")
        }
        emitScanList(force: isNew)      // 新设备立刻推，信号强度变化攒一攒再推
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        print("[GolfBLE] 已连接，发现服务")
        emitConnection("connecting", "已建立连接，正在匹配串口服务…")
        peripheral.discoverServices(serviceUUID.isEmpty ? nil : [CBUUID(string: serviceUUID)])
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        print("[GolfBLE] 断开 \(error?.localizedDescription ?? "")")
        txChar = nil
        rxChar = nil
        // 本地主动断开 → idle（界面不该弹「连接失败」）；异常掉线 → error
        if let e = error {
            emitConnection("error", "连接异常断开：\(e.localizedDescription)")
        } else {
            emitConnection("idle", "与机器人断开")
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BleBridge: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        txChar = nil
        rxChar = nil
        for s in services {
            peripheral.discoverCharacteristics(nil, for: s)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard let chars = service.characteristics else { return }
        for c in chars {
            let u = c.uuid.uuidString.uppercased()
            if !writeCharUUID.isEmpty && u == writeCharUUID.uppercased() { txChar = c }
            if !notifyCharUUID.isEmpty && u == notifyCharUUID.uppercased() { rxChar = c }
        }
        if let rx = rxChar {
            peripheral.setNotifyValue(true, for: rx)
        }
        if txChar != nil && rxChar != nil {
            emitConnection("connected", "已连接机器人")
        } else {
            emitConnection("error", "机器人服务不匹配：请确认「写入 / 通知特征」UUID，" +
                           "可在设置 → 机器人 BLE 标识里改")
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard let d = characteristic.value else { return }
        onFrame(d)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        // 上一帧发完了 → 出队 → 发下一帧
        writing = false
        if error != nil {
            print("[GolfBLE] 写失败 \(error!.localizedDescription)，剩余 \(txQueue.count) 帧已丢弃")
            txQueue.removeAll()
            emitError(type: "write", msg: "蓝牙写入失败")
            return
        }
        if !txQueue.isEmpty { txQueue.removeFirst() }
        pump()
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let e = error {
            emitError(type: "notify", msg: "开启上报失败：\(e.localizedDescription)")
        }
    }
}

// MARK: - CLLocationManagerDelegate

extension BleBridge: CLLocationManagerDelegate {

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        emitPosition(l)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        emitError(type: "getPosition", msg: "定位失败：\(error.localizedDescription)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let st = manager.authorizationStatus
        if st == .authorizedWhenInUse || st == .authorizedAlways {
            if wantLocation { startLocation() }
        } else if st == .denied || st == .restricted {
            emitError(type: "getPosition", msg: "定位权限被拒绝")
        }
    }
}
