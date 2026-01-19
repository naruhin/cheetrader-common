# CheeTrader Common

Shared models and events for CheeTrader microservices.

## Installation

### JitPack

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.YOUR_USERNAME:cheetrader-common:0.1.0")
}
```

## Models

### SignalDto

```kotlin
SignalDto(
    signalId = "uuid",
    symbol = "BTCUSDT",
    type = SignalType.LONG,
    entryPrice = 65000.0,
    takeProfit = 67000.0,
    stopLoss = 64000.0,
    timeframe = Timeframe.h1,
    orderType = OrderType.MARKET,
    strategyName = "SmartMomentum1HStrategy",
    metadata = mapOf("confidence" to "0.85")
)
```

### SignalEvent

```kotlin
SignalEvent(
    eventType = SignalEventType.OPENED,
    signal = signalDto,
    timestamp = Instant.now()
)
```

## Timeframes

Available: `m1`, `m5`, `m15`, `m30`, `h1`, `h4`, `d1`, `w1`

Parse from string:
```kotlin
Timeframe.fromString("15m") // -> Timeframe.m15
Timeframe.fromString("1h")  // -> Timeframe.h1
```
