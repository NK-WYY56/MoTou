import UIKit

/// Visual design system mirroring Android 思骑CarPlay (DiPlayStyle.java):
/// calm blue-slate day/night palette, 20pt rounded cards with 1pt outline,
/// pill status chips, uppercase colored kickers, and crisp rounded buttons.
enum SiqiStyle {
    static let windowBackground = dynamic(
        light: UIColor(red: 233 / 255, green: 238 / 255, blue: 246 / 255, alpha: 1),
        dark: UIColor(red: 12 / 255, green: 17 / 255, blue: 27 / 255, alpha: 1)
    )
    static let surface = dynamic(
        light: UIColor(red: 248 / 255, green: 250 / 255, blue: 253 / 255, alpha: 1),
        dark: UIColor(red: 21 / 255, green: 30 / 255, blue: 44 / 255, alpha: 1)
    )
    static let button = dynamic(
        light: UIColor(red: 220 / 255, green: 229 / 255, blue: 241 / 255, alpha: 1),
        dark: UIColor(red: 31 / 255, green: 43 / 255, blue: 61 / 255, alpha: 1)
    )
    static let outline = dynamic(
        light: UIColor(red: 167 / 255, green: 181 / 255, blue: 199 / 255, alpha: 1),
        dark: UIColor(red: 42 / 255, green: 56 / 255, blue: 75 / 255, alpha: 1)
    )
    static let textPrimary = dynamic(
        light: UIColor(red: 28 / 255, green: 28 / 255, blue: 30 / 255, alpha: 1),
        dark: UIColor(red: 241 / 255, green: 245 / 255, blue: 252 / 255, alpha: 1)
    )
    static let textSecondary = dynamic(
        light: UIColor(red: 90 / 255, green: 100 / 255, blue: 116 / 255, alpha: 1),
        dark: UIColor(red: 168 / 255, green: 182 / 255, blue: 202 / 255, alpha: 1)
    )
    static let accent = dynamic(
        light: UIColor(red: 36 / 255, green: 90 / 255, blue: 156 / 255, alpha: 1),
        dark: UIColor(red: 166 / 255, green: 200 / 255, blue: 255 / 255, alpha: 1)
    )
    static let onAccent = dynamic(
        light: .white,
        dark: UIColor(red: 12 / 255, green: 17 / 255, blue: 27 / 255, alpha: 1)
    )
    static let success = dynamic(
        light: UIColor(red: 40 / 255, green: 107 / 255, blue: 63 / 255, alpha: 1),
        dark: UIColor(red: 127 / 255, green: 205 / 255, blue: 154 / 255, alpha: 1)
    )
    static let warning = dynamic(
        light: UIColor(red: 122 / 255, green: 69 / 255, blue: 0 / 255, alpha: 1),
        dark: UIColor(red: 255 / 255, green: 196 / 255, blue: 128 / 255, alpha: 1)
    )
    static let danger = dynamic(
        light: UIColor(red: 155 / 255, green: 48 / 255, blue: 48 / 255, alpha: 1),
        dark: UIColor(red: 190 / 255, green: 45 / 255, blue: 45 / 255, alpha: 1)
    )

    static func dynamic(light: UIColor, dark: UIColor) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? dark : light }
    }

    static func makeKicker(_ text: String) -> UILabel {
        let label = UILabel()
        label.attributedText = NSAttributedString(
            string: text,
            attributes: [
                .font: UIFont.systemFont(ofSize: 12, weight: .bold),
                .foregroundColor: accent,
                .kern: 0.9
            ]
        )
        label.setContentCompressionResistancePriority(.required, for: .vertical)
        return label
    }

    static func makeSectionTitle(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 14, weight: .bold)
        label.textColor = textPrimary
        label.numberOfLines = 0
        return label
    }

    static func makeSecondaryLabel(_ text: String = "", size: CGFloat = 13) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: size, weight: .regular)
        label.textColor = textSecondary
        label.numberOfLines = 0
        return label
    }

    static func styleSwitch(_ toggle: UISwitch) {
        toggle.onTintColor = accent
    }
}

/// 20pt rounded card with subtle 1pt border matching Android DiPlayStyle.styleCard.
final class SiqiCardView: UIView {
    let stack = UIStackView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = SiqiStyle.surface
        layer.cornerRadius = 20
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = SiqiStyle.outline.resolvedColor(with: traitCollection).cgColor

        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -20),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -20)
        ])

        if #available(iOS 17.0, *) {
            registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (self: Self, _) in
                self.updateBorder()
            }
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if #unavailable(iOS 17.0) { updateBorder() }
    }

    private func updateBorder() {
        layer.borderColor = SiqiStyle.outline.resolvedColor(with: traitCollection).cgColor
    }
}

/// Pill status badge matching Android DiPlayStyle.styleChip.
final class SiqiChipView: UIControl {
    private let label = UILabel()
    private var tint: UIColor = SiqiStyle.textSecondary

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.borderWidth = 1
        label.font = .systemFont(ofSize: 11, weight: .bold)
        label.numberOfLines = 1
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: topAnchor, constant: 5),
            label.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -5),
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10)
        ])
        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)

        if #available(iOS 17.0, *) {
            registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (self: Self, _) in
                self.refreshColors()
            }
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if #unavailable(iOS 17.0) { refreshColors() }
    }

    func apply(text: String, color: UIColor) {
        label.text = text
        tint = color
        refreshColors()
    }

    private func refreshColors() {
        let resolved = tint.resolvedColor(with: traitCollection)
        let dark = traitCollection.userInterfaceStyle == .dark
        backgroundColor = resolved.withAlphaComponent(dark ? 0.17 : 0.13)
        layer.borderColor = resolved.withAlphaComponent(dark ? 0.42 : 0.35).cgColor
        label.textColor = resolved
    }
}

/// Styled rounded button matching Android DiPlayStyle primary / secondary / danger / segmented buttons.
final class SiqiButton: UIButton {
    enum Variant {
        case primary
        case secondary
        case danger
        case segmented(selected: Bool)
    }

    private var variant: Variant = .secondary

    init(title: String, variant: Variant = .secondary, fontSize: CGFloat = 14, height: CGFloat = 44) {
        super.init(frame: .zero)
        setTitle(title, for: .normal)
        titleLabel?.font = .systemFont(ofSize: fontSize, weight: .bold)
        titleLabel?.adjustsFontSizeToFitWidth = true
        titleLabel?.minimumScaleFactor = 0.78
        titleLabel?.lineBreakMode = .byTruncatingTail
        contentEdgeInsets = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        layer.cornerCurve = .continuous
        heightAnchor.constraint(equalToConstant: height).isActive = true
        apply(variant: variant)

        if #available(iOS 17.0, *) {
            registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (self: Self, _) in
                self.refreshColors()
            }
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.alpha = self.isHighlighted ? 0.72 : (self.isEnabled ? 1.0 : 0.5)
            }
        }
    }

    override var isEnabled: Bool {
        didSet {
            alpha = isEnabled ? 1.0 : 0.5
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if #unavailable(iOS 17.0) { refreshColors() }
    }

    func apply(variant: Variant) {
        self.variant = variant
        switch variant {
        case .primary, .secondary, .danger:
            layer.cornerRadius = 14
        case .segmented:
            layer.cornerRadius = 12
        }
        refreshColors()
    }

    private func refreshColors() {
        switch variant {
        case .primary:
            backgroundColor = SiqiStyle.accent
            setTitleColor(SiqiStyle.onAccent, for: .normal)
            layer.borderWidth = 0
            layer.borderColor = UIColor.clear.cgColor
        case .secondary:
            backgroundColor = SiqiStyle.button
            setTitleColor(SiqiStyle.textPrimary, for: .normal)
            layer.borderWidth = 1
            layer.borderColor = SiqiStyle.outline.resolvedColor(with: traitCollection).cgColor
        case .danger:
            backgroundColor = SiqiStyle.danger
            setTitleColor(.white, for: .normal)
            layer.borderWidth = 0
            layer.borderColor = UIColor.clear.cgColor
        case .segmented(let selected):
            if selected {
                backgroundColor = SiqiStyle.accent
                setTitleColor(SiqiStyle.onAccent, for: .normal)
                layer.borderWidth = 0
                layer.borderColor = UIColor.clear.cgColor
            } else {
                backgroundColor = SiqiStyle.button
                setTitleColor(SiqiStyle.textPrimary, for: .normal)
                layer.borderWidth = 1
                layer.borderColor = SiqiStyle.outline.resolvedColor(with: traitCollection).cgColor
            }
        }
    }
}
