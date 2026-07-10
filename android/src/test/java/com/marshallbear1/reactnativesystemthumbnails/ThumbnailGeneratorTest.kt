package com.marshallbear1.reactnativesystemthumbnails

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class ThumbnailGeneratorTest {
  private lateinit var context: Context
  private lateinit var generator: ThumbnailGenerator
  private lateinit var sourceDirectory: File

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    generator = ThumbnailGenerator(context)
    generator.clearCache()
    sourceDirectory = File(context.filesDir, "thumbnail-generator-tests").apply {
      deleteRecursively()
      check(mkdirs())
    }
  }

  @After
  fun tearDown() {
    generator.clearCache()
    sourceDirectory.deleteRecursively()
  }

  @Test
  fun `local image is resized persisted and reused from cache`() {
    val source = File(sourceDirectory, "landscape.png")
    val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(42, 110, 180))
    }
    FileOutputStream(source).use { output ->
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    bitmap.recycle()

    val request = request(uri = source.absolutePath, width = 100, height = 100)
    val first = generator.generate(request, NativeCancellation())
    val second = generator.generate(request.copy(requestId = "image-2"), NativeCancellation())

    assertEquals(ThumbnailKind.THUMBNAIL, first.kind)
    assertEquals(100, first.width)
    assertEquals(50, first.height)
    assertFalse(first.fromCache)
    val firstOutput = File(Uri.parse(first.uri).path!!)
    assertTrue("Expected ${first.uri} (${firstOutput.absolutePath}) to exist", firstOutput.isFile)
    assertEquals(first.uri, second.uri)
    assertTrue(second.fromCache)
  }

  @Test
  fun `non previewable file produces deterministic icon when allowed`() {
    val source = File(sourceDirectory, "notes.txt").apply {
      writeText("native thumbnail fixture")
    }

    val result = generator.generate(
      request(uri = source.absolutePath, width = 80, height = 60),
      NativeCancellation()
    )

    assertEquals(ThumbnailKind.ICON, result.kind)
    assertEquals(80, result.width)
    assertEquals(60, result.height)
    assertEquals("image/png", result.mimeType)
    val output = File(Uri.parse(result.uri).path!!)
    assertTrue("Expected ${result.uri} (${output.absolutePath}) to exist", output.isFile)
  }

  @Test
  fun `pre-cancelled work fails with stable cancellation code`() {
    val cancellation = NativeCancellation().apply { cancel() }
    val source = File(sourceDirectory, "cancelled.txt").apply { writeText("cancel") }

    val error = try {
      generator.generate(request(source.absolutePath, 32, 32), cancellation)
      throw AssertionError("Expected generation to be cancelled")
    } catch (error: ThumbnailNativeException) {
      error
    }

    assertEquals(ThumbnailErrorCode.CANCELLED, error.code)
  }

  private fun request(uri: String, width: Int, height: Int) = ThumbnailRequest(
    requestId = "image-1",
    uri = uri,
    width = width,
    height = height,
    format = ThumbnailFormat.PNG,
    qualityPercent = 90,
    useCache = true,
    allowIconFallback = true
  )
}
