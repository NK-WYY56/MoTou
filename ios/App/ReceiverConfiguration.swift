import Foundation
import Darwin
import Security

struct ReceiverConfiguration {
    var name = "摩投"
    var manufacturer = "Kove"
    var modelName = "Kove-450RR-CarPlay"
    static let discoveryType = "_tds-carkit._tcp."
    var address: String
    var ssid: String
    var password: String
    var port: UInt16 = 7000
    var width = 1280
    var height = 560
    var maxFPS = 30
    var videoCodec: VideoCodec = .h264
    var wifiChannel: UInt8 = 36
    var audioEnabled = false
    var rightHandDrive = false
    let deviceID: String
    var siqiKoveEnabled = true
    var siqiKoveVideoMode: SiqiVideoMode = .transcode
    /// Output stream resolution written into the 69-byte SiQi header and H.264 encoder.
    var siqiStreamWidth = SiqiKoveProtocol.width
    var siqiStreamHeight = SiqiKoveProtocol.height
    /// When true, declare `cornerMasks = true` and omit `safeArea` so CarPlay draws crisp square corners.
    var disableSafeAreaSquareCorners = true
    var safeAreaTopPixels = 0
    var safeAreaBottomPixels = 0
    var drawUIOutsideSafeArea = true
    /// Active CarPlay viewport inside the 1280×640 cluster output frame (Kove 450RR: y = 78..562, h = 484).
    var viewportTopPixels = 78
    var viewportHeightPixels = 484
    var overscanX: Double = 1.0
    /// Physical width declared to iPhone; smaller values produce larger CarPlay icons.
    var widthPhysicalMM = SiqiIconSizePreset.large.widthPhysicalMM
    /// Square PNG (≤ 192 px) for the car button on the CarPlay home screen; nil hides it.
    var oemIconPNG: Data?
    var oemIconLabel = "KOVE"

    /// CarPlay day/night appearance. Raw values are persisted.
    enum Appearance: Int, CaseIterable {
        case system = 0, light = 1, dark = 2
        var title: String { ["跟随系统", "白天", "夜间"][rawValue] }
    }

    static func wifiIPv4Address() -> String? {
        localIPv4Addresses(interface: "en0").first
    }

    static func localIPv4Addresses(interface: String? = nil) -> [String] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return [] }
        defer { freeifaddrs(first) }
        var result: [String] = []
        var current = first
        while let entry = current {
            defer { current = entry.pointee.ifa_next }
            let name = String(cString: entry.pointee.ifa_name)
            guard interface == nil || interface == name,
                  let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  entry.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                  entry.pointee.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let value = String(cString: host)
                if interface != nil && (value.hasPrefix("169.254.") || value.hasPrefix("0.")) { continue }
                if name == "en0" { result.insert(value, at: 0) }
                else { result.append(value) }
            }
        }
        return result
    }

    static func persistentDeviceID() -> String {
        if let existing = UserDefaults.standard.string(forKey: "receiverDeviceID") { return existing }
        var random = [UInt8](repeating: 0, count: 6)
        _ = SecRandomCopyBytes(kSecRandomDefault, random.count, &random)
        random[0] = (random[0] | 2) & 0xfe
        let value = random.map { String(format: "%02X", $0) }.joined(separator: ":")
        UserDefaults.standard.set(value, forKey: "receiverDeviceID")
        return value
    }

    func validate(requiresLocalAddress: Bool = true, requiresWiFiCredentials: Bool = true) throws {
        var parsed = in_addr()
        guard inet_pton(AF_INET, address, &parsed) == 1 else {
            throw ConfigurationError("未检测到有效的 Wi-Fi IPv4 地址。")
        }
        if requiresLocalAddress && !Self.localIPv4Addresses().contains(address) {
            throw ConfigurationError("当前 IP 地址不属于本机 Wi-Fi，请重新连接仪表 Wi-Fi。")
        }
        guard (320...3840).contains(width), (240...3840).contains(height), width % 2 == 0, height % 2 == 0,
              width * height <= 3840 * 2160,
              (320...1920).contains(siqiStreamWidth), (240...1200).contains(siqiStreamHeight),
              siqiStreamWidth % 2 == 0, siqiStreamHeight % 2 == 0,
              (30...60).contains(maxFPS), maxFPS % 5 == 0, (80...400).contains(widthPhysicalMM) else {
            throw ConfigurationError("画面分辨率或图标参数无效。")
        }
        guard requiresWiFiCredentials, !ssid.isEmpty else { return }
        guard ssid.utf8.count <= 32 else {
            throw ConfigurationError("Wi-Fi 名称不能超过 32 个 UTF-8 字节。")
        }
        guard password.isEmpty || (8...63).contains(password.utf8.count) else {
            throw ConfigurationError("Wi-Fi 密码需为 8–63 位，开放网络请留空。")
        }
    }
}

struct ConfigurationError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
