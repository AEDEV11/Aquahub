# AquaHub Navigation

Sources: `core/navigation/Screen.kt`, `core/navigation/AppNavGraph.kt`, and `feature/main/MainContainerScreen.kt`.

## Root graph

```text
splash
├── intro_onboarding
├── login
├── registration_flow
│   ├── register/{planId}/{billingCycle}
│   ├── station_setup
│   └── subscription
└── main_container
    ├── bottom graph (dashboard/orders/inventory/customers/settings)
    └── root detail/management destinations
```

Actual route names/argument encoding must be taken from `Screen.kt`; do not duplicate raw strings in features.

## Main-container destinations

| Destination | Arguments/notes |
|---|---|
| Dashboard | Bottom navigation start. |
| Orders | Optional tab query argument. |
| Inventory | Bottom navigation. |
| Customers | Bottom navigation. |
| Settings | Bottom navigation. |
| Order detail | Numeric `orderId`. |
| Customer detail | Numeric `customerId`. |
| Products | Root operational screen/FAB shortcut. |
| Riders | Root operational screen/FAB shortcut. |
| Reports | Root operational screen/FAB shortcut. |
| Expenses | Business/Pro root screen from the Dashboard quick action. |
| Station profile | Settings route. |
| Manage stations | Settings route. |
| Subscription billing | Settings route. |
| Plan comparison | Subscription route. |

Quick walk-in sale is presented as a sheet from the main container rather than a durable route.

## Navigation state and debt

- The main container presents the same five role-filtered destinations through `AquaHubAdaptiveNavigationScaffold`. It reads `currentWindowAdaptiveInfoV2()` and uses the Material 3 standard policy: compact width, compact height, or tabletop posture uses a bottom navigation bar; other widths use a navigation rail.
- The nested `rememberNavController()` remains owned by `MainContainerScreen`; changing the navigation presentation does not key or recreate the nested `NavHost`. Destination taps keep the existing `saveState`, `restoreState`, and `launchSingleTop` behavior.
- The mobile and tablet presentations share the same destination items and selected-route mapping. Secondary operational routes remain reachable through existing settings and quick-action entry points.
- Authentication routing is decided from splash/session/onboarding state. Avoid firing repeated navigation from recomposition; consume events or use stable effect keys.
- After authentication or setup completes, the root stack must not retain splash, login, onboarding, or registration destinations beneath `main_container`; Android Back must not return to an authenticated user's auth screen.
- Detail arguments are local numeric Room IDs. Remote IDs must be mapped before navigation.
- No manifest/NavGraph deep links are implemented. FCM does not provide a complete order-routing contract.
- Root and nested controller ownership is split by the main container. Add a destination at the layer that owns its chrome/back behavior rather than creating another controller.
- Do not put large domain objects in routes; pass IDs and reload from the source of truth.
