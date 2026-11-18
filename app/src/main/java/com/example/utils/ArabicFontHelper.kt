package com.example.utils

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.example.R

object ArabicFontHelper {
  @Volatile
  private var cachedRegular: Typeface? = null

  @Volatile
  private var cachedBold: Typeface? = null

  fun getRegular(context: Context?): Typeface {
    cachedRegular?.let { return it }
    if (context == null) return Typeface.DEFAULT
    val loaded = runCatching {
      ResourcesCompat.getFont(context, R.font.amiri)
    }.getOrNull() ?: runCatching {
      Typeface.createFromAsset(context.assets, "www/fonts/amiri.ttf")
    }.getOrNull() ?: Typeface.DEFAULT
    cachedRegular = loaded
    return loaded
  }

  fun getBold(context: Context?): Typeface {
    cachedBold?.let { return it }
    val base = getRegular(context)
    val bold = runCatching {
      Typeface.create(base, Typeface.BOLD)
    }.getOrNull() ?: Typeface.DEFAULT_BOLD
    cachedBold = bold
    return bold
  }
}
