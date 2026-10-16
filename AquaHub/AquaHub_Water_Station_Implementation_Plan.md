# AquaHub Water Station App — Complete Implementation Plan

## 1. Project Overview

**AquaHub Water Station** is the merchant/owner-side Android application for water refilling stations. The application manages the operational lifecycle of a water delivery business: authentication, station profile, product pricing, incoming customer orders, preparation, rider assignment, delivery monitoring, customer records, container inventory, sales reports, and settings.

The Android app will be built with **Jetpack Compose + Kotlin**, following **MVVM + Clean Architecture**, with clear separation between presentation, domain, and data layers. Local persistence will use **Room**, while the remote backend will be exposed through a REST/WebSocket API (recommended: Spring Boot + PostgreSQL).

### Primary Goals

- Give water station owners a real-time operations dashboard.
- Reduce manual order handling through calls, SMS, Messenger, and paper logs.
- Track orders from acceptance through delivery completion.
- Assign and monitor riders efficiently.
- Track refill containers, returned empties, damaged containers, and customer-held containers.
- Maintain customer order history and repeat-order data.
- Provide sales and operational analytics.
- Support offline-first reads and controlled synchronization.
- Keep business logic testable and independent from Android UI/framework code.

---

# 2. Core Water Station Flow

## 2.1 Main Owner Flow

```text
Launch App
   ↓
Authentication Check
   ↓
Login
   ↓
Station Initialization
   ↓
Dashboard
   ↓
Incoming Orders
   ↓
Open Order Details
   ↓
Accept / Reject
   ↓
Preparing
   ↓
Ready for Pickup
   ↓
Assign Rider
   ↓
Rider Assigned
   ↓
Out for Delivery
   ↓
Delivered
   ↓
Payment Verification
   ↓
Order Completed
   ↓
Update:
- Sales
- Customer history
- Rider stats
- Container inventory
- Reports
```

## 2.2 Secondary Management Flows

```text
Dashboard
 ├── Orders
 ├── Products / Pricing
 ├── Riders
 ├── Customers
 ├── Container Inventory
 ├── Reports & Sales
 └── Settings
```

---

# 3. Recommended Technology Stack

## Android

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- ViewModel
- StateFlow / SharedFlow
- Kotlin Coroutines
- Koin Dependency Injection
- Room
- DataStore Preferences
- Retrofit
- OkHttp
- Kotlin Serialization or Gson/Moshi
- WorkManager
- Firebase Cloud Messaging
- Google Maps Compose
- Play Services Location
- WebSocket (OkHttp or STOMP depending on backend)

## Backend Recommendation

- Spring Boot 3.x
- Java 21
- Spring Security
- Spring Data JPA
- PostgreSQL
- PostGIS
- Redis
- WebSocket
- Firebase Admin SDK
- Flyway
- Docker

---

# 4. Clean Architecture

The project must follow this dependency rule:

```text
Presentation → Domain ← Data
```

The **domain layer must not depend on Android, Retrofit, Room, or Compose**.

## 4.1 Package Structure

```text
com.aquahub.station
│
├── app/
│   ├── AquaHubApplication.kt
│   └── MainActivity.kt
│
├── core/
│   ├── common/
│   │   ├── Result.kt
│   │   ├── AppError.kt
│   │   ├── DispatcherProvider.kt
│   │   └── Constants.kt
│   │
│   ├── designsystem/
│   │   ├── theme/
│   │   ├── components/
│   │   ├── typography/
│   │   └── icons/
│   │
│   ├── network/
│   │   ├── NetworkMonitor.kt
│   │   ├── AuthInterceptor.kt
│   │   └── ApiResultMapper.kt
│   │
│   ├── database/
│   │   ├── AquaHubDatabase.kt
│   │   ├── converters/
│   │   └── migration/
│   │
│   ├── navigation/
│   │   ├── AppNavGraph.kt
│   │   └── Routes.kt
│   │
│   └── util/
│
├── domain/
│   ├── model/
│   ├── repository/
│   └── usecase/
│
├── data/
│   ├── local/
│   │   ├── dao/
│   │   ├── entity/
│   │   └── mapper/
│   │
│   ├── remote/
│   │   ├── api/
│   │   ├── dto/
│   │   └── websocket/
│   │
│   ├── repository/
│   └── sync/
│
└── feature/
    ├── auth/
    ├── dashboard/
    ├── orders/
    ├── products/
    ├── riders/
    ├── customers/
    ├── inventory/
    ├── reports/
    └── settings/
```

---

# 5. Domain Models

Domain models are pure Kotlin data classes.

## 5.1 User / Station Owner

```kotlin
data class StationOwner(
    val id: String,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String?,
    val stationId: String,
    val role: OwnerRole,
    val active: Boolean
)

enum class OwnerRole {
    OWNER,
    MANAGER,
    STAFF
}
```

## 5.2 Water Station

```kotlin
data class WaterStation(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String?,
    val phone: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val openingTime: String,
    val closingTime: String,
    val status: StationStatus,
    val logoUrl: String?,
    val deliveryRadiusKm: Double,
    val minimumOrderAmount: Double,
    val baseDeliveryFee: Double
)

enum class StationStatus {
    OPEN,
    CLOSED,
    BUSY,
    TEMPORARILY_UNAVAILABLE,
    SUSPENDED
}
```

## 5.3 Product

```kotlin
data class Product(
    val id: String,
    val stationId: String,
    val name: String,
    val description: String?,
    val category: ProductCategory,
    val containerSizeLiters: Double?,
    val price: Double,
    val stockQuantity: Int,
    val active: Boolean,
    val imageUrl: String?,
    val updatedAt: Long
)

enum class ProductCategory {
    REFILL,
    NEW_CONTAINER,
    EMPTY_CONTAINER,
    ACCESSORY,
    OTHER
}
```

## 5.4 Customer

```kotlin
data class Customer(
    val id: String,
    val fullName: String,
    val phone: String,
    val email: String?,
    val defaultAddress: CustomerAddress?,
    val totalOrders: Int,
    val lifetimeValue: Double,
    val lastOrderAt: Long?
)
```

## 5.5 Order

```kotlin
data class Order(
    val id: String,
    val orderNumber: String,
    val stationId: String,
    val customerId: String,
    val riderId: String?,
    val deliveryAddress: DeliveryAddress,
    val items: List<OrderItem>,
    val subtotal: Double,
    val deliveryFee: Double,
    val discount: Double,
    val totalAmount: Double,
    val paymentMethod: PaymentMethod,
    val paymentStatus: PaymentStatus,
    val status: OrderStatus,
    val note: String?,
    val scheduledDeliveryAt: Long?,
    val createdAt: Long,
    val acceptedAt: Long?,
    val readyAt: Long?,
    val pickedUpAt: Long?,
    val deliveredAt: Long?
)
```

## 5.6 Order Item

```kotlin
data class OrderItem(
    val id: String,
    val orderId: String,
    val productId: String,
    val productName: String,
    val productImageUrl: String?,
    val unitPrice: Double,
    val quantity: Int,
    val subtotal: Double,
    val containerTransactionType: ContainerTransactionType?
)
```

## 5.7 Rider

```kotlin
data class Rider(
    val id: String,
    val stationId: String,
    val fullName: String,
    val phone: String,
    val vehicleType: VehicleType,
    val plateNumber: String?,
    val online: Boolean,
    val availability: RiderAvailability,
    val latitude: Double?,
    val longitude: Double?,
    val activeOrderCount: Int,
    val rating: Double,
    val lastLocationAt: Long?
)
```

---

# 6. Order State Machine

Do not allow arbitrary status updates.

## 6.1 Statuses

```kotlin
enum class OrderStatus {
    PENDING,
    ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    RIDER_ASSIGNED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,
    REJECTED,
    CANCELLED
}
```

## 6.2 Valid Transitions

```text
PENDING
 ├── ACCEPTED
 ├── REJECTED
 └── CANCELLED

ACCEPTED
 ├── PREPARING
 └── CANCELLED

PREPARING
 ├── READY_FOR_PICKUP
 └── CANCELLED

READY_FOR_PICKUP
 ├── RIDER_ASSIGNED
 └── CANCELLED

RIDER_ASSIGNED
 ├── OUT_FOR_DELIVERY
 └── CANCELLED

OUT_FOR_DELIVERY
 └── DELIVERED

DELIVERED
 └── COMPLETED
```

## 6.3 Domain Validator

```kotlin
class ValidateOrderTransitionUseCase {
    operator fun invoke(
        current: OrderStatus,
        target: OrderStatus
    ): Boolean {
        val allowed = mapOf(
            OrderStatus.PENDING to setOf(
                OrderStatus.ACCEPTED,
                OrderStatus.REJECTED,
                OrderStatus.CANCELLED
            ),
            OrderStatus.ACCEPTED to setOf(
                OrderStatus.PREPARING,
                OrderStatus.CANCELLED
            ),
            OrderStatus.PREPARING to setOf(
                OrderStatus.READY_FOR_PICKUP,
                OrderStatus.CANCELLED
            ),
            OrderStatus.READY_FOR_PICKUP to setOf(
                OrderStatus.RIDER_ASSIGNED,
                OrderStatus.CANCELLED
            ),
            OrderStatus.RIDER_ASSIGNED to setOf(
                OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.CANCELLED
            ),
            OrderStatus.OUT_FOR_DELIVERY to setOf(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED to setOf(OrderStatus.COMPLETED)
        )

        return target in allowed[current].orEmpty()
    }
}
```

The backend must enforce the same state machine. Android validation is UX protection only; server validation is authoritative.

---

# 7. Room Database Design

Room serves as the local source for cached station data, orders, customers, products, riders, inventory, and dashboard summaries.

## 7.1 Database

```kotlin
@Database(
    entities = [
        StationEntity::class,
        ProductEntity::class,
        CustomerEntity::class,
        CustomerAddressEntity::class,
        OrderEntity::class,
        OrderItemEntity::class,
        RiderEntity::class,
        ContainerInventoryEntity::class,
        ContainerMovementEntity::class,
        PaymentEntity::class,
        SyncQueueEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AquaHubDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
    abstract fun productDao(): ProductDao
    abstract fun customerDao(): CustomerDao
    abstract fun orderDao(): OrderDao
    abstract fun riderDao(): RiderDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun paymentDao(): PaymentDao
    abstract fun syncQueueDao(): SyncQueueDao
}
```

---

# 8. Local Database Tables

## 8.1 stations

```text
stations
--------
id TEXT PK
owner_id TEXT
name TEXT
description TEXT
phone TEXT
address TEXT
latitude REAL
longitude REAL
opening_time TEXT
closing_time TEXT
status TEXT
logo_url TEXT
delivery_radius_km REAL
minimum_order_amount REAL
base_delivery_fee REAL
updated_at INTEGER
```

## 8.2 products

```text
products
--------
id TEXT PK
station_id TEXT INDEX
name TEXT
description TEXT
category TEXT
container_size_liters REAL
price REAL
stock_quantity INTEGER
active INTEGER
image_url TEXT
updated_at INTEGER
sync_status TEXT
```

## 8.3 customers

```text
customers
---------
id TEXT PK
full_name TEXT
phone TEXT INDEX
email TEXT
total_orders INTEGER
lifetime_value REAL
last_order_at INTEGER
updated_at INTEGER
```

## 8.4 customer_addresses

```text
customer_addresses
------------------
id TEXT PK
customer_id TEXT INDEX
label TEXT
address_line TEXT
barangay TEXT
city TEXT
province TEXT
postal_code TEXT
latitude REAL
longitude REAL
is_default INTEGER
```

## 8.5 orders

```text
orders
------
id TEXT PK
order_number TEXT UNIQUE
station_id TEXT INDEX
customer_id TEXT INDEX
rider_id TEXT NULL INDEX
address_snapshot TEXT
latitude REAL
longitude REAL
subtotal REAL
delivery_fee REAL
discount REAL
total_amount REAL
payment_method TEXT
payment_status TEXT
status TEXT INDEX
note TEXT
scheduled_delivery_at INTEGER
created_at INTEGER
accepted_at INTEGER
ready_at INTEGER
picked_up_at INTEGER
delivered_at INTEGER
updated_at INTEGER
sync_status TEXT
```

## 8.6 order_items

```text
order_items
-----------
id TEXT PK
order_id TEXT INDEX
product_id TEXT
product_name TEXT
product_image_url TEXT
unit_price REAL
quantity INTEGER
subtotal REAL
container_transaction_type TEXT NULL
```

## 8.7 riders

```text
riders
------
id TEXT PK
station_id TEXT INDEX
full_name TEXT
phone TEXT
vehicle_type TEXT
plate_number TEXT
online INTEGER
availability TEXT
latitude REAL NULL
longitude REAL NULL
active_order_count INTEGER
rating REAL
last_location_at INTEGER
updated_at INTEGER
```

## 8.8 container_inventory

```text
container_inventory
-------------------
id TEXT PK
station_id TEXT INDEX
container_type TEXT
size_liters REAL
total_owned INTEGER
available_full INTEGER
available_empty INTEGER
with_customers INTEGER
with_riders INTEGER
damaged INTEGER
lost INTEGER
updated_at INTEGER
```

## 8.9 container_movements

```text
container_movements
-------------------
id TEXT PK
station_id TEXT
order_id TEXT NULL
customer_id TEXT NULL
rider_id TEXT NULL
container_type TEXT
movement_type TEXT
quantity INTEGER
created_at INTEGER
note TEXT
```

Movement types:

```text
FILLED
SENT_TO_CUSTOMER
EMPTY_COLLECTED
RETURNED_TO_STATION
DAMAGED
LOST
PURCHASED_NEW
ADJUSTMENT
```

## 8.10 payments

```text
payments
--------
id TEXT PK
order_id TEXT UNIQUE
provider TEXT
provider_reference TEXT
amount REAL
payment_method TEXT
status TEXT
created_at INTEGER
paid_at INTEGER
updated_at INTEGER
```

## 8.11 sync_queue

```text
sync_queue
----------
id TEXT PK
resource_type TEXT
resource_id TEXT
operation TEXT
payload TEXT
retry_count INTEGER
created_at INTEGER
last_attempt_at INTEGER
```

---

# 9. DAO Interfaces

## Order DAO

```kotlin
@Dao
interface OrderDao {

    @Query("SELECT * FROM orders ORDER BY created_at DESC")
    fun observeAll(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE status = :status ORDER BY created_at ASC")
    fun observeByStatus(status: String): Flow<List<OrderEntity>>

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :orderId")
    fun observeOrderWithItems(orderId: String): Flow<OrderWithItemsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(orders: List<OrderEntity>)

    @Query("UPDATE orders SET status = :status, updated_at = :updatedAt WHERE id = :orderId")
    suspend fun updateStatus(orderId: String, status: String, updatedAt: Long)
}
```

---

# 10. Repository Contracts — Domain Layer

Repository interfaces belong in `domain/repository`.

## 10.1 AuthRepository

```kotlin
interface AuthRepository {
    suspend fun login(phone: String, password: String): Result<StationOwner>
    suspend fun logout(): Result<Unit>
    fun observeSession(): Flow<StationOwner?>
    suspend fun refreshSession(): Result<StationOwner>
}
```

## 10.2 StationRepository

```kotlin
interface StationRepository {
    fun observeStation(): Flow<WaterStation?>
    suspend fun refreshStation(): Result<Unit>
    suspend fun updateStation(station: WaterStation): Result<Unit>
    suspend fun updateStationStatus(status: StationStatus): Result<Unit>
}
```

## 10.3 OrderRepository

```kotlin
interface OrderRepository {
    fun observeOrders(): Flow<List<Order>>
    fun observeOrders(status: OrderStatus): Flow<List<Order>>
    fun observeOrder(orderId: String): Flow<Order?>

    suspend fun refreshOrders(): Result<Unit>
    suspend fun acceptOrder(orderId: String): Result<Order>
    suspend fun rejectOrder(orderId: String, reason: String): Result<Order>
    suspend fun markPreparing(orderId: String): Result<Order>
    suspend fun markReadyForPickup(orderId: String): Result<Order>
    suspend fun assignRider(orderId: String, riderId: String): Result<Order>
    suspend fun markOutForDelivery(orderId: String): Result<Order>
    suspend fun markDelivered(orderId: String): Result<Order>
    suspend fun completeOrder(orderId: String): Result<Order>
}
```

## 10.4 ProductRepository

```kotlin
interface ProductRepository {
    fun observeProducts(): Flow<List<Product>>
    suspend fun refreshProducts(): Result<Unit>
    suspend fun createProduct(product: ProductDraft): Result<Product>
    suspend fun updateProduct(product: Product): Result<Product>
    suspend fun updateStock(productId: String, quantity: Int): Result<Unit>
    suspend fun setAvailability(productId: String, active: Boolean): Result<Unit>
}
```

## 10.5 RiderRepository

```kotlin
interface RiderRepository {
    fun observeRiders(): Flow<List<Rider>>
    fun observeAvailableRiders(): Flow<List<Rider>>
    suspend fun refreshRiders(): Result<Unit>
    suspend fun addRider(draft: RiderDraft): Result<Rider>
    suspend fun updateRider(rider: Rider): Result<Rider>
    suspend fun setRiderActive(riderId: String, active: Boolean): Result<Unit>
}
```

## 10.6 InventoryRepository

```kotlin
interface InventoryRepository {
    fun observeContainerInventory(): Flow<List<ContainerInventory>>
    fun observeContainerMovements(): Flow<List<ContainerMovement>>
    suspend fun refreshInventory(): Result<Unit>
    suspend fun recordMovement(movement: ContainerMovementDraft): Result<Unit>
    suspend fun adjustInventory(adjustment: ContainerAdjustment): Result<Unit>
}
```

---

# 11. Repository Implementations — Data Layer

Repository implementations coordinate local DAO + remote API.

Example:

```kotlin
class OrderRepositoryImpl @Inject constructor(
    private val api: OrderApi,
    private val orderDao: OrderDao,
    private val mapper: OrderMapper,
    private val networkMonitor: NetworkMonitor,
    private val dispatcherProvider: DispatcherProvider
) : OrderRepository {

    override fun observeOrders(): Flow<List<Order>> =
        orderDao.observeAll()
            .map { entities -> entities.map(mapper::toDomain) }

    override suspend fun refreshOrders(): Result<Unit> =
        withContext(dispatcherProvider.io) {
            runCatching {
                val remote = api.getOrders()
                orderDao.upsertAll(remote.map(mapper::toEntity))
            }
        }

    override suspend fun acceptOrder(orderId: String): Result<Order> =
        withContext(dispatcherProvider.io) {
            runCatching {
                val response = api.acceptOrder(orderId)
                val entity = mapper.toEntity(response)
                orderDao.upsert(entity)
                mapper.toDomain(entity)
            }
        }

    // Other operations follow the same server-authoritative pattern.
}
```

### Repository Rule

For server-owned state such as orders, payments, rider assignment, and delivery status:

```text
UI action
   ↓
Use case
   ↓
Repository
   ↓
Remote API success
   ↓
Update Room
   ↓
Room Flow updates UI
```

Do not optimistically finalize money-sensitive or order-state actions without server confirmation.

---

# 12. Use Cases

Each use case handles one business operation.

## Auth

```text
LoginUseCase
LogoutUseCase
ObserveSessionUseCase
```

## Dashboard

```text
ObserveDashboardSummaryUseCase
RefreshDashboardUseCase
GetTodaysSalesUseCase
GetPendingOrderCountUseCase
```

## Orders

```text
ObserveOrdersUseCase
ObserveOrderDetailsUseCase
RefreshOrdersUseCase
AcceptOrderUseCase
RejectOrderUseCase
StartPreparingOrderUseCase
MarkOrderReadyUseCase
AssignRiderUseCase
MarkOutForDeliveryUseCase
MarkDeliveredUseCase
CompleteOrderUseCase
ValidateOrderTransitionUseCase
```

## Products

```text
ObserveProductsUseCase
CreateProductUseCase
UpdateProductUseCase
UpdateProductStockUseCase
ToggleProductAvailabilityUseCase
```

## Riders

```text
ObserveRidersUseCase
ObserveAvailableRidersUseCase
FindBestRiderUseCase
AssignRiderUseCase
AddRiderUseCase
```

## Customers

```text
ObserveCustomersUseCase
SearchCustomersUseCase
ObserveCustomerDetailsUseCase
GetCustomerOrderHistoryUseCase
```

## Inventory

```text
ObserveContainerInventoryUseCase
ObserveContainerMovementsUseCase
RecordContainerMovementUseCase
ProcessDeliveredContainerExchangeUseCase
AdjustContainerInventoryUseCase
```

## Reports

```text
GetDailySalesUseCase
GetWeeklySalesUseCase
GetMonthlySalesUseCase
GetTopProductsUseCase
GetDeliveryPerformanceUseCase
GetRiderPerformanceUseCase
```

---

# 13. Rider Assignment Algorithm

For a single water station, rider assignment can remain simple but should still consider availability and workload.

## 13.1 Candidate Filtering

```text
All riders
   ↓
stationId matches
   ↓
online == true
   ↓
availability == AVAILABLE
   ↓
activeOrderCount < configured maximum
   ↓
valid recent GPS location
   ↓
Candidate riders
```

## 13.2 Distance Calculation

Use Haversine distance for the MVP.

```text
distance = haversine(
    station.lat,
    station.lng,
    rider.lat,
    rider.lng
)
```

## 13.3 Scoring

```text
normalizedDistance = min(distanceKm / maxRadiusKm, 1.0)
workloadScore = min(activeOrderCount / maxConcurrentOrders, 1.0)
ratingPenalty = 1.0 - (rating / 5.0)
staleLocationPenalty = locationAgePenalty

finalScore =
    normalizedDistance * 0.50 +
    workloadScore      * 0.30 +
    ratingPenalty      * 0.10 +
    staleLocationPenalty * 0.10
```

Lowest score wins.

## 13.4 Domain Use Case

```kotlin
class FindBestRiderUseCase {
    operator fun invoke(
        station: WaterStation,
        riders: List<Rider>
    ): Rider? {
        return riders
            .asSequence()
            .filter { it.online }
            .filter { it.availability == RiderAvailability.AVAILABLE }
            .filter { it.latitude != null && it.longitude != null }
            .map { rider ->
                val distance = haversineKm(
                    station.latitude,
                    station.longitude,
                    rider.latitude!!,
                    rider.longitude!!
                )

                val distanceScore = (distance / 10.0).coerceIn(0.0, 1.0)
                val workloadScore = (rider.activeOrderCount / 3.0).coerceIn(0.0, 1.0)
                val ratingPenalty = 1.0 - (rider.rating / 5.0).coerceIn(0.0, 1.0)

                val score =
                    distanceScore * 0.50 +
                    workloadScore * 0.30 +
                    ratingPenalty * 0.20

                rider to score
            }
            .minByOrNull { it.second }
            ?.first
    }
}
```

The backend should remain authoritative for final assignment to prevent race conditions.

---

# 14. Container Inventory Algorithm

This is one of AquaHub's differentiating features.

## Example Delivery

Customer order:

```text
2 × 5-gallon refill
Customer returns 2 empty containers
```

Before delivery:

```text
available_full -= 2
with_riders += 2
```

After successful delivery:

```text
with_riders -= 2
with_customers += 2
```

If rider collects 2 empties:

```text
with_customers -= 2
available_empty += 2
```

If the customer's containers are customer-owned, do not add them into station-owned `with_customers` counts. Use a container ownership field.

## Container Ownership

```kotlin
enum class ContainerOwnership {
    STATION,
    CUSTOMER
}
```

## Reconciliation Rule

For every station-owned container type:

```text
totalOwned =
    availableFull
  + availableEmpty
  + withCustomers
  + withRiders
  + damaged
  + lost
```

If reconciliation fails, flag inventory inconsistency.

---

# 15. Delivery Fee Algorithm

MVP rule:

```text
baseDeliveryFee = ₱20
freeDeliveryRadius = 2 km
additionalRate = ₱5/km
```

Formula:

```text
if distance <= freeDeliveryRadius:
    fee = baseDeliveryFee
else:
    fee = baseDeliveryFee + ceil(distance - freeDeliveryRadius) * additionalRate
```

The backend must calculate and return the authoritative delivery fee.

---

# 16. Dashboard Calculation

Dashboard should be generated from Room for instant rendering, then refreshed from server.

## Dashboard Model

```kotlin
data class DashboardSummary(
    val todaysOrders: Int,
    val pendingOrders: Int,
    val preparingOrders: Int,
    val outForDeliveryOrders: Int,
    val completedOrders: Int,
    val todaysSales: Double,
    val unpaidAmount: Double,
    val availableRiders: Int,
    val lowStockProducts: Int,
    val overdueContainers: Int
)
```

Room can calculate these values using SQL aggregation queries.

---

# 17. Presentation Architecture

Each feature follows:

```text
Compose Screen
   ↓ emits UiAction
ViewModel
   ↓ invokes
Use Case
   ↓ calls
Repository Interface
   ↓ implemented by
RepositoryImpl
   ↓
Room / Retrofit / WebSocket
```

UI observes only immutable `UiState`.

---

# 18. UI State Pattern

Use a consistent state/event/action approach.

## Example Order Details

```kotlin
data class OrderDetailsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val order: Order? = null,
    val availableRiders: List<Rider> = emptyList(),
    val actionInProgress: OrderAction? = null,
    val errorMessage: String? = null
)
```

## Actions

```kotlin
sealed interface OrderDetailsAction {
    data object Refresh : OrderDetailsAction
    data object AcceptOrder : OrderDetailsAction
    data class RejectOrder(val reason: String) : OrderDetailsAction
    data object StartPreparing : OrderDetailsAction
    data object MarkReady : OrderDetailsAction
    data class AssignRider(val riderId: String) : OrderDetailsAction
    data object MarkOutForDelivery : OrderDetailsAction
    data object MarkDelivered : OrderDetailsAction
}
```

## One-Time Events

```kotlin
sealed interface OrderDetailsEvent {
    data class ShowSnackbar(val message: String) : OrderDetailsEvent
    data object OrderAccepted : OrderDetailsEvent
    data object OrderRejected : OrderDetailsEvent
    data object RiderAssigned : OrderDetailsEvent
}
```

Use `StateFlow` for durable UI state and `SharedFlow` or `Channel` for one-time events.

---

# 19. ViewModel Example

```kotlin
@HiltViewModel
class OrderDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeOrderDetails: ObserveOrderDetailsUseCase,
    private val acceptOrder: AcceptOrderUseCase,
    private val rejectOrder: RejectOrderUseCase,
    private val markReady: MarkOrderReadyUseCase,
    private val assignRider: AssignRiderUseCase
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle["orderId"])

    private val _uiState = MutableStateFlow(OrderDetailsUiState())
    val uiState: StateFlow<OrderDetailsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<OrderDetailsEvent>()
    val events: SharedFlow<OrderDetailsEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            observeOrderDetails(orderId).collect { order ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        order = order,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun onAction(action: OrderDetailsAction) {
        when (action) {
            OrderDetailsAction.AcceptOrder -> accept()
            is OrderDetailsAction.RejectOrder -> reject(action.reason)
            OrderDetailsAction.MarkReady -> ready()
            is OrderDetailsAction.AssignRider -> assign(action.riderId)
            else -> Unit
        }
    }

    private fun accept() = viewModelScope.launch {
        _uiState.update { it.copy(actionInProgress = OrderAction.ACCEPT) }

        acceptOrder(orderId)
            .onSuccess {
                _events.emit(OrderDetailsEvent.OrderAccepted)
            }
            .onFailure { error ->
                _events.emit(
                    OrderDetailsEvent.ShowSnackbar(
                        error.message ?: "Unable to accept order"
                    )
                )
            }

        _uiState.update { it.copy(actionInProgress = null) }
    }
}
```

---

# 20. Screen Navigation

```text
Splash
  ↓
Login
  ↓
Dashboard
  ├── Orders
  │    ├── Order List
  │    └── Order Details
  │          ├── Accept/Reject
  │          ├── Prepare
  │          ├── Ready
  │          ├── Assign Rider
  │          └── Delivery Progress
  │
  ├── Products
  │    ├── Product List
  │    ├── Add Product
  │    └── Edit Product
  │
  ├── Riders
  │    ├── Rider List
  │    └── Rider Details
  │
  ├── Customers
  │    ├── Customer List
  │    └── Customer Details
  │
  ├── Inventory
  │    ├── Container Dashboard
  │    └── Movement History
  │
  ├── Reports
  │
  └── Settings
```

---

# 21. Compose Screen Design

## 21.1 Dashboard

### Header

- Station name
- Current station status
- Notification icon
- Profile/menu

### KPI Cards

```text
Today's Orders
Pending
Out for Delivery
Completed
Today's Sales
```

### Quick Actions

```text
New Order
Inventory
Customers
Reports
```

### Current Orders

Show active statuses only:

```text
PENDING
ACCEPTED
PREPARING
READY_FOR_PICKUP
RIDER_ASSIGNED
OUT_FOR_DELIVERY
```

## 21.2 Order List

Tabs:

```text
New
Preparing
On the Way
Completed
Cancelled
```

Each order card contains:

```text
Order number
Customer
Scheduled time
Product summary
Amount
Payment method
Status badge
```

## 21.3 Order Details

Sections:

```text
Order header
Customer details
Delivery address + map button
Product items
Container exchange details
Payment
Customer note
Order timeline
Primary action
```

Primary action changes based on status.

Example:

```text
PENDING → Accept Order
ACCEPTED → Start Preparing
PREPARING → Mark Ready for Pickup
READY_FOR_PICKUP → Assign Rider
RIDER_ASSIGNED → Monitor Rider
OUT_FOR_DELIVERY → Track Delivery
DELIVERED → Complete Order
```

---

# 22. Design System

## Brand Colors

```text
Primary Blue       #0A63F6
Aqua               #13C7E8
Deep Navy          #07366D
Success            #16A86B
Warning            #F6A623
Error              #D83B3E
Background         #F7FAFF
Surface            #FFFFFF
Text Primary       #12213A
Text Secondary     #697386
Border             #E3EAF3
```

## Shape

```text
Small radius       8dp
Standard cards     12dp
Large cards        16dp
Buttons             12dp
Bottom sheets      24dp top corners
```

## Spacing Scale

```text
4dp
8dp
12dp
16dp
24dp
32dp
```

## Typography

- Material 3 typography
- Bold headings
- Medium-weight amounts/status labels
- Avoid excessive font sizes
- Use tabular number styling where useful for sales amounts

## Status Colors

```text
PENDING             Orange
ACCEPTED            Blue
PREPARING           Purple/Blue
READY_FOR_PICKUP    Cyan
RIDER_ASSIGNED      Indigo
OUT_FOR_DELIVERY    Teal
DELIVERED           Green
COMPLETED           Dark Green
CANCELLED           Gray
REJECTED            Red
```

Do not rely on color alone; always display status text/icon.

---

# 23. Offline-First Strategy

## Read Operations

Use Room as the observable source of truth.

```text
Screen
 ↓
Room Flow renders cached data immediately
 ↓
Repository refreshes server
 ↓
Room updated
 ↓
UI automatically updates
```

## Critical Write Operations

Require remote confirmation for:

```text
Accept/reject order
Order state change
Assign rider
Payment update
Delivery completion
Inventory finalization
```

If offline:

```text
"Internet connection is required to update this order."
```

Low-risk changes such as draft product edits may optionally be queued, but MVP should keep writes server-confirmed to avoid reconciliation complexity.

---

# 24. Synchronization

## Startup Sync

After login:

```text
Fetch station
Fetch products
Fetch active orders
Fetch riders
Fetch customers summary
Fetch inventory
Fetch dashboard aggregates
```

## Foreground Refresh

- Pull-to-refresh
- refresh on app resume after configurable stale interval
- WebSocket events for new orders and status updates

## Background Sync

Use WorkManager for:

```text
Periodic product refresh
Customer summary refresh
Inventory verification
Failed non-critical sync retries
```

Do not use WorkManager for second-by-second tracking.

---

# 25. Real-Time Event Flow

Recommended WebSocket events:

```text
ORDER_CREATED
ORDER_CANCELLED
ORDER_PAYMENT_UPDATED
RIDER_LOCATION_UPDATED
RIDER_STATUS_UPDATED
DELIVERY_STATUS_UPDATED
```

Example:

```text
Customer places order
      ↓
Backend creates order
      ↓
WebSocket ORDER_CREATED
      ↓
Station app receives event
      ↓
Repository refreshes/saves order
      ↓
Room emits new order
      ↓
Dashboard + Orders screen update
      ↓
FCM notification as fallback
```

---

# 26. Notifications

Water station notifications:

```text
New order received
Customer cancelled order
Online payment confirmed
Rider arrived at station
Delivery completed
Container return overdue
Product low stock
```

Notification deep links should navigate directly to the relevant order or screen.

---

# 27. Security

## Android

- Never hardcode backend secrets.
- Store session token securely.
- Use HTTPS only.
- Attach access token through OkHttp interceptor.
- Clear credentials on logout.
- Never trust locally calculated price/payment/status as authoritative.

## Backend

- JWT/session authentication
- Role authorization
- Station-scoped access checks
- Audit order state changes
- Validate every state transition
- Validate rider belongs to station
- Verify payment through provider webhook
- Rate-limit authentication and mutation endpoints

---

# 28. Error Model

Use a domain-level error model.

```kotlin
sealed interface AppError {
    data object NoInternet : AppError
    data object Unauthorized : AppError
    data object Forbidden : AppError
    data object NotFound : AppError
    data object Conflict : AppError
    data class Validation(val message: String) : AppError
    data class Server(val code: Int?, val message: String?) : AppError
    data class Unknown(val cause: Throwable?) : AppError
}
```

Convert network/DB exceptions inside the data layer.

Do not expose Retrofit exceptions directly to ViewModels.

---

# 29. Testing Strategy

## Unit Tests

Prioritize domain logic:

```text
ValidateOrderTransitionUseCaseTest
FindBestRiderUseCaseTest
CalculateDeliveryFeeUseCaseTest
ProcessContainerExchangeUseCaseTest
DashboardSummaryCalculatorTest
```

## Repository Tests

```text
OrderRepositoryImplTest
ProductRepositoryImplTest
InventoryRepositoryImplTest
```

Use fake API + in-memory Room database.

## ViewModel Tests

```text
DashboardViewModelTest
OrderDetailsViewModelTest
InventoryViewModelTest
```

Verify:

- loading states
- action states
- error messages
- events
- state updates from Flow

## UI Tests

Critical user journeys:

```text
Login → Dashboard
Open pending order → Accept
Prepare → Ready
Assign rider
Complete delivery
Add/edit product
Inventory adjustment
```

---

# 30. Backend API Contract Recommendation

## Authentication

```text
POST /api/v1/auth/login
POST /api/v1/auth/logout
POST /api/v1/auth/refresh
```

## Station

```text
GET    /api/v1/stations/me
PATCH  /api/v1/stations/me
PATCH  /api/v1/stations/me/status
```

## Products

```text
GET    /api/v1/stations/me/products
POST   /api/v1/stations/me/products
PATCH  /api/v1/stations/me/products/{productId}
PATCH  /api/v1/stations/me/products/{productId}/stock
PATCH  /api/v1/stations/me/products/{productId}/availability
```

## Orders

```text
GET    /api/v1/stations/me/orders
GET    /api/v1/stations/me/orders/{orderId}
POST   /api/v1/stations/me/orders/{orderId}/accept
POST   /api/v1/stations/me/orders/{orderId}/reject
POST   /api/v1/stations/me/orders/{orderId}/prepare
POST   /api/v1/stations/me/orders/{orderId}/ready
POST   /api/v1/stations/me/orders/{orderId}/assign-rider
POST   /api/v1/stations/me/orders/{orderId}/out-for-delivery
POST   /api/v1/stations/me/orders/{orderId}/delivered
POST   /api/v1/stations/me/orders/{orderId}/complete
```

## Riders

```text
GET    /api/v1/stations/me/riders
POST   /api/v1/stations/me/riders
PATCH  /api/v1/stations/me/riders/{riderId}
```

## Customers

```text
GET /api/v1/stations/me/customers
GET /api/v1/stations/me/customers/{customerId}
GET /api/v1/stations/me/customers/{customerId}/orders
```

## Inventory

```text
GET  /api/v1/stations/me/container-inventory
GET  /api/v1/stations/me/container-movements
POST /api/v1/stations/me/container-movements
POST /api/v1/stations/me/container-adjustments
```

## Reports

```text
GET /api/v1/stations/me/reports/sales
GET /api/v1/stations/me/reports/products
GET /api/v1/stations/me/reports/riders
GET /api/v1/stations/me/reports/containers
```

---

# 31. Suggested Feature Modules

If the codebase grows, migrate feature packages into Gradle modules.

```text
:app
:core:common
:core:designsystem
:core:network
:core:database
:domain
:data
:feature:auth
:feature:dashboard
:feature:orders
:feature:products
:feature:riders
:feature:customers
:feature:inventory
:feature:reports
:feature:settings
```

Do not over-modularize on day one. Start with packages following these boundaries, then extract modules when build size and ownership justify it.

---

# 32. Development Phases

## Phase 1 — Foundation

- Project setup
- Hilt
- Navigation Compose
- Theme/design system
- Retrofit/OkHttp
- Room
- DataStore
- Result/error model
- Base network authentication
- Login
- Session restoration

### Definition of Done

```text
Owner can log in
Session persists
Dashboard shell opens
App survives process recreation
```

---

## Phase 2 — Station + Products

- Station profile
- Open/closed status
- Product list
- Add product
- Edit product
- Stock quantity
- Availability toggle
- Product caching

### Definition of Done

```text
Station can fully configure its sellable water products and prices.
```

---

## Phase 3 — Orders

- Order list
- Status tabs
- Order details
- Accept
- Reject
- Preparing
- Ready for pickup
- Order timeline
- WebSocket new order event
- FCM fallback notification

### Definition of Done

```text
A customer-created order can appear in the station app and progress to READY_FOR_PICKUP.
```

---

## Phase 4 — Riders

- Rider list
- Rider availability
- Rider location model
- Manual rider assignment
- Suggested best rider
- Rider status monitoring

### Definition of Done

```text
Station can assign an available rider to a ready order.
```

---

## Phase 5 — Delivery Completion

- Out-for-delivery state
- Live rider location view
- Delivered state
- Payment status
- Complete order
- Delivery history

### Definition of Done

```text
Complete order lifecycle works from PENDING to COMPLETED.
```

---

## Phase 6 — Customers + Containers

- Customer directory
- Customer details
- Order history
- Container inventory
- Container movements
- Customer-held container balances
- Damaged/lost adjustments
- Reconciliation warnings

### Definition of Done

```text
Station can determine where its containers are and which customers hold station-owned containers.
```

---

## Phase 7 — Reports

- Daily sales
- Weekly sales
- Monthly sales
- Top-selling products
- Rider performance
- Delivery times
- Container loss/damage
- Export-ready domain/report structures

---

## Phase 8 — Hardening

- Unit tests
- ViewModel tests
- Room migration tests
- UI tests
- API conflict handling
- Token refresh
- Offline behavior
- Retry policies
- Crash reporting
- Logging
- Performance profiling
- Release build

---

# 33. Recommended MVP

Build only the following first:

```text
Authentication
Dashboard
Station profile
Products/pricing
Incoming orders
Accept/reject
Prepare order
Ready for pickup
Rider list
Manual rider assignment
Out for delivery
Delivered
Completed
Basic customer history
Basic container inventory
Daily sales
```

Do **not** delay MVP for:

```text
Advanced analytics
Automatic route optimization
Multiple branches
Complex subscription plans
Loyalty program
AI forecasting
Automated rider dispatch
Full accounting
```

---

# 34. Clean Coding Rules

1. Composables do not call repositories directly.
2. ViewModels do not depend on Retrofit or Room DAO classes.
3. Domain models do not use Room/Retrofit annotations.
4. Repository interfaces live in the domain layer.
5. Repository implementations live in the data layer.
6. Mapping between DTO/entity/domain objects is explicit.
7. One use case should represent one business operation.
8. Do not duplicate order-state rules in every ViewModel.
9. Keep server-authoritative operations centralized in repositories/use cases.
10. Use immutable UI state.
11. Use `StateFlow` instead of manually managed mutable Compose state inside ViewModels.
12. Use `collectAsStateWithLifecycle()` in Compose.
13. Do not hardcode strings; use resources.
14. Do not hardcode dimensions repeatedly; use design tokens/components.
15. Keep business calculation functions deterministic and unit-testable.
16. Avoid generic `Utils` classes; name classes by responsibility.
17. Use interfaces where there is a real boundary, not automatically for every class.
18. Prefer composition over inheritance.
19. Use structured concurrency; avoid `GlobalScope`.
20. Treat Room schema migrations as production code.

---

# 35. End-to-End Reference Flow

```text
CUSTOMER BACKEND
creates order
      ↓
Spring Boot stores order: PENDING
      ↓
WebSocket + FCM
      ↓
AquaHub Station app receives notification
      ↓
OrderRepository refreshes order
      ↓
Room stores order
      ↓
Order list automatically updates
      ↓
Owner opens order
      ↓
AcceptOrderUseCase
      ↓
OrderRepository.acceptOrder()
      ↓
POST /orders/{id}/accept
      ↓
Server validates PENDING → ACCEPTED
      ↓
Server returns authoritative order
      ↓
Room updated
      ↓
UI shows ACCEPTED
      ↓
StartPreparingOrderUseCase
      ↓
PREPARING
      ↓
MarkOrderReadyUseCase
      ↓
READY_FOR_PICKUP
      ↓
ObserveAvailableRidersUseCase
      ↓
FindBestRiderUseCase suggests rider
      ↓
Owner confirms rider
      ↓
AssignRiderUseCase
      ↓
RIDER_ASSIGNED
      ↓
Rider pickup
      ↓
OUT_FOR_DELIVERY
      ↓
Rider tracking events
      ↓
DELIVERED
      ↓
Payment confirmed
      ↓
Container exchange processed
      ↓
CompleteOrderUseCase
      ↓
COMPLETED
      ↓
Sales/customer/rider/container reports updated
```

---

# 36. Final Architecture Summary

```text
┌─────────────────────────────────────────────┐
│              Jetpack Compose                │
│ Screens / Components / Navigation           │
└─────────────────────┬───────────────────────┘
                      │ UiAction
                      ▼
┌─────────────────────────────────────────────┐
│                  ViewModel                  │
│ StateFlow + Events                          │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│                  Use Cases                  │
│ Business rules / orchestration              │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│           Repository Interfaces             │
│                  Domain                     │
└─────────────────────┬───────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│         Repository Implementations          │
│                    Data                     │
└───────────────┬──────────────────┬──────────┘
                │                  │
                ▼                  ▼
        ┌──────────────┐    ┌──────────────┐
        │     Room     │    │ Retrofit /   │
        │ Local Cache  │    │ WebSocket    │
        └──────────────┘    └──────┬───────┘
                                  │
                                  ▼
                          ┌────────────────┐
                          │  Spring Boot   │
                          │ PostgreSQL     │
                          │ Redis/PostGIS  │
                          └────────────────┘
```

The most important architectural principle for AquaHub is that **Room drives the UI, the domain layer owns business rules, and the backend remains authoritative for shared transactional state** such as order transitions, payments, rider assignment, and container reconciliation.

---

# 37. Recommended First Coding Milestone

Implement this exact vertical slice before expanding the system:

```text
Login
  ↓
Dashboard
  ↓
Load pending orders from backend
  ↓
Persist in Room
  ↓
Display with Flow
  ↓
Open Order Details
  ↓
Accept Order
  ↓
Server validates transition
  ↓
Room updates
  ↓
UI updates automatically
  ↓
Mark Preparing
  ↓
Mark Ready for Pickup
```

Once this vertical slice is stable, add rider assignment, delivery, inventory, and analytics. This prevents building many disconnected screens before the core AquaHub business flow is proven.
