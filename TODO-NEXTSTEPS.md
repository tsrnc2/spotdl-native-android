# TODO / Next Steps — spotdl-native-android

Status: original Android integration/port project  
Primary goal: make the Android-native packaging/runtime boundary reproducible, maintainable, and legally clear.

## P0 — project identity and upstream boundary

- [ ] Add README explaining what this project adds beyond upstream spotDL and which parts are original Android integration work.
- [ ] Document the exact upstream spotDL version/commit compatibility target.
- [ ] Keep vendored/upstream code clearly separated from Android-specific glue and patches.
- [ ] Record licensing obligations for spotDL, Python/runtime components, ffmpeg/media tooling, and bundled dependencies.

## P0 — reproducible Android build

- [ ] Document a clean build from fresh checkout with exact Android SDK/NDK/JDK/Gradle/Python requirements.
- [ ] Pin dependency versions and generate/verify lockfiles where supported.
- [ ] Eliminate machine-specific absolute paths and undocumented local prerequisites.
- [ ] Add a single build script/task for debug APK and one for release-ready artifact generation.

## P1 — runtime architecture

- [ ] Define the boundary between Android UI/service lifecycle and Python/native downloader runtime.
- [ ] Handle Android process death, background restrictions, storage permissions, network interruption, and resumed downloads explicitly.
- [ ] Keep long-running downloads out of fragile Activity lifecycle state.
- [ ] Add bounded logs and user-visible error states for missing codecs, provider failures, permissions, and storage exhaustion.

## P1 — security/privacy

- [ ] Audit WebView, intents, exported activities/services, file-provider paths, and external storage use.
- [ ] Never log account/session secrets, tokens, or sensitive URLs.
- [ ] Verify downloaded filenames/paths cannot escape the intended media directory.
- [ ] Add dependency and secret scanning.

## P1 — tests and CI

- [ ] Add unit tests for Android-specific path/config/process wrappers.
- [ ] Add instrumentation/smoke test for app start, permission flow, one deterministic mock download, cancellation, and resume.
- [ ] Build APK/AAB in CI.
- [ ] Add at least one emulator API-level matrix and document real-device tests.

## P2 — release readiness

- [ ] Add versioning tied to both app version and supported upstream spotDL version.
- [ ] Add release notes and upgrade/migration guidance.
- [ ] Verify package size and remove unnecessary vendored/build artifacts.
- [ ] Document media/service terms and user responsibilities without implying unsupported platform guarantees.

## Done when

A clean machine can build the Android app reproducibly, upstream compatibility is explicit, lifecycle/storage/security behavior is tested, and the APK contains only intentional dependencies/assets.