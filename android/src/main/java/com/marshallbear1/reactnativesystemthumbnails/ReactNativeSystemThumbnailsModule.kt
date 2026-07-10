package com.marshallbear1.reactnativesystemthumbnails

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReactNativeSystemThumbnailsModule(reactContext: ReactApplicationContext) :
  NativeReactNativeSystemThumbnailsSpec(reactContext) {

  private val generator = ThumbnailGenerator(reactContext.applicationContext)
  private val requests = ConcurrentHashMap<String, PendingRequest>()
  private val lifecycleLock = Any()
  private val executor: ExecutorService = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors().coerceIn(MIN_WORKERS, MAX_WORKERS),
    ThumbnailThreadFactory()
  )
  private val invalidated = AtomicBoolean(false)

  override fun generate(
    requestId: String,
    uri: String,
    width: Double,
    height: Double,
    format: String,
    quality: Double,
    useCache: Boolean,
    allowIconFallback: Boolean,
    promise: Promise
  ) {
    val request = try {
      ThumbnailRequest.fromBridge(
        requestId = requestId,
        uri = uri,
        width = width,
        height = height,
        format = format,
        quality = quality,
        useCache = useCache,
        allowIconFallback = allowIconFallback
      )
    } catch (error: Throwable) {
      reject(promise, ThumbnailErrors.normalize(error))
      return
    }

    synchronized(lifecycleLock) {
      if (invalidated.get()) {
        reject(
          promise,
          ThumbnailNativeException(
            ThumbnailErrorCode.GENERATION_FAILED,
            "The native thumbnail module is no longer active."
          )
        )
        return
      }

      val pending = PendingRequest(promise)
      if (requests.putIfAbsent(request.requestId, pending) != null) {
        reject(
          promise,
          ThumbnailNativeException(
            ThumbnailErrorCode.INVALID_ARGUMENT,
            "requestId must be unique while a request is in flight."
          )
        )
        return
      }

      try {
        val future = executor.submit {
          try {
            pending.cancellation.throwIfCancelled()
            val result = generator.generate(request, pending.cancellation)
            pending.cancellation.throwIfCancelled()

            val response = Arguments.createMap().apply {
              putString("uri", result.uri)
              putInt("width", result.width)
              putInt("height", result.height)
              putString("kind", result.kind.wireValue)
              putString("mimeType", result.mimeType)
              putBoolean("fromCache", result.fromCache)
            }
            pending.resolve(response)
          } catch (error: Throwable) {
            pending.reject(ThumbnailErrors.normalize(error, pending.cancellation.isCancelled))
          } finally {
            requests.remove(request.requestId, pending)
          }
        }
        pending.attach(future)
      } catch (error: RejectedExecutionException) {
        requests.remove(request.requestId, pending)
        pending.reject(
          ThumbnailNativeException(
            ThumbnailErrorCode.GENERATION_FAILED,
            "The native thumbnail worker is unavailable.",
            error
          )
        )
      }
    }
  }

  override fun cancel(requestId: String) {
    requests.remove(requestId)?.cancel()
  }

  override fun clearCache(promise: Promise) {
    synchronized(lifecycleLock) {
      if (invalidated.get()) {
        reject(
          promise,
          ThumbnailNativeException(
            ThumbnailErrorCode.CACHE_ERROR,
            "The native thumbnail module is no longer active."
          )
        )
        return
      }

      try {
        executor.execute {
          try {
            promise.resolve(generator.clearCache())
          } catch (error: Throwable) {
            reject(promise, ThumbnailErrors.normalizeCacheError(error))
          }
        }
      } catch (error: RejectedExecutionException) {
        reject(promise, ThumbnailErrors.normalizeCacheError(error))
      }
    }
  }

  override fun invalidate() {
    synchronized(lifecycleLock) {
      if (invalidated.compareAndSet(false, true)) {
        requests.values.forEach(PendingRequest::cancel)
        requests.clear()
        // Pending generation Futures are cancelled above. A queued clearCache
        // call is allowed to drain so its Promise cannot be stranded.
        executor.shutdown()
      }
    }
    super.invalidate()
  }

  private class PendingRequest(private val promise: Promise) {
    val cancellation = NativeCancellation()
    private val settled = AtomicBoolean(false)

    @Volatile
    private var future: Future<*>? = null

    fun attach(value: Future<*>) {
      future = value
      if (cancellation.isCancelled) {
        value.cancel(true)
      }
    }

    fun resolve(value: Any) {
      if (settled.compareAndSet(false, true)) {
        promise.resolve(value)
      }
    }

    fun reject(error: ThumbnailNativeException) {
      if (settled.compareAndSet(false, true)) {
        ReactNativeSystemThumbnailsModule.reject(promise, error)
      }
    }

    fun cancel() {
      cancellation.cancel()
      future?.cancel(true)
      reject(ThumbnailErrors.cancelled())
    }
  }

  private class ThumbnailThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
      Thread(runnable, "system-thumbnail-${sequence.incrementAndGet()}").apply {
        isDaemon = true
      }
  }

  companion object {
    const val NAME = NativeReactNativeSystemThumbnailsSpec.NAME
    private const val MIN_WORKERS = 2
    private const val MAX_WORKERS = 4

    private fun reject(promise: Promise, error: ThumbnailNativeException) {
      promise.reject(error.code.wireValue, error.message, error)
    }
  }
}
