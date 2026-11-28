package top.wkbin.zaomeng.ktor.services

/** 模型调用失败的可分类原因，供上层路由给出用户可读的提示。 */
enum class ModelRequestFailure {
    /** 请求或 socket 超时。 */
    Timeout,

    /** 无法建立连接或连接中断（网络、接口地址错误）。 */
    Network,

    /** 服务商返回 408/429/5xx，重试后仍失败。 */
    ProviderUnavailable,

    /** 服务端返回 200，但响应体不是预期的 JSON 结构。 */
    InvalidResponse,
}

/**
 * 模型请求的分类异常。
 *
 * 只携带分类与可读提示，不携带提示词、正文或响应原文，避免进入日志与诊断记录。
 */
class ModelRequestException(
    val failure: ModelRequestFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
