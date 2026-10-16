# Owner Google Play Compliance Audit

## Phase 3 release evidence update — 2026-10-12

The current source declares `targetSdk=37`, `minSdk=26`, `versionCode=12`, `versionName=aq-1.0.7`, package `com.aesprt.aquahub`, and Play Billing Library 9.1.0. Published Google Play guidance requires API 36 or higher for submissions after August 31, 2026; API 37 meets that threshold. Billing Library 9 remains supported until August 31, 2028. See [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) and [Billing deprecation timeline](https://developer.android.com/google/play/billing/deprecation-faq).

Google’s 16 KB guidance applies to apps targeting API 35+ for relevant Play submissions; the available emulator is API 35 but reports an actual 4096-byte page size. Therefore a 16 KB runtime test was **not** performed. Current production release lint/R8/AAB completed successfully in 23m47s with 93 lint warnings and 0 errors. Bundletool 1.18.3 validation, manifest/config dump, `zipalign -c -P 16 -v 4` and 8-library ELF PT_LOAD alignment all passed. AAB SHA-256 is `b503c5727bc6c53dc02c62c35be8da97a4ea824b718bbc44708d6d2dbf1aca2c`; bundle is unsigned and the local mapping upload task was excluded. These static checks cannot substitute for a 16 KB runtime test. See [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes).

No Play Console session, signing key, upload certificate, license purchase, listing submission or production rollout was used. Play App Signing match, candidate provenance, pricing/base plans, Data Safety, privacy and deletion disclosures, reviewer access, content rating, audience/ads, countries, pre-launch report, Android vitals, and developer/package registration remain owner-only NOT VERIFIED gates. Google’s Android developer verification guidance says Play-distributed app package names must be registered; the stated September 30, 2026 deadline has passed. Confirm `com.aesprt.aquahub` appears under the intended verified developer account in Play Console. See [Play Console package registration guidance](https://developer.android.com/developer-verification/guides/google-play-console?hl=en). Current Data Safety and account deletion policies: [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) and [account deletion](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).


## Remediation update — 2026-10-11

| Check | Current result | Evidence / boundary |
|---|---|---|
| Release endpoint guard | PASS | Production pre-build validates HTTPS and exact production Supabase host; QA uses isolated config and has a release-only non-production host guard. Synthetic HTTP production config failed before packaging. |
| Historical 2026-10-11 production AAB | PASS for local generation and bundle structure | That build's `bundleProductionRelease` completed locally; `bundletool validate` and manifest inspection passed. Artifact was unsigned locally and is not proven to be the Console upload artifact. SHA-256: `7466898d08cc585f1c7e901599ba4da5750a295f879e15b5b2a0ad7a35e2507d`. The current 2026-10-12 AAB identity is in the Phase 3 section above. |
| R8/mapping | PASS for local R8 output; remote handling NOT VERIFIED | Release minification completed and mapping file generated locally. Compose mapping warnings refer to Google Places internals. Final approved local run excluded `uploadCrashlyticsMappingFileProductionRelease`; no mapping upload was initiated by that run. An earlier audit build did execute the Crashlytics mapping task; see the test report. |
| 16 KB native alignment | PASS for static ELF load-segment alignment | Fresh AAB contains 8 native libraries with zero misaligned PT_LOAD segments across arm64-v8a, armeabi-v7a, x86 and x86_64. Bundle config requests `PAGE_ALIGNMENT_16K`; 16 KB runtime/device smoke remains NOT VERIFIED. |
| Play signing/provenance | NOT VERIFIED | No Play Console certificate access or configured release signing key was used; local AAB is unsigned. No AAB upload to Play occurred. |
| Console declarations and developer registration | NOT VERIFIED | Data Safety, privacy/account deletion, app access, target audience, ads, content rating, price/base-plan, listing, countries, pre-launch report, Android vitals and developer/package registration require owner evidence. |

The current Android target is API 37. Starting August 31, 2026, new apps and updates must target API 36 or higher; Billing Library 9 is supported until August 31, 2028. The fresh AAB requests 16 KB page alignment. Console declarations and Play review remain unverified. Sources: [target API requirements](https://developer.android.com/google/play/requirements/target-sdk), [Billing deprecation timeline](https://developer.android.com/google/play/billing/deprecation-faq?hl=en), and [16 KB page guidance](https://developer.android.com/guide/practices/page-sizes). No Play AAB upload occurred.

**Result: NOT VERIFIED / release blocked pending Console evidence.** Review date 2026-10-11. No production release or Play Console mutation was performed.

## Baseline technical checks before remediation

Current results in the remediation table above supersede stale AAB/configuration entries below.

| Check | Result | Evidence |
|---|---|---|
| Application ID | PASS in source | `com.aesprt.aquahub` production package. |
| Target SDK | PASS in source | Current production source targets API 37, above the verified 2026 API 36 requirement. Official policy: [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en). |
| Minimum SDK / supported devices | NOT VERIFIED | Source value exists; no device catalog or supported-country evaluation. |
| Billing library | PASS in source | Billing 9.1.0; official [release notes](https://developer.android.com/google/play/billing/release-notes?authuser=9) and [deprecation FAQ](https://developer.android.com/google/play/billing/deprecation-faq?hl=en) reviewed. No Play purchase transaction. |
| AAB | NOT VERIFIED | Existing `app/release/app-release.aab` is pre-existing modified artifact; not proven from current source. A fresh release bundle check was running/awaiting final evidence in this audit. |
| Signing / Play App Signing | NOT VERIFIED | No Gradle release `signingConfig` found. Play Console upload and app-signing certificates unavailable. |
| R8 / mapping file | NOT VERIFIED | Release lint/R8 check status recorded in test report; mapping-file upload/retention not verified. |
| 16 KB pages | PARTIAL | Final-source AAB: 8 native libraries, 0 misaligned PT_LOAD segments; bundle config requests PAGE_ALIGNMENT_16K. Artifact is unsigned; Play Console provenance and 16 KB runtime test remain unverified. See [Android 16 KB page guidance](https://developer.android.com/guide/practices/page-sizes). Google Play enforcement for unsupported updates is scheduled from 2027-02-01. |
| 64-bit | PARTIAL | Existing AAB contains arm64-v8a and x86_64, plus 32-bit ABIs; provenance not established. |
| Debug/test endpoints | NOT VERIFIED | Flavor config is not flavor-bound for Supabase URL/key; see BUILD-01. |
| Permissions | PARTIAL | INTERNET, location, network state and notifications in source; location/notification disclosure and runtime rationale need review. |

## Console and policy checklist

All items below are **NOT VERIFIED** because Play Console access/evidence was unavailable. Google Play requires Data Safety declarations to cover app and SDK data handling and align with the privacy policy; account-creating apps need an in-app and external deletion path. See the official [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [Data Safety form guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en), and [review access instructions](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en). The public listing URL returned 404 to anonymous lookup; that does not establish a policy failure or current availability.

- Play Console policy status, rejected/warning items, package ownership and production eligibility details; verify `com.aesprt.aquahub` is registered to the intended verified developer account under current Android developer verification requirements.
- Data Safety answers and data categories/processors aligned to Supabase, Firebase diagnostics/push, location and account deletion.
- Published privacy URL and its accuracy. Local policy source contains stale Firebase security-rules text.
- In-app deletion plus external web deletion request path and Data Safety deletion answer. Local `aquahub-website/account-deletion.html` says production should implement deletion; hosted page could not be validated. See [Google Play account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
- App access/reviewer credentials and instructions, content rating, ads declaration, target audience, functionality declaration, store listing screenshots/descriptions, release notes, supported countries and distribution.
- Subscription product IDs/prices/offers/trials and tax/price regions; pre-launch report; Android vitals; internal closed-test purchase/restore results.
- Closed-test-to-production install/update and data migration path, signing certificate continuity, versionCode/versionName, staged rollout configuration.

## Store listing accuracy

Owner source contains dashboard, orders/POS, inventory, customers, reports, expenses, staff, delivery, settings and subscription screens. Presence of a screen does not prove the advertised workflow is complete. Browser-based customer ordering is a separate web flow and does not require a Customer APK. Do not advertise fully offline POS, automated refunds/settlement, complete analytics, or any unverified capability.

## Manual evidence owner must supply

In Play Console, inspect **Policy and programs → Policy status**, **App content** (Data Safety, privacy policy, account deletion, app access, ads, target audience, content rating), **Monetize with Play → Products → Subscriptions**, **Testing → Pre-launch report**, **Quality → Android vitals**, **Release → App bundle explorer/App integrity**, and **Reach and devices → Device catalog**. Provide redacted screenshots/export or direct owner attestation for each. No release submission is authorized by this audit.
