import Darwin
import Foundation

/// 思骑出行车型仪表预设（当前仅内置凯越 450RR，后期可按思骑车型直接扩展）。
enum SiqiBikePreset: Int, CaseIterable, Sendable {
    case kove450RR = 0

    var title: String {
        switch self {
        case .kove450RR: return "思骑出行 / 凯越 450RR"
        }
    }

    var shortTitle: String {
        switch self {
        case .kove450RR: return "凯越 450RR"
        }
    }

    var description: String {
        switch self {
        case .kove450RR:
            return "直角无圆角黑边 · 四边等距留白 · 1:1 图标比例矫正"
        }
    }

    /// CarPlay 基准虚拟画布（16:7，与 Android 端实测完美方案一致）。
    var baseWidth: Int { 2560 }
    var baseHeight: Int { 1120 }
    /// 推流到 1280×640 仪表画面中的有效显示视口（y = 78..562，上下各留 78px 黑边隐藏在仪表上下栏后）。
    var viewportTop: Int { 78 }
    var viewportHeight: Int { 484 }
    var overscanX: Double { 1.0 }
    /// 为 true 时向 CarPlay 声明 cornerMasks = true 且不下发 safeArea，消除圆角黑边。
    var disableSafeAreaSquareCorners: Bool { true }

    var streamWidth: Int { SiqiKoveProtocol.width }
    var streamHeight: Int { SiqiKoveProtocol.height }
    var oemLabel: String { "KOVE" }
    var manufacturer: String { "Kove" }
    var modelName: String { "Kove-450RR-CarPlay" }

    /// 按车型基准尺寸（2560×1120）与图标档位缩放（40%/50%/60%）得到 16px 对齐的 CarPlay 画布：
    /// - 超大图标（40%, 100mm）：1024×448
    /// - 大图标推荐（50%, 100mm）：1280×560
    /// - 标准（60%, 200mm）：1536×672
    /// 转码输出时再精准合成至 1280×640 中的 1280×484（y=78..562），仪表拉伸后图标正好 1:1 无畸变且四边等距。
    func carPlayCanvasSize(for icon: SiqiIconSizePreset, videoMode: SiqiVideoMode) -> (width: Int, height: Int) {
        switch videoMode {
        case .transcode:
            return (Self.scaleEven(baseWidth, percent: icon.scalePercent),
                    Self.scaleEven(baseHeight, percent: icon.scalePercent))
        case .passthrough:
            return (streamWidth, streamHeight)
        }
    }

    /// 推流画面 1280×640 内的有效视频视口范围。
    func viewportRect(for videoMode: SiqiVideoMode) -> (top: Int, height: Int) {
        switch videoMode {
        case .transcode:
            return (viewportTop, viewportHeight)
        case .passthrough:
            return (0, streamHeight)
        }
    }

    /// 下发给 iOS CarPlay `/info` 的安全区 / 直角遮罩策略。
    func airPlaySafeArea(for videoMode: SiqiVideoMode) -> (disableSafeAreaSquareCorners: Bool, top: Int, bottom: Int, drawOutside: Bool) {
        switch videoMode {
        case .transcode:
            return (disableSafeAreaSquareCorners, 0, 0, true)
        case .passthrough:
            let bottom = max(0, streamHeight - viewportTop - viewportHeight)
            return (false, viewportTop, bottom, true)
        }
    }

    private static func scaleEven(_ pixels: Int, percent: Int) -> Int {
        let clamped = max(30, min(160, percent))
        let scaled = max(2, (pixels * clamped + 50) / 100)
        return scaled % 2 == 0 ? scaled : scaled + 1
    }
}

/// CarPlay 图标大小档位（与 Android 版实测方案完全同步：40%/100mm、50%/100mm、60%/200mm）。
enum SiqiIconSizePreset: Int, CaseIterable, Sendable {
    case xl = 0
    case large = 1
    case standard = 2

    var title: String {
        switch self {
        case .xl: return "超大图标"
        case .large: return "大图标(推荐)"
        case .standard: return "标准"
        }
    }

    var shortTitle: String {
        switch self {
        case .xl: return "超大图标"
        case .large: return "大图标"
        case .standard: return "标准"
        }
    }

    /// 相对车型基准画布（2560×1120）的缩放百分比。
    var scalePercent: Int {
        switch self {
        case .xl: return 40
        case .large: return 50
        case .standard: return 60
        }
    }

    var widthPhysicalMM: Int {
        switch self {
        case .xl: return 100
        case .large: return 100
        case .standard: return 200
        }
    }
}

/// 思骑 / 凯越 450RR 视频输出模式。
enum SiqiVideoMode: Int, CaseIterable, Sendable {
    case transcode = 0
    case passthrough = 1

    var title: String {
        switch self {
        case .transcode: return "兼容转码(1:1矫正)"
        case .passthrough: return "H.264直传(低发热)"
        }
    }

    var shortTitle: String {
        switch self {
        case .transcode: return "1:1 防拉伸转码"
        case .passthrough: return "H.264 直传"
        }
    }
}

/// 仪表连接与推流实时快照，供主界面卡片展示。
struct SiqiClusterSnapshot: Equatable, Sendable {
    let isRunning: Bool
    let controlConnected: Bool
    let videoActive: Bool
    let peerIP: String?
    let framesStreamed: Int64
    let bytesStreamed: Int64
    let summary: String

    static let idle = SiqiClusterSnapshot(
        isRunning: false,
        controlConnected: false,
        videoActive: false,
        peerIP: nil,
        framesStreamed: 0,
        bytesStreamed: 0,
        summary: "等待启动无线 CarPlay"
    )
}

/// 思骑（Thinkerride / Kove 450RR）投屏与控制报文构造器。
enum SiqiKoveProtocol {
    static let width = 1280
    static let height = 640
    static let controlPort: UInt16 = 17818
    static let heartbeatPort: UInt16 = 15457
    static let videoPort: UInt16 = 15456

    static func wrapFrame(_ payload: Data) -> Data {
        var frame = Data(capacity: payload.count + 7)
        frame.append(0xEE)
        frame.append(0xFD)
        appendUInt32BE(UInt32(payload.count), to: &frame)
        frame.append(payload)
        frame.append(0xFF)
        return frame
    }

    static func tucQueryPacket() -> Data {
        wrapFrame(Data("{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}".utf8))
    }

    static func deviceTypePacket() -> Data {
        var packet = Data(count: 10)
        packet[0] = 1
        packet[1] = 23
        writeInt32BE(4, to: &packet, offset: 2)
        writeInt32BE(2, to: &packet, offset: 6) // 2 = Android
        return packet
    }

    static func linkInfoPacket(nickname: String = "CarPlay-Bridge") -> Data {
        var out = Data([0x01, 0x0C, 0x00, 0x00, 0x01, 0x00])
        var padded = Data(count: 256)
        let nameBytes = Array(nickname.utf8.prefix(256))
        if !nameBytes.isEmpty {
            padded.replaceSubrange(0..<nameBytes.count, with: nameBytes)
        }
        out.append(padded)
        return out
    }

    static func firmwareRequestPacket() -> Data {
        Data([0x01, 0x01, 0x00, 0x00, 0x00, 0x00])
    }

    static func keepalivePacket() -> Data {
        Data([0x02, 0x01, 0x00, 0x00, 0x00, 0x00])
    }

    static func naviStartPacket() -> Data {
        let json = "{\"msg_id\":27,\"func\":\"NAVI\",\"CMD\":\"inside_naviinfo\"," +
            "\"start\":\"30.0,120.0\",\"waynum\":0,\"end\":\"30.1,120.1\"," +
            "\"routeId\":1,\"strategy\":0,\"dst\":\"Apple CarPlay\"," +
            "\"naviType\":1,\"carType\":1,\"isRestricted\":0,\"carNumber\":\"\",\"carCC\":450,\"themeMode\":0}"
        return wrapFrame(Data(json.utf8))
    }

    static func naviInfoPacket(
        turnIcon: Int32 = 1,
        nextRoad: String = "Apple CarPlay",
        turnDist: Int32 = 1000,
        totalDist: Int32 = 10000
    ) -> Data {
        var packet = Data(count: 274)
        packet[0] = 3
        packet[1] = 1
        writeInt32BE(268, to: &packet, offset: 2)
        writeInt32BE(turnIcon, to: &packet, offset: 6)
        writeInt32BE(turnDist, to: &packet, offset: 10)
        writeInt32BE(totalDist, to: &packet, offset: 14)
        writeInt32BE(1800, to: &packet, offset: 18)
        let roadBytes = Array(nextRoad.utf8.prefix(252))
        if !roadBytes.isEmpty {
            packet.replaceSubrange(22..<(22 + roadBytes.count), with: roadBytes)
        }
        return packet
    }

    static func videoFormatHeader(width: Int = width, height: Int = height) -> Data {
        var header = Data(count: 69)
        let tag = Array("android".utf8)
        header.replaceSubrange(1..<(1 + tag.count), with: tag)
        header[65] = UInt8((width >> 8) & 0xFF)
        header[66] = UInt8(width & 0xFF)
        header[67] = UInt8((height >> 8) & 0xFF)
        header[68] = UInt8(height & 0xFF)
        return header
    }

    private static func appendUInt32BE(_ value: UInt32, to data: inout Data) {
        data.append(UInt8((value >> 24) & 0xFF))
        data.append(UInt8((value >> 16) & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8(value & 0xFF))
    }

    private static func writeInt32BE(_ value: Int32, to data: inout Data, offset: Int) {
        let u = UInt32(bitPattern: value)
        data[offset] = UInt8((u >> 24) & 0xFF)
        data[offset + 1] = UInt8((u >> 16) & 0xFF)
        data[offset + 2] = UInt8((u >> 8) & 0xFF)
        data[offset + 3] = UInt8(u & 0xFF)
    }
}

/// 思骑 / 凯越 450RR 仪表服务端（TCP 17818 控制、15457 心跳、15456 H.264 视频推流）。
final class SiqiKoveServer: @unchecked Sendable {
    private static let maxQueuedFrames = 30

    private final class SocketBox: @unchecked Sendable {
        private let fdLock = NSLock()
        private let writeLock = NSLock()
        private var fd: Int32
        private(set) var peerIP: String = ""

        init(_ fd: Int32, peerIP: String = "") {
            self.fd = fd
            self.peerIP = peerIP
            Self.configureCommon(fd)
        }

        deinit { close() }

        var isOpen: Bool {
            fdLock.lock()
            defer { fdLock.unlock() }
            return fd >= 0
        }

        var boundPort: UInt16? {
            fdLock.lock()
            let current = fd
            fdLock.unlock()
            guard current >= 0 else { return nil }
            var addr = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let res = withUnsafeMutablePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getsockname(current, $0, &len)
                }
            }
            guard res == 0 else { return nil }
            return UInt16(bigEndian: addr.sin_port)
        }

        static func listen(port: UInt16) throws -> SocketBox {
            let sock = Darwin.socket(AF_INET, SOCK_STREAM, 0)
            guard sock >= 0 else {
                throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
            }
            let box = SocketBox(sock)
            var reuse: Int32 = 1
            _ = setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = port.bigEndian
            addr.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)
            let bindResult = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.bind(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            guard bindResult == 0, Darwin.listen(sock, 4) == 0 else {
                let err = errno
                box.close()
                throw NSError(domain: NSPOSIXErrorDomain, code: Int(err))
            }
            return box
        }

        func accept(shouldContinue: () -> Bool) -> SocketBox? {
            while shouldContinue() {
                fdLock.lock()
                let current = fd
                fdLock.unlock()
                guard current >= 0 else { return nil }
                var pfd = pollfd(fd: current, events: Int16(POLLIN), revents: 0)
                let ready = Darwin.poll(&pfd, 1, 50)
                if ready < 0 {
                    if errno == EINTR { continue }
                    return nil
                }
                if ready == 0 { continue }
                if (pfd.revents & Int16(POLLNVAL | POLLERR | POLLHUP)) != 0 { return nil }
                var addr = sockaddr_in()
                var len = socklen_t(MemoryLayout<sockaddr_in>.size)
                let clientFD = withUnsafeMutablePointer(to: &addr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        Darwin.accept(current, $0, &len)
                    }
                }
                if clientFD < 0 {
                    if errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR { continue }
                    return nil
                }
                var hostBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                var rawAddr = addr.sin_addr
                let ip = inet_ntop(AF_INET, &rawAddr, &hostBuf, socklen_t(hostBuf.count))
                    .map { String(cString: $0) } ?? ""
                return SocketBox(clientFD, peerIP: ip)
            }
            return nil
        }

        func enableVideoTuning(sendBufferSize: Int32 = 16384) {
            fdLock.lock()
            let current = fd
            fdLock.unlock()
            guard current >= 0 else { return }
            var nodelay: Int32 = 1
            var sndbuf = sendBufferSize
            _ = setsockopt(current, IPPROTO_TCP, TCP_NODELAY, &nodelay, socklen_t(MemoryLayout<Int32>.size))
            _ = setsockopt(current, SOL_SOCKET, SO_SNDBUF, &sndbuf, socklen_t(MemoryLayout<Int32>.size))
        }

        func enableNoDelay() {
            fdLock.lock()
            let current = fd
            fdLock.unlock()
            guard current >= 0 else { return }
            var nodelay: Int32 = 1
            _ = setsockopt(current, IPPROTO_TCP, TCP_NODELAY, &nodelay, socklen_t(MemoryLayout<Int32>.size))
        }

        func readSome(maxBytes: Int = 2048, shouldContinue: () -> Bool) -> Data? {
            while shouldContinue() {
                fdLock.lock()
                let current = fd
                fdLock.unlock()
                guard current >= 0 else { return nil }
                var pfd = pollfd(fd: current, events: Int16(POLLIN), revents: 0)
                let ready = Darwin.poll(&pfd, 1, 50)
                if ready < 0 {
                    if errno == EINTR { continue }
                    return nil
                }
                if ready == 0 { continue }
                if (pfd.revents & Int16(POLLNVAL)) != 0 { return nil }
                var buffer = [UInt8](repeating: 0, count: maxBytes)
                let count = Darwin.recv(current, &buffer, buffer.count, 0)
                if count > 0 {
                    return Data(buffer.prefix(count))
                }
                if count == 0 { return nil }
                if errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR { continue }
                return nil
            }
            return nil
        }

        @discardableResult
        func writeAll(_ data: Data, timeout: TimeInterval = 5, shouldContinue: () -> Bool) -> Bool {
            writeLock.lock()
            defer { writeLock.unlock() }
            guard !data.isEmpty else { return true }
            let deadline = ProcessInfo.processInfo.systemUptime + timeout
            var offset = 0
            while offset < data.count && shouldContinue() {
                fdLock.lock()
                let current = fd
                fdLock.unlock()
                guard current >= 0 else { return false }
                let sent = data.withUnsafeBytes { raw in
                    Darwin.send(current, raw.baseAddress!.advanced(by: offset), data.count - offset, 0)
                }
                if sent > 0 {
                    offset += sent
                    continue
                }
                if sent == 0 { return false }
                if errno == EINTR { continue }
                if errno != EAGAIN && errno != EWOULDBLOCK { return false }
                let remaining = deadline - ProcessInfo.processInfo.systemUptime
                guard remaining > 0 else { return false }
                var pfd = pollfd(fd: current, events: Int16(POLLOUT), revents: 0)
                let ready = Darwin.poll(&pfd, 1, Int32(max(1, min(50, remaining * 1000))))
                if ready < 0 && errno != EINTR { return false }
                if (pfd.revents & Int16(POLLERR | POLLHUP | POLLNVAL)) != 0 { return false }
            }
            return offset == data.count
        }

        func close() {
            fdLock.lock()
            let current = fd
            fd = -1
            fdLock.unlock()
            if current >= 0 {
                _ = Darwin.shutdown(current, SHUT_RDWR)
                Darwin.close(current)
            }
        }

        private static func configureCommon(_ fd: Int32) {
            var enabled: Int32 = 1
            _ = setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, socklen_t(MemoryLayout<Int32>.size))
            let flags = fcntl(fd, F_GETFL, 0)
            if flags >= 0 {
                _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
            }
        }
    }

    private struct VideoFrame {
        let data: Data
        let isKeyframe: Bool
    }

    private let configuredControlPort: UInt16
    private let configuredHeartbeatPort: UInt16
    private let configuredVideoPort: UInt16

    private let stateLock = NSLock()
    private var running = false
    private var serverControl: SocketBox?
    private var serverHeartbeat: SocketBox?
    private var serverVideo: SocketBox?
    private var currentControlClient: SocketBox?
    private var currentHeartbeatClient: SocketBox?
    private var currentVideoClient: SocketBox?

    private let queueCondition = NSCondition()
    private var frameQueue: [VideoFrame] = []
    private var awaitingIDR = true
    private var lastCongestionResetTime: TimeInterval = -Double.infinity
    private var totalFramesStreamed: Int64 = 0
    private var totalBytesStreamed: Int64 = 0

    var onState: ((String) -> Void)?
    var onSnapshot: ((SiqiClusterSnapshot) -> Void)?
    var onLog: ((String) -> Void)?
    var onVideoClientConnected: (() -> Void)?
    var onVideoClientDisconnected: (() -> Void)?
    var onNeedKeyframe: (() -> Void)?

    private(set) var boundControlPort: UInt16?
    private(set) var boundHeartbeatPort: UInt16?
    private(set) var boundVideoPort: UInt16?

    init(
        controlPort: UInt16 = SiqiKoveProtocol.controlPort,
        heartbeatPort: UInt16 = SiqiKoveProtocol.heartbeatPort,
        videoPort: UInt16 = SiqiKoveProtocol.videoPort
    ) {
        self.configuredControlPort = controlPort
        self.configuredHeartbeatPort = heartbeatPort
        self.configuredVideoPort = videoPort
    }

    deinit { stop() }

    var isRunning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return running
    }

    var isControlConnected: Bool {
        stateLock.lock()
        let c = currentControlClient
        stateLock.unlock()
        return c?.isOpen == true
    }

    var isVideoActive: Bool {
        stateLock.lock()
        let v = currentVideoClient
        let active = running
        stateLock.unlock()
        return active && v?.isOpen == true
    }

    private func publishStatus(_ summary: String) {
        stateLock.lock()
        let isRun = running
        let ctrl = currentControlClient?.isOpen == true
        let vid = currentVideoClient?.isOpen == true
        let ip = (currentVideoClient?.peerIP.isEmpty == false ? currentVideoClient?.peerIP : nil)
            ?? (currentControlClient?.peerIP.isEmpty == false ? currentControlClient?.peerIP : nil)
        let frames = totalFramesStreamed
        let bytes = totalBytesStreamed
        stateLock.unlock()

        onState?(summary)
        onSnapshot?(SiqiClusterSnapshot(
            isRunning: isRun,
            controlConnected: ctrl,
            videoActive: vid,
            peerIP: ip,
            framesStreamed: frames,
            bytesStreamed: bytes,
            summary: summary
        ))
    }

    func start() throws {
        stateLock.lock()
        if running {
            stateLock.unlock()
            return
        }
        do {
            let sControl = try SocketBox.listen(port: configuredControlPort)
            let sHeartbeat: SocketBox
            do {
                sHeartbeat = try SocketBox.listen(port: configuredHeartbeatPort)
            } catch {
                sControl.close()
                throw error
            }
            let sVideo: SocketBox
            do {
                sVideo = try SocketBox.listen(port: configuredVideoPort)
            } catch {
                sControl.close()
                sHeartbeat.close()
                throw error
            }
            running = true
            serverControl = sControl
            serverHeartbeat = sHeartbeat
            serverVideo = sVideo
            boundControlPort = sControl.boundPort
            boundHeartbeatPort = sHeartbeat.boundPort
            boundVideoPort = sVideo.boundPort
            totalFramesStreamed = 0
            totalBytesStreamed = 0
            stateLock.unlock()

            onLog?("思骑服务已监听 TCP \(boundControlPort ?? configuredControlPort) / \(boundHeartbeatPort ?? configuredHeartbeatPort) / \(boundVideoPort ?? configuredVideoPort)")
            publishStatus("等待凯越 450RR 仪表接入…")

            startAcceptThread(name: "SiqiControlAccept", server: sControl) { [weak self] client in
                self?.handleControlClient(client)
            }
            startAcceptThread(name: "SiqiHeartbeatAccept", server: sHeartbeat) { [weak self] client in
                self?.handleHeartbeatClient(client)
            }
            startAcceptThread(name: "SiqiVideoAccept", server: sVideo) { [weak self] client in
                self?.handleVideoClient(client)
            }
        } catch {
            stateLock.unlock()
            throw error
        }
    }

    func stop() {
        stateLock.lock()
        let wasRunning = running
        running = false
        let sCtrl = serverControl; serverControl = nil
        let sHb = serverHeartbeat; serverHeartbeat = nil
        let sVid = serverVideo; serverVideo = nil
        let cCtrl = currentControlClient; currentControlClient = nil
        let cHb = currentHeartbeatClient; currentHeartbeatClient = nil
        let cVid = currentVideoClient; currentVideoClient = nil
        boundControlPort = nil
        boundHeartbeatPort = nil
        boundVideoPort = nil
        totalFramesStreamed = 0
        totalBytesStreamed = 0
        stateLock.unlock()

        sCtrl?.close()
        sHb?.close()
        sVid?.close()
        cCtrl?.close()
        cHb?.close()
        cVid?.close()

        queueCondition.lock()
        frameQueue.removeAll()
        awaitingIDR = true
        queueCondition.broadcast()
        queueCondition.unlock()

        if wasRunning {
            if cVid != nil {
                onVideoClientDisconnected?()
            }
            publishStatus("思骑服务已停止")
        }
    }

    func forceKeyframe() {
        queueCondition.lock()
        frameQueue.removeAll()
        awaitingIDR = true
        queueCondition.broadcast()
        queueCondition.unlock()
        onNeedKeyframe?()
    }

    func enqueueH264Frame(_ data: Data, isKeyframe: Bool) {
        guard isVideoActive, !data.isEmpty else { return }

        var needCongestionRestart = false
        queueCondition.lock()
        if awaitingIDR {
            guard isKeyframe else {
                queueCondition.unlock()
                return
            }
            frameQueue.removeAll()
            frameQueue.append(VideoFrame(data: data, isKeyframe: true))
            awaitingIDR = false
            queueCondition.signal()
            queueCondition.unlock()
            return
        }

        if isKeyframe {
            frameQueue.removeAll()
            frameQueue.append(VideoFrame(data: data, isKeyframe: true))
            queueCondition.signal()
            queueCondition.unlock()
            return
        }

        if frameQueue.count >= Self.maxQueuedFrames {
            frameQueue.removeAll()
            awaitingIDR = true
            let now = ProcessInfo.processInfo.systemUptime
            if now - lastCongestionResetTime > 1.2 {
                lastCongestionResetTime = now
                needCongestionRestart = true
            }
            queueCondition.unlock()
            if needCongestionRestart {
                onLog?("思骑推流队列拥塞（≥\(Self.maxQueuedFrames) 帧），已丢弃积压并请求关键帧")
                onNeedKeyframe?()
            }
            return
        }

        frameQueue.append(VideoFrame(data: data, isKeyframe: false))
        queueCondition.signal()
        queueCondition.unlock()
    }

    private func startAcceptThread(name: String, server: SocketBox, handler: @escaping (SocketBox) -> Void) {
        let thread = Thread { [weak self] in
            while let self, self.isRunning, server.isOpen {
                guard let client = server.accept(shouldContinue: { [weak self] in
                    (self?.isRunning ?? false) && server.isOpen
                }) else {
                    break
                }
                handler(client)
            }
        }
        thread.name = name
        thread.start()
    }

    private func handleControlClient(_ client: SocketBox) {
        stateLock.lock()
        let prev = currentControlClient
        currentControlClient = client
        stateLock.unlock()
        if prev !== client { prev?.close() }

        onLog?("仪表控制通道已连接（\(client.peerIP)）")
        if !isVideoActive {
            publishStatus("仪表已握手（17818），请在仪表端打开投屏…")
        }

        let thread = Thread { [weak self] in
            guard let self else { client.close(); return }
            let stillActive: () -> Bool = { [weak self] in
                guard let self, self.isRunning, client.isOpen else { return false }
                self.stateLock.lock()
                let match = self.currentControlClient === client
                self.stateLock.unlock()
                return match
            }

            let naviStart = SiqiKoveProtocol.naviStartPacket()
            let initial = SiqiKoveProtocol.tucQueryPacket()
                + SiqiKoveProtocol.deviceTypePacket()
                + SiqiKoveProtocol.linkInfoPacket(nickname: "CarPlay-Bridge")
                + SiqiKoveProtocol.firmwareRequestPacket()
                + naviStart
            guard client.writeAll(initial, shouldContinue: stillActive) else {
                self.cleanupControlClient(client)
                return
            }

            let keepaliveThread = Thread {
                let hb = SiqiKoveProtocol.keepalivePacket()
                let naviData = SiqiKoveProtocol.naviInfoPacket()
                var count = 0
                while stillActive() {
                    var chunk = hb + naviData
                    count += 1
                    if count % 2 == 0 {
                        chunk += naviStart
                    }
                    guard client.writeAll(chunk, shouldContinue: stillActive) else { break }
                    var slept: TimeInterval = 0
                    while slept < 2.0 && stillActive() {
                        Thread.sleep(forTimeInterval: 0.05)
                        slept += 0.05
                    }
                }
            }
            keepaliveThread.name = "SiqiControlKeepalive"
            keepaliveThread.start()

            while stillActive() {
                guard let incoming = client.readSome(maxBytes: 2048, shouldContinue: stillActive) else {
                    break
                }
                if let text = String(data: incoming, encoding: .utf8),
                   text.contains("NAVI") || text.contains("get_") || text.contains("status") {
                    let reply = naviStart + SiqiKoveProtocol.naviInfoPacket()
                    if !client.writeAll(reply, shouldContinue: stillActive) { break }
                }
            }
            self.cleanupControlClient(client)
        }
        thread.name = "SiqiControlClient"
        thread.start()
    }

    private func cleanupControlClient(_ client: SocketBox) {
        client.close()
        stateLock.lock()
        let wasCurrent = currentControlClient === client
        if wasCurrent { currentControlClient = nil }
        let videoActive = currentVideoClient?.isOpen == true
        stateLock.unlock()
        if wasCurrent {
            onLog?("仪表控制通道已断开")
            if !videoActive && isRunning {
                publishStatus("仪表已断开，等待重连…")
            }
        }
    }

    private func handleHeartbeatClient(_ client: SocketBox) {
        client.enableNoDelay()
        stateLock.lock()
        let prev = currentHeartbeatClient
        currentHeartbeatClient = client
        stateLock.unlock()
        if prev !== client { prev?.close() }

        let readerThread = Thread { [weak self] in
            while let self, self.isRunning, client.isOpen {
                if client.readSome(maxBytes: 128, shouldContinue: { [weak self] in
                    (self?.isRunning ?? false) && client.isOpen
                }) == nil {
                    break
                }
            }
            client.close()
        }
        readerThread.name = "SiqiHeartbeatReader"
        readerThread.start()

        let senderThread = Thread { [weak self] in
            let hb = SiqiKoveProtocol.keepalivePacket()
            while let self, self.isRunning, client.isOpen {
                self.stateLock.lock()
                let match = self.currentHeartbeatClient === client
                self.stateLock.unlock()
                guard match else { break }
                guard client.writeAll(hb, shouldContinue: { [weak self] in
                    (self?.isRunning ?? false) && client.isOpen
                }) else { break }
                var slept: TimeInterval = 0
                while slept < 0.45 && self.isRunning && client.isOpen {
                    Thread.sleep(forTimeInterval: 0.05)
                    slept += 0.05
                }
            }
            client.close()
            self?.stateLock.lock()
            if self?.currentHeartbeatClient === client { self?.currentHeartbeatClient = nil }
            self?.stateLock.unlock()
        }
        senderThread.name = "SiqiHeartbeatSender"
        senderThread.start()
    }

    private func handleVideoClient(_ client: SocketBox) {
        client.enableVideoTuning()
        stateLock.lock()
        let prev = currentVideoClient
        currentVideoClient = client
        totalFramesStreamed = 0
        totalBytesStreamed = 0
        stateLock.unlock()
        if prev !== client { prev?.close() }

        queueCondition.lock()
        frameQueue.removeAll()
        awaitingIDR = true
        queueCondition.broadcast()
        queueCondition.unlock()

        let stillActive: () -> Bool = { [weak self] in
            guard let self, self.isRunning, client.isOpen else { return false }
            self.stateLock.lock()
            let match = self.currentVideoClient === client
            self.stateLock.unlock()
            return match
        }

        let header = SiqiKoveProtocol.videoFormatHeader()
        guard client.writeAll(header, shouldContinue: stillActive) else {
            cleanupVideoClient(client)
            return
        }

        onLog?("仪表视频通道已连接（\(client.peerIP)），已发送 69B 头（\(SiqiKoveProtocol.width)×\(SiqiKoveProtocol.height)）")
        publishStatus("仪表视频已连接，等待 CarPlay 关键帧…")
        onVideoClientConnected?()
        onNeedKeyframe?()

        let readerThread = Thread { [weak self] in
            while stillActive() {
                if client.readSome(maxBytes: 64, shouldContinue: stillActive) == nil {
                    break
                }
            }
            self?.cleanupVideoClient(client)
        }
        readerThread.name = "SiqiVideoReader"
        readerThread.start()

        let senderThread = Thread { [weak self] in
            guard let self else { return }
            var firstKeyframeSent = false
            while stillActive() {
                self.queueCondition.lock()
                while self.frameQueue.isEmpty && stillActive() {
                    _ = self.queueCondition.wait(until: Date(timeIntervalSinceNow: 0.2))
                }
                guard stillActive() else {
                    self.queueCondition.unlock()
                    break
                }
                let nextFrame = self.frameQueue.isEmpty ? nil : self.frameQueue.removeFirst()
                self.queueCondition.unlock()

                guard let frame = nextFrame else { continue }
                if !firstKeyframeSent {
                    guard frame.isKeyframe else { continue }
                    firstKeyframeSent = true
                    self.onLog?("已向思骑仪表发出首帧 SPS/PPS+IDR（\(frame.data.count) 字节）")
                }
                guard client.writeAll(frame.data, shouldContinue: stillActive) else {
                    break
                }
                self.stateLock.lock()
                self.totalFramesStreamed += 1
                self.totalBytesStreamed += Int64(frame.data.count)
                let frames = self.totalFramesStreamed
                let bytes = self.totalBytesStreamed
                self.stateLock.unlock()

                if frames == 1 || frames % 30 == 0 {
                    let kb = bytes / 1024
                    self.publishStatus("正在投屏 · \(SiqiKoveProtocol.width)×\(SiqiKoveProtocol.height) · 已推 \(frames) 帧（\(kb) KB）")
                }
            }
            self.cleanupVideoClient(client)
        }
        senderThread.name = "SiqiVideoSender"
        senderThread.qualityOfService = .userInteractive
        senderThread.start()
    }

    private func cleanupVideoClient(_ client: SocketBox) {
        client.close()
        stateLock.lock()
        let wasCurrent = currentVideoClient === client
        if wasCurrent { currentVideoClient = nil }
        let controlAlive = currentControlClient?.isOpen == true
        stateLock.unlock()
        if wasCurrent {
            queueCondition.lock()
            frameQueue.removeAll()
            awaitingIDR = true
            queueCondition.broadcast()
            queueCondition.unlock()

            onLog?("仪表视频通道已断开")
            onVideoClientDisconnected?()
            if isRunning {
                publishStatus(controlAlive ? "仪表视频已暂停，等待重新进入投屏…" : "等待凯越 450RR 仪表接入…")
            }
        }
    }
}
