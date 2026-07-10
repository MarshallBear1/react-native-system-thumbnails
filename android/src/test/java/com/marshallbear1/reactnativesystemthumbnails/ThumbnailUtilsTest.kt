package com.marshallbear1.reactnativesystemthumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbnailUtilsTest {
  @Test
  fun `fitDimensions preserves aspect ratio inside bounds`() {
    assertEquals(
      PixelSize(width = 256, height = 128),
      ThumbnailUtils.fitDimensions(
        sourceWidth = 4000,
        sourceHeight = 2000,
        maximumWidth = 256,
        maximumHeight = 256
      )
    )
    assertEquals(
      PixelSize(width = 80, height = 160),
      ThumbnailUtils.fitDimensions(
        sourceWidth = 1000,
        sourceHeight = 2000,
        maximumWidth = 320,
        maximumHeight = 160
      )
    )
  }

  @Test
  fun `fitDimensions never upscales a source`() {
    assertEquals(
      PixelSize(width = 64, height = 32),
      ThumbnailUtils.fitDimensions(
        sourceWidth = 64,
        sourceHeight = 32,
        maximumWidth = 256,
        maximumHeight = 256
      )
    )
  }

  @Test
  fun `cacheKey is deterministic and component boundaries are significant`() {
    val first = ThumbnailUtils.cacheKey("content://fixture/image", "256", "png")
    val repeated = ThumbnailUtils.cacheKey("content://fixture/image", "256", "png")

    assertEquals(first, repeated)
    assertEquals(64, first.length)
    assertNotEquals(
      ThumbnailUtils.cacheKey("ab", "c"),
      ThumbnailUtils.cacheKey("a", "bc")
    )
  }

  @Test
  fun `iconLabel prefers a short normalized extension`() {
    assertEquals("PDF", ThumbnailUtils.iconLabel("Quarterly.Report.Pdf", "application/pdf"))
    assertEquals("JSON", ThumbnailUtils.iconLabel(null, "application/json"))
    assertEquals("FILE", ThumbnailUtils.iconLabel(null, null))
  }
}
