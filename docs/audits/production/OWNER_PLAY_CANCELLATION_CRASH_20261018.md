# Owner: crash after returning from Google Play subscription management

Audit date: 2026-10-18 (Asia/Manila).

## Release decision

**HOLD for open testing until the patched build passes checks and the cancellation/return workflow is retested on Android.** Version 13 is already uploaded according to the owner. The fixed candidate therefore needs version code 14 (`aq-1.0.9`). No Play upload or backend deployment is part of this fix.

**Candidate verdict: INSUFFICIENT EVIDENCE — critical Android runtime verification of version 14 is missing.** The uploaded version 13 has a confirmed P1 fatal defect (**NO-GO** for rollout with that defect). Passing local checks does not change the version-14 runtime gate.

## Actual Crashlytics evidence — PASS (retrieved)

- Issue: `4e33aa1d8b62a1925a39b953bd5a964e`, still OPEN.
- App: AquaHub Owner, `com.aesprt.aquahub`, `aq-1.0.8 (13)`.
- Sample event: 2026-10-17 19:14:32 UTC / 2026-10-18 03:14:32 Manila.
- Device: Samsung SM-A366B, Android 16.
- One fatal event/one impacted user in the queried 2026-10-11 through investigation-time interval. This count is not an all-time stability assessment.
- Blamed location: `RoomSubscriptionRepository.observeCurrentSubscription`'s mapped flow emission.
- Root exception: `android.os.NetworkOnMainThreadException`.
- Exception chain: nested `kotlinx.coroutines.CompletionHandlerException` during cancellation, through Ktor's `attachToUserJob` cleanup, with Android Conscrypt SSL output/close, Android HttpURLConnection stream cleanup, and `kotlinx.io.InputStreamSource.close` frames.

The owner identified Google Play subscription management as the cancellation screen. Crashlytics proves the HTTP cancellation failure; correlating every lifecycle step and reproducing the full Play workflow still requires runtime validation.

## Cause and changes

The previous shared Supabase client explicitly selected Ktor's Android/HttpURLConnection engine. Its cancellation cleanup can synchronously close a response/connection on the cancelling thread. The reported cancellation ran on the main thread, where SSL stream cleanup attempted network I/O and Android threw the fatal exception. Catching ordinary request errors alone does not fix a failure thrown by coroutine completion handlers.

The subscription billing observer also fetched remote history inside `collectLatest` for each Room subscription emission. A new snapshot could cancel the previous history request and start another one, creating unnecessary opportunities for this cleanup failure.

Scoped patch:

1. Use Ktor's OkHttp engine for the shared Owner Supabase client at the existing pinned Ktor version. Retain the existing request timeout and normal TLS verification.
2. Keep remote history reads outside the Room subscription observer. Refresh history at screen load and after returning from Play.
3. Coalesce repeated Play-return refresh calls. Report refresh errors without discarding the displayed subscription or leaving a loading indicator active, including when the owner changes stations during a refresh. History/error writes remain scoped to the original station.
4. Propagate coroutine cancellation from `RefreshSubscriptionStateUseCase` rather than continuing expiration processing after cancellation.
5. Prepare the next Android version because the owner confirmed version 13 is already uploaded.

No subscription status is granted by the client. Backend results remain authoritative; cancellation at period end must retain access only through the server-provided paid deadline.

The engine is shared by Owner Auth, database, Storage, and Edge Function requests. Their protocol and endpoints are unchanged, but a phone smoke test of sign-in, online sale, upload, and network recovery is needed alongside the billing retest. The patch does not change backend functions, schema, RLS, or Play billing products.

The final release R8 mapping contains `io.ktor.client.engine.okhttp.OkHttpEngine` and the production engine factory (inlined/removed after optimization). No `io.ktor.client.engine.android.AndroidClientEngine` mapping entry was found. This corroborates inclusion of the engine change in release optimization; it is not Android runtime proof.

## Verification

| Check | Status | Evidence / limit |
| --- | --- | --- |
| New billing ViewModel regression tests against previous implementation | PASS (failure reproduced) | All four tests failed before the patch: return refresh error, initial refresh error, repeated resumes, and a cancellation snapshot during history refresh. |
| Patched regressions | PASS | Seven tests passed: five billing ViewModel regressions, one cancellation-propagation test, and one real HTTP engine test. The original six passed in the focused run; all seven passed again in the final full suite. |
| Real HTTP cancellation against local sockets | PASS (JVM only) | The actual production OkHttp engine cancelled a partially received response, closed the connection, and successfully made a subsequent request. JVM checks do not establish Android StrictMode behavior. |
| Full production unit suite | PASS | Final `testProductionDebugUnitTest`: 209 tests across 61 suites, zero failures/errors/skips. XML results under `AquaHub/app/build/test-results/testProductionDebugUnitTest/`. |
| Production debug APK | PASS | Final `assembleProductionDebug` completed. APK metadata confirms `com.aesprt.aquahub`, version 14 / `aq-1.0.9`, min SDK 26, target SDK 37. SHA-256: `f4e90e15e7bf63479a2d354cfcba3dd66416c4447b7dacbc8fb8bbb61d1da842`. |
| Minified release APK/AAB | PASS | `assembleProductionRelease` and `bundleProductionRelease` completed. Both actual artifacts report version 14 / `aq-1.0.9`; release is not debuggable. |
| AAB structure and local signatures | PASS | Bundletool 1.18.3 validation exited 0; `apksigner verify` exited 0 (v2), and `jarsigner -verify` reported `jar verified` (exit 0). Both artifacts have the same local upload certificate. Play Console certificate comparison is NOT VERIFIED. |
| Full release lint | PASS with warnings | `lintProductionRelease`: zero errors, 85 warnings. Categories: ExifInterface 2, Typos 35, ObsoleteSdkInt 1, UnusedResources 9, IconLocation 2, UseKtx 36. Reports under `AquaHub/app/build/reports/lint-results-productionRelease.*`. |
| Android cancellation/return runtime | BLOCKED | No ADB device was visible during investigation, and the owner asked to finish local checks first. The final candidate has not been installed or run. Crashlytics supplied the original crash evidence, but does not prove the patched build's runtime behavior. |
| Play upload / rollout | NOT VERIFIED | Not performed. |

Local verification command (from `AquaHub/`):

```sh
./gradlew :app:testProductionDebugUnitTest :app:assembleProductionDebug \
  :app:assembleProductionRelease :app:bundleProductionRelease \
  :app:lintProductionRelease \
  -x :app:uploadCrashlyticsMappingFileProductionRelease --max-workers=1 --console=plain
```

Crashlytics mapping upload is explicitly excluded from this local build. No release artifact has been uploaded by this investigation.

The final combined Gradle command completed with `BUILD SUCCESSFUL` in 24m 16s (115 actionable tasks: 39 executed, 76 up-to-date). The earlier packaging run was deliberately interrupted to include the station-change loading cleanup; the hashes below belong to the completed final run. `git diff --check` passed afterward.

## Final candidate artifact evidence

Paths are relative to the workspace root:

| Artifact | Path | SHA-256 |
| --- | --- | --- |
| Production debug APK | `AquaHub/app/build/outputs/apk/production/debug/app-production-debug.apk` | `f4e90e15e7bf63479a2d354cfcba3dd66416c4447b7dacbc8fb8bbb61d1da842` |
| Minified release APK | `AquaHub/app/build/outputs/apk/production/release/app-production-release.apk` | `f326d7f1fcfeb5e5d4c22242815fb7c7b177b3c4dcb9eeaa52a08807cc4375a2` |
| Signed release AAB | `AquaHub/app/build/outputs/bundle/productionRelease/app-production-release.aab` | `028e379c88b47cecb90b49a6784dea98c02424a3fe20aade869ba093895cb91c` |

Release APK: 7,536,024 bytes. AAB: 16,411,784 bytes. Both use package `com.aesprt.aquahub`, version code 14, version name `aq-1.0.9`. Min/target SDK: 26/37. Generated production-release `BuildConfig` confirms `DEBUG=false` and `OFFLINE_POS_ENABLED=false`.

Local upload certificate SHA-256: `B7:B3:84:B2:64:9C:D2:22:00:F0:A5:36:66:FF:23:3A:50:E8:EB:08:6E:88:60:2E:A8:39:DC:FD:09:3D:E1:AD`. This is not a verification against Play Console's registered upload certificate or its separate app-signing certificate. Version-14 availability in Console was not checked; version 13 being uploaded is the owner's confirmation.

Keep `AquaHub/app/build/outputs/mapping/productionRelease/mapping.txt` with this candidate. Build warnings include incomplete Compose crash mappings for Google Places classes and unstripped symbols for two packaged AndroidX native libraries. `jarsigner` also reports the local certificate as self-signed, without a trusted public CA chain or signing timestamp; cryptographic integrity verification passed. No Android native-runtime or 16 KB runtime claim is made here.

## Remaining defects and release blockers

- **P1, confirmed in uploaded version 13:** the fatal HTTP cancellation crash. The version-14 code path is patched and passes local regression checks; Android verification remains BLOCKED.
- **BLOCKED mandatory runtime gate:** cancellation in Play management, return to Owner, poor connectivity, reopening, and server-confirmed paid-access deadline on the affected phone. The shared HTTP engine also needs the sign-in/online-sale/upload smoke checks described above.
- **P2 diagnostic limitation:** some Google Places Compose mappings could not be collected. The normal R8 mapping exists; this warning limits diagnostics for those third-party Compose frames.
- **P3 cleanup:** 85 nonfatal lint warnings remain as enumerated above. This fix does not resolve those unrelated warnings.
- **NOT VERIFIED:** current Console upload-certificate match, version-14 availability, Play-distributed runtime, and the broader original production/compliance gates. This focused investigation does not establish them.

No additional P0 defect was established within this focused investigation. This statement is not a full-app security or production-readiness clearance.

## Required Android retest before open testing

Use the fixed build on the affected phone with a Play license-test subscription. Start from active paid access, open Google Play management, cancel renewal, and return to Owner. Verify no crash, a responsive billing screen, cancelled renewal with access retained until the verified period end, and correct state after reopening the app. Repeat the return with connectivity unavailable and during a slow refresh; a connection error must remain recoverable. Verify renewal/resume and expiration do not grant paid access from local state alone.

The owner's reported sales, ordering, account, subscription, and cancellation tests are useful user validation, but this focused crash investigation is not a fresh verification of every gate in the broader production audit.
