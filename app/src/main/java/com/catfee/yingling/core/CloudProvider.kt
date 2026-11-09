package com.catfee.yingling.core

/**
 * 一个 OpenAI 兼容的云端翻译供应商配置。
 * 每个供应商独立保存 Key，切换时不会覆盖其他供应商。
 */
data class CloudProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val extraJson: String = "",
)
