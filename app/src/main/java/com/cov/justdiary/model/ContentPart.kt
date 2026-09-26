package com.cov.justdiary.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlin.math.abs
import kotlin.math.floor

/**
 * `edit_block.content_json` 的存储格式。移植自 iOS `JustDiary/Models/ContentPart.swift`。
 *
 * ## 为什么键的顺序写在代码里
 *
 * iOS 用 `JSONEncoder` 的 `.sortedKeys` 输出，因此**键是字母序**、且 `withoutEscapingSlashes`
 * 让 `/` 不转义、非 ASCII 原样输出。kotlinx.serialization 按**属性声明顺序**输出，
 * 所以本文件的属性顺序被刻意写成字母序，才能与 iOS 产出**逐字节一致**的 JSON。
 *
 * 这一点由 `ContentFormatTest` 用**真实 iOS 编码器产出的 golden file**守住
 * （`app/src/test/resources/golden/` 下的 json）。
 *
 * ## v1 兼容
 *
 * v1 是裸数组，块样式写在 `type` 里且用 HTML 名（`h1`/`h2`/`p`/`ul`/`img`），
 * 每个 run 还带 `size`（磅值）。解码时 `type` 由 [JsonNames] 映射到 `style`，
 * `size` 作为未知键被忽略，样式名由 [ContentPartStyle.normalized] 归一，
 * 标志位 `false` 归一为 `null`（见 [ContentFlatten.parseContent]）。
 */
@Serializable
data class TextRun(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val strike: Boolean? = null,
    val text: String,
    val underline: Boolean? = null,
)

/**
 * 一个段落级内容块：带样式的行、列表、待办或图片。
 *
 * 注意 `done` 是**列表元素**，`false` 必须原样保留（与标志位不同）。
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)  // @JsonNames
@Serializable
data class ContentPart(
    val align: String? = null,
    val done: List<Boolean>? = null,
    @Serializable(with = SwiftDoubleSerializer::class) val h: Double? = null,
    val items: List<String>? = null,
    val runs: List<TextRun>? = null,
    val src: String? = null,
    @JsonNames("type") val style: String = ContentPartStyle.body,
    val text: String? = null,
    @Serializable(with = SwiftDoubleSerializer::class) val w: Double? = null,
)

/**
 * `content_json` 的顶层信封：`{"parts":[…],"v":2}`。
 *
 * v1 是裸数组，因此解码时要先试信封、再试数组（见 [ContentFlatten.parseContent]）。
 */
@Serializable
data class ContentDocument(
    val parts: List<ContentPart> = emptyList(),
    val v: Int = CURRENT_VERSION,
) {
    companion object {
        /** 每当 `parts` 的含义变化时递增。 */
        const val CURRENT_VERSION: Int = 2
    }
}

/**
 * 一个存储块可以有的样式。值是**持久化的稳定字符串**（不是枚举序号），
 * 未知值必须能解码而不是抛异常。
 */
object ContentPartStyle {
    const val title = "title"
    const val heading = "heading"
    const val body = "body"
    const val list = "list"
    const val todo = "todo"
    const val quote = "quote"
    const val image = "image"

    /** v1 用 HTML 名拼写样式；保留映射以便旧备份导入后语义不变。 */
    private val legacyNames = mapOf(
        "h1" to title,
        "h2" to heading,
        "p" to body,
        "ul" to list,
        "img" to image,
    )

    fun normalized(raw: String): String = legacyNames[raw] ?: raw
}

/** 图片路径工具，移植自 iOS `ImagePathUtil`。 */
object ImagePathUtil {

    /** `x.jpg` → `images/x.jpg`；已经是 `images/…` 的原样返回。 */
    fun normalizeSrcKey(src: String): String {
        if (src.startsWith(IMAGE_DIR_PREFIX)) return src
        val basename = src.substringAfterLast('/')
        return IMAGE_DIR_PREFIX + basename
    }

    /**
     * 把存储内容重写成当前格式，并把图片路径归一（`"x.jpg"` → `"images/x.jpg"`）。
     *
     * 总是重新编码，因此它同时充当 v1 → v2 的升级入口 —— 与 iOS 的三条调用路径
     * （保存编辑、导入备份、启动时一次性迁移）语义一致。
     *
     * 注意：路径归一**只在写出时**做（`parseContent` 不动 `src`），这与 iOS 一致 ——
     * 所以只解码不重写的路径（例如显示、收集引用）看到的是库里原始的值。
     */
    fun normalizeContent(raw: String): String {
        val normalized = ContentFlatten.parseContent(raw).map { part ->
            part.src?.let { part.copy(src = normalizeSrcKey(it)) } ?: part
        }
        return ContentFlatten.serializeContent(normalized)
    }

    /** 收集内容里引用到的全部图片 src。 */
    fun collectImageSrcs(raw: String): List<String> =
        ContentFlatten.parseContent(raw).mapNotNull { it.src }

    const val IMAGE_DIR_PREFIX: String = "images/"
    const val IMAGE_DIR_NAME: String = "images"
}

/** 扁平化与编解码。 */
object ContentFlatten {

    /**
     * JSON 配置，与 iOS 的 `JSONEncoder`/`JSONDecoder` 行为对齐：
     *  - `encodeDefaults = true` → `v` / `style` 这类带默认值的字段也要写出来；
     *  - `explicitNulls = false` → `null` 字段整体省略（对应 Swift 的可选字段不写）；
     *  - `ignoreUnknownKeys = true` → v1 的 `size` 等未知键被忽略而不是报错。
     */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** 段落的纯文本；`runs` 优先，其次 `text`，再次 `items`。 */
    fun flattenPart(part: ContentPart): String {
        val runs = part.runs
        if (!runs.isNullOrEmpty()) return runs.joinToString(separator = "") { it.text }
        part.text?.let { return it }
        part.items?.let { return it.joinToString(separator = "") }
        return ""
    }

    fun flattenContent(raw: String): String =
        parseContent(raw).joinToString(separator = "") { flattenPart(it) }

    /** 解码 `content_json`，同时接受 v2 信封与 v1 裸数组。 */
    fun parseContent(raw: String): List<ContentPart> {
        if (raw.isEmpty()) return emptyList()

        val fromDocument = runCatching {
            json.decodeFromString(ContentDocument.serializer(), raw)
        }.getOrNull()
        if (fromDocument != null) return fromDocument.parts.map(::normalizeOnRead)

        // v1：裸数组。`type` 键、HTML 样式名与每 run 的寸尺在解码时映射/丢弃。
        val fromArray = runCatching {
            json.decodeFromString(ListSerializer(ContentPart.serializer()), raw)
        }.getOrNull() ?: return emptyList()

        return fromArray.map(::normalizeOnRead)
    }

    /** 用当前格式编码；任何经过这里的路径（编辑、导入、一次性迁移）都会被升级到 v2。 */
    fun serializeContent(parts: List<ContentPart>): String =
        runCatching {
            json.encodeToString(
                ContentDocument.serializer(),
                ContentDocument(parts.map(::normalizeOnWrite)),
            )
        }.getOrElse { "{}" }

    /**
     * 读入侧归一：样式名去 HTML 化；**标志位 `false` 读作「未设置」**（v1 会写 `false`，
     * 与 `null` 同义，`null` 是编码时的规范形式）。
     */
    private fun normalizeOnRead(part: ContentPart): ContentPart = part.copy(
        style = ContentPartStyle.normalized(part.style),
        runs = part.runs?.map { run ->
            run.copy(
                bold = run.bold.onlyTrue(),
                italic = run.italic.onlyTrue(),
                strike = run.strike.onlyTrue(),
                underline = run.underline.onlyTrue(),
            )
        },
    )

    /** 写出侧归一：只写开启的行内样式，`false` 绝不落库。 */
    private fun normalizeOnWrite(part: ContentPart): ContentPart = part.copy(
        style = ContentPartStyle.normalized(part.style),
        runs = part.runs?.map { run ->
            run.copy(
                bold = run.bold.onlyTrue(),
                italic = run.italic.onlyTrue(),
                strike = run.strike.onlyTrue(),
                underline = run.underline.onlyTrue(),
            )
        },
    )

    private fun Boolean?.onlyTrue(): Boolean? = if (this == true) true else null
}

/**
 * `Double` 的序列化：**整数时不输出 `.0`**。
 *
 * Swift 的 `JSONEncoder` 把 `300.0` 写成 `300`，而 kotlinx 默认写 `300.0`；
 * 图片的 `w`/`h` 都是整数值，若不处理，Android 写出的 JSON 与 iOS 就不逐字节一致，
 * 备份文件会出现无意义差异。
 */
@OptIn(ExperimentalSerializationApi::class)
object SwiftDoubleSerializer : KSerializer<Double> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.cov.justdiary.SwiftDouble", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("SwiftDoubleSerializer 只支持 JSON 编码")
        val literal = if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        jsonEncoder.encodeJsonElement(JsonUnquotedLiteral(literal))
    }

    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
}
