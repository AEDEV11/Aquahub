# Offline and synchronization plan

Status: `PLANNED`.

The owner app remains Room-first. The existing transactional outbox, sync metadata, cursors, WorkManager constraints, retries, and local↔remote ID mappings remain during migration. A Supabase repository must first be introduced behind the same repository boundary, with dual-read/dual-write only after conflict semantics are specified and tested.

The customer app keeps its current Room profile/address cache and intentionally non-durable in-memory cart/onboarding/scheduling behavior until a separate requirement changes it.

