# AquaHub Owner Android Build and Lint Report — 2026-10-17

## Configuration

- Application/namespace: `com.aesprt.aquahub`; module config at `AquaHub/app/google-services.json`.
- Firebase project/package IDs match `aquahub-506411` / Owner app. There is one Owner Android client, one web OAuth client and five SHA-associated Android OAuth clients. The generated AAB signer matches the configured local upload-keystore alias, but its fingerprint has not been compared with the Play Console upload certificate.
- Production variant: `productionRelease`; QA variant: `qaDebug`.
- Version code 12, version name `aq-1.0.7`, min SDK 26, target/compile SDK 37.
- Release build uses R8 and resource shrinking. Production release validation now checks the expected Supabase project origin, valid P-256 offline POS verifier public key, and an existing upload keystore with alias and passwords. It is wired before production R8/package tasks.
- `OFFLINE_POS_VERIFY_PUBLIC_KEY` is standard Base64 X.509 SubjectPublicKeyInfo DER for the P-256 public key derived from the same pair as Edge's `OFFLINE_POS_SIGNING_PUBLIC_JWK`. It is not a Firebase key, Play key, Supabase anon key, JWK JSON, or private key. The key and existing upload-keystore settings are now present in local `AquaHub/local.properties`; their values were not printed or added to source control.

## Commands and evidence

| Command | Result | Evidence/limit |
|---|---|---|
| `./gradlew :app:testQaDebugUnitTest :app:assembleQaDebug --no-daemon --max-workers=2` | PASS — 190 tests, 0 failures, 0 errors, 0 skipped; APK assembled. | `AquaHub/app/build/outputs/apk/qa/debug/app-qa-debug.apk` (45,362,456 bytes at build). |
| `./gradlew :app:lintQaDebug --no-daemon --max-workers=1` | PASS — 0 errors, 87 warning-level findings, 7m59s. | Final source/resource run: `AquaHub/app/build/reports/lint-results-qaDebug.sarif`. Rules: 37 `UseKtx`, 35 `Typos`, 9 `UnusedResources`, 2 `ExifInterface`, 2 `IconLocation`, 1 `ExportedReceiver`, 1 `ObsoleteSdkInt`. |
| `./gradlew :app:lintProductionRelease --no-daemon --max-workers=1` | PASS — 0 errors, 85 warning-level findings, 7m10s; Google Services and release Kotlin compilation succeeded. | Final source/resource run: `AquaHub/app/build/reports/lint-results-productionRelease.sarif`. Rules: 36 `UseKtx`, 35 `Typos`, 9 `UnusedResources`, 2 `ExifInterface`, 2 `IconLocation`, 1 `ObsoleteSdkInt`. |
| `./gradlew :app:bundleProductionRelease --no-daemon --max-workers=2` | PASS — production AAB built and signed; 20m41s. | `AquaHub/app/build/outputs/bundle/productionRelease/app-production-release.aab`; 16,313,034 bytes; SHA-256 `3fbda6a0e1ddeba342ca97dc3c9b087659a368b0d24687d6dcf096fcbfcaecb3`. |
| AAB signature and archive | PASS with limits. `jarsigner -verify` returned 0; AAB certificate SHA-256 `B7:B3:84:B2:64:9C:D2:22:00:F0:A5:36:66:FF:23:3A:50:E8:EB:08:6E:88:60:2E:A8:39:DC:FD:09:3D:E1:AD` matches the configured keystore alias. `unzip -t` found no archive errors. | Strict `jarsigner` mode reports the self-signed certificate chain as untrusted by the local JVM and notes no signature timestamp. Play Console upload acceptance and certificate comparison were not performed. |
| AAB manifest and R8 outputs | PASS. Package `com.aesprt.aquahub`, version code 12, version name `aq-1.0.7`, min SDK 26, target SDK 37. R8 mapping and configuration outputs exist. | Standalone `bundletool validate` was unavailable: the cached bundletool is a library jar without its runtime dependencies. Gradle's bundle packaging task completed successfully. |
| Native ELF alignment in generated AAB | PASS for all 8 packaged `.so` files across `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`; every `PT_LOAD` alignment is at least `0x4000`. | Static ELF inspection does not replace runtime testing on a 16 KB page-size device. |
| `git diff --check` | PASS. | No whitespace errors. |

Earlier bundle attempts failed before packaging because local release configuration was missing; a still earlier AAB was unsigned and removed. After the verifier key and existing upload signing configuration became available locally, the latest production bundle command succeeded. Release compilation emitted Firebase messaging deprecation warnings and Compose mapping warnings from Google Places; the build completed successfully.

To derive the Android value from an existing public JWK file, import it with WebCrypto as `ECDSA` / `P-256`, export it as `spki`, then standard-Base64 encode those DER bytes. Put the result (without quotes) in `AquaHub/local.properties` as `OFFLINE_POS_VERIFY_PUBLIC_KEY=...`. Keep `OFFLINE_POS_SIGNING_PRIVATE_JWK` only in Supabase Edge secrets. If the original keypair is lost, generate a new pair and set both Edge secrets (`OFFLINE_POS_SIGNING_PRIVATE_JWK` and `OFFLINE_POS_SIGNING_PUBLIC_JWK`) together before building; do not rotate one side alone.

## Lint triage

The previous hard failure was a stale `CrashlyticsInitProvider` class reference in the QA manifest (`MissingClass`); it was removed. The auth recovery intent filters were separated and redundant Kotlin constructs corrected. Remaining advisory findings are mostly `UseKtx`, `ExifInterface`, `Typos` caused by base64 trust-anchor resource strings, a QA/debug-only exported receiver, redundant SDK/resource observations, and unused legacy colors/logo resources. The two logo PNGs are unreferenced by app code; resource shrinking is enabled. Unused generated launcher XMLs were removed, and the app's shared logo moved to `drawable-nodpi`. No broad lint suppression was added. Advisory warnings remain and are not represented as “all fixed.”

## Remaining release work

Confirm the configured Android verifier key matches the production Edge signing public key, compare the AAB certificate fingerprint with the Play Console upload certificate, and have the owner validate/upload the AAB in Play Console. No upload or publication was performed. Run a 16 KB page-size device test and finish the billing, device, and workflow gates. Do not generate or replace an upload key automatically.
