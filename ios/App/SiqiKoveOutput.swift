import Accelerate
import CoreImage
import Foundation
import VideoToolbox

/// 思骑 / 凯越 450RR 仪表投屏输出：
/// - 兼容转码模式（1:1 防拉伸矫正，同步 Android 实测完美方案）：
///   CPU 软解 CarPlay 16:7 画布（1280×560 / 1024×448 / 1536×672）
///   -> CPU vImage 合成到 1280×640 中的有效视口（默认 y=78..562, h=484，上下 78px 黑边隐藏于仪表上下栏）
///   -> VideoToolbox Baseline 重编码 -> TCP 15456
/// - H.264 直传模式：直接将解密后的 Annex-B H.264 帧（IDR 前补齐 SPS/PPS）推入 TCP 15456
final class SiqiKoveOutput {
    private let queue = DispatchQueue(label: "SiQiCarPlay.siqiKove", qos: .userInitiated)
    private var session: Session?

    deinit { stop() }

    func start(
        videoMode: SiqiVideoMode,
        viewportTop: Int = 78,
        viewportHeight: Int = 484,
        overscanX: Double = 1.0,
        onState: @escaping (String) -> Void,
        onSnapshot: @escaping (SiqiClusterSnapshot) -> Void = { _ in },
        onLog: @escaping (String) -> Void,
        requestKeyframe: @escaping () -> Void
    ) {
        stop()
        let next = Session(
            queue: queue,
            videoMode: videoMode,
            viewportTop: viewportTop,
            viewportHeight: viewportHeight,
            overscanX: overscanX,
            onState: onState,
            onSnapshot: onSnapshot,
            onLog: onLog,
            requestKeyframe: requestKeyframe
        )
        session = next
        next.start()
    }

    func append(_ data: Data, codec: VideoCodec, keyframe: Bool) {
        guard codec == .h264 else { return }
        session?.append(data, keyframe: keyframe)
    }

    func forceKeyframe() {
        session?.forceKeyframe()
    }

    func stop() {
        guard let previous = session else { return }
        session = nil
        previous.cancel()
        queue.async { previous.close() }
    }

    private final class Session: @unchecked Sendable {
        let queue: DispatchQueue
        let videoMode: SiqiVideoMode
        let viewportTop: Int
        let viewportHeight: Int
        let overscanX: Double
        let onState: (String) -> Void
        let onSnapshot: (SiqiClusterSnapshot) -> Void
        let onLog: (String) -> Void
        let requestKeyframe: () -> Void

        private let lock = NSLock()
        private var cancelled = false
        private var acceptingFrames = true
        private var queued = 0
        private var skipping = false
        private var forceNextKeyframe = true
        private var decoded: CVPixelBuffer?
        private var decodeError: Error?
        private var encoded: Result<H264EncodedFrame, Error>?

        private let server = SiqiKoveServer()
        private var decoder: TailingH264Decoder?
        private var encoder: H264Encoder?
        private var pixelBufferPool: CVPixelBufferPool?
        private var parameters = VideoParameterSets(codec: .h264)
        private var passthroughParameters = VideoParameterSets(codec: .h264)
        private var waitingForIDR = true
        private var latest: CVPixelBuffer?
        private var fresh = false
        private lazy var compositor = CIContext(options: [.useSoftwareRenderer: true, .cacheIntermediates: false])
        private var lastKeyframe = -Double.infinity
        private var lastCodecFailure = -Double.infinity

        init(
            queue: DispatchQueue,
            videoMode: SiqiVideoMode,
            viewportTop: Int,
            viewportHeight: Int,
            overscanX: Double,
            onState: @escaping (String) -> Void,
            onSnapshot: @escaping (SiqiClusterSnapshot) -> Void,
            onLog: @escaping (String) -> Void,
            requestKeyframe: @escaping () -> Void
        ) {
            self.queue = queue
            self.videoMode = videoMode
            let clampedTop = max(0, min(viewportTop, SiqiKoveProtocol.height - 64))
            self.viewportTop = clampedTop
            self.viewportHeight = max(64, min(viewportHeight, SiqiKoveProtocol.height - clampedTop))
            self.overscanX = max(1.0, overscanX)
            self.onState = onState
            self.onSnapshot = onSnapshot
            self.onLog = onLog
            self.requestKeyframe = requestKeyframe
        }

        private var active: Bool {
            lock.withLock { !cancelled }
        }

        func start() {
            server.onLog = onLog
            server.onState = { [weak self] message in
                guard let self, self.active else { return }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.active else { return }
                    self.onState(message)
                }
            }
            server.onSnapshot = { [weak self] snapshot in
                guard let self else { return }
                DispatchQueue.main.async { [weak self] in
                    self?.onSnapshot(snapshot)
                }
            }
            server.onNeedKeyframe = { [weak self] in
                guard let self, self.active else { return }
                self.lock.withLock { self.forceNextKeyframe = true }
                self.requestKeyframe()
            }
            server.onVideoClientConnected = { [weak self] in
                guard let self, self.active else { return }
                self.lock.withLock { self.forceNextKeyframe = true }
                self.requestKeyframe()
            }
            server.onVideoClientDisconnected = { [weak self] in
                guard let self, self.active else { return }
                self.queue.async { [weak self] in
                    guard let self else { return }
                    self.encoder?.invalidate()
                    self.encoder = nil
                    self.decoder?.invalidate()
                    self.decoder = nil
                    self.waitingForIDR = true
                    self.latest = nil
                    self.fresh = false
                }
            }

            do {
                try server.start()
                onLog("思骑/凯越 450RR 推流模式：\(videoMode.title) · 输出 \(SiqiKoveProtocol.width)×\(SiqiKoveProtocol.height)（有效视口 y=\(viewportTop), h=\(viewportHeight)）@ 30 FPS")
            } catch {
                onLog("思骑服务启动失败：\(error.localizedDescription)")
                DispatchQueue.main.async { [weak self] in
                    self?.onState("思骑端口启动失败：\(error.localizedDescription)")
                }
                return
            }

            if videoMode == .transcode {
                startTranscodeLoop()
            }
        }

        func forceKeyframe() {
            guard active else { return }
            lock.withLock { forceNextKeyframe = true }
            server.forceKeyframe()
            requestKeyframe()
        }

        func cancel() {
            lock.withLock {
                cancelled = true
                acceptingFrames = false
            }
            server.stop()
        }

        func append(_ data: Data, keyframe: Bool) {
            if videoMode == .passthrough {
                guard lock.withLock({ !cancelled && acceptingFrames }) else { return }
                appendPassthrough(data)
                return
            }

            let (accepted, needsKeyframe) = lock.withLock {
                guard !cancelled, acceptingFrames else { return (false, false) }
                guard server.isVideoActive else { return (false, false) }
                if queued >= 6 {
                    let request = !skipping
                    skipping = true
                    return (false, request)
                }
                guard !skipping || keyframe else { return (false, false) }
                skipping = false
                queued += 1
                return (true, false)
            }
            if needsKeyframe { requestKeyframe() }
            guard accepted else { return }
            queue.async { [self] in
                defer { lock.withLock { queued -= 1 } }
                guard lock.withLock({ !cancelled && acceptingFrames }) else { return }
                decode(data, keyframe: keyframe)
            }
        }

        private func appendPassthrough(_ data: Data) {
            do {
                let units = try AnnexB.units(in: data)
                var picture: [Data] = []
                for unit in units {
                    if VideoCodec.h264.parameterSet(unit) != nil {
                        _ = passthroughParameters.absorb(unit)
                    } else {
                        picture.append(unit)
                    }
                }
                guard server.isVideoActive else { return }
                guard !picture.isEmpty else { return }
                let isKey = picture.contains { VideoCodec.h264.isRandomAccess($0) }
                if isKey && !passthroughParameters.isComplete {
                    requestKeyframe()
                    return
                }
                var annexB = Data()
                let orderedUnits = (isKey ? passthroughParameters.ordered : []) + picture
                for unit in orderedUnits {
                    annexB.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
                    annexB.append(unit)
                }
                server.enqueueH264Frame(annexB, isKeyframe: isKey)
            } catch {
                onLog("思骑直传分帧异常：\(error.localizedDescription)")
            }
        }

        private func startTranscodeLoop() {
            DispatchQueue.global(qos: .userInitiated).async { [self] in
                var nextFrame = ProcessInfo.processInfo.systemUptime
                while active {
                    guard server.isVideoActive else {
                        Thread.sleep(forTimeInterval: 0.05)
                        nextFrame = ProcessInfo.processInfo.systemUptime
                        continue
                    }
                    let remaining = nextFrame - ProcessInfo.processInfo.systemUptime
                    if remaining > 0 {
                        Thread.sleep(forTimeInterval: min(remaining, 0.03))
                        continue
                    }
                    let wantKeyframe = lock.withLock { forceNextKeyframe }
                    let encodedFrame = queue.sync { () -> H264EncodedFrame? in
                        guard active, server.isVideoActive, let image = latest else { return nil }
                        guard fresh || wantKeyframe else { return nil }
                        let frame = encode(
                            image,
                            at: ProcessInfo.processInfo.systemUptime,
                            forceKeyframe: wantKeyframe
                        )
                        if frame != nil {
                            fresh = false
                            if wantKeyframe {
                                lock.withLock { forceNextKeyframe = false }
                            }
                        }
                        return frame
                    }
                    if let encodedFrame {
                        server.enqueueH264Frame(encodedFrame.data, isKeyframe: encodedFrame.isKeyframe)
                        nextFrame = ProcessInfo.processInfo.systemUptime + (1.0 / 30.0)
                    } else {
                        Thread.sleep(forTimeInterval: 0.01)
                    }
                }
            }
        }

        private func decode(_ data: Data, keyframe: Bool) {
            do {
                var picture: [Data] = []
                for unit in try AnnexB.units(in: data) {
                    if VideoCodec.h264.parameterSet(unit) != nil {
                        if parameters.absorb(unit) { waitingForIDR = true }
                    } else {
                        picture.append(unit)
                    }
                }
                if waitingForIDR {
                    guard parameters.isComplete, keyframe, !picture.isEmpty else { return }
                    guard ProcessInfo.processInfo.systemUptime - lastCodecFailure >= 0.5 else {
                        requestKeyframe()
                        return
                    }
                    decoder?.invalidate()
                    decoder = try TailingH264Decoder(
                        configuration: parameters.decoderConfigurationRecord(),
                        output: { [weak self] image in
                            self?.lock.withLock { self?.decoded = image }
                        },
                        failure: { [weak self] error in
                            self?.lock.withLock { self?.decodeError = error }
                        }
                    )
                    waitingForIDR = false
                }
                guard let decoder, !picture.isEmpty else { return }
                try decoder.decode(
                    AnnexB.lengthPrefixed(picture),
                    presentationTimeMicroseconds: Int64(ProcessInfo.processInfo.systemUptime * 1_000_000),
                    isKeyframe: keyframe
                )
                decoder.waitForFrames()
                let (image, error) = lock.withLock {
                    defer { decoded = nil; decodeError = nil }
                    return (decoded, decodeError)
                }
                if let error { throw error }
                if let image {
                    latest = image
                    fresh = true
                }
            } catch {
                decoder?.invalidate()
                decoder = nil
                lock.withLock { decoded = nil; decodeError = nil }
                waitingForIDR = true
                logCodecFailure("解码", error)
                requestKeyframe()
            }
        }

        private func encode(_ image: CVPixelBuffer, at now: Double, forceKeyframe: Bool) -> H264EncodedFrame? {
            do {
                if encoder == nil {
                    guard now - lastCodecFailure >= 0.5 else { return nil }
                    encoder = try H264Encoder(
                        width: SiqiKoveProtocol.width,
                        height: SiqiKoveProtocol.height,
                        fps: 30,
                        bitrate: 2_500_000
                    ) { [weak self] result in
                        self?.lock.withLock { self?.encoded = result }
                    }
                    lastKeyframe = -.infinity
                }
                guard let encoder else { return nil }
                let fitted = try fittedImage(image)
                let stamp = CMTime(seconds: now, preferredTimescale: 1_000_000)
                try encoder.encode(
                    fitted,
                    presentationTime: stamp,
                    forceKeyframe: forceKeyframe || lastKeyframe == -.infinity
                )
                encoder.complete()
                let result = lock.withLock {
                    defer { encoded = nil }
                    return encoded
                }
                let frame = try result?.get()
                if frame?.isKeyframe == true { lastKeyframe = now }
                return frame
            } catch H264EncoderError.frameDropped {
                return nil
            } catch {
                encoder?.invalidate()
                encoder = nil
                lastKeyframe = -.infinity
                lock.withLock { encoded = nil }
                logCodecFailure("编码", error)
                return nil
            }
        }

        private func ensurePixelBufferPool(width: Int, height: Int) throws -> CVPixelBufferPool {
            if let existing = pixelBufferPool { return existing }
            let poolAttrs: [CFString: Any] = [
                kCVPixelBufferPoolMinimumBufferCountKey: 3
            ]
            let bufferAttrs: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey: width,
                kCVPixelBufferHeightKey: height,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
            ]
            var created: CVPixelBufferPool?
            let status = CVPixelBufferPoolCreate(
                kCFAllocatorDefault,
                poolAttrs as CFDictionary,
                bufferAttrs as CFDictionary,
                &created
            )
            guard status == kCVReturnSuccess, let created else {
                throw ConfigurationError("无法创建思骑帧缓冲池（\(status)）")
            }
            pixelBufferPool = created
            return created
        }

        /// 将 CarPlay 16:7 画布（如 1280×560 / 1024×448 / 1536×672）精准合成到
        /// 1280×640 黑色底图的居中有效视口（凯越 450RR：y=78..562, h=484，上下 78px 黑边隐藏于仪表上下状态栏），
        /// 抵消仪表纵横比拉伸实现 1:1 图标比例、四边等距留白与直角无圆角黑边；全程纯 CPU 运算，锁屏/后台稳定运行。
        private func fittedImage(_ image: CVPixelBuffer) throws -> CVPixelBuffer {
            let width = SiqiKoveProtocol.width
            let height = SiqiKoveProtocol.height
            let srcWidth = CVPixelBufferGetWidth(image)
            let srcHeight = CVPixelBufferGetHeight(image)
            let isIdentity = srcWidth == width
                && srcHeight == height
                && viewportTop == 0
                && viewportHeight == height
                && overscanX <= 1.0005
            if isIdentity {
                return image
            }

            let pool = try ensurePixelBufferPool(width: width, height: height)
            var outputBuffer: CVPixelBuffer?
            let status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &outputBuffer)
            guard status == kCVReturnSuccess, let dst = outputBuffer else {
                throw ConfigurationError("无法分配思骑缩放画面（\(status)）")
            }

            if CVPixelBufferGetPixelFormatType(image) == kCVPixelFormatType_32BGRA {
                CVPixelBufferLockBaseAddress(image, .readOnly)
                CVPixelBufferLockBaseAddress(dst, [])
                defer {
                    CVPixelBufferUnlockBaseAddress(dst, [])
                    CVPixelBufferUnlockBaseAddress(image, .readOnly)
                }
                if let srcBase = CVPixelBufferGetBaseAddress(image),
                   let dstBase = CVPixelBufferGetBaseAddress(dst) {
                    let dstRowBytes = CVPixelBufferGetBytesPerRow(dst)
                    let blackBGRA: [UInt8] = [0, 0, 0, 255]

                    // 填充顶部和底部隐藏黑边（避免旧缓冲区残影，且不重复写有效视口区域）
                    if viewportTop > 0 {
                        var topBar = vImage_Buffer(
                            data: dstBase,
                            height: vImagePixelCount(viewportTop),
                            width: vImagePixelCount(width),
                            rowBytes: dstRowBytes
                        )
                        _ = vImageBufferFill_ARGB8888(&topBar, blackBGRA, vImage_Flags(kvImageNoFlags))
                    }
                    let bottomStart = viewportTop + viewportHeight
                    if bottomStart < height {
                        var bottomBar = vImage_Buffer(
                            data: dstBase.advanced(by: bottomStart * dstRowBytes),
                            height: vImagePixelCount(height - bottomStart),
                            width: vImagePixelCount(width),
                            rowBytes: dstRowBytes
                        )
                        _ = vImageBufferFill_ARGB8888(&bottomBar, blackBGRA, vImage_Flags(kvImageNoFlags))
                    }

                    let srcCropWidth = overscanX > 1.0005
                        ? max(2, min(srcWidth, Int((Double(srcWidth) / overscanX).rounded())))
                        : srcWidth
                    let srcCropX = (srcWidth - srcCropWidth) / 2
                    var srcVImg = vImage_Buffer(
                        data: srcBase.advanced(by: srcCropX * 4),
                        height: vImagePixelCount(srcHeight),
                        width: vImagePixelCount(srcCropWidth),
                        rowBytes: CVPixelBufferGetBytesPerRow(image)
                    )
                    var dstViewport = vImage_Buffer(
                        data: dstBase.advanced(by: viewportTop * dstRowBytes),
                        height: vImagePixelCount(viewportHeight),
                        width: vImagePixelCount(width),
                        rowBytes: dstRowBytes
                    )
                    let err = vImageScale_ARGB8888(
                        &srcVImg,
                        &dstViewport,
                        nil,
                        vImage_Flags(kvImageHighQualityResampling)
                    )
                    if err == kvImageNoError {
                        return dst
                    }
                }
            }

            // 非 32BGRA 格式兜底：使用软件 CoreImage 合成到黑色底图的有效视口（注意 CoreImage 原点在左下角）
            let fullBounds = CGRect(x: 0, y: 0, width: width, height: height)
            let source = CIImage(cvPixelBuffer: image)
            let scaledW = CGFloat(width) * CGFloat(overscanX)
            let offsetX = (CGFloat(width) - scaledW) * 0.5
            let ciY = CGFloat(height - viewportTop - viewportHeight)
            let scaleX = scaledW / max(1, source.extent.width)
            let scaleY = CGFloat(viewportHeight) / max(1, source.extent.height)
            let viewportBounds = CGRect(x: 0, y: ciY, width: CGFloat(width), height: CGFloat(viewportHeight))
            let transformed = source
                .transformed(by: CGAffineTransform(translationX: -source.extent.origin.x, y: -source.extent.origin.y))
                .transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
                .transformed(by: CGAffineTransform(translationX: offsetX, y: ciY))
                .cropped(to: viewportBounds)
            let composited = transformed.composited(over: CIImage(color: .black).cropped(to: fullBounds))
            compositor.render(
                composited,
                to: dst,
                bounds: fullBounds,
                colorSpace: CGColorSpaceCreateDeviceRGB()
            )
            return dst
        }

        private func logCodecFailure(_ stage: String, _ error: Error) {
            let now = ProcessInfo.processInfo.systemUptime
            if now - lastCodecFailure > 4 {
                onLog("思骑\(stage)恢复：\(error.localizedDescription)")
            }
            lastCodecFailure = now
        }

        func close() {
            lock.withLock { acceptingFrames = false }
            server.stop()
            decoder?.invalidate()
            decoder = nil
            encoder?.invalidate()
            encoder = nil
            pixelBufferPool = nil
            latest = nil
            fresh = false
        }
    }
}
