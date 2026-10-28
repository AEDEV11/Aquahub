# AquaHub Owner Google Play Readiness Checklist — 2026-10-17

| Requirement | Result | Evidence / next action |
|---|---|---|
| Target API | PASS | App target SDK 37; Google Play currently requires API 36+ for new apps/updates. Verify Console acceptance at upload. |
| Production application ID | PASS | Gradle production app ID `com.aesprt.aquahub`; verify against existing Play listing before upload. |
| Versioning | NOT VERIFIED | Local version code 12, name `aq-1.0.7`; Play Console current version code/listing access unavailable. Increment if already used. |
| Firebase project/package | PASS | `google-services.json` in app module points to expected Owner package/project. |
| Firebase OAuth SHA | NOT VERIFIED | Existing signer unavailable; compare upload and Play app-signing fingerprints with OAuth clients. |
| Signed AAB / Play App Signing | NOT VERIFIED | Local production AAB was built and signed; its certificate matches the configured keystore alias. Compare the SHA-256 fingerprint with Play Console's upload certificate and verify upload acceptance; no upload was attempted. |
| Billing products/base plans/prices | NOT VERIFIED | No Console catalog access. Confirm IDs, monthly/annual base plans, localized pricing, acknowledgement, replacement and RTDN mapping. App should use Play `ProductDetails` pricing. |
| Billing purchase lifecycle | NOT VERIFIED | No license-tester workflows. Test pending, success, restore, renewal, cancellation, grace/hold, refund/revoke, expiry, multi-device, duplicate/out-of-order RTDN. |
| Account deletion in-app | NOT VERIFIED | Source path is Settings > Delete AquaHub Account; deletion reauthentication bug fixed locally. Test user verification, pending sync, data effects and failures. |
| External account deletion URL | FAIL | Local `account-deletion.html` corrected; custom URL returned 403 in this environment and old Firebase URL still showed stale copy. Deploy and verify anonymously over HTTPS, then enter URL in Console. |
| Privacy policy | NOT VERIFIED | Local policy aligned; production page/Console URL not verified. Confirm public accessible policy and Data Safety consistency. |
| Data Safety / app content / permissions / app access | NOT VERIFIED | Play Console inaccessible. Reconcile declarations with actual Auth, business records, marketing, analytics, notifications and deletion behavior. |
| Store listing / production access / test track | NOT VERIFIED | No Play Console access; confirm listing, reviewer credentials/instructions, testing access and release track. |
| Native libraries / 16 KB page size | NOT VERIFIED | All 8 libraries in the generated AAB have `PT_LOAD` alignment at least `0x4000`; runtime on a 16 KB device/emulator was not performed. Standalone bundletool validation was unavailable. |
| Staged rollout | NOT VERIFIED | A local signed AAB exists, but no Play Console track check or upload was performed. After all gates pass, begin conservatively and monitor purchase, sync, crash and inventory integrity before expansion. |

## Official policy references

- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en)
- [Google Play Billing backend integration](https://developer.android.com/google/play/billing/backend)
- [Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)

No Play Console submission, upload, public publication, or rollout change was made.
