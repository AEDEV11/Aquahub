# Cutover plan

Status: `PLANNED`.

Use bounded module cutovers with a rollback window: Firebase read/write → optional validated dual-write → Supabase read with Firebase fallback → Supabase read/write → remove only the migrated Firebase business dependency. Keep Firebase initialization, Analytics, Crashlytics, FCM, App Check, and unreplaced Functions operational. Do not delete Firestore collections, Firebase users, Functions, or Storage objects as part of a cutover.

