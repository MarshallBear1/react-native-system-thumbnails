package com.marshallbear1.reactnativesystemthumbnails

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PixelSize(val width: Int, val height: Int)

internal object ThumbnailUtils {
  fun fitDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    maximumWidth: Int,
    maximumHeight: Int
  ): PixelSize {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(maximumWidth > 0 && maximumHeight > 0)

    val scale = min(
      1.0,
      min(
        maximumWidth.toDouble() / sourceWidth.toDouble(),
        maximumHeight.toDouble() / sourceHeight.toDouble()
      )
    )

    return PixelSize(
      width = max(1, (sourceWidth * scale).roundToInt()),
      height = max(1, (sourceHeight * scale).roundToInt())
    )
  }

  fun cacheKey(vararg components: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    components.forEach { component ->
      val bytes = component.toByteArray(StandardCharsets.UTF_8)
      digest.update((bytes.size ushr 24).toByte())
      digest.update((bytes.size ushr 16).toByte())
      digest.update((bytes.size ushr 8).toByte())
      digest.update(bytes.size.toByte())
      digest.update(bytes)
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  fun iconLabel(displayName: String?, mimeType: String?): String {
    val name = displayName.orEmpty().substringBeforeLast('?')
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
      .takeIf { it.isNotBlank() && it.length < name.length }
    val mimeSubtype = mimeType
      ?.substringAfter('/', missingDelimiterValue = "")
      ?.substringBefore('+')

    return (extension ?: mimeSubtype ?: "FILE")
      .filter(Char::isLetterOrDigit)
      .uppercase(Locale.ROOT)
      .take(4)
      .ifEmpty { "FILE" }
  }
}
