# AquaHub source audit and Firebase migration report

Audit basis: the source in this repository on 2026-08-30. The implementation-plan Markdown file was treated as background only; Kotlin, Gradle, manifest, Room annotations, and tests were authoritative.

## A. Architecture before the migration

- One Gradle module: `:app`.
- UI: Jetpack Compose + Navigation Compose.
- Presentation: feature ViewModels using `StateFlow` and use cases.
- Domain: models, repository interfaces, use cases, subscription policy, report exporters.
- Data: Room repository implementations directly wrapping DAOs; Google Places/Geocoder repository; Preferences DataStore session/active-station state.
- DI: Koin modules for database, DataStore, repositories, use cases, and ViewModels.
- Database: Room 2.8.4, database schema version 2, 16 entities. `fallbackToDestructiveMigration(true)` was enabled and no explicit migration was registered.
- Firebase: Google Services plugin, `google-services.json`, Firebase BoM, and Analytics only. There was no Auth, Firestore, FCM, App Check, remote data source, outbox, cursor, worker, or connectivity monitor.
- Authentication: phone/password checked against `station_owners.password` in plaintext. DataStore held a Boolean login flag plus owner/station IDs.
- Multi-station: all stations were visible locally; active station ID was kept in DataStore. `Owner.stationId` acted as the primary/default station even though subscription code could create additional stations.
- Demo data: seeded only when “Explore demo” was selected. It included fixed IDs, products, customers, riders, containers, transactions, orders, payments, and a simulated Business subscription.
- Reports: generated locally from Room; CSV, Excel, and PDF exporters remain local.
- AI: no Aqua AI, Cohere, RAG, chatbot, embeddings, AI navigation, or AI entitlement code was present.

### Package map

- `core/common`: errors, constants, date/time and dispatcher helpers.
- `core/database`: Room database.
- `core/designsystem`, `ui/theme`: Compose UI system.
- `core/navigation`: root and nested navigation.
- `data/local/{entity,dao,mapper,seeder}`: Room persistence and demo seed.
- `data/repository`: Room-backed repositories and Google location implementation.
- `data/export`: local CSV/XLSX/PDF output.
- `domain/{model,repository,usecase,context,export}`: domain contracts and behavior.
- `feature/*`: auth, dashboard, orders, customers, products, inventory, riders, reports, settings, station management, location picker.

## B. Definitive pre-migration Room schema (version 2)

“FK” means a Room-declared foreign key, not merely a numeric relationship field.

| Room entity | Table | Primary key | Foreign keys / relationships | stationId? | Purpose | Repository / DAO | Cloud decision | Firestore target |
|---|---|---|---|---|---|---|---|---|
| `StationEntity` | `stations` | auto `Long id` | none | entity itself | station profile/location/hours | `RoomStationRepository` / `StationDao` | two-way | `businesses/{businessId}/stations/{stationRemoteId}` |
| `OwnerEntity` | `station_owners` | auto `Long id` | `stationId` was not an FK | yes | local owner profile, credential, default station | auth repository / `OwnerDao` | local profile cache; credential moves to Auth | `users/{uid}` + business member document; never password |
| `CustomerEntity` | `customers` | auto `Long id` | station relationship not FK | yes | customer profile and derived totals | `RoomCustomerRepository` / `CustomerDao` | two-way | station `customers` |
| `CustomerAddressEntity` | `customer_addresses` | auto `Long id` | FK `customerId -> customers.id`, cascade | through customer | address/location | customer repository / `CustomerDao` | two-way | station `customerAddresses` |
| `ProductEntity` | `products` | auto `Long id` | station relationship not FK | yes | sellable product, free-form dynamic `sizeLabel`, stock | `RoomProductRepository` / `ProductDao` | two-way | station `products` |
| `RiderEntity` | `riders` | auto `Long id` | station relationship not FK | yes | rider/vehicle/availability and low-frequency cached coordinates | `RoomRiderRepository` / `RiderDao` | two-way | station `riders` |
| `OrderEntity` | `orders` | auto `Long id` | customer/rider/address are numeric relationships but not FKs | yes | order header/status/timeline and price totals | `RoomOrderRepository` / `OrderDao` | two-way with transition conflict checks | station `orders` |
| `OrderItemEntity` | `order_items` | auto `Long id` | FK `orderId -> orders.id`, cascade | through order | immutable historical item/name/type/size/price snapshots | order repository / `OrderDao`, analytics via `OrderItemDao` | embedded with order | `orders/{id}.items[]` |
| `ContainerEntity` | `containers` | auto `Long id` | station relationship not FK | yes | reusable-container aggregate by free-form size label | `RoomInventoryRepository` / `ContainerDao` | two-way; movements authoritative | station `containerInventory` |
| `ContainerTransactionEntity` | `container_transactions` | auto `Long id` | station/customer/order numeric relationships, no FK | yes | append-style container ledger | inventory repository / `ContainerTransactionDao` | two-way immutable/idempotent | station `containerMovements` |
| `PaymentEntity` | `payments` | auto `Long id` | FK `orderId -> orders.id`, cascade | through order | operational order payment record | no domain repository; `PaymentDao` used by seed | two-way for supported cash payments | station `payments` |
| `SubscriptionPlanEntity` | `subscription_plans` | auto `Long id`; unique `code` | none | no | cached system plan catalog | `RoomSubscriptionPlanRepository` / `SubscriptionPlanDao` | future cloud-to-local | `subscriptionPlans/{planId}` |
| `PlanFeatureEntity` | `plan_features` | auto `Long id` | FK `planId`, cascade; unique plan+feature | no | cached feature configuration | plan repository / `SubscriptionPlanDao` | local seeded cache until trusted plan service exists | embedded/subcollection under `subscriptionPlans` |
| `PlanLimitEntity` | `plan_limits` | auto `Long id` | FK `planId`, cascade; unique plan+limit | no | cached plan limits | plan repository / `SubscriptionPlanDao` | local seeded cache until trusted plan service exists | embedded/subcollection under `subscriptionPlans` |
| `SubscriptionEntity` | `subscriptions` | auto `Long id` | FK station cascade; FK plan restrict | yes | cached/simulated station subscription state | `RoomSubscriptionRepository` / `SubscriptionDao` | server-to-local only in production | `businesses/{businessId}/subscription/current` |
| `SubscriptionHistoryEntity` | `subscription_history` | auto `Long id` | FK station cascade; subscription ID not FK | yes | simulated subscription event history | subscription repository / `SubscriptionHistoryDao` | server-to-local only in production | business subscription history |

No pre-migration Room tables existed for businesses, members/staff, dedicated container sizes, consumable inventory, inventory movements/reservations, water inventory/movements, booking intake, delivery records, provider billing, notifications, a sync queue, or sync cursors.

### Schema changes made

- Room is version 3.
- Explicit `1 -> 2` and `2 -> 3` migrations replace destructive fallback.
- `stations.placeId`, `station_owners.firebaseUid`, and `station_owners.businessId` are nullable, additive columns.
- `sync_metadata` maps installation-local `Long` IDs to UUID Firestore IDs and records versions/timestamps/tombstones.
- `sync_queue` is the durable outbox, including stable operation IDs, retry/error state, and order transition preconditions.
- `sync_cursors` stores per-business/station/resource high-water marks.
- These three sync tables are infrastructure/local-only and are never copied into Firestore.

## C. Workflow audit before Firebase work

| Workflow | Status discovered | Evidence / limitation |
|---|---|---|
| Authentication | WORKING locally, insecure | phone/password UX; plaintext Room password; Boolean DataStore session |
| Station setup | WORKING locally | onboarding creates station, owner, and trial; not one atomic operation |
| Multi-station switching | WORKING locally | DataStore active ID and station-scoped UI flows; ownership is not business-scoped in v2 |
| Products | WORKING | CRUD-ish add/edit/availability/stock; price is `Double` |
| Dynamic sizes | PARTIALLY WORKING | product/container size is free-form text, but inventory UI had hardcoded presets and no dedicated size metadata entity |
| Customers | WORKING after audit fix | list/search/create/detail; create previously discarded entered addresses; now persisted transactionally |
| Customer addresses | PARTIALLY WORKING | create is fixed; no full address edit UI |
| Walk-in POS | WORKING locally, transaction boundary improved | creates completed order and ledger rows; now wrapped in one Room transaction/outbox unit; no dedicated `PaymentEntity` creation |
| Booking orders | NOT IMPLEMENTED | UI can manage seeded/existing orders; no public/customer booking intake path |
| Order transitions | WORKING locally | state machine validates transitions; remote sync now carries expected previous status; rider assignment double-write fixed |
| Inventory reservation | NOT IMPLEMENTED | no entity/repository/use case |
| Consumables deduction | NOT IMPLEMENTED | `Product.stockQuantity` exists; no consumable ledger or reservation |
| Container borrowing | PARTIALLY WORKING | delivery ledger and aggregate adjustments exist; business invariants are not one domain-wide transaction in every screen flow |
| Container exchange | NOT IMPLEMENTED as a distinct operation | can be represented by separate return/delivery rows only |
| Container return | WORKING locally | customer return use case updates ledger and aggregate |
| Damaged/lost containers | PARTIALLY WORKING | adjustment UI/ledger exists; origin-state/invariant validation is incomplete |
| Riders | WORKING locally | create/update/availability and recommendation |
| Rider live location | PLACEHOLDER | latitude/longitude columns and seeded coordinates; no active tracking service; Realtime Database intentionally not added |
| Delivery | PARTIALLY WORKING | assignment/status timeline exists; no route tracking, proof, or separate delivery entity |
| Operational payments | PARTIALLY WORKING | entity/DAO and order fields exist; active POS does not insert payment row; no provider integration |
| Owner subscription billing | PLACEHOLDER / local simulation | local state machine, dev plan switch/reset/expiration actions; no PayMongo checkout/webhook or trusted server |
| Analytics | PARTIALLY WORKING | Room-based and offline; some delivery/walk-in classification is heuristic and average delivery time is fixed |
| Reports | WORKING locally | Room queries and CSV/XLSX/PDF exporters; entitlement cache is local |
| Maps/location | WORKING online with fallback | Places + device location + Geocoder; `placeId` is now preserved in station data |
| Demo data | WORKING and isolated | seeded only through demo action; demo has no Firebase session and therefore cannot enter the cloud outbox |
| Sync / connectivity / workers | NOT IMPLEMENTED before change | added in v3 |
| AI | NOT IMPLEMENTED | nothing active to disable; remains excluded |

### Offline operation policy

| Operation | Policy |
|---|---|
| View cached dashboard/stations/products/customers/orders/inventory/reports | FULLY OFFLINE SAFE |
| Switch among cached stations | FULLY OFFLINE SAFE |
| Add/edit station, customer, address, product, rider | OFFLINE QUEUEABLE after an authenticated Firebase session has been established |
| Walk-in sale | OFFLINE QUEUEABLE; Room/outbox first; stable order and movement remote IDs prevent duplicate replay |
| Local order transition | OFFLINE QUEUEABLE; cloud verifies expected previous status/version and records conflicts |
| Container return/adjustment | OFFLINE QUEUEABLE as immutable movement; cloud aggregate update is transactional |
| First login/register on a device | ONLINE REQUIRED |
| Google Places search / current online place detail | ONLINE REQUIRED; typed/manual cached station location remains usable |
| Subscription upgrade/payment, PayMongo operations | ONLINE REQUIRED and not implemented until trusted Functions/webhooks exist |
| Remote booking acceptance with concurrency guarantees | ONLINE RECOMMENDED; stale offline transition is rejected on reconciliation |

## D. Firebase mapping and conflict behavior

| Local resource | Cloud path | Direction | Conflict strategy | Offline support |
|---|---|---|---|---|
| Owner/session | Auth + `users/{uid}` + business member | Auth/profile both; credential Auth-only | Firebase UID authoritative | cached valid session/data |
| Station | business stations | two-way | expected remote version; cloud wins after recorded conflict | queueable |
| Customer | station customers | two-way | expected version; totals remain snapshots pending a future event model | queueable |
| Address | station customerAddresses | two-way | expected version | queueable |
| Product | station products | two-way | expected version; integer centavos remotely | queueable |
| Rider | station riders | two-way | expected version; current location is low-frequency only | queueable |
| Order + items | station orders with embedded item snapshots | two-way | remote version + expected previous status; queued transitions compact to latest valid target | queueable with conflict record |
| Container aggregate | station containerInventory | two-way | initial/base record versioned; stale aggregate replacement is not used for movements | queueable bootstrap |
| Container transaction | station containerMovements | two-way immutable | stable document/client-operation ID; Firestore transaction creates once and updates aggregate once | queueable/idempotent |
| Payment | station payments | two-way only for present supported records | versioned; rules restrict client creation to cash/COD | queueable where supported |
| Subscription tables | business subscription + global plans | intended cloud-to-local | server-authoritative; client writes denied | latest Room cache remains readable |
| Plan feature/limit tables | global plan configuration | intended cloud-to-local | trusted configuration only | local seeded cache |
| Sync infrastructure tables | none | local-only | n/a | essential to offline reliability |

Remote timestamps use Firestore server timestamps for `updatedAt`; device time is retained separately as `clientUpdatedAt`. Money is converted at the Firebase boundary from current Room `Double` values to `Long` centavos. A separate high-risk Room money migration was intentionally not bundled into this schema change.

## E. Firebase services added

- Firebase Authentication: preserves phone/password screen through a deterministic internal email alias. Email/password must be enabled in the Firebase console. Existing local users are upgraded once after their old local password validates, then the plaintext local credential is cleared.
- Cloud Firestore: business/member/station hierarchy and resource-specific remote data sources.
- Firebase Cloud Messaging: service and per-user device-token registration; feature-specific foreground notification presentation is not yet designed.
- Firebase App Check: debug provider in debug builds; Play Integrity provider in release builds.
- Firebase Analytics: already present and retained.
- Not added: Storage (no implemented cloud image upload workflow), Realtime Database (no active high-frequency rider GPS), Crashlytics, Functions, AI services.

## F. Synchronization implementation

1. Repository mutation and outbox entry commit in Room (walk-in operations share an outer Room transaction).
2. UI observes Room and updates immediately.
3. A unique, network-constrained `FirebaseSyncWorker` is enqueued. WorkManager retains it across process restart/reboot and runs it when connectivity is available.
4. Pushes are processed in outbox order. Existing-data migration enqueues dependencies first.
5. Firestore transactions enforce version/status preconditions. Container movement document IDs are idempotency keys. During first migration, historical movement documents are imported without reapplying deltas to the already-uploaded current aggregate.
6. Successful writes update sync metadata and remove the outbox row. Retryable failures remain `RETRY`; permission/validation/conflict failures remain visible as `FAILED`.
7. Incremental pulls run in dependency order using server timestamp cursors.
8. Remote DTOs are mapped into Room; Compose is updated only through Room `Flow`.

Existing local migration uses stable metadata UUIDs and can resume after a crash without creating a second cloud document. New devices authenticate, resolve their user/business, pull stations into Room, create a local owner cache, and then run normal incremental sync.

## G. Security model

`firestore.rules` requires authentication, active business membership, and station access. Owner/manager and staff capabilities are role-scoped. The current domain has operational rider records but no authenticated rider-account binding, so generic rider writes are intentionally denied. Cross-business access and unauthenticated access are denied. Subscription writes are denied to Android clients. Cash/COD payments are the only payment records staff may create directly. Deletion uses tombstone/archive writes; immutable movement rows cannot be edited/deleted.

`firebase-tests/firestore.rules.test.mjs` covers own-business access, cross-business denial, assigned versus unassigned staff stations, unauthenticated denial, and subscription-authority denial. Run it against the Emulator Suite after installing its pinned dev dependencies.

## H. AI exclusion

No AI dependency, navigation item, plan entitlement, Cohere client, RAG code, vector store, conversation table, or AI cloud function was added. Source search found no existing active AI implementation that required removal.

## I. Known risks and intentionally unfinished cloud domains

- Firebase console state (Auth provider, Firestore database/rules deployment, App Check registration, FCM credentials) cannot be completed by Android source alone.
- Subscription/PayMongo is not production billing. The existing UI remains a local simulation and security rules prevent it from asserting production subscription authority. Trusted Cloud Functions and webhook idempotency are a separate server-side deliverable.
- Booking intake/public QR ordering, consumable and water ledgers, reservations, staff accounts, proof of delivery, and live rider GPS do not exist in the current domain and were not invented during migration.
- Dedicated dynamic container-size metadata (volume/unit/shape) does not exist. Free-form size labels remain dynamic and hardcoded operational presets were removed, but a future entity is needed for normalized volume semantics.
- Container damage/loss origin-state validation and several cross-aggregate delivery operations need a dedicated domain transaction API before multi-writer use is safe.
- Firestore realtime listeners were not attached broadly. Reliable WorkManager push/pull is implemented first; targeted listeners for incoming bookings/subscription changes should be added only with lifecycle and cost limits once those cloud workflows exist.
- `POST_NOTIFICATIONS` runtime permission and a foreground notification design remain UI work; token registration is present.
- Emulator rule tests are supplied but require Node dependencies and the Firebase emulator binary; they are not Android unit tests.

## J. Verification record

| Task | Result |
|---|---|
| Pre-change `testDebugUnitTest` | PASSED (baseline) |
| `assembleDebug` (cold clean) | PASSED, APK produced |
| `lintDebug` | PASSED — 0 errors (pre-existing warnings only) |
| `testDebugUnitTest` (post-change) | PASSED — all unit tests including new Firebase DTO/money/sync identity tests |
| `assembleDebugAndroidTest` | PASSED — instrumentation test APK compiled successfully (BUILD SUCCESSFUL in 1m 40s) |

Latest continuation verification on 2026-08-30:

- `testDebugUnitTest`: PASSED (32 tests, 0 failures, 0 errors).
- `assembleDebug`: PASSED; debug APK produced at `app/build/outputs/apk/debug/app-debug.apk`.
- `assembleDebugAndroidTest`: PASSED (latest packaging run completed in 9s); instrumentation APK at `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- `lintDebug`: PASSED (0 errors, 99 warnings; existing resource/deprecation findings plus the new `menuAnchor` deprecation warning).
- Firebase rules test: NOT RUN; `npm test` exits 127 because the Firebase CLI is not installed in `firebase-tests`.

## K. Continuation changes (2026-08-30)

- Registration/setup is guarded by a process-local coroutine mutex and reuses an exact station draft (same normalized name, address, and contact) so rapid taps or retry-after-network-failure cannot create duplicate branches. The setup button was already disabled while loading; the use-case guard closes the remaining race.
- Main navigation now exposes Material3 pull-to-refresh. Refresh schedules the same durable Firebase sync worker; screens continue to render Room flows.

## Authentication configuration

- New owner registration sends an SMS with `PhoneAuthProvider.verifyPhoneNumber`, validates the code with Firebase before onboarding can continue, then links AquaHub's existing phone/password credential to the verified Firebase phone identity.
- Login now includes Google sign-in. Replace `default_web_client_id` in `app/src/main/res/values/strings.xml` with the Firebase project's **Web** OAuth client ID (the downloaded `google-services.json` currently contains no OAuth clients), and enable Google in Firebase Authentication.
- Enable Phone provider in Firebase Authentication and add the Android SHA-1/SHA-256 fingerprints so Play Integrity/reCAPTCHA app verification can complete on real devices. Use Firebase fictional phone numbers for emulator tests only.
- Real-user product creation has no seeded size or stock defaults. It requires a user-entered size/format, positive price, non-negative stock, and an explicit category (`Refill`, `New Container`, `Empty Container`, or `Other`). Demo seed/preview data remains unchanged.
- Inventory mutations now run inside the Room transaction runner. Walk-in return/refill updates the local container aggregate in the same transaction as the order and movement rows. Damage removes available stock before moving it to damaged; loss removes customer-held stock before moving it to lost. Invalid blank sizes and non-positive quantities are rejected.
- Research basis for the inventory guidance: Philippine DOH rules require washing and sanitizing containers before filling and separate filled/empty storage, while CDC guidance describes wash → sanitize → rinse/air-dry → refill. The UI now explains this operational cycle; dedicated sanitation-state columns/queues remain future work.
