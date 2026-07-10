import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  ThumbnailError,
  clearThumbnailCache,
  generateThumbnail,
} from '@marshallbear1/react-native-system-thumbnails';

const THUMBNAIL_SIZE = { width: 128, height: 128 } as const;
const ANDROID_APPLICATION_ID =
  'marshallbear1.reactnativesystemthumbnails.example';

const sourceAsset = Image.resolveAssetSource(
  require('./assets/thumbnail-source.png')
);

if (!sourceAsset) {
  throw new Error('Could not resolve the bundled thumbnail fixture.');
}

const SOURCE_URI =
  Platform.OS === 'android'
    ? `content://${ANDROID_APPLICATION_ID}.fixtures/thumbnail-source.png`
    : 'bundle://thumbnail_source.png';
const UNKNOWN_SOURCE_URI =
  Platform.OS === 'android'
    ? `content://${ANDROID_APPLICATION_ID}.fixtures/unknown-source.rnstunknown`
    : 'bundle://unknown_source.rnstunknown';

type ThumbnailResult = Awaited<ReturnType<typeof generateThumbnail>>;
type TestPhase = 'idle' | 'running' | 'pass' | 'fail';
type VerificationCheck =
  'generated' | 'rendered' | 'cache' | 'strict' | 'icon' | 'cleanup';

const PROOF_ITEMS: ReadonlyArray<{
  id: VerificationCheck;
  label: string;
}> = [
  { id: 'generated', label: 'Generated' },
  { id: 'rendered', label: 'Rendered' },
  { id: 'cache', label: 'Cache hit' },
  { id: 'strict', label: 'Strict fallback' },
  { id: 'icon', label: 'Icon fallback' },
  { id: 'cleanup', label: 'Cache cleared' },
];

type ImageWaiter = {
  resolve: () => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function validateResult(
  result: ThumbnailResult,
  expectedKind: ThumbnailResult['kind']
) {
  assert(
    result.uri.startsWith('file://'),
    'Result URI is not a local file URI'
  );
  assert(
    result.kind === expectedKind,
    `Expected ${expectedKind}, got ${result.kind}`
  );
  assert(
    result.mimeType === 'image/png',
    `Unexpected MIME type ${result.mimeType}`
  );
  assert(Number.isInteger(result.width), 'Result width is not an integer');
  assert(Number.isInteger(result.height), 'Result height is not an integer');
  assert(
    result.width > 0 && result.width <= THUMBNAIL_SIZE.width,
    `Width ${result.width} is outside the requested bounds`
  );
  assert(
    result.height > 0 && result.height <= THUMBNAIL_SIZE.height,
    `Height ${result.height} is outside the requested bounds`
  );
}

function verifyImageCanLoad(uri: string) {
  return new Promise<void>((resolve, reject) => {
    let settled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const finish = (error?: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      if (timer) {
        clearTimeout(timer);
      }
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };

    timer = setTimeout(
      () =>
        finish(
          new Error('The generated icon did not decode within 10 seconds')
        ),
      10_000
    );
    Image.getSize(
      uri,
      (width, height) => {
        if (width > 0 && height > 0) {
          finish();
        } else {
          finish(new Error('The generated image has invalid dimensions'));
        }
      },
      () =>
        finish(new Error('React Native could not decode the generated icon'))
    );
  });
}

function describeError(error: unknown) {
  if (error instanceof ThumbnailError) {
    return `${error.code}: ${error.message}`;
  }

  if (error instanceof Error) {
    return error.message;
  }

  return String(error);
}

export default function App() {
  const [phase, setPhase] = useState<TestPhase>('idle');
  const [detail, setDetail] = useState(
    'Ready to exercise the native module on this device.'
  );
  const [thumbnail, setThumbnail] = useState<ThumbnailResult | null>(null);
  const [imageState, setImageState] = useState<'empty' | 'loading' | 'loaded'>(
    'empty'
  );
  const [completedChecks, setCompletedChecks] = useState<VerificationCheck[]>(
    []
  );
  const imageWaiter = useRef<ImageWaiter | null>(null);

  useEffect(
    () => () => {
      if (imageWaiter.current) {
        clearTimeout(imageWaiter.current.timer);
        imageWaiter.current = null;
      }
    },
    []
  );

  const waitForImage = useCallback((result: ThumbnailResult) => {
    return new Promise<void>((resolve, reject) => {
      setImageState('loading');
      setThumbnail(result);

      const timer = setTimeout(() => {
        imageWaiter.current = null;
        reject(
          new Error('The generated thumbnail did not render within 10 seconds')
        );
      }, 10_000);

      imageWaiter.current = { resolve, reject, timer };
    });
  }, []);

  const finishImageWait = useCallback((error?: Error) => {
    const waiter = imageWaiter.current;
    if (!waiter) {
      return;
    }

    clearTimeout(waiter.timer);
    imageWaiter.current = null;

    if (error) {
      waiter.reject(error);
    } else {
      setImageState('loaded');
      waiter.resolve();
    }
  }, []);

  const markCheckComplete = useCallback((check: VerificationCheck) => {
    setCompletedChecks((current) =>
      current.includes(check) ? current : [...current, check]
    );
  }, []);

  const runSelfTest = useCallback(async () => {
    setPhase('running');
    setDetail('Clearing the cache for a cold start…');
    setThumbnail(null);
    setImageState('empty');
    setCompletedChecks([]);

    try {
      await clearThumbnailCache();

      const options = {
        uri: SOURCE_URI,
        size: THUMBNAIL_SIZE,
        format: 'png' as const,
        quality: 0.9,
        cache: true,
        fallback: 'error' as const,
      };

      setDetail('Generating and rendering a native thumbnail…');
      const first = await generateThumbnail(options);
      validateResult(first, 'thumbnail');
      assert(!first.fromCache, 'Cold generation unexpectedly came from cache');
      markCheckComplete('generated');
      await waitForImage(first);
      markCheckComplete('rendered');

      setDetail('Requesting the same thumbnail to verify the cache…');
      const cached = await generateThumbnail(options);
      validateResult(cached, 'thumbnail');
      assert(cached.fromCache, 'Second generation was not served from cache');
      assert(
        cached.uri === first.uri,
        'Cache hit returned a different file URI'
      );
      markCheckComplete('cache');

      setDetail('Proving strict mode rejects an icon-only file…');
      let strictFailure: unknown = null;
      try {
        await generateThumbnail({
          ...options,
          uri: UNKNOWN_SOURCE_URI,
          cache: false,
        });
      } catch (error) {
        strictFailure = error;
      }
      assert(
        strictFailure instanceof ThumbnailError,
        'Strict fallback did not reject with a ThumbnailError'
      );
      assert(
        strictFailure.code === 'GENERATION_FAILED',
        `Strict fallback rejected with ${strictFailure.code}`
      );
      markCheckComplete('strict');

      setDetail('Generating and decoding the file-icon fallback…');
      const icon = await generateThumbnail({
        ...options,
        uri: UNKNOWN_SOURCE_URI,
        cache: false,
        fallback: 'icon',
      });
      validateResult(icon, 'icon');
      assert(!icon.fromCache, 'cache:false unexpectedly returned a cache hit');
      await verifyImageCanLoad(icon.uri);
      markCheckComplete('icon');

      setDetail('Clearing the generated cache entry…');
      const clearedEntries = await clearThumbnailCache();
      assert(
        Number.isInteger(clearedEntries) && clearedEntries >= 1,
        'Cache clear did not report the generated entry'
      );
      markCheckComplete('cleanup');

      setPhase('pass');
      setDetail(
        `${first.width}×${first.height} PNG and icon decoded • ${clearedEntries} files cleared`
      );
    } catch (error) {
      setPhase('fail');
      setDetail(describeError(error));
    }
  }, [markCheckComplete, waitForImage]);

  const clearPreview = useCallback(async () => {
    setPhase('running');
    setDetail('Clearing native thumbnail cache…');

    try {
      const clearedEntries = await clearThumbnailCache();
      setThumbnail(null);
      setImageState('empty');
      setCompletedChecks([]);
      setPhase('idle');
      setDetail(`Cache cleared (${clearedEntries} entries removed).`);
    } catch (error) {
      setPhase('fail');
      setDetail(describeError(error));
    }
  }, []);

  const statusLabel = {
    idle: 'READY',
    running: 'RUNNING',
    pass: 'PASS',
    fail: 'FAIL',
  }[phase];
  const statusTitle = {
    idle: 'Ready for a native run',
    running: 'Checking the native pipeline',
    pass: 'Every check passed',
    fail: 'A native check failed',
  }[phase];
  const resultStateLabel = {
    empty: 'WAITING',
    loading: 'RENDERING',
    loaded: 'NATIVE FILE',
  }[imageState];

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar
        barStyle="dark-content"
        backgroundColor={styles.safeArea.backgroundColor}
      />
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.header}>
          <View style={styles.brand}>
            <View style={styles.brandMark}>
              <Text style={styles.brandMarkText}>ST</Text>
            </View>
            <View>
              <Text style={styles.brandName}>System Thumbnails</Text>
              <Text style={styles.brandMeta}>React Native TurboModule</Text>
            </View>
          </View>
          <View style={styles.platformPill}>
            <View style={styles.platformDot} />
            <Text style={styles.platformPillText}>
              {Platform.OS.toUpperCase()} · NATIVE
            </Text>
          </View>
        </View>

        <Text style={styles.title} testID="screen-title">
          Native previews,
          {'\n'}one clean API.
        </Text>
        <Text style={styles.subtitle}>
          Generate, render, cache, and clean up a local file without moving its
          bytes through JavaScript.
        </Text>

        <View style={styles.showcaseCard}>
          <View style={styles.fileHeader}>
            <View style={styles.fileIcon}>
              <Text style={styles.fileIconText}>PNG</Text>
            </View>
            <View style={styles.fileCopy}>
              <Text style={styles.fileName}>thumbnail-source.png</Text>
              <Text style={styles.fileMeta}>Bundled fixture · 192 × 192</Text>
            </View>
            <View style={styles.outputSpec}>
              <Text style={styles.outputSpecValue}>128²</Text>
              <Text style={styles.outputSpecLabel}>OUTPUT</Text>
            </View>
          </View>

          <View style={styles.previewRow}>
            <View style={styles.previewColumn}>
              <View style={styles.previewLabelRow}>
                <Text style={styles.previewLabel}>SOURCE</Text>
                <Text style={styles.previewMeta}>ORIGINAL</Text>
              </View>
              <View style={styles.imageFrame}>
                <Image
                  accessibilityLabel="Bundled source image"
                  resizeMode="cover"
                  source={sourceAsset}
                  style={styles.previewImage}
                  testID="source-image"
                />
              </View>
            </View>

            <View style={styles.transformArrow}>
              <Text style={styles.transformArrowText}>→</Text>
            </View>

            <View style={styles.previewColumn}>
              <View style={styles.previewLabelRow}>
                <Text style={styles.previewLabel}>THUMBNAIL</Text>
                <Text style={styles.resultState} testID="result-image-state">
                  {resultStateLabel}
                </Text>
              </View>
              <View style={[styles.imageFrame, styles.resultFrame]}>
                {thumbnail ? (
                  <Image
                    accessibilityLabel="Generated native thumbnail"
                    onError={() =>
                      finishImageWait(
                        new Error(
                          'React Native Image could not load the result URI'
                        )
                      )
                    }
                    onLoad={() => finishImageWait()}
                    resizeMode="contain"
                    source={{ uri: thumbnail.uri }}
                    style={styles.previewImage}
                    testID="result-image"
                  />
                ) : (
                  <View style={styles.placeholder}>
                    <Text style={styles.placeholderIcon}>◇</Text>
                    <Text style={styles.placeholderText}>128 × 128</Text>
                  </View>
                )}
              </View>
            </View>
          </View>

          <View style={styles.divider} />

          <View
            accessibilityLiveRegion="polite"
            style={[styles.statusSurface, styles[`statusSurface_${phase}`]]}
            testID={`self-test-${phase}`}
          >
            <View style={[styles.statusIcon, styles[`statusIcon_${phase}`]]}>
              {phase === 'running' ? (
                <ActivityIndicator color="#1f8068" size="small" />
              ) : (
                <Text
                  style={[
                    styles.statusIconText,
                    styles[`statusIconText_${phase}`],
                  ]}
                >
                  {phase === 'pass' ? '✓' : phase === 'fail' ? '!' : '•'}
                </Text>
              )}
            </View>
            <View style={styles.statusCopy}>
              <View style={styles.statusTopLine}>
                <Text style={styles.statusTitle}>{statusTitle}</Text>
                <Text
                  style={[styles.statusLabel, styles[`statusLabel_${phase}`]]}
                >
                  {statusLabel}
                </Text>
              </View>
              <Text style={styles.statusDetail} testID="self-test-detail">
                {detail}
              </Text>
            </View>
          </View>

          <View style={styles.proofGrid}>
            {PROOF_ITEMS.map((item) => {
              const complete = completedChecks.includes(item.id);
              return (
                <View
                  accessibilityLabel={`${item.label} ${complete ? 'passed' : 'pending'}`}
                  key={item.id}
                  style={[
                    styles.proofItem,
                    complete && styles.proofItemComplete,
                  ]}
                  testID={`proof-${item.id}-${complete ? 'pass' : 'pending'}`}
                >
                  <View
                    style={[
                      styles.proofDot,
                      complete && styles.proofDotComplete,
                    ]}
                  >
                    <Text
                      style={[
                        styles.proofDotText,
                        complete && styles.proofDotTextComplete,
                      ]}
                    >
                      {complete ? '✓' : '·'}
                    </Text>
                  </View>
                  <Text
                    style={[
                      styles.proofLabel,
                      complete && styles.proofLabelComplete,
                    ]}
                  >
                    {item.label}
                  </Text>
                </View>
              );
            })}
          </View>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ disabled: phase === 'running' }}
          disabled={phase === 'running'}
          onPress={runSelfTest}
          style={({ pressed }) => [
            styles.primaryButton,
            pressed && styles.buttonPressed,
            phase === 'running' && styles.buttonDisabled,
          ]}
          testID="run-self-test"
        >
          <Text style={styles.primaryButtonText}>
            {phase === 'running'
              ? 'Running native checks…'
              : 'Generate & verify'}
          </Text>
          <Text style={styles.buttonArrow}>→</Text>
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ disabled: phase === 'running' }}
          disabled={phase === 'running'}
          onPress={clearPreview}
          style={({ pressed }) => [
            styles.secondaryButton,
            pressed && styles.buttonPressed,
            phase === 'running' && styles.buttonDisabled,
          ]}
          testID="clear-thumbnail-cache"
        >
          <Text style={styles.secondaryButtonText}>Reset demo</Text>
        </Pressable>

        <View style={styles.sourceDetail}>
          <Text style={styles.sourceDetailLabel}>INPUT</Text>
          <Text
            ellipsizeMode="middle"
            numberOfLines={1}
            style={styles.uri}
            testID="source-uri"
          >
            {SOURCE_URI}
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#f4f7f3',
  },
  content: {
    alignSelf: 'center',
    flexGrow: 1,
    maxWidth: 560,
    paddingBottom: 24,
    paddingHorizontal: 20,
    paddingTop: 18,
    width: '100%',
  },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  brand: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
  },
  brandMark: {
    alignItems: 'center',
    backgroundColor: '#173f35',
    borderRadius: 10,
    height: 36,
    justifyContent: 'center',
    width: 36,
  },
  brandMarkText: {
    color: '#effff8',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0.5,
  },
  brandName: {
    color: '#17231f',
    fontSize: 14,
    fontWeight: '800',
  },
  brandMeta: {
    color: '#7a867f',
    fontSize: 10,
    marginTop: 2,
  },
  platformPill: {
    alignItems: 'center',
    backgroundColor: '#e7f4ee',
    borderColor: '#cfe5da',
    borderRadius: 18,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 6,
    paddingHorizontal: 10,
    paddingVertical: 7,
  },
  platformDot: {
    backgroundColor: '#21866d',
    borderRadius: 4,
    height: 7,
    width: 7,
  },
  platformPillText: {
    color: '#246b59',
    fontSize: 9,
    fontWeight: '900',
    letterSpacing: 0.7,
  },
  title: {
    color: '#17231f',
    fontSize: 34,
    fontWeight: '800',
    letterSpacing: -1.2,
    lineHeight: 38,
    marginTop: 24,
  },
  subtitle: {
    color: '#69766f',
    fontSize: 15,
    lineHeight: 22,
    marginTop: 10,
    maxWidth: 500,
  },
  showcaseCard: {
    backgroundColor: '#ffffff',
    borderColor: '#dce4de',
    borderRadius: 20,
    borderWidth: 1,
    elevation: 2,
    marginTop: 22,
    padding: 16,
    shadowColor: '#15231e',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.06,
    shadowRadius: 18,
  },
  fileHeader: {
    alignItems: 'center',
    flexDirection: 'row',
  },
  fileIcon: {
    alignItems: 'center',
    backgroundColor: '#e8f4ee',
    borderRadius: 11,
    height: 40,
    justifyContent: 'center',
    width: 40,
  },
  fileIconText: {
    color: '#25765f',
    fontSize: 9,
    fontWeight: '900',
    letterSpacing: 0.6,
  },
  fileCopy: {
    flex: 1,
    marginLeft: 11,
  },
  fileName: {
    color: '#1c2925',
    fontSize: 14,
    fontWeight: '800',
  },
  fileMeta: {
    color: '#859089',
    fontSize: 11,
    marginTop: 3,
  },
  outputSpec: {
    alignItems: 'flex-end',
  },
  outputSpecValue: {
    color: '#32413b',
    fontSize: 13,
    fontWeight: '800',
  },
  outputSpecLabel: {
    color: '#99a29d',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 0.8,
    marginTop: 2,
  },
  previewRow: {
    alignItems: 'center',
    flexDirection: 'row',
    marginTop: 18,
  },
  previewColumn: {
    flex: 1,
  },
  previewLabelRow: {
    alignItems: 'center',
    flexDirection: 'row',
    height: 20,
    justifyContent: 'space-between',
    paddingHorizontal: 2,
  },
  previewLabel: {
    color: '#495851',
    fontSize: 9,
    fontWeight: '900',
    letterSpacing: 0.8,
  },
  previewMeta: {
    color: '#a0aaa4',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  resultState: {
    color: '#23775f',
    fontSize: 8,
    fontWeight: '900',
    letterSpacing: 0.5,
  },
  transformArrow: {
    alignItems: 'center',
    backgroundColor: '#f0f4f1',
    borderColor: '#e0e7e2',
    borderRadius: 16,
    borderWidth: 1,
    height: 32,
    justifyContent: 'center',
    marginHorizontal: 10,
    marginTop: 20,
    width: 32,
  },
  transformArrowText: {
    color: '#64726b',
    fontSize: 17,
    lineHeight: 19,
  },
  imageFrame: {
    aspectRatio: 1,
    backgroundColor: '#edf2ee',
    borderRadius: 14,
    overflow: 'hidden',
  },
  resultFrame: {
    backgroundColor: '#f7f9f7',
    borderColor: '#cfd9d2',
    borderStyle: 'dashed',
    borderWidth: 1,
  },
  previewImage: {
    height: '100%',
    width: '100%',
  },
  placeholder: {
    alignItems: 'center',
    flex: 1,
    justifyContent: 'center',
  },
  placeholderIcon: {
    color: '#aab5ae',
    fontSize: 30,
    lineHeight: 34,
  },
  placeholderText: {
    color: '#9ba69f',
    fontSize: 9,
    fontWeight: '700',
    letterSpacing: 0.7,
    marginTop: 2,
  },
  divider: {
    backgroundColor: '#e8ede9',
    height: 1,
    marginVertical: 16,
  },
  statusSurface: {
    alignItems: 'flex-start',
    backgroundColor: '#f6f8f6',
    borderColor: '#e2e8e3',
    borderRadius: 14,
    borderWidth: 1,
    flexDirection: 'row',
    padding: 12,
  },
  statusSurface_idle: {},
  statusSurface_running: {
    backgroundColor: '#f0f8f4',
    borderColor: '#cfe7dc',
  },
  statusSurface_pass: {
    backgroundColor: '#edf8f3',
    borderColor: '#bfe1d3',
  },
  statusSurface_fail: {
    backgroundColor: '#fff1f1',
    borderColor: '#f0caca',
  },
  statusIcon: {
    alignItems: 'center',
    backgroundColor: '#e7ebe8',
    borderRadius: 14,
    height: 28,
    justifyContent: 'center',
    width: 28,
  },
  statusIcon_idle: {},
  statusIcon_running: {
    backgroundColor: '#dff2e9',
  },
  statusIcon_pass: {
    backgroundColor: '#21866d',
  },
  statusIcon_fail: {
    backgroundColor: '#bd4e52',
  },
  statusIconText: {
    fontSize: 14,
    fontWeight: '900',
  },
  statusIconText_idle: {
    color: '#738078',
  },
  statusIconText_running: {
    color: '#21866d',
  },
  statusIconText_pass: {
    color: '#ffffff',
  },
  statusIconText_fail: {
    color: '#ffffff',
  },
  statusCopy: {
    flex: 1,
    marginLeft: 10,
  },
  statusTopLine: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  statusTitle: {
    color: '#22302b',
    flex: 1,
    fontSize: 13,
    fontWeight: '800',
  },
  statusLabel: {
    fontSize: 9,
    fontWeight: '900',
    letterSpacing: 1,
  },
  statusLabel_idle: {
    color: '#7f8a84',
  },
  statusLabel_running: {
    color: '#21866d',
  },
  statusLabel_pass: {
    color: '#176b56',
  },
  statusLabel_fail: {
    color: '#ae3e43',
  },
  statusDetail: {
    color: '#69766f',
    fontSize: 11,
    lineHeight: 16,
    marginTop: 4,
  },
  proofGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginTop: 12,
  },
  proofItem: {
    alignItems: 'center',
    backgroundColor: '#f7f9f7',
    borderColor: '#e5eae6',
    borderRadius: 10,
    borderWidth: 1,
    flexDirection: 'row',
    minHeight: 34,
    paddingHorizontal: 9,
    width: '48.5%',
  },
  proofItemComplete: {
    backgroundColor: '#f0f8f4',
    borderColor: '#cfe7dc',
  },
  proofDot: {
    alignItems: 'center',
    backgroundColor: '#e6ebe7',
    borderRadius: 9,
    height: 18,
    justifyContent: 'center',
    width: 18,
  },
  proofDotComplete: {
    backgroundColor: '#21866d',
  },
  proofDotText: {
    color: '#89948e',
    fontSize: 12,
    fontWeight: '900',
    lineHeight: 14,
  },
  proofDotTextComplete: {
    color: '#ffffff',
    fontSize: 10,
  },
  proofLabel: {
    color: '#89948e',
    fontSize: 10,
    fontWeight: '700',
    marginLeft: 7,
  },
  proofLabelComplete: {
    color: '#315e50',
  },
  primaryButton: {
    alignItems: 'center',
    backgroundColor: '#173f35',
    borderRadius: 14,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 16,
    minHeight: 54,
    paddingHorizontal: 18,
  },
  primaryButtonText: {
    color: '#f2fff9',
    fontSize: 15,
    fontWeight: '800',
  },
  buttonArrow: {
    color: '#b9ebd9',
    fontSize: 21,
    fontWeight: '500',
  },
  buttonPressed: {
    opacity: 0.78,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  secondaryButton: {
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 5,
    minHeight: 44,
  },
  secondaryButtonText: {
    color: '#66736c',
    fontSize: 12,
    fontWeight: '700',
  },
  sourceDetail: {
    alignItems: 'center',
    backgroundColor: '#e9eeea',
    borderRadius: 10,
    flexDirection: 'row',
    marginTop: 2,
    minHeight: 36,
    paddingHorizontal: 10,
  },
  sourceDetailLabel: {
    color: '#809087',
    fontSize: 8,
    fontWeight: '900',
    letterSpacing: 0.8,
  },
  uri: {
    color: '#7a8780',
    flex: 1,
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
    fontSize: 9,
    marginLeft: 9,
    textAlign: 'right',
  },
});
