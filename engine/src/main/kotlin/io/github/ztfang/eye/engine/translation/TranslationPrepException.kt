package io.github.ztfang.eye.engine.translation

/**
 * 离线翻译模型准备失败的分类。
 *
 * 存在的意义：**失败弹窗只需要给用户一句人话，技术细节只对排障有价值**。
 * 早先的实现把两者拼在同一个字符串里（形如"已等待 300s 仍未就绪。无法访问 Google 下载服务器：
 * dl.google.com 被解析到 211.95.34.129，但对方不是 Google（TLS 证书校验失败）……（原始错误：
 * Timed out waiting for 300000 ms）"），弹窗里塞满域名、IP、证书、超时毫秒数，用户看不懂，
 * 真正需要这些信息的开发者又只能在日志里翻。
 *
 * 现在按 [kind] 分开：UI 按分类取一句人话，[TranslationPrepException.detail] 收进"诊断"入口。
 */
enum class TranslationPrepFailureKind {
    /** 设备缺少 Google Play 服务框架（GMS），模型根本没有下载通道。与网络无关。 */
    GMS_UNAVAILABLE,

    /** 网络探测显示 Google 不可达：DNS 被污染，或代理/VPN 的分流规则没覆盖下载域名。 */
    NETWORK_UNREACHABLE,

    /** 网络可达，但下载未在等待窗口内完成（慢速网络或 GMS 限流）。重试通常可恢复。 */
    DOWNLOAD_INCOMPLETE,

    /**
     * 当前语言对不受本地引擎支持。
     * 这类失败**重试永远不会成功**，必须引导用户换引擎，不能和"网络问题"混为一谈。
     */
    UNSUPPORTED_LANGUAGE,

    /** 其它未分类失败（例如参数非法）。 */
    UNKNOWN,
}

/**
 * 离线翻译模型准备失败。
 *
 * @param kind 失败分类，UI 据此选择一句人话
 * @param detail 技术细节（域名 / 解析到的 IP / 证书结果 / 原始异常），
 *        **仅用于日志与"诊断"入口**，不要直接展示在弹窗正文里
 */
class TranslationPrepException(
    val kind: TranslationPrepFailureKind,
    val detail: String,
    cause: Throwable? = null,
) : RuntimeException(detail, cause)
