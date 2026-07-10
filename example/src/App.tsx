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

type ThumbnailResult = Awaited<ReturnType<typeof generateThumbnail>>;
type TestPhase = 'idle' | 'running' | 'pass' | 'fail';

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

function validateThumbnail(result: ThumbnailResult) {
  assert(
    result.uri.startsWith('file://'),
    'Result URI is not a local file URI'
  );
  assert(result.kind === 'thumbnail', `Expected thumbnail, got ${result.kind}`);
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

  const runSelfTest = useCallback(async () => {
    setPhase('running');
    setDetail('Clearing the cache for a cold start…');
    setThumbnail(null);
    setImageState('empty');

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
      validateThumbnail(first);
      assert(!first.fromCache, 'Cold generation unexpectedly came from cache');
      await waitForImage(first);

      setDetail('Requesting the same thumbnail to verify the cache…');
      const cached = await generateThumbnail(options);
      validateThumbnail(cached);
      assert(cached.fromCache, 'Second generation was not served from cache');
      assert(
        cached.uri === first.uri,
        'Cache hit returned a different file URI'
      );

      setDetail('Clearing the generated cache entry…');
      const clearedEntries = await clearThumbnailCache();
      assert(
        Number.isInteger(clearedEntries) && clearedEntries >= 1,
        'Cache clear did not report the generated entry'
      );

      setPhase('pass');
      setDetail(
        `Rendered ${first.width}×${first.height} PNG • cache hit • cleared ${clearedEntries}`
      );
    } catch (error) {
      setPhase('fail');
      setDetail(describeError(error));
    }
  }, [waitForImage]);

  const clearPreview = useCallback(async () => {
    setPhase('running');
    setDetail('Clearing native thumbnail cache…');

    try {
      const clearedEntries = await clearThumbnailCache();
      setThumbnail(null);
      setImageState('empty');
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

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar
        barStyle="light-content"
        backgroundColor={styles.safeArea.backgroundColor}
      />
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.eyebrow}>
          <View style={styles.liveDot} />
          <Text style={styles.eyebrowText}>ON-DEVICE PROOF</Text>
        </View>

        <Text style={styles.title} testID="screen-title">
          System thumbnails,
          {'\n'}verified natively.
        </Text>
        <Text style={styles.subtitle}>
          Generate a local preview with the platform thumbnail APIs, then prove
          rendering, caching, and cleanup in one tap.
        </Text>

        <View style={styles.previewGrid}>
          <View style={styles.previewCard}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardLabel}>SOURCE</Text>
              <Text style={styles.cardMeta}>BUNDLED PNG</Text>
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

          <View style={styles.previewCard}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardLabel}>RESULT</Text>
              <Text style={styles.cardMeta} testID="result-image-state">
                {imageState === 'loaded' ? 'NATIVE FILE' : 'AWAITING RUN'}
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
                  <Text style={styles.placeholderIcon}>⌁</Text>
                  <Text style={styles.placeholderText}>128 × 128</Text>
                </View>
              )}
            </View>
          </View>
        </View>

        <View
          accessibilityLiveRegion="polite"
          style={[styles.statusCard, styles[`statusCard_${phase}`]]}
          testID={`self-test-${phase}`}
        >
          <View style={styles.statusTopLine}>
            <Text style={[styles.statusLabel, styles[`statusLabel_${phase}`]]}>
              {statusLabel}
            </Text>
            {phase === 'running' ? (
              <ActivityIndicator color="#74e6c1" size="small" />
            ) : (
              <Text style={styles.platformLabel}>
                {Platform.OS.toUpperCase()}
              </Text>
            )}
          </View>
          <Text style={styles.statusDetail} testID="self-test-detail">
            {detail}
          </Text>
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
              : 'Run native self-test'}
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
          ]}
          testID="clear-thumbnail-cache"
        >
          <Text style={styles.secondaryButtonText}>
            Clear cache &amp; preview
          </Text>
        </Pressable>

        <Text numberOfLines={2} style={styles.uri} testID="source-uri">
          {SOURCE_URI}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#071019',
  },
  content: {
    flexGrow: 1,
    paddingHorizontal: 22,
    paddingTop: 32,
    paddingBottom: 28,
  },
  eyebrow: {
    alignItems: 'center',
    alignSelf: 'flex-start',
    backgroundColor: '#10241f',
    borderColor: '#255d4e',
    borderRadius: 20,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 11,
    paddingVertical: 7,
  },
  liveDot: {
    backgroundColor: '#74e6c1',
    borderRadius: 4,
    height: 7,
    width: 7,
  },
  eyebrowText: {
    color: '#8ef0d0',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1.3,
  },
  title: {
    color: '#f4f7f9',
    fontSize: 36,
    fontWeight: '800',
    letterSpacing: -1.4,
    lineHeight: 41,
    marginTop: 19,
  },
  subtitle: {
    color: '#94a5b3',
    fontSize: 15,
    lineHeight: 22,
    marginTop: 13,
    maxWidth: 530,
  },
  previewGrid: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 27,
  },
  previewCard: {
    backgroundColor: '#0d1924',
    borderColor: '#1b2c3b',
    borderRadius: 18,
    borderWidth: 1,
    flex: 1,
    padding: 10,
  },
  cardHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingBottom: 9,
    paddingHorizontal: 2,
  },
  cardLabel: {
    color: '#eaf1f5',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1.1,
  },
  cardMeta: {
    color: '#627687',
    fontSize: 8,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  imageFrame: {
    aspectRatio: 1,
    backgroundColor: '#172635',
    borderRadius: 12,
    overflow: 'hidden',
  },
  resultFrame: {
    backgroundColor: '#0a141e',
    borderColor: '#243746',
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
    color: '#395064',
    fontSize: 38,
    lineHeight: 42,
  },
  placeholderText: {
    color: '#52687a',
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1,
    marginTop: 3,
  },
  statusCard: {
    backgroundColor: '#0d1924',
    borderColor: '#1b2c3b',
    borderLeftWidth: 4,
    borderRadius: 14,
    borderWidth: 1,
    marginTop: 15,
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  statusCard_idle: {
    borderLeftColor: '#527086',
  },
  statusCard_running: {
    borderLeftColor: '#74e6c1',
  },
  statusCard_pass: {
    backgroundColor: '#0c211d',
    borderColor: '#225c4c',
    borderLeftColor: '#74e6c1',
  },
  statusCard_fail: {
    backgroundColor: '#271416',
    borderColor: '#6b3037',
    borderLeftColor: '#ff7d88',
  },
  statusTopLine: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  statusLabel: {
    fontSize: 12,
    fontWeight: '900',
    letterSpacing: 1.5,
  },
  statusLabel_idle: {
    color: '#91a8b9',
  },
  statusLabel_running: {
    color: '#74e6c1',
  },
  statusLabel_pass: {
    color: '#8ef0d0',
  },
  statusLabel_fail: {
    color: '#ff98a1',
  },
  platformLabel: {
    color: '#526779',
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 1,
  },
  statusDetail: {
    color: '#b6c2cb',
    fontSize: 13,
    lineHeight: 19,
    marginTop: 7,
  },
  primaryButton: {
    alignItems: 'center',
    backgroundColor: '#74e6c1',
    borderRadius: 14,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 15,
    minHeight: 54,
    paddingHorizontal: 18,
  },
  primaryButtonText: {
    color: '#06251c',
    fontSize: 15,
    fontWeight: '900',
  },
  buttonArrow: {
    color: '#06251c',
    fontSize: 23,
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
    borderColor: '#243949',
    borderRadius: 14,
    borderWidth: 1,
    justifyContent: 'center',
    marginTop: 9,
    minHeight: 45,
  },
  secondaryButtonText: {
    color: '#9db0be',
    fontSize: 13,
    fontWeight: '700',
  },
  uri: {
    color: '#415665',
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
    fontSize: 9,
    lineHeight: 13,
    marginTop: 14,
    textAlign: 'center',
  },
});
