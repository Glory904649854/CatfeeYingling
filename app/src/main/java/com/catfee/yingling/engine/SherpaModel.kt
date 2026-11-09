package com.catfee.yingling.engine

import java.io.File

data class SherpaModel(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
)
