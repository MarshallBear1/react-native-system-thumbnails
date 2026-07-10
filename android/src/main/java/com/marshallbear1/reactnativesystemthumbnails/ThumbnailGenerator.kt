package com.marshallbear1.reactnativesystemthumbnails

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.util.Size
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class ThumbnailErrorCode(val wireValue: String) {
  INVALID_ARGUMENT("INVALID_ARGUMENT"),
  UNSUPPORTED_URI("UNSUPPORTED_URI"),
  NOT_FOUND("NOT_FOUND"),
  PERMISSION_DENIED("PERMISSION_DENIED"),
  CANCELLED("CANCELLED"),
  GENERATION_FAILED("GENERATION_FAILED"),
  CACHE_ERROR("CACHE_ERROR")
}

internal class ThumbnailNativeException(
  val code: ThumbnailErrorCode,
  message: String,
  cause: Throwable? = null
) : RuntimeException(message, cause)

internal object ThumbnailErrors {
  fun cancelled(cause: Throwable? = null) = ThumbnailNativeException(
    ThumbnailErrorCode.CANCELLED,
    "The thumbnail request was cancelled.",
    cause
  )

  fun normalize(error: Throwable, cancellationRequested: Boolean = false): ThumbnailNativeException {
    if (cancellationRequested) {
      return cancelled(error)
    }

    return when (error) {
      is ThumbnailNativeException -> error
      is OperationCanceledException,
      is CancellationException,
      is InterruptedException,
      is InterruptedIOException -> cancelled(error)
      is FileNotFoundException -> ThumbnailNativeException(
        ThumbnailErrorCode.NOT_FOUND,
        "The local source could not be found.",
        error
      )
      is SecurityException -> ThumbnailNativeException(
        ThumbnailErrorCode.PERMISSION_DENIED,
        "The app does not have permission to read the local source.",
        error
      )
      else -> ThumbnailNativeException(
        ThumbnailErrorCode.GENERATION_FAILED,
        error.message?.takeIf(String::isNotBlank) ?: "Android could not generate a thumbnail.",
        error
      )
    }
  }

  fun normalizeCacheError(error: Throwable): ThumbnailNativeException {
    if (error is ThumbnailNativeException && error.code == ThumbnailErrorCode.CACHE_ERROR) {
      return error
    }
    return ThumbnailNativeException(
      ThumbnailErrorCode.CACHE_ERROR,
      error.message?.takeIf(String::isNotBlank) ?: "Android could not update the thumbnail cache.",
      error
    )
  }
}

internal class NativeCancellation {
  private val cancelled = AtomicBoolean(false)
  val signal = CancellationSignal()

  val isCancelled: Boolean
    get() = cancelled.get() || signal.isCanceled

  fun cancel() {
    if (cancelled.compareAndSet(false, true)) {
      signal.cancel()
    }
  }

  fun throwIfCancelled() {
    if (isCancelled || Thread.currentThread().isInterrupted) {
      throw ThumbnailErrors.cancelled()
    }
  }
}

internal enum class ThumbnailFormat(
  val extension: String,
  val mimeType: String,
  val compressFormat: Bitmap.CompressFormat
) {
  PNG("png", "image/png", Bitmap.CompressFormat.PNG),
  JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
}

internal enum class ThumbnailKind(val wireValue: String) {
  THUMBNAIL("thumbnail"),
  ICON("icon")
}

internal data class ThumbnailRequest(
  val requestId: String,
  val uri: String,
  val width: Int,
  val height: Int,
  val format: ThumbnailFormat,
  val qualityPercent: Int,
  val useCache: Boolean,
  val allowIconFallback: Boolean
) {
  companion object {
    private const val MAX_DIMENSION = 4096

    fun fromBridge(
      requestId: String,
      uri: String,
      width: Double,
      height: Double,
      format: String,
      quality: Double,
      useCache: Boolean,
      allowIconFallback: Boolean
    ): ThumbnailRequest {
      if (requestId.isBlank()) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.INVALID_ARGUMENT,
          "requestId must be a non-empty string."
        )
      }
      if (uri.isBlank()) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.INVALID_ARGUMENT,
          "uri must be a non-empty string."
        )
      }
      if (!width.isFinite() || width < 1.0 || width % 1.0 != 0.0 ||
        width > MAX_DIMENSION.toDouble() ||
        !height.isFinite() || height < 1.0 || height % 1.0 != 0.0 ||
        height > MAX_DIMENSION.toDouble()
      ) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.INVALID_ARGUMENT,
          "width and height must be whole numbers from 1 to $MAX_DIMENSION."
        )
      }
      if (!quality.isFinite() || quality < 0.0 || quality > 1.0) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.INVALID_ARGUMENT,
          "quality must be a finite number from 0 through 1."
        )
      }

      val nativeFormat = when (format.lowercase(Locale.ROOT)) {
        "png" -> ThumbnailFormat.PNG
        "jpeg", "jpg" -> ThumbnailFormat.JPEG
        else -> throw ThumbnailNativeException(
          ThumbnailErrorCode.INVALID_ARGUMENT,
          "format must be either png or jpeg."
        )
      }

      return ThumbnailRequest(
        requestId = requestId,
        uri = uri.trim(),
        width = width.toInt(),
        height = height.toInt(),
        format = nativeFormat,
        qualityPercent = (quality * 100.0).roundToInt(),
        useCache = useCache,
        allowIconFallback = allowIconFallback
      )
    }
  }
}

internal data class ThumbnailResult(
  val uri: String,
  val width: Int,
  val height: Int,
  val kind: ThumbnailKind,
  val mimeType: String,
  val fromCache: Boolean
)

internal class ThumbnailGenerator(context: Context) {
  private val context = context.applicationContext
  private val resolver = this.context.contentResolver
  private val cacheDirectory: File by lazy {
    // Context.getCacheDir() may create the app cache directory, so defer it
    // until the generator is already running on the native worker executor.
    File(this.context.cacheDir, CACHE_DIRECTORY_NAME)
  }
  private val cacheLock = Any()

  fun generate(request: ThumbnailRequest, cancellation: NativeCancellation): ThumbnailResult {
    cancellation.throwIfCancelled()
    val source = LocalSource.parse(request.uri)
    val metadata = readMetadata(source, cancellation)
    val cacheKey = ThumbnailUtils.cacheKey(
      CACHE_SCHEMA_VERSION,
      metadata.fingerprint,
      request.width.toString(),
      request.height.toString(),
      request.format.extension,
      if (request.format == ThumbnailFormat.JPEG) request.qualityPercent.toString() else "lossless",
      request.allowIconFallback.toString()
    )

    if (request.useCache) {
      findCached(cacheKey, request, cancellation)?.let { return it }
    }

    val generated = generateBitmap(source, metadata, request, cancellation)
    try {
      cancellation.throwIfCancelled()
      val outputFile = writeAtomically(cacheKey, generated, request, cancellation)
      cancellation.throwIfCancelled()
      return ThumbnailResult(
        uri = Uri.fromFile(outputFile).toString(),
        width = generated.bitmap.width,
        height = generated.bitmap.height,
        kind = generated.kind,
        mimeType = request.format.mimeType,
        fromCache = false
      )
    } finally {
      generated.bitmap.recycle()
    }
  }

  fun clearCache(): Int = synchronized(cacheLock) {
    if (!cacheDirectory.exists()) {
      return@synchronized 0
    }
    if (!cacheDirectory.isDirectory) {
      throw ThumbnailNativeException(
        ThumbnailErrorCode.CACHE_ERROR,
        "The thumbnail cache path is not a directory."
      )
    }

    val children = cacheDirectory.listFiles() ?: throw ThumbnailNativeException(
      ThumbnailErrorCode.CACHE_ERROR,
      "The thumbnail cache directory could not be listed."
    )
    var deletedCount = 0
    children.forEach { child ->
      if (!child.isFile || !child.delete()) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.CACHE_ERROR,
          "Could not delete cached file ${child.name}."
        )
      }
      deletedCount += 1
    }
    deletedCount
  }

  private fun generateBitmap(
    source: LocalSource,
    metadata: SourceMetadata,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): GeneratedBitmap {
    val failures = StrategyFailures()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      attemptBitmap(failures, cancellation) {
        fitOwnedBitmap(
          resolver.loadThumbnail(
            source.uri,
            Size(request.width, request.height),
            cancellation.signal
          ),
          request.width,
          request.height
        )
      }?.let { return GeneratedBitmap(it, ThumbnailKind.THUMBNAIL) }
    }

    val mimeType = metadata.mimeType
    if (mimeType == null || mimeType.startsWith("image/")) {
      attemptBitmap(failures, cancellation) {
        decodeImage(source, request, cancellation)
      }?.let { return GeneratedBitmap(it, ThumbnailKind.THUMBNAIL) }
    }

    val isPdf = mimeType == PDF_MIME_TYPE ||
      (mimeType == null && probePdf(source, failures, cancellation))
    if (isPdf) {
      attemptBitmap(failures, cancellation) {
        renderPdf(source, request, cancellation)
      }?.let { return GeneratedBitmap(it, ThumbnailKind.THUMBNAIL) }
    }

    if (mimeType == null || mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
      attemptBitmap(failures, cancellation) {
        retrieveMediaBitmap(source, mimeType, request, cancellation)
      }?.let { return GeneratedBitmap(it, ThumbnailKind.THUMBNAIL) }
    }

    // A fallback must never make a missing or inaccessible content URI appear valid.
    verifyReadable(source, cancellation)
    cancellation.throwIfCancelled()

    if (request.allowIconFallback) {
      return GeneratedBitmap(
        createFileIcon(metadata.displayName, mimeType, request.width, request.height),
        ThumbnailKind.ICON
      )
    }

    throw ThumbnailNativeException(
      ThumbnailErrorCode.GENERATION_FAILED,
      "No native preview is available for this local source.",
      failures.last
    )
  }

  private inline fun attemptBitmap(
    failures: StrategyFailures,
    cancellation: NativeCancellation,
    operation: () -> Bitmap?
  ): Bitmap? {
    cancellation.throwIfCancelled()
    return try {
      val bitmap = operation() ?: return null
      try {
        cancellation.throwIfCancelled()
        bitmap
      } catch (error: Throwable) {
        bitmap.recycle()
        throw error
      }
    } catch (error: Throwable) {
      when (error) {
        is ThumbnailNativeException -> throw error
        is SecurityException,
        is OperationCanceledException,
        is CancellationException,
        is InterruptedException,
        is InterruptedIOException -> throw ThumbnailErrors.normalize(error)
        else -> {
          // A provider may reject only this strategy (for example, it may not
          // expose a thumbnail or a seekable descriptor). The final readability
          // check distinguishes that from a source which is genuinely absent.
          failures.last = error
          null
        }
      }
    }
  }

  private fun decodeImage(
    source: LocalSource,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): Bitmap? {
    cancellation.throwIfCancelled()
    val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val imageSource = source.file?.let(ImageDecoder::createSource)
        ?: ImageDecoder.createSource(resolver, source.uri)
      ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val fitted = ThumbnailUtils.fitDimensions(
          info.size.width,
          info.size.height,
          request.width,
          request.height
        )
        if (fitted.width != info.size.width || fitted.height != info.size.height) {
          decoder.setTargetSize(fitted.width, fitted.height)
        }
      }
    } else {
      decodeStream(source, request, cancellation)
    }

    return decoded?.let { fitOwnedBitmap(it, request.width, request.height) }
  }

  private fun decodeStream(
    source: LocalSource,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.openInputStream(resolver).use { stream ->
      BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      return null
    }

    cancellation.throwIfCancelled()
    val orientation = readExifOrientation(source)
    val swapsDimensions = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
      orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
      orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
      orientation == ExifInterface.ORIENTATION_ROTATE_270
    val fitted = ThumbnailUtils.fitDimensions(
      if (swapsDimensions) bounds.outHeight else bounds.outWidth,
      if (swapsDimensions) bounds.outWidth else bounds.outHeight,
      request.width,
      request.height
    )
    val decodeTarget = if (swapsDimensions) {
      PixelSize(fitted.height, fitted.width)
    } else {
      fitted
    }
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= decodeTarget.width &&
      bounds.outHeight / (sampleSize * 2) >= decodeTarget.height
    ) {
      sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = source.openInputStream(resolver).use { stream ->
      BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    return applyExifOrientation(decoded, orientation)
  }

  private fun readExifOrientation(source: LocalSource): Int = try {
    source.openInputStream(resolver).use { stream ->
      ExifInterface(stream).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED
      )
    }
  } catch (error: SecurityException) {
    throw error
  } catch (error: FileNotFoundException) {
    throw error
  } catch (_: Exception) {
    // EXIF is optional metadata; decoding the image is still useful without it.
    ExifInterface.ORIENTATION_UNDEFINED
  }

  private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.setRotate(180f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
      else -> return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true
      )
      if (transformed !== bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (error: Throwable) {
      bitmap.recycle()
      throw error
    }
  }

  private fun renderPdf(
    source: LocalSource,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): Bitmap? {
    val descriptor = source.openFileDescriptor(resolver, cancellation.signal)
      ?: throw FileNotFoundException("The PDF source could not be opened.")

    descriptor.use { parcelFileDescriptor ->
      val renderer = PdfRenderer(parcelFileDescriptor)
      try {
        if (renderer.pageCount <= 0) {
          return null
        }
        val page = renderer.openPage(0)
        try {
          cancellation.throwIfCancelled()
          val fitted = ThumbnailUtils.fitDimensions(
            page.width,
            page.height,
            request.width,
            request.height
          )
          val bitmap = Bitmap.createBitmap(fitted.width, fitted.height, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(Color.WHITE)
          try {
            page.render(
              bitmap,
              Rect(0, 0, fitted.width, fitted.height),
              null,
              PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            cancellation.throwIfCancelled()
            return bitmap
          } catch (error: Throwable) {
            bitmap.recycle()
            throw error
          }
        } finally {
          page.close()
        }
      } finally {
        renderer.close()
      }
    }
  }

  private fun retrieveMediaBitmap(
    source: LocalSource,
    mimeType: String?,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): Bitmap? {
    val retriever = MediaMetadataRetriever()
    try {
      source.file?.let { retriever.setDataSource(it.absolutePath) }
        ?: retriever.setDataSource(context, source.uri)
      cancellation.throwIfCancelled()

      var frameFailure: Throwable? = null
      if (mimeType?.startsWith("audio/") != true) {
        val frame = try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val sourceWidth = retriever.extractMetadata(
              MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()
            val sourceHeight = retriever.extractMetadata(
              MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()
            val rotation = retriever.extractMetadata(
              MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val dimensions = if (sourceWidth != null && sourceHeight != null &&
              sourceWidth > 0 && sourceHeight > 0
            ) {
              val rotated = rotation % 180 != 0
              ThumbnailUtils.fitDimensions(
                if (rotated) sourceHeight else sourceWidth,
                if (rotated) sourceWidth else sourceHeight,
                request.width,
                request.height
              )
            } else {
              PixelSize(request.width, request.height)
            }
            retriever.getScaledFrameAtTime(
              -1L,
              MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
              dimensions.width,
              dimensions.height
            )
          } else {
            retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          }
        } catch (error: SecurityException) {
          throw error
        } catch (error: RuntimeException) {
          frameFailure = error
          null
        }

        if (frame != null) {
          return fitOwnedBitmap(frame, request.width, request.height)
        }
      }

      cancellation.throwIfCancelled()
      val artwork = retriever.embeddedPicture
      if (artwork != null) {
        return decodeByteArray(artwork, request.width, request.height)
      }
      frameFailure?.let { throw it }
      return null
    } finally {
      try {
        retriever.release()
      } catch (_: RuntimeException) {
        // The source failure, if any, is more useful than a release failure.
      }
    }
  }

  private fun decodeByteArray(bytes: ByteArray, width: Int, height: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      return null
    }
    val fitted = ThumbnailUtils.fitDimensions(bounds.outWidth, bounds.outHeight, width, height)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= fitted.width &&
      bounds.outHeight / (sampleSize * 2) >= fitted.height
    ) {
      sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    ) ?: return null
    return fitOwnedBitmap(decoded, width, height)
  }

  private fun fitOwnedBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
    val fitted = ThumbnailUtils.fitDimensions(bitmap.width, bitmap.height, width, height)
    if (fitted.width == bitmap.width && fitted.height == bitmap.height) {
      return bitmap
    }

    val scaled = Bitmap.createScaledBitmap(bitmap, fitted.width, fitted.height, true)
    if (scaled !== bitmap) {
      bitmap.recycle()
    }
    return scaled
  }

  private fun probePdf(
    source: LocalSource,
    failures: StrategyFailures,
    cancellation: NativeCancellation
  ): Boolean {
    cancellation.throwIfCancelled()
    return try {
      source.openInputStream(resolver).use { stream ->
        val signature = ByteArray(PDF_SIGNATURE.size)
        var offset = 0
        while (offset < signature.size) {
          val read = stream.read(signature, offset, signature.size - offset)
          if (read < 0) break
          offset += read
        }
        offset == signature.size && signature.contentEquals(PDF_SIGNATURE)
      }
    } catch (error: Throwable) {
      when (error) {
        is SecurityException,
        is OperationCanceledException,
        is InterruptedIOException -> throw ThumbnailErrors.normalize(error)
        else -> {
          failures.last = error
          false
        }
      }
    }
  }

  private fun verifyReadable(source: LocalSource, cancellation: NativeCancellation) {
    cancellation.throwIfCancelled()
    if (source.file != null) {
      source.validateFile()
      return
    }

    val descriptor = resolver.openAssetFileDescriptor(source.uri, "r", cancellation.signal)
      ?: throw FileNotFoundException("The local source could not be opened.")
    descriptor.close()
    cancellation.throwIfCancelled()
  }

  private fun createFileIcon(
    displayName: String?,
    mimeType: String?,
    width: Int,
    height: Int
  ): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val label = ThumbnailUtils.iconLabel(displayName, mimeType)
    val shortestSide = min(width, height).toFloat()
    val pageWidth = min(width * 0.72f, height * 0.58f).coerceAtLeast(1f)
    val pageHeight = min(height * 0.78f, pageWidth * 1.34f).coerceAtLeast(1f)
    val left = (width - pageWidth) / 2f
    val top = (height - pageHeight) / 2f
    val right = left + pageWidth
    val bottom = top + pageHeight
    val fold = min(pageWidth, pageHeight) * 0.23f
    val radius = shortestSide * 0.045f
    val hue = Math.floorMod("${mimeType.orEmpty()}|$label".hashCode(), 360).toFloat()
    val accent = Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.78f))

    val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(245, 247, 250)
      style = Paint.Style.FILL
      setShadowLayer(shortestSide * 0.035f, 0f, shortestSide * 0.018f, 0x33000000)
    }
    canvas.drawRoundRect(left, top, right, bottom, radius, radius, pagePaint)
    pagePaint.clearShadowLayer()

    val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(216, 222, 230)
      style = Paint.Style.FILL
    }
    val foldPath = Path().apply {
      moveTo(right - fold, top)
      lineTo(right, top + fold)
      lineTo(right - fold, top + fold)
      close()
    }
    canvas.drawPath(foldPath, foldPaint)

    val bandTop = top + pageHeight * 0.55f
    val bandBottom = min(bottom - radius, bandTop + pageHeight * 0.24f)
    val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    canvas.drawRect(left, bandTop, right, bandBottom, bandPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = min(pageWidth * 0.22f, (bandBottom - bandTop) * 0.62f).coerceAtLeast(1f)
    }
    val metrics = textPaint.fontMetrics
    val baseline = (bandTop + bandBottom - metrics.ascent - metrics.descent) / 2f
    canvas.drawText(label, (left + right) / 2f, baseline, textPaint)
    return bitmap
  }

  private fun findCached(
    key: String,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): ThumbnailResult? = synchronized(cacheLock) {
    cancellation.throwIfCancelled()
    ensureCacheDirectory()
    val candidates = ThumbnailKind.entries
      .map { kind -> cacheFile(key, kind, request.format) to kind }
      .filter { (file, _) -> file.isFile }
      .sortedByDescending { (file, _) -> file.lastModified() }

    candidates.forEach { (file, kind) ->
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      if (bounds.outWidth > 0 && bounds.outHeight > 0 &&
        bounds.outWidth <= request.width && bounds.outHeight <= request.height
      ) {
        return@synchronized ThumbnailResult(
          uri = Uri.fromFile(file).toString(),
          width = bounds.outWidth,
          height = bounds.outHeight,
          kind = kind,
          mimeType = request.format.mimeType,
          fromCache = true
        )
      }

      if (!file.delete()) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.CACHE_ERROR,
          "A corrupt cached thumbnail could not be removed."
        )
      }
    }
    null
  }

  private fun writeAtomically(
    key: String,
    generated: GeneratedBitmap,
    request: ThumbnailRequest,
    cancellation: NativeCancellation
  ): File = synchronized(cacheLock) {
    cancellation.throwIfCancelled()
    ensureCacheDirectory()
    val target = cacheFile(key, generated.kind, request.format)
    val alternateKind = if (generated.kind == ThumbnailKind.THUMBNAIL) {
      ThumbnailKind.ICON
    } else {
      ThumbnailKind.THUMBNAIL
    }
    val alternate = cacheFile(key, alternateKind, request.format)
    val temporary = try {
      File.createTempFile("$key-", ".tmp", cacheDirectory)
    } catch (error: IOException) {
      throw ThumbnailErrors.normalizeCacheError(error)
    }

    try {
      val compressionBitmap = if (request.format == ThumbnailFormat.JPEG) {
        Bitmap.createBitmap(
          generated.bitmap.width,
          generated.bitmap.height,
          Bitmap.Config.ARGB_8888
        ).also { jpegBitmap ->
          val canvas = Canvas(jpegBitmap)
          canvas.drawColor(Color.WHITE)
          canvas.drawBitmap(generated.bitmap, 0f, 0f, null)
        }
      } else {
        generated.bitmap
      }

      try {
        FileOutputStream(temporary).use { output ->
          val compressed = compressionBitmap.compress(
            request.format.compressFormat,
            if (request.format == ThumbnailFormat.JPEG) request.qualityPercent else 100,
            output
          )
          if (!compressed) {
            throw IOException("Bitmap compression returned no output.")
          }
          output.flush()
          output.fd.sync()
        }
      } finally {
        if (compressionBitmap !== generated.bitmap) {
          compressionBitmap.recycle()
        }
      }

      cancellation.throwIfCancelled()
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          try {
            Files.move(
              temporary.toPath(),
              target.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING
            )
          } catch (_: AtomicMoveNotSupportedException) {
            // Both paths are in the same private cache directory, so rename(2)
            // retains atomic replacement semantics on older filesystems.
            Os.rename(temporary.absolutePath, target.absolutePath)
          }
        } else {
          Os.rename(temporary.absolutePath, target.absolutePath)
        }
      } catch (error: Throwable) {
        throw ThumbnailErrors.normalizeCacheError(error)
      }
      if (alternate.exists() && !alternate.delete()) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.CACHE_ERROR,
          "An obsolete cached thumbnail could not be removed."
        )
      }
      target
    } catch (error: ThumbnailNativeException) {
      throw error
    } catch (error: Throwable) {
      throw ThumbnailErrors.normalizeCacheError(error)
    } finally {
      if (temporary.exists()) {
        temporary.delete()
      }
    }
  }

  private fun ensureCacheDirectory() {
    if (cacheDirectory.isDirectory) {
      return
    }
    if (cacheDirectory.exists() || !cacheDirectory.mkdirs()) {
      throw ThumbnailNativeException(
        ThumbnailErrorCode.CACHE_ERROR,
        "The thumbnail cache directory could not be created."
      )
    }
  }

  private fun cacheFile(key: String, kind: ThumbnailKind, format: ThumbnailFormat): File =
    File(cacheDirectory, "${key}_${kind.wireValue}.${format.extension}")

  private fun readMetadata(
    source: LocalSource,
    cancellation: NativeCancellation
  ): SourceMetadata {
    source.file?.let { file ->
      val mimeType = guessMimeType(file.name)
      return SourceMetadata(
        displayName = file.name,
        mimeType = mimeType,
        fingerprint = listOf(
          "file",
          file.absolutePath,
          file.length().toString(),
          file.lastModified().toString(),
          mimeType.orEmpty()
        ).joinToString("\u0000")
      )
    }

    cancellation.throwIfCancelled()
    val resolverMimeType = try {
      resolver.getType(source.uri)
    } catch (error: SecurityException) {
      throw ThumbnailErrors.normalize(error)
    } catch (_: RuntimeException) {
      null
    }

    var displayName: String? = null
    val identityParts = mutableListOf("resolver", source.uri.normalizeScheme().toString())
    if (source.uri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE) {
      try {
        val packageInfo = context.packageManager.getPackageInfo(source.uri.authority.orEmpty(), 0)
        identityParts += "packageUpdate=${packageInfo.lastUpdateTime}"
        @Suppress("DEPRECATION")
        identityParts += "packageVersion=${packageInfo.versionCode}"
      } catch (_: Exception) {
        // The URI plus app cache lifetime still provides a safe resource fallback.
      }
    }
    if (source.uri.scheme == ContentResolver.SCHEME_CONTENT) {
      try {
        resolver.query(source.uri, null, null, null, null, cancellation.signal)?.use { cursor ->
          if (cursor.moveToFirst()) {
            displayName = cursor.stringValue(OpenableColumns.DISPLAY_NAME)
            METADATA_COLUMNS.forEach { column ->
              cursor.stringValue(column)?.let { value ->
                identityParts += "$column=$value"
              }
            }
          }
        }
      } catch (error: SecurityException) {
        throw ThumbnailErrors.normalize(error)
      } catch (error: OperationCanceledException) {
        throw ThumbnailErrors.normalize(error)
      } catch (_: RuntimeException) {
        // Some providers reject metadata queries but still support opening the URI.
      }
    }

    cancellation.throwIfCancelled()
    val name = displayName ?: source.uri.lastPathSegment
    val mimeType = resolverMimeType ?: guessMimeType(name)
    identityParts += "name=${name.orEmpty()}"
    identityParts += "mime=${mimeType.orEmpty()}"
    return SourceMetadata(
      displayName = name,
      mimeType = mimeType,
      fingerprint = identityParts.joinToString("\u0000")
    )
  }

  private fun android.database.Cursor.stringValue(columnName: String): String? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index) || getType(index) == android.database.Cursor.FIELD_TYPE_BLOB) {
      return null
    }
    return getString(index)
  }

  private fun guessMimeType(name: String?): String? {
    val extension = name
      ?.substringBeforeLast('?')
      ?.substringAfterLast('.', missingDelimiterValue = "")
      ?.lowercase(Locale.ROOT)
      ?.takeIf(String::isNotBlank)
      ?: return null

    return when (extension) {
      "jpg", "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "gif" -> "image/gif"
      "webp" -> "image/webp"
      "heic", "heif" -> "image/heif"
      "pdf" -> PDF_MIME_TYPE
      "mp4", "m4v" -> "video/mp4"
      "mov" -> "video/quicktime"
      "mp3" -> "audio/mpeg"
      "m4a" -> "audio/mp4"
      else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
  }

  private data class GeneratedBitmap(val bitmap: Bitmap, val kind: ThumbnailKind)

  private data class SourceMetadata(
    val displayName: String?,
    val mimeType: String?,
    val fingerprint: String
  )

  private class StrategyFailures {
    var last: Throwable? = null
  }

  private data class LocalSource(val uri: Uri, val file: File?) {
    fun openInputStream(resolver: ContentResolver): InputStream =
      file?.let(::FileInputStream)
        ?: resolver.openInputStream(uri)
        ?: throw FileNotFoundException("The local source could not be opened.")

    fun openFileDescriptor(
      resolver: ContentResolver,
      cancellationSignal: CancellationSignal
    ): ParcelFileDescriptor? = file?.let {
      ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
    } ?: resolver.openFileDescriptor(uri, "r", cancellationSignal)

    fun validateFile() {
      val localFile = file ?: return
      if (!localFile.exists()) {
        throw FileNotFoundException("The local source does not exist.")
      }
      if (!localFile.isFile) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.INVALID_ARGUMENT,
          "The local source must be a file, not a directory."
        )
      }
      if (!localFile.canRead()) {
        throw ThumbnailNativeException(
          ThumbnailErrorCode.PERMISSION_DENIED,
          "The app does not have permission to read the local source."
        )
      }
    }

    companion object {
      fun parse(value: String): LocalSource {
        val parsed = Uri.parse(value)
        val source = when (parsed.scheme?.lowercase(Locale.ROOT)) {
          null -> {
            val path = File(value)
            if (!path.isAbsolute) {
              throw ThumbnailNativeException(
                ThumbnailErrorCode.UNSUPPORTED_URI,
                "Local file paths must be absolute."
              )
            }
            fromFile(path)
          }
          ContentResolver.SCHEME_FILE -> {
            if (!parsed.authority.isNullOrBlank() &&
              !parsed.authority.equals("localhost", ignoreCase = true)
            ) {
              throw ThumbnailNativeException(
                ThumbnailErrorCode.UNSUPPORTED_URI,
                "Network file URIs are not supported."
              )
            }
            val path = parsed.path ?: throw ThumbnailNativeException(
              ThumbnailErrorCode.INVALID_ARGUMENT,
              "The file URI does not contain a path."
            )
            val file = File(path)
            if (!file.isAbsolute) {
              throw ThumbnailNativeException(
                ThumbnailErrorCode.UNSUPPORTED_URI,
                "file:// URIs must contain an absolute path."
              )
            }
            fromFile(file)
          }
          ContentResolver.SCHEME_CONTENT,
          ContentResolver.SCHEME_ANDROID_RESOURCE -> {
            if (parsed.authority.isNullOrBlank()) {
              throw ThumbnailNativeException(
                ThumbnailErrorCode.INVALID_ARGUMENT,
                "The local URI does not contain an authority."
              )
            }
            LocalSource(parsed.normalizeScheme(), null)
          }
          else -> throw ThumbnailNativeException(
            ThumbnailErrorCode.UNSUPPORTED_URI,
            "Only absolute paths, file://, content://, and android.resource:// URIs are supported."
          )
        }
        source.validateFile()
        return source
      }

      private fun fromFile(file: File): LocalSource {
        val canonical = try {
          file.canonicalFile
        } catch (error: IOException) {
          throw ThumbnailNativeException(
            ThumbnailErrorCode.INVALID_ARGUMENT,
            "The local file path could not be resolved.",
            error
          )
        }
        return LocalSource(Uri.fromFile(canonical), canonical)
      }
    }
  }

  companion object {
    private const val CACHE_DIRECTORY_NAME = "react-native-system-thumbnails"
    private const val CACHE_SCHEMA_VERSION = "android-v1"
    private const val PDF_MIME_TYPE = "application/pdf"
    private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val METADATA_COLUMNS = listOf(
      OpenableColumns.DISPLAY_NAME,
      OpenableColumns.SIZE,
      "last_modified",
      "date_modified",
      "document_id",
      "_id"
    )
  }
}
