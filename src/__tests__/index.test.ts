jest.mock('../NativeReactNativeSystemThumbnails', () => ({
  __esModule: true,
  default: {
    generate: jest.fn(),
    cancel: jest.fn(),
    clearCache: jest.fn(),
  },
}));

import NativeSystemThumbnails from '../NativeReactNativeSystemThumbnails';
import {
  clearThumbnailCache,
  generateThumbnail,
  ThumbnailError,
  type GenerateThumbnailOptions,
} from '../index';

const mockNative = jest.mocked(NativeSystemThumbnails);

const thumbnail = {
  uri: 'file:///cache/thumbnail.png',
  width: 128,
  height: 64,
  kind: 'thumbnail',
  mimeType: 'image/png',
  fromCache: false,
};

describe('generateThumbnail', () => {
  beforeEach(() => {
    mockNative.generate.mockResolvedValue(thumbnail);
    mockNative.clearCache.mockResolvedValue(0);
  });

  it('passes documented defaults to native and returns its result', async () => {
    await expect(
      generateThumbnail({ uri: 'file:///tmp/report.pdf' })
    ).resolves.toEqual(thumbnail);

    expect(mockNative.generate).toHaveBeenCalledWith(
      expect.any(String),
      'file:///tmp/report.pdf',
      256,
      256,
      'png',
      0.9,
      true,
      true
    );
  });

  it('passes explicit rendering options to native', async () => {
    await generateThumbnail({
      uri: 'content://documents/42',
      size: { width: 320, height: 180 },
      format: 'jpeg',
      quality: 0.72,
      cache: false,
      fallback: 'error',
    });

    expect(mockNative.generate).toHaveBeenCalledWith(
      expect.any(String),
      'content://documents/42',
      320,
      180,
      'jpeg',
      0.72,
      false,
      false
    );
  });

  it.each([
    [{ uri: '' }, 'INVALID_ARGUMENT'],
    [
      { uri: 'file:///tmp/a', size: { width: 0, height: 12 } },
      'INVALID_ARGUMENT',
    ],
    [
      { uri: 'file:///tmp/a', size: { width: 12.5, height: 12 } },
      'INVALID_ARGUMENT',
    ],
    [{ uri: 'file:///tmp/a', quality: 1.1 }, 'INVALID_ARGUMENT'],
    [
      {
        uri: 'file:///tmp/a',
        quality: null,
      } as unknown as GenerateThumbnailOptions,
      'INVALID_ARGUMENT',
    ],
    [
      {
        uri: 'file:///tmp/a',
        cache: 'yes',
      } as unknown as GenerateThumbnailOptions,
      'INVALID_ARGUMENT',
    ],
    [
      {
        uri: 'file:///tmp/a',
        size: null,
      } as unknown as GenerateThumbnailOptions,
      'INVALID_ARGUMENT',
    ],
    [
      {
        uri: 'file:///tmp/a',
        signal: {},
      } as unknown as GenerateThumbnailOptions,
      'INVALID_ARGUMENT',
    ],
    [{ uri: 'https://example.com/a.pdf' }, 'UNSUPPORTED_URI'],
  ])('rejects invalid options %#', async (options, code) => {
    await expect(generateThumbnail(options)).rejects.toMatchObject({ code });
    expect(mockNative.generate).not.toHaveBeenCalled();
  });

  it('normalizes native error codes', async () => {
    mockNative.generate.mockRejectedValue({
      code: 'E_PERMISSION_DENIED',
      message: 'The provider refused access.',
    });

    await expect(
      generateThumbnail({ uri: 'content://documents/private' })
    ).rejects.toEqual(
      expect.objectContaining({
        name: 'ThumbnailError',
        code: 'PERMISSION_DENIED',
        message: 'The provider refused access.',
      })
    );
  });

  it.each([
    { kind: 'preview' },
    { mimeType: 'image/webp' },
    { uri: '' },
    { uri: 'content://generated/thumbnail.png' },
    { width: 0 },
    { width: 257 },
    { height: 1.5 },
    { fromCache: 'yes' },
  ])('rejects malformed native output %#', async (override) => {
    mockNative.generate.mockResolvedValue({
      ...thumbnail,
      ...override,
    } as never);

    await expect(
      generateThumbnail({ uri: 'file:///tmp/report.pdf' })
    ).rejects.toMatchObject({ code: 'GENERATION_FAILED' });
  });

  it('does not start native work for an already-aborted request', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(
      generateThumbnail({
        uri: 'file:///tmp/large.mov',
        signal: controller.signal,
      })
    ).rejects.toMatchObject({ code: 'CANCELLED' });
    expect(mockNative.generate).not.toHaveBeenCalled();
  });

  it('forwards cancellation to the matching native request', async () => {
    const controller = new AbortController();
    let rejectNative: (reason: unknown) => void = () => undefined;
    mockNative.generate.mockReturnValue(
      new Promise((_, reject) => {
        rejectNative = reject;
      })
    );
    mockNative.cancel.mockImplementation(() => {
      rejectNative({ code: 'CANCELLED', message: 'Cancelled.' });
    });

    const request = generateThumbnail({
      uri: 'file:///tmp/large.mov',
      signal: controller.signal,
    });
    controller.abort();

    await expect(request).rejects.toMatchObject({ code: 'CANCELLED' });
    const requestId = mockNative.generate.mock.calls[0]?.[0];
    expect(mockNative.cancel).toHaveBeenCalledWith(requestId);
  });
});

describe('clearThumbnailCache', () => {
  it('returns the number of deleted cache entries', async () => {
    mockNative.clearCache.mockResolvedValue(4);
    await expect(clearThumbnailCache()).resolves.toBe(4);
  });

  it('wraps cache failures in ThumbnailError', async () => {
    mockNative.clearCache.mockRejectedValue(new Error('Disk unavailable'));

    await expect(clearThumbnailCache()).rejects.toEqual(
      expect.objectContaining<Partial<ThumbnailError>>({
        name: 'ThumbnailError',
        code: 'CACHE_ERROR',
      })
    );
  });
});
