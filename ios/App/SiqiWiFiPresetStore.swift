import Foundation

/// 仪表 Wi-Fi 预设配置（支持多台车辆/热点独立保存名称、密码、开放网络开关与兼容信道）。
struct SiqiWiFiPreset: Codable, Equatable, Identifiable {
    static let defaultPassword = "12345678"
    static let supportedChannels: [UInt8] = [36, 1, 6, 11, 149]

    let id: UUID
    var name: String
    var ssid: String
    var password: String
    var isOpenNetwork: Bool
    var wifiChannel: UInt8

    init(
        id: UUID = UUID(),
        name: String,
        ssid: String,
        password: String = SiqiWiFiPreset.defaultPassword,
        isOpenNetwork: Bool = false,
        wifiChannel: UInt8 = 36
    ) {
        self.id = id
        self.name = name
        self.ssid = ssid
        self.password = password
        self.isOpenNetwork = isOpenNetwork
        self.wifiChannel = wifiChannel
    }

    var effectivePassword: String {
        if isOpenNetwork { return "" }
        let trimmed = password.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? Self.defaultPassword : trimmed
    }

    var passwordSummary: String {
        if isOpenNetwork { return "开放网络（无密码）" }
        let effective = effectivePassword
        return effective == Self.defaultPassword ? "默认密码 \(Self.defaultPassword)" : "自定义密码（已设置）"
    }

    var sanitized: SiqiWiFiPreset {
        let cleanSSID = ssid.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = cleanName.isEmpty ? (cleanSSID.isEmpty ? "默认仪表热点" : cleanSSID) : cleanName
        let resolvedChannel: UInt8 = Self.supportedChannels.contains(wifiChannel) ? wifiChannel : 36
        let resolvedPassword: String
        if isOpenNetwork {
            resolvedPassword = ""
        } else {
            let trimmed = password.trimmingCharacters(in: .whitespacesAndNewlines)
            resolvedPassword = trimmed.isEmpty ? Self.defaultPassword : trimmed
        }
        return SiqiWiFiPreset(
            id: id,
            name: resolvedName,
            ssid: cleanSSID,
            password: resolvedPassword,
            isOpenNetwork: isOpenNetwork,
            wifiChannel: resolvedChannel
        )
    }
}

/// 持久化管理多组思骑仪表 Wi-Fi 预设，并自动从旧版单一 SSID 平滑迁移。
final class SiqiWiFiPresetStore {
    static let shared = SiqiWiFiPresetStore()

    private let presetsKey = "motouWiFiPresetsV1"
    private let selectedIDKey = "motouSelectedWiFiPresetIDV1"
    private let legacySSIDKey = "siqiClusterWiFiSSID"

    private(set) var presets: [SiqiWiFiPreset] = []
    private(set) var selectedPresetID: UUID?

    private init() {
        reload()
    }

    static func containsSpace(_ text: String) -> Bool {
        text.contains(" ") || text.contains("\u{3000}") || text.contains("\t")
    }

    static func removingSpaces(_ text: String) -> String {
        text.replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\u{3000}", with: "")
            .replacingOccurrences(of: "\t", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var selectedPreset: SiqiWiFiPreset? {
        if let selectedPresetID, let match = presets.first(where: { $0.id == selectedPresetID }) {
            return match
        }
        return presets.first
    }

    var activeSSID: String {
        selectedPreset?.ssid ?? ""
    }

    var activePassword: String {
        selectedPreset?.effectivePassword ?? SiqiWiFiPreset.defaultPassword
    }

    var activeChannel: UInt8 {
        selectedPreset?.wifiChannel ?? 36
    }

    var isOpenNetwork: Bool {
        selectedPreset?.isOpenNetwork ?? false
    }

    func reload() {
        let d = UserDefaults.standard
        if let data = d.data(forKey: presetsKey),
           let decoded = try? JSONDecoder().decode([SiqiWiFiPreset].self, from: data) {
            presets = decoded.map(\.sanitized)
        } else {
            presets = []
        }

        if let rawID = d.string(forKey: selectedIDKey),
           let uuid = UUID(uuidString: rawID),
           presets.contains(where: { $0.id == uuid }) {
            selectedPresetID = uuid
        } else {
            selectedPresetID = presets.first?.id
        }

        // Migrate legacy single SSID if preset list is empty
        if presets.isEmpty {
            let legacy = (d.string(forKey: legacySSIDKey)
                ?? d.string(forKey: "siqiCustomWiFiSSID")
                ?? d.string(forKey: "wifiSSID")
                ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !legacy.isEmpty {
                let migrated = SiqiWiFiPreset(
                    name: "默认仪表热点",
                    ssid: legacy,
                    password: SiqiWiFiPreset.defaultPassword,
                    isOpenNetwork: false,
                    wifiChannel: 36
                )
                presets = [migrated]
                selectedPresetID = migrated.id
                persist()
            }
        }
    }

    func select(id: UUID) {
        guard presets.contains(where: { $0.id == id }) else { return }
        selectedPresetID = id
        persist()
    }

    func upsert(_ preset: SiqiWiFiPreset, selectAfterSave: Bool = true) {
        let clean = preset.sanitized
        if let idx = presets.firstIndex(where: { $0.id == clean.id }) {
            presets[idx] = clean
        } else {
            presets.insert(clean, at: 0)
        }
        if selectAfterSave || selectedPresetID == nil {
            selectedPresetID = clean.id
        }
        persist()
    }

    func delete(id: UUID) {
        presets.removeAll { $0.id == id }
        if selectedPresetID == id {
            selectedPresetID = presets.first?.id
        }
        persist()
    }

    /// 首页直接输入/修改 Wi-Fi 名称时，同步更新当前选中预设或自动创建一条默认预设。
    func syncSSIDFromHomeInput(_ rawSSID: String) {
        let trimmed = rawSSID.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            if let current = selectedPreset {
                var updated = current
                updated.ssid = ""
                upsert(updated, selectAfterSave: true)
            }
            UserDefaults.standard.set("", forKey: legacySSIDKey)
            return
        }

        if var current = selectedPreset {
            current.ssid = trimmed
            upsert(current, selectAfterSave: true)
        } else if let sameSSID = presets.first(where: { $0.ssid == trimmed }) {
            select(id: sameSSID.id)
        } else {
            let created = SiqiWiFiPreset(
                name: trimmed,
                ssid: trimmed,
                password: SiqiWiFiPreset.defaultPassword,
                isOpenNetwork: false,
                wifiChannel: 36
            )
            upsert(created, selectAfterSave: true)
        }
        UserDefaults.standard.set(trimmed, forKey: legacySSIDKey)
    }

    private func persist() {
        let d = UserDefaults.standard
        if let encoded = try? JSONEncoder().encode(presets) {
            d.set(encoded, forKey: presetsKey)
        }
        if let selectedPresetID {
            d.set(selectedPresetID.uuidString, forKey: selectedIDKey)
        } else {
            d.removeObject(forKey: selectedIDKey)
        }
        d.set(activeSSID, forKey: legacySSIDKey)
    }
}
