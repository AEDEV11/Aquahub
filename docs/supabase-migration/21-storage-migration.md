# Storage Migration

Status: `STORAGE_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`

## Audited asset classes

| Asset | Existing usage | Supabase bucket | Privacy | Status |
|---|---|---|---|---|
| Product images | Owner upload; customer catalog read | `product-images` | PUBLIC | New owner writes use Supabase; legacy Firebase reads remain |
| Station logo | Owner upload; public station projection | `station-assets` | PUBLIC | New owner writes use Supabase; legacy Firebase reads remain |
| GCash/Maya QR | Owner payment settings upload | `payment-assets` | PRIVATE, station-manager access | New owner writes use Supabase |
| Expense receipts | No implemented UI or stored records found | none | N/A | Not invented |
| Delivery proof | No implemented upload flow found | none | N/A | Not invented |
| Avatar/profile/document uploads | No owner/customer Storage flow found | none | N/A | Not invented |

Buckets and MIME/size constraints are deployed by `20261004164542_expenses_storage_foundation.sql`. Product/station assets use public buckets; payment assets require authenticated manager access. Object paths are tenant-scoped as `organizations/{organizationId}/stations/{stationId}/{area}/{entityId}-{random}.{extension}`. New database asset fields use `supabase://bucket/path`; signed URLs are never persisted. Payment QR and station-logo settings are written through the protected station-settings RPC. Legacy Firebase HTTPS URLs/paths remain read-only compatibility values until an approved import.

`SupabaseStorageDataSource` centralizes upload, public URL, signed URL, and delete operations. `StorageAssetResolver` is now the read boundary: `supabase://bucket/path` references resolve to public URLs for public buckets and cached short-lived signed URLs for private payment assets; HTTPS/`gs://` Firebase references remain compatibility reads. Uploads validate JPEG/PNG/WebP and 5 MB limits. No service-role key is used in Android.

`tools/supabase-migration/migrate-storage.mjs` consumes a reviewed manifest, validates source size/checksum/MIME/path/bucket, uploads idempotently, reports mappings, and never deletes Firebase objects. It requires `--apply --confirm-hosted-production`. It supports local exported source files because no Firebase Storage export credential is embedded in the repository.

No destructive object action, URL blind-rewrite, signed-URL persistence, private-object public policy, or expense/delivery receipt feature was introduced.
# Runtime dependency note

FCM/device-token ownership is now Supabase-based for the customer app. Firebase Storage remains read-only compatibility for legacy `gs://` assets through the resolver; the SDK cannot be removed until those reads are imported or represented by verified HTTPS/Supabase paths.

## Subscription and notification boundary

Payment QR paths remain private Supabase object paths resolved through the signed-URL resolver. Subscription verification never stores a purchase token in Storage or Android cache. Notification outbox payloads contain business metadata only; FCM credentials and dispatch secrets remain server-side and are not part of Storage migration.

The remaining Firebase Storage SDK removal blocker is the explicit legacy-read boundary in `AquaHub/app/src/main/java/com/aesprt/aquahub/data/supabase/storage/StorageAssetResolver.kt`, which still resolves legacy `gs://` references. The former `FirebaseImageStorageRepository` had zero callers and was removed. New product, station-logo, and payment-QR writes do not use Firebase Storage.

Subscription and notification work does not add Storage dependencies. The Firebase Storage SDK remains centralized in the two legacy-read boundaries above; the final removal prerequisite is a verified import/path manifest proving no active `gs://` asset needs Firebase Auth-backed resolution.
