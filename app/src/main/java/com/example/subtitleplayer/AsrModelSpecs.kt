package com.example.subtitleplayer

/**
 * 识别（ASR）模型规格（3.1：从「只有日语」扩成多模型）。
 *
 * 为什么需要它：3.1 之前模型是**写死**的 —— 目录名、文件名、镜像源、
 * `modelingUnit` 全是常量，只能跑那一个日语模型。要加韩语就必须抽象，
 * 否则会出现「日语和韩语的主文件名完全相同（都叫 encoder-epoch-99-avg-1.int8.onnx）
 * 所以互相覆盖」这种事故 —— 这也是每个模型必须**独立目录**的原因。
 *
 * 语言与模型的对应关系（不是二选一，是可以共存的）：
 * - 日语：`zipformer-ja-reazonspeech`，ReazonSpeech 3.5 万小时日语专训，约 165 MB
 * - 韩语：`zipformer-korean-2024-06-24`，KsponSpeech 广播语料专训，约 93 MB
 *
 * 两者都是**同一个 zipformer/transducer 架构**，所以：
 * - 加载用同一个 [androidx.media3] 之外的 `OfflineTransducerModelConfig`
 * - 台本热词（modified_beam_search）两边都能用
 * - `r.timestamps` 逐字时间戳机制一致，lrc 生成逻辑零改动
 *
 * 唯一的实质差异是 `modelingUnit`（日语按字切 `cjkchar`、韩语按子词 `bpe`），
 * 这个字段必须跟着模型走，不能写死。
 */
object AsrModelSpecs {

    /** 一个模型文件角色：可有多个候选文件名（不同镜像仓库命名不一）。 */
    data class ModelFile(val role: String, val candidates: List<String>)

    /**
     * 模型规格。
     *
     * @param id 用于目录名与 prefs 存储（**只用 ASCII**，不进 UI）
     * @param displayName 设置页显示名
     * @param sizeMb 大致下载体积，用于提示文案
     * @param files 需要下载的文件角色
     * @param sources 镜像源（按序尝试），必须以 `/` 结尾
     * @param modelingUnit 符号表切分单位：`cjkchar`（按字）/ `bpe`（按子词）
     * @param needsBpeModel 是否还需要 `bpe.model`（bpe 切分必需，cjkchar 不需要）
     */
    data class Spec(
        val id: String,
        val displayName: String,
        val sizeMb: Int,
        val files: List<ModelFile>,
        val sources: List<String>,
        val modelingUnit: String,
        val needsBpeModel: Boolean = false
    )

    /** 默认模型 id（老用户升级上来仍走日语，体验不变）。 */
    const val DEFAULT_ID = "ja"

    private val JA_FILES = listOf(
        ModelFile("encoder", listOf("encoder-epoch-99-avg-1.int8.onnx")),
        ModelFile("decoder", listOf("decoder-epoch-99-avg-1.onnx")),
        // 镜像仓库 joiner 只有 fp32 版：两个候选都试，任一下载成功即可
        ModelFile("joiner", listOf("joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx")),
        ModelFile("tokens", listOf("tokens.txt"))
    )

    private val JA_SOURCES = listOf(
        "https://hf-mirror.com/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://hf-mirror.com/k2-fsa/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/",
        "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main/"
    )

    /**
     * 韩语模型的文件清单（3.1 实测自 HF 文件列表）。
     *
     * ⚠️ 与日语**不一样的两处**，写错会 404：
     * 1. `decoder` / `joiner` 只有 fp32 版（没有 int8）；
     * 2. 多一个 `bpe.model` —— 韩语模型用 bpe 子词切分，没有它加载会失败。
     */
    private val KO_FILES = listOf(
        ModelFile("encoder", listOf("encoder-epoch-99-avg-1.int8.onnx")),
        ModelFile("decoder", listOf("decoder-epoch-99-avg-1.onnx")),
        ModelFile("joiner", listOf("joiner-epoch-99-avg-1.onnx")),
        ModelFile("tokens", listOf("tokens.txt")),
        ModelFile("bpe", listOf("bpe.model"))
    )

    private val KO_SOURCES = listOf(
        "https://hf-mirror.com/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24/resolve/main/",
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24/resolve/main/"
    )

    val JA = Spec(
        id = "ja",
        displayName = "日语",
        sizeMb = 165,
        files = JA_FILES,
        sources = JA_SOURCES,
        modelingUnit = "cjkchar"
    )

    val KO = Spec(
        id = "ko",
        displayName = "韩语",
        sizeMb = 93,
        files = KO_FILES,
        sources = KO_SOURCES,
        modelingUnit = "bpe",
        needsBpeModel = true
    )

    val ALL: List<Spec> = listOf(JA, KO)

    fun byId(id: String?): Spec = ALL.firstOrNull { it.id == id } ?: JA
}
