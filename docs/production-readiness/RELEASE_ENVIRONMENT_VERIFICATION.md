# Released environment verification

Snapshot: 2026-10-09. **Result: BLOCKED — production of record is not verified.** Inspection was read-only. No app was uninstalled, no app data was cleared, no linked database/function was changed, and no customer records or credentials were copied into this report.

## Evidence and confidence

| Evidence | Observation | What it proves / does not prove |
|---|---|---|
| Current Owner Gradle source | `com.aesprt.aquahub`, versionCode 12, versionName `aq-1.0.7`; current dirty tree configures Supabase business/auth repositories and Firebase messaging/analytics/crash reporting. | Describes this checkout only, not the binary in Play Closed Testing. |
| Current Customer Gradle source | `com.aesprt.aquahub_customer`, versionCode 3, versionName `aqc-1.0.2`; current dirty tree configures Supabase business/auth repositories and Firebase messaging. | Describes this checkout only, not a Play-installed release. |
| Connected Samsung SM-A366B, Android 16, user 0 | Customer package metadata reports versionCode 3 / `aqc-1.0.2`, installer package `null`, and same-day install. | Not evidence of Play installation/provenance; may be a local install. No Customer data was inspected. |
| Owner package in Secure Folder user 150 | Shell inspection was denied. | Installed Owner binary/version and installer remain NOT VERIFIED. No uninstall or data clearing attempted. |
| Linked Supabase metadata | Ref `pfkxrggexueltytxtqil`, project label AquahubDB, ACTIVE_HEALTHY, Postgres 17.11; nonempty business tables and 8 active hosted Edge Function versions were observed through authorized read-only metadata: `send-sms` v8, `staff-admin` v5, `send-fcm-notification` v5, `verify-play-purchase` v3, `subscription-trial` v2, `dispatch-notification-outbox` v3, `play-rtdn` v4, `delete-account` v1. | Confirms a reachable linked Supabase project and its reported function versions, not that it is production, staging, or used by either released app. Function source revisions were not matched to local commits. |
| Firebase configuration leads | Project id `aquahub-506411`; Firebase Messaging remains in checked-out apps; Firebase Hosting and messaging are distinct from business API authority. | Does not establish deployed Firestore/Auth/Functions usage. The Firebase project may still serve compatibility or notification paths. |
| Supabase CLI link | The workspace is linked to `pfkxrggexueltytxtqil`; the read-only linked migration list has 45 entries. | A CLI link is not production identity evidence. |
| CI/CD / Play provenance | No authorized Play release record, signed artifact provenance, or deployment record ties app revisions to the linked project/function revisions. | NOT VERIFIED. |

## Environment matrix

| Surface | Current checkout | Linked environment | Released-app authority |
|---|---|---|---|
| Owner business/auth API | Supabase code path | Project metadata reachable; Group 1–4 changes absent from linked migration history | NOT VERIFIED |
| Customer business/auth API | Supabase code path | Same linked project is a candidate, not proven shared release target | NOT VERIFIED |
| Firebase Auth/Firestore | Retained configuration/rules and migration remnants; current checkout uses Supabase for business/auth flows | Deployed project details not inspected/verified | NOT VERIFIED |
| Firebase Functions | Customer firebase.json declares a functions target, but the prior functions source is absent from this dirty checkout | Deployed function revision/source mapping NOT VERIFIED | NOT VERIFIED |
| FCM | Both current apps retain Firebase messaging integrations | Delivery configuration/targeting NOT VERIFIED | NOT VERIFIED |
| Website hosting | Firebase Hosting configuration is present separately | Hosted site linkage NOT inspected | NOT a proxy for mobile backend identity |
| Google Play billing | Local Owner uses a backend verifier/RTDN path | Hosted function names exist, but local-to-deployed revisions and Play Console products are unverified | NOT VERIFIED |

## Required evidence to unblock

1. Obtain Closed Testing release records for both package IDs: version code/name, artifact hash/signing identity, release track, and source revision.
2. Inspect the exact installed package metadata/provenance without removing app data; request access to Owner's Secure Folder context or inspect the release artifact separately.
3. Match release build endpoint/project identifiers to the authorized Supabase/Firebase projects without exposing client secrets. Establish Auth provider, database, storage, realtime, messaging, and Functions separately.
4. Obtain deployment revision/checksum evidence for Edge Functions and Firebase Functions and reconcile account/business/station/order/payment lineage using synthetic or approved anonymized data.
5. Label environments through authorized release records; do not infer production from the Supabase CLI link or project health.

Until then: do not deploy migration changes, restore compatibility grants, delete Firebase paths, or call the linked project “production”.

## Group 4 direct-to-production evidence refresh — 2026-10-09

Read-only Play Console inspection and local artifact inspection add evidence, but do **not** establish that the linked project is the production-of-record:

| Evidence | Observation | Conclusion |
|---|---|---|
| Owner Play Console app | `Aquahub - Owner` (`com.aesprt.aquahub`) is listed on Closed testing; its Production track is inactive and production access is still under review. The app-list audience showed 11 installed testers and last update Oct 4, 2026. | The inspected app is not released through the Production track. |
| Owner Closed testing Alpha summary | Release name `11 (aq-1.0.6)`, serving since Sep 27, 2026 at 6:36 AM. The release-detail route did not finish rendering its artifact details. | Establishes a closed-test release label/time only; exact served bundle identity, hash, endpoint and source revision remain NOT VERIFIED. |
| Customer Play Console app | `AquaHub – Water Delivery` (`com.aesprt.aquahub_customer`) is listed on Closed testing; app-list audience showed 0 installed testers and last update Sep 27, 2026. | Exact Customer track release/version and artifact details were not obtained; NOT VERIFIED. |
| Locally available signed Owner AAB | Local artifact reports package versionCode 12 / `aq-1.0.7`; signature verification succeeded; SHA-256 `3b08c7d922fa236f72ba81c4f5ae2d1dc8dc2e5dc4441b651edf13da9053fdfb`. Its embedded Supabase project ref matches `pfkxrggexueltytxtqil`. | Identifies only this local artifact. It differs from the visible Owner Alpha release label and cannot be equated with the Play-served bundle. The existing AAB was not rebuilt or overwritten during this check. |
| Locally available signed Customer AAB | Local artifact reports package versionCode 3 / `aqc-1.0.2`; signature verification succeeded; SHA-256 `d8f00354e949f1f1d2a168773a2d455f2faa2418ddf7799294e4a4ff29d59a74`. Its embedded Supabase project ref matches `pfkxrggexueltytxtqil`. | Identifies only this local artifact. Customer Play track bundle hash/version and source revision remain NOT VERIFIED. |
| Supabase target and source inputs | Both local `local.properties` files and the two inspected local AABs reference `pfkxrggexueltytxtqil`; Management API labels it `AquahubDB`, `ap-southeast-2`, `ACTIVE_HEALTHY`. | Strong evidence this is the intended project for the current local builds, but not proof it serves the Play Closed Testing binaries. Do not perform a remote write on this evidence alone. |

### Deployment gate

**Direct-to-production preflight: NO-GO / STOP BEFORE REMOTE MUTATION.** The release track is now visible, but artifact provenance for both apps is still missing, Owner's visible closed-test release does not match the locally available Owner AAB version, and Customer's exact served release is unknown. The linked Supabase schema is materially divergent from the local migrations, its generated schema diff contains destructive/drop statements, and no restorable backup of the linked project's database plus Auth/Storage state has been validated. No exact safe migration subset or function deployment list can be approved from the current evidence.

The production-linked backup was intentionally **not** exported: the target remains unverified and a dump would contain nonpublic customer/business data. Only the disposable empty local database was dumped for format/access-mode rehearsal; those files are not production data, not encrypted off-site, and not a validated production recovery set. No linked DB migration, history repair, Edge Function deployment, Firebase action, Play setting, or live data operation occurred.
