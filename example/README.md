# System Thumbnails example

This app is both a visual demo and an on-device smoke test for the native
module. Tap **Generate & verify** to verify, in order:

1. a cold thumbnail can be generated from the bundled PNG fixture;
2. the returned value is a local PNG thumbnail with valid dimensions;
3. React Native's `Image` can render the returned file URI;
4. an identical second request is served from the native cache; and
5. an icon-only file rejects with `GENERATION_FAILED` when fallback is strict;
6. the same file produces a decodable icon when fallback is enabled; and
7. all generated cache entries can be removed.

The status card only displays **PASS** after every check succeeds. Failures keep
the native error code or assertion message visible on screen.

## Run it manually

From the repository root, start Metro and install the example on Android:

```sh
yarn example start
yarn example android
```

Android exposes the packaged drawable through an internal, read-only example
`ContentProvider`. The self-test therefore exercises the same
`content://<applicationId>.fixtures/thumbnail-source.png` path used by document
pickers and other Android providers, including MIME, name, and size metadata.
The provider also exposes an opaque custom-extension fixture for the strict and
icon fallback checks.

On iOS, the same fixture is a Copy Bundle Resources entry. The demo passes
`bundle://thumbnail_source.png`, which the native module resolves through the
main application bundle, so normal Metro-backed Debug builds work:

```sh
yarn example ios
```

## Run the automated smoke test

Install [Maestro](https://maestro.mobile.dev/) and make sure the example app is
installed on the currently booted simulator or emulator. Then run:

```sh
yarn example test:e2e
```

The flow in `../.maestro/native-self-test.yaml` launches a clean app, taps the
self-test control, waits up to 30 seconds for `self-test-pass`, then verifies
the generated image and all six on-screen proof states are visible.
