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
  fun `non previewable file fails when icon fallback is disabled`() {
    val source = File(sourceDirectory, "strict.txt").apply {
      writeText("RNST_UNKNOWN_FIXTURE_V1")
    }

    val error = try {
      generator.generate(
        request(uri = source.absolutePath, width = 80, height = 60)
          .copy(allowIconFallback = false),
        NativeCancellation()
      )
      throw AssertionError("Expected strict thumbnail generation to fail")
    } catch (error: ThumbnailNativeException) {
      error
    }

    assertEquals(ThumbnailErrorCode.GENERATION_FAILED, error.code)
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

  @Test
  fun `bridge request rejects dimensions above maximum`() {
    val error = try {
      request("file:///tmp/too-large.png", 4097, 32)
      throw AssertionError("Expected oversized dimensions to fail")
    } catch (error: ThumbnailNativeException) {
      error
    }

    assertEquals(ThumbnailErrorCode.INVALID_ARGUMENT, error.code)
  }

  @Test
  fun `bridge request rejects quality outside supported range`() {
    val error = try {
      request("file:///tmp/too-lossy.jpg", 32, 32, qualityPercent = 101)
      throw AssertionError("Expected invalid quality to fail")
    } catch (error: ThumbnailNativeException) {
      error
    }

    assertEquals(ThumbnailErrorCode.INVALID_ARGUMENT, error.code)
  }

  private fun request(
    uri: String,
    width: Int,
    height: Int,
    qualityPercent: Int = 90
  ) = ThumbnailRequest.fromBridge(
    requestId = "image-1",
    uri = uri,
    width = width.toDouble(),
    height = height.toDouble(),
    format = "png",
    quality = qualityPercent.toDouble() / 100.0,
    useCache = true,
    allowIconFallback = true
  )
}
