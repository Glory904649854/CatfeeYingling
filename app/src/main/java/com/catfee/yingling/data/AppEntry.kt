package com.catfee.yingling.data

import androidx.compose.ui.graphics.ImageBitmap

data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val icon: ImageBitmap?,
)
