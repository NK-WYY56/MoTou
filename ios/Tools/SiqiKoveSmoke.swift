import Darwin
import Foundation

private func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
    if !condition() {
        fputs("FAIL: \(message)\n", stderr)
        exit(1)
    }
}

private func connectLoopback(port: UInt16) -> Int32 {
    let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
    expect(fd >= 0, "socket creation failed")
    var addr = sockaddr_in()
    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = port.bigEndian
    _ = inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr)
    let res = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    expect(res == 0, "connect to 127.0.0.1:\(port) failed: errno=\(errno)")
    return fd
}

private func readExact(_ fd: Int32, count: Int, timeoutMs: Int32 = 3000) -> Data {
    var result = Data()
    let deadline = ProcessInfo.processInfo.systemUptime + Double(timeoutMs) / 1000.0
    while result.count < count {
        let remaining = deadline - ProcessInfo.processInfo.systemUptime
        expect(remaining > 0, "timed out reading \(count) bytes (got \(result.count))")
        var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        let ready = Darwin.poll(&pfd, 1, Int32(max(1, min(100, remaining * 1000))))
        if ready <= 0 { continue }
        var buf = [UInt8](repeating: 0, count: count - result.count)
        let n = Darwin.recv(fd, &buf, buf.count, 0)
        expect(n > 0, "recv failed or EOF while reading \(count) bytes (got \(result.count))")
        result.append(contentsOf: buf.prefix(n))
    }
    return result
}

private func testPacketLayouts() {
    let payload = Data([0x41, 0x42, 0x43])
    let wrapped = SiqiKoveProtocol.wrapFrame(payload)
    expect(wrapped == Data([0xEE, 0xFD, 0, 0, 0, 3, 0x41, 0x42, 0x43, 0xFF]), "wrapFrame mismatch")

    let devType = SiqiKoveProtocol.deviceTypePacket()
    expect(devType == Data([1, 23, 0, 0, 0, 4, 0, 0, 0, 2]), "deviceTypePacket mismatch")

    let link = SiqiKoveProtocol.linkInfoPacket(nickname: "CarPlay-Bridge")
    expect(link.count == 262, "linkInfoPacket length should be 262, got \(link.count)")
    expect(link.prefix(6) == Data([0x01, 0x0C, 0x00, 0x00, 0x01, 0x00]), "linkInfo header mismatch")
    expect(String(data: link.subdata(in: 6..<20), encoding: .utf8) == "CarPlay-Bridge", "linkInfo name mismatch")
    expect(link[20..<262].allSatisfy { $0 == 0 }, "linkInfo padding should be zero")

    expect(SiqiKoveProtocol.firmwareRequestPacket() == Data([0x01, 0x01, 0, 0, 0, 0]), "firmwareRequest mismatch")
    expect(SiqiKoveProtocol.keepalivePacket() == Data([0x02, 0x01, 0, 0, 0, 0]), "keepalive mismatch")

    let naviInfo = SiqiKoveProtocol.naviInfoPacket()
    expect(naviInfo.count == 274, "naviInfo length should be 274, got \(naviInfo.count)")
    expect(naviInfo.prefix(22) == Data([
        3, 1,
        0, 0, 1, 12,        // 268
        0, 0, 0, 1,         // turnIcon = 1
        0, 0, 3, 232,       // turnDist = 1000
        0, 0, 39, 16,       // totalDist = 10000
        0, 0, 7, 8          // 1800
    ]), "naviInfo header mismatch")
    expect(String(data: naviInfo.subdata(in: 22..<35), encoding: .utf8) == "Apple CarPlay", "naviInfo road mismatch")

    let header = SiqiKoveProtocol.videoFormatHeader()
    expect(header.count == 69, "videoFormatHeader length should be 69, got \(header.count)")
    expect(header[0] == 0, "videoFormatHeader[0] must be 0")
    expect(String(data: header.subdata(in: 1..<8), encoding: .utf8) == "android", "videoFormatHeader tag mismatch")
    expect(header[65] == 0x05 && header[66] == 0x00, "videoFormatHeader width 1280 mismatch")
    expect(header[67] == 0x02 && header[68] == 0x80, "videoFormatHeader height 640 mismatch")

    let tuc = SiqiKoveProtocol.tucQueryPacket()
    expect(tuc.prefix(2) == Data([0xEE, 0xFD]) && tuc.last == 0xFF, "tucQueryPacket framing mismatch")
    let naviStart = SiqiKoveProtocol.naviStartPacket()
    expect(String(data: naviStart.subdata(in: 6..<(naviStart.count - 1)), encoding: .utf8)?.contains("inside_naviinfo") == true,
           "naviStartPacket missing inside_naviinfo")

    // Verify Kove 450RR synced Android 16:7 resolution, square-corner viewport (y=78, h=484), and custom profile
    expect(SiqiBikePreset.allCases == [.kove450RR, .custom], "expected kove450RR and custom presets")
    expect(SiqiBikePreset.kove450RR.baseWidth == 2560 && SiqiBikePreset.kove450RR.baseHeight == 1120,
           "kove450RR base canvas must be 2560x1120 (16:7)")
    expect(SiqiBikePreset.kove450RR.viewportRect(for: .transcode) == (78, 484),
           "kove450RR transcode viewport must be top=78, height=484")
    expect(SiqiBikePreset.kove450RR.viewportRect(for: .passthrough) == (0, 640),
           "kove450RR passthrough viewport must be full 640")
    expect(SiqiBikePreset.kove450RR.carPlayCanvasSize(for: .xl, videoMode: .transcode) == (1024, 448),
           "kove450RR XL transcode canvas must be 1024x448")
    expect(SiqiBikePreset.kove450RR.carPlayCanvasSize(for: .large, videoMode: .transcode) == (1280, 560),
           "kove450RR Large transcode canvas must be 1280x560")
    expect(SiqiBikePreset.kove450RR.carPlayCanvasSize(for: .standard, videoMode: .transcode) == (1536, 672),
           "kove450RR Standard transcode canvas must be 1536x672")
    expect(SiqiBikePreset.kove450RR.carPlayCanvasSize(for: .large, videoMode: .passthrough) == (1280, 640),
           "kove450RR passthrough canvas must be 1280x640")
    let transcodeSafe = SiqiBikePreset.kove450RR.airPlaySafeArea(for: .transcode)
    expect(transcodeSafe == (true, 0, 0, true), "kove450RR transcode must use square-corner mode (disableSafeArea=true)")
    let passthroughSafe = SiqiBikePreset.kove450RR.airPlaySafeArea(for: .passthrough)
    expect(passthroughSafe == (false, 78, 78, true), "kove450RR passthrough safeArea must be top=78, bottom=78")
    expect(SiqiIconSizePreset.xl.widthPhysicalMM == 100
           && SiqiIconSizePreset.large.widthPhysicalMM == 100
           && SiqiIconSizePreset.standard.widthPhysicalMM == 200, "icon physical mm mismatch")

    // Verify custom profile sanitization & export report
    let rawCustom = SiqiCustomDisplayProfile(
        bikeName: "  凯越 321RR  ",
        streamWidth: 801, streamHeight: 481,
        carPlayWidth: 799, carPlayHeight: 479,
        viewportTop: 20, viewportHeight: 999,
        widthPhysicalMM: 120, disableSafeAreaSquareCorners: true
    ).sanitized()
    expect(rawCustom.bikeName == "凯越 321RR", "custom bikeName should be trimmed")
    expect(rawCustom.streamWidth == 800 && rawCustom.streamHeight == 480, "custom stream dims must align even")
    expect(rawCustom.carPlayWidth == 798 && rawCustom.carPlayHeight == 478, "custom carPlay dims must align even")
    expect(rawCustom.viewportTop == 20 && rawCustom.viewportHeight == 460, "custom viewport height must clamp inside streamHeight")
    let report = rawCustom.exportReport(videoMode: .transcode)
    expect(report.contains("凯越 321RR") && report.contains("800 × 480") && report.contains("\"streamWidth\" : 800"),
           "export report missing expected fields")

    // Verify custom 800x480 69B video header
    let header800 = SiqiKoveProtocol.videoFormatHeader(width: 800, height: 480)
    expect(header800[65] == 0x03 && header800[66] == 0x20 && header800[67] == 0x01 && header800[68] == 0xE0,
           "800x480 video header bytes mismatch")
}

private func testServerLoopback() throws {
    let server = SiqiKoveServer(controlPort: 0, heartbeatPort: 0, videoPort: 0)
    let lock = NSLock()
    var videoConnectedCount = 0
    var needKeyframeCount = 0
    server.onVideoClientConnected = { lock.withLock { videoConnectedCount += 1 } }
    server.onNeedKeyframe = { lock.withLock { needKeyframeCount += 1 } }

    try server.start()
    guard let ctrlPort = server.boundControlPort,
          let hbPort = server.boundHeartbeatPort,
          let vidPort = server.boundVideoPort else {
        expect(false, "bound ports missing")
        return
    }

    // 1. Control handshake & NAVI query response
    let ctrlFD = connectLoopback(port: ctrlPort)
    let expectedInitial = SiqiKoveProtocol.tucQueryPacket()
        + SiqiKoveProtocol.deviceTypePacket()
        + SiqiKoveProtocol.linkInfoPacket(nickname: "CarPlay-Bridge")
        + SiqiKoveProtocol.firmwareRequestPacket()
        + SiqiKoveProtocol.naviStartPacket()
    let gotInitial = readExact(ctrlFD, count: expectedInitial.count)
    expect(gotInitial == expectedInitial, "control initial handshake mismatch")

    let expectedTick1 = SiqiKoveProtocol.keepalivePacket() + SiqiKoveProtocol.naviInfoPacket()
    let gotTick1 = readExact(ctrlFD, count: expectedTick1.count)
    expect(gotTick1 == expectedTick1, "control keepalive tick 1 mismatch")

    let query = Data("{\"func\":\"NAVI\",\"act\":\"status\"}".utf8)
    _ = query.withUnsafeBytes { Darwin.send(ctrlFD, $0.baseAddress!, query.count, 0) }
    let expectedReply = SiqiKoveProtocol.naviStartPacket() + SiqiKoveProtocol.naviInfoPacket()
    let gotReply = readExact(ctrlFD, count: expectedReply.count)
    expect(gotReply == expectedReply, "control NAVI query reply mismatch")

    // 2. Heartbeat packets
    let hbFD = connectLoopback(port: hbPort)
    let hb1 = readExact(hbFD, count: 6)
    let hb2 = readExact(hbFD, count: 6)
    expect(hb1 == SiqiKoveProtocol.keepalivePacket() && hb2 == SiqiKoveProtocol.keepalivePacket(), "heartbeat mismatch")

    // 3. Video 69B header, pre-IDR P-frame drop, IDR + P-frame delivery
    let vidFD = connectLoopback(port: vidPort)
    let gotHeader = readExact(vidFD, count: 69)
    expect(gotHeader == SiqiKoveProtocol.videoFormatHeader(), "video format header mismatch")
    Thread.sleep(forTimeInterval: 0.05)
    expect(lock.withLock { videoConnectedCount == 1 && needKeyframeCount >= 1 }, "video callbacks did not fire")

    let stalePFrame = Data([0x00, 0x00, 0x00, 0x01, 0x41, 0xAA, 0xBB])
    let idrFrame = Data([0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F,
                         0x00, 0x00, 0x00, 0x01, 0x68, 0xCE, 0x3C, 0x80,
                         0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84])
    let nextPFrame = Data([0x00, 0x00, 0x00, 0x01, 0x41, 0x9A, 0x11, 0x22])

    server.enqueueH264Frame(stalePFrame, isKeyframe: false)
    server.enqueueH264Frame(idrFrame, isKeyframe: true)
    server.enqueueH264Frame(nextPFrame, isKeyframe: false)

    let gotVideo = readExact(vidFD, count: idrFrame.count + nextPFrame.count)
    expect(gotVideo == idrFrame + nextPFrame, "video IDR gate failed (stale P-frame leaked or IDR corrupted)")

    Darwin.close(ctrlFD)
    Darwin.close(hbFD)
    Darwin.close(vidFD)
    server.stop()

    // 4. Verify stop() cleanly frees sockets for immediate re-bind on the exact same ports
    let rebindServer = SiqiKoveServer(controlPort: ctrlPort, heartbeatPort: hbPort, videoPort: vidPort)
    try rebindServer.start()
    expect(rebindServer.isRunning, "rebindServer failed to start on freed ports")
    rebindServer.stop()
}

@main
struct SiqiKoveSmoke {
    static func main() throws {
        testPacketLayouts()
        try testServerLoopback()
        print("SiqiKoveSmoke: ALL TESTS PASSED")
    }
}
