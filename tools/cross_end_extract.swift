// 跨端互操作验证：用 **iOS 真实的 ZipArchive.swift / CRC32** 去解 Android 产出的备份包。
//
// 这个文件只负责"读 + 断言 + 打印"；它不参与 App 构建，只由
// tools/verify_backup_interop.sh 编译执行。
//
// BackupManifest / BackupImageRef 的定义照抄 JustDiary/Services/BackupService.swift:5-20，
// 目的是顺带验证 **Kotlin 写出的 manifest.json 能被 iOS 的 JSONDecoder 解析**。

import Foundation

struct BackupManifest: Codable {
    var formatVersion: Int
    var appVersion: String
    var exportedAt: Int64
    var includeSettings: Bool
    var diaryCount: Int
    var blockCount: Int
    var imageCount: Int
    var images: [BackupImageRef]
    var settingsJson: String
}

struct BackupImageRef: Codable {
    var src: String
    var file: String
}

/// 与 iOS `AppSettings.swift:15-26` 的 CodingKeys 一致 —— 必须带这层映射，
/// 否则测的就不是"iOS 能不能解码 Android 的 settingsJson"，而是"Swift 能不能解码 camelCase"。
struct AppSettingsProbe: Codable {
    var dayStartHour: Int
    var autoTime: Bool

    enum CodingKeys: String, CodingKey {
        case dayStartHour = "day_start_hour"
        case autoTime = "auto_time"
    }
}

var failures = 0

func check(_ label: String, _ ok: Bool, _ detail: String = "") {
    print("  \(ok ? "PASS" : "FAIL")  \(label)" + (ok || detail.isEmpty ? "" : "  — \(detail)"))
    if !ok { failures += 1 }
}

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    print("用法：cross_end_extract <backup.jdiary>")
    exit(2)
}

let path = arguments[1]
let data = try Data(contentsOf: URL(fileURLWithPath: path))
print("iOS ZipArchive 解析 Android 产出的备份包（\(data.count) 字节）\n")

let entries: [String: Data]
do {
    entries = try ZipArchive.extract(data)
} catch {
    print("  FAIL  ZipArchive.extract 抛错：\(error)")
    exit(1)
}

print("[entry 列表]")
for name in entries.keys.sorted() {
    print("  \(name)  \(entries[name]!.count) 字节")
}

check("包含 manifest.json", entries["manifest.json"] != nil)
check("包含 db/just_diary.db", entries["db/just_diary.db"] != nil)
check("包含 images/ 前缀的图片", entries.keys.contains { $0.hasPrefix("images/") })
check("条目数正好 3", entries.count == 3, "实际 \(entries.count)")

print("\n[manifest.json 能否被 iOS 解码]")
guard let manifestData = entries["manifest.json"] else {
    print("  FAIL  没有 manifest")
    exit(1)
}
do {
    let manifest = try JSONDecoder().decode(BackupManifest.self, from: manifestData)
    check("formatVersion == 1", manifest.formatVersion == 1, "\(manifest.formatVersion)")
    check("diaryCount / blockCount 可读", manifest.diaryCount == 2 && manifest.blockCount == 3,
          "\(manifest.diaryCount)/\(manifest.blockCount)")
    check("imageCount 与 images 数量一致", manifest.imageCount == manifest.images.count)
    check("images[0].src / file 可读", manifest.images.first?.src == "images/img_1.jpg",
          manifest.images.first?.src ?? "nil")
    check("settingsJson 非空", !manifest.settingsJson.isEmpty)
    if let settingsData = manifest.settingsJson.data(using: .utf8) {
        let probe = try? JSONDecoder().decode(AppSettingsProbe.self, from: settingsData)
        check("settingsJson 的键是 snake_case 且可解码", probe?.dayStartHour == 4 && probe?.autoTime == true)
    }
} catch {
    check("manifest.json 解码", false, "\(error)")
}

print("\n[数据库与图片内容]")
if let db = entries["db/just_diary.db"] {
    check("db 头部是 SQLite 魔数", db.count >= 16 && String(data: db.prefix(15), encoding: .utf8) == "SQLite format 3")
}
if let image = entries["images/img_1.jpg"] {
    check("图片长度与写入一致", image.count == 2048, "\(image.count)")
    check("图片内容未被破坏（首字节）", image.first == 0)
}

print()
if failures > 0 {
    print("❌ \(failures) 项失败")
    exit(1)
}
print("✅ Android 写出的备份包可被 iOS 的 ZipArchive 完整读取")
