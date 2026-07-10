import NativeSystemThumbnails from './NativeReactNativeSystemThumbnails';

export type ThumbnailFormat = 'png' | 'jpeg';
export type ThumbnailFallback = 'icon' | 'error';
export type ThumbnailKind = 'thumbnail' | 'icon';

export type ThumbnailErrorCode =
  | 'INVALID_ARGUMENT'
  | 'UNSUPPORTED_URI'
  | 'NOT_FOUND'
  | 'PERMISSION_DENIED'
  | 'CANCELLED'
  | 'GENERATION_FAILED'
  | 'CACHE_ERROR';

export type ThumbnailSize = Readonly<{
  width: number;
  height: number;
}>;

export type GenerateThumbnailOptions = Readonly<{
  /** A local absolute path, file:// URI, or Android content:// URI. */
  uri: string;
  /** Maximum thumbnail bounds in pixels. Defaults to 256 × 256. */
  size?: ThumbnailSize;
  /** Output encoding. Defaults to png. */
  format?: ThumbnailFormat;
  /** JPEG quality from 0 through 1. Ignored for PNG. Defaults to 0.9. */
  quality?: number;
  /** Reuse an existing thumbnail when the source has not changed. */
  cache?: boolean;
  /** Return a file-type icon when no preview is available. Defaults to icon. */
  fallback?: ThumbnailFallback;
  /** Cancels in-flight native work when aborted. */
  signal?: AbortSignal;
}>;

export type ThumbnailResult = Readonly<{
  uri: string;
  width: number;
  height: number;
  kind: ThumbnailKind;
  mimeType: 'image/png' | 'image/jpeg';
  fromCache: boolean;
}>;

const ERROR_CODES = new Set<ThumbnailErrorCode>([
  'INVALID_ARGUMENT',
  'UNSUPPORTED_URI',
  'NOT_FOUND',
  'PERMISSION_DENIED',
  'CANCELLED',
  'GENERATION_FAILED',
  'CACHE_ERROR',
]);

const DEFAULT_SIZE = 256;
const MAX_SIZE = 4096;
let requestSequence = 0;

export class ThumbnailError extends Error {
  readonly code: ThumbnailErrorCode;
  readonly cause?: unknown;

  constructor(code: ThumbnailErrorCode, message: string, cause?: unknown) {
    super(message);
    this.name = 'ThumbnailError';
    this.code = code;
    this.cause = cause;
  }
}

function invalidArgument(message: string): ThumbnailError {
  return new ThumbnailError('INVALID_ARGUMENT', message);
}

function validateDimension(value: number, label: string): number {
  if (!Number.isInteger(value) || value < 1 || value > MAX_SIZE) {
    throw invalidArgument(`${label} must be an integer from 1 to ${MAX_SIZE}.`);
  }

  return value;
}

function validateSignal(value: unknown): AbortSignal | undefined {
  if (value === undefined) {
    return undefined;
  }

  if (
    typeof value !== 'object' ||
    value === null ||
    typeof (value as AbortSignal).aborted !== 'boolean' ||
    typeof (value as AbortSignal).addEventListener !== 'function' ||
    typeof (value as AbortSignal).removeEventListener !== 'function'
  ) {
    throw invalidArgument('signal must be an AbortSignal.');
  }

  return value as AbortSignal;
}

function nativeErrorCode(value: unknown): ThumbnailErrorCode | undefined {
  if (typeof value !== 'string') {
    return undefined;
  }

  const normalized = value.startsWith('E_') ? value.slice(2) : value;
  return ERROR_CODES.has(normalized as ThumbnailErrorCode)
    ? (normalized as ThumbnailErrorCode)
    : undefined;
}

function toThumbnailError(
  error: unknown,
  fallbackCode: ThumbnailErrorCode = 'GENERATION_FAILED'
): ThumbnailError {
  if (error instanceof ThumbnailError) {
    return error;
  }

  if (typeof error === 'object' && error !== null) {
    const candidate = error as { code?: unknown; message?: unknown };
    const code = nativeErrorCode(candidate.code) ?? fallbackCode;
    const message =
      typeof candidate.message === 'string'
        ? candidate.message
        : 'The native thumbnail request failed.';
    return new ThumbnailError(code, message, error);
  }

  return new ThumbnailError(fallbackCode, String(error), error);
}

function nextRequestId(): string {
  requestSequence = (requestSequence + 1) % Number.MAX_SAFE_INTEGER;
  return `${Date.now().toString(36)}-${requestSequence.toString(36)}`;
}

/**
 * Generates a system-native thumbnail for local content.
 *
 * This function never downloads remote URLs. The returned file lives in the
 * app's cache directory and can be passed directly to React Native's Image.
 */
export async function generateThumbnail(
  options: GenerateThumbnailOptions
): Promise<ThumbnailResult> {
  if (!options || typeof options !== 'object') {
    throw invalidArgument('options must be an object.');
  }

  if (typeof options.uri !== 'string' || options.uri.trim().length === 0) {
    throw invalidArgument('uri must be a non-empty string.');
  }

  if (/^https?:\/\//i.test(options.uri)) {
    throw new ThumbnailError(
      'UNSUPPORTED_URI',
      'Remote URLs are not supported. Download the file before generating a thumbnail.'
    );
  }

  if (
    options.size !== undefined &&
    (typeof options.size !== 'object' ||
      options.size === null ||
      Array.isArray(options.size))
  ) {
    throw invalidArgument('size must contain numeric width and height fields.');
  }

  const width =
    options.size === undefined
      ? DEFAULT_SIZE
      : validateDimension(options.size.width, 'size.width');
  const height =
    options.size === undefined
      ? DEFAULT_SIZE
      : validateDimension(options.size.height, 'size.height');
  const format = options.format === undefined ? 'png' : options.format;
  const quality = options.quality === undefined ? 0.9 : options.quality;
  const useCache = options.cache === undefined ? true : options.cache;
  const fallback = options.fallback === undefined ? 'icon' : options.fallback;
  const signal = validateSignal(options.signal);

  if (format !== 'png' && format !== 'jpeg') {
    throw invalidArgument('format must be either "png" or "jpeg".');
  }

  if (!Number.isFinite(quality) || quality < 0 || quality > 1) {
    throw invalidArgument('quality must be a finite number from 0 through 1.');
  }

  if (fallback !== 'icon' && fallback !== 'error') {
    throw invalidArgument('fallback must be either "icon" or "error".');
  }

  if (typeof useCache !== 'boolean') {
    throw invalidArgument('cache must be a boolean.');
  }

  if (signal?.aborted) {
    throw new ThumbnailError(
      'CANCELLED',
      'The thumbnail request was cancelled.'
    );
  }

  const requestId = nextRequestId();
  const abort = () => {
    try {
      NativeSystemThumbnails.cancel(requestId);
    } catch {
      // Cancellation is best-effort; the in-flight promise still owns errors.
    }
  };
  signal?.addEventListener('abort', abort, { once: true });

  try {
    const result = await NativeSystemThumbnails.generate(
      requestId,
      options.uri,
      width,
      height,
      format,
      quality,
      useCache,
      fallback === 'icon'
    );

    if (signal?.aborted) {
      throw new ThumbnailError(
        'CANCELLED',
        'The thumbnail request was cancelled.'
      );
    }

    if (result.kind !== 'thumbnail' && result.kind !== 'icon') {
      throw new ThumbnailError(
        'GENERATION_FAILED',
        `Native returned an unknown thumbnail kind: ${result.kind}`
      );
    }

    if (result.mimeType !== 'image/png' && result.mimeType !== 'image/jpeg') {
      throw new ThumbnailError(
        'GENERATION_FAILED',
        `Native returned an unsupported output type: ${result.mimeType}`
      );
    }

    if (typeof result.uri !== 'string' || !/^file:\/\//i.test(result.uri)) {
      throw new ThumbnailError(
        'GENERATION_FAILED',
        'Native returned a thumbnail URI that is not a local file URL.'
      );
    }

    if (
      !Number.isInteger(result.width) ||
      !Number.isInteger(result.height) ||
      result.width < 1 ||
      result.height < 1 ||
      result.width > width ||
      result.height > height
    ) {
      throw new ThumbnailError(
        'GENERATION_FAILED',
        'Native returned thumbnail dimensions outside the requested bounds.'
      );
    }

    if (typeof result.fromCache !== 'boolean') {
      throw new ThumbnailError(
        'GENERATION_FAILED',
        'Native returned an invalid cache status.'
      );
    }

    return {
      uri: result.uri,
      width: result.width,
      height: result.height,
      kind: result.kind,
      mimeType: result.mimeType,
      fromCache: result.fromCache,
    };
  } catch (error) {
    throw toThumbnailError(error);
  } finally {
    signal?.removeEventListener('abort', abort);
  }
}

/** Deletes every file managed by the thumbnail cache and returns the count. */
export async function clearThumbnailCache(): Promise<number> {
  try {
    return await NativeSystemThumbnails.clearCache();
  } catch (error) {
    throw toThumbnailError(error, 'CACHE_ERROR');
  }
}
