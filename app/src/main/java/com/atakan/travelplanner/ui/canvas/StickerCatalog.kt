package com.atakan.travelplanner.ui.canvas

import androidx.annotation.DrawableRes
import com.atakan.travelplanner.R

data class StickerAsset(
    val name: String,
    @DrawableRes val drawableRes: Int
)

val STICKER_CATALOG: List<StickerAsset> = listOf(
    StickerAsset("stamp_1", R.drawable.stamp_1),
    StickerAsset("stamp_2", R.drawable.stamp_2),
    StickerAsset("stamp_3", R.drawable.stamp_3),
    StickerAsset("stamp_4", R.drawable.stamp_4),
    StickerAsset("stamp_5", R.drawable.stamp_5),
    StickerAsset("stamp_6", R.drawable.stamp_6),
    StickerAsset("stamp_7", R.drawable.stamp_7),
    StickerAsset("stamp_8", R.drawable.stamp_8),
    StickerAsset("stamp_9", R.drawable.stamp_9),
    StickerAsset("stump_10", R.drawable.stump_10),
    StickerAsset("maple_leaf", R.drawable.maple_leaf),
    StickerAsset("approve_stamp", R.drawable.approve_stamp)
)

fun drawableResFor(name: String): Int? =
    STICKER_CATALOG.firstOrNull { it.name == name }?.drawableRes
