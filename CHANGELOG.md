# Changelog

All notable changes to this project are documented here.

## 0.1.1 - 2026-07-10

- Enforce `fallback: 'error'` on iOS by excluding Quick Look icon
  representations and ignoring stale icon cache entries in strict mode.
- Downsample oversized Quick Look file icons to the requested output bounds.
- Add an Android strict-fallback native test.
- Replace the example's test-harness layout with a compact product-style demo
  that visibly proves generation, rendering, caching, strict fallback, icon
  fallback decoding, and cleanup on both platforms.

## 0.1.0 - 2026-07-10

- Initial New-Architecture TurboModule for iOS and Android.
- Generate native previews for local and provider-backed files.
- Return deterministic file icons when native preview generation is
  unavailable.
- Support PNG/JPEG output, source-aware caching, cache cleanup, typed errors,
  and `AbortSignal` cancellation.
- Include a native example self-test, JavaScript/native tests, CI, and GitHub
  Packages release automation.
