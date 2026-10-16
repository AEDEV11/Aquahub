# Products and categories migration

Status: `IMPLEMENTED — Supabase-primary catalog repository; Room cache; Firestore compatibility mirror; device validation blocked by entitlement prerequisite`

## Current Firestore model

Owner-app products are stored under:

`businesses/{businessId}/stations/{stationId}/products/{productId}`

The actual fields are `id`, `stationId`, `name`, `productType`, `sizeLabel`, `priceCentavos`, `stockQuantity`, `imagePath`, `isAvailable`, promotion fields (`promotionLabel`, `promotionType`, `promotionPercentBps`, `promotionalPriceCentavos`, `promotionMinimumQuantity`, `promotionStartsAt`, `promotionEndsAt`, `promotionIsActive`), `createdAt`, `clientUpdatedAt`, `isActive`, and `deletedAt`.

There is no Firestore category collection and no category table in the Android domain. The category contract is the `ProductType` enum: `REFILL`, `NEW_CONTAINER`, `EMPTY_CONTAINER`, and `OTHER`. Creating a new category table would invent a model not present in the application.

Product images remain Firebase Storage paths under the existing owner/station product path. Image binaries are explicitly not part of this phase. Product stock is also not cut over here because inventory and customer-order Functions still update it transactionally in Firestore.

## Supabase mapping

`public.products` already maps station ownership, `name`, `product_type`, `size_label`, description, integer-centavo `price_centavos`, stock, availability, promotion fields, timestamps, and `firebase_id`. Existing RLS uses `app_private.can_operate_station(station_id)` with `USING` and `WITH CHECK`, blocking cross-organization and unassigned-station access. Customer-facing public catalog projections remain separate and are not widened by this migration.

## Transition plan

`RoomProductRepository` now refreshes product catalog reads from Supabase before exposing Room flows. Catalog create/update/availability/archive writes are Supabase-first, then Room, then the existing Firestore compatibility queue using the preserved legacy document ID. The existing Firebase Storage `image_path` reference is retained; binaries are not moved in this phase. A temporary Firestore mirror remains required until the order Functions and customer projections consume Supabase product rows.

Stock is intentionally excluded from the catalog cutover. Product catalog updates omit `stock_quantity` on Supabase and retain the Room quantity; explicit stock changes continue through the existing Firebase-primary `STOCK_ADJUSTMENT` queue. This prevents a stale catalog edit from overwriting an order/inventory decrement. No inventory migration was started.

The same dry-run tool validates product type, integer-centavo pricing, promotion type, station ownership, duplicate legacy IDs, and preserved image references. No production apply was run because no authorized Firestore export was available.

Validation: Android `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`, and `compileReleaseKotlin` pass. Physical-device catalog CRUD remains blocked by the controlled account's `FREE` plan (`BASIC_INVENTORY=false`), so no product fixture was created and no entitlement was bypassed. Product centavo, offline hydration, mirror failure/retry, and concurrent stock/order checks remain unrun. Inventory migration remains explicitly out of scope.
