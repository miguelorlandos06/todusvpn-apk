# Informe de optimización — ToDus Relay VPN (Flutter)

**Fecha:** 2026-11-15
**Proyecto:** todus-relay-vpn-flutter
**Objetivo:** Hacer la app extremadamente eficiente, estable y frugal con
batería/datos en redes inestables con rate limits.

## Diagnóstico: cuellos de botella detectados

Tras leer los ~3.6 KLOC de Kotlin + 0.6 KLOC de Dart del proyecto original,
identifiqué seis problemas que se retroalimentaban en bucles destructivos:

| # | Problema | Archivo | Síntoma |
|---|---|---|---|
| 1 | `pollInterval = 0.01 s` (10 ms) — sondeo agresivísimo | `RelaySettings.kt` | ~600 GETs por cada `connectTimeout` (25 s) por sesión TCP |
| 2 | `maxSessions = 256` sin cuota | `RelaySettings.kt` | El ThreadPool del SOCKS5 llega a 256 hilos y colapsa bajo carga |
| 3 | Reintento PUT con backoff `min(0.2·2^n, 2 s)` — techo de 2 s | `TodusStore.kt` | Tras 4-5 fallos se reintentaba cada 2 s para siempre |
| 4 | `GET` no reintentaba; el caller tampoco coordinaba | `TodusStore.kt` | Cada fallo de red = PUT perdido; sin protección ante tormentas |
| 5 | `relayClient` hacía 1 PUT por `input.read()` | `SocksServer.kt` | Tráfico interactivo TLS (keystrokes, ACKs de QUIC) generaba cientos de PUTs de 1 byte |
| 6 | Solo se excluía la propia app; todo el teléfono iba por la VPN | `VpnEngine.kt` | Google Play Services, telemetría, push, etc. saturaban el túnel |

**Cadena de fallo típica observada:**

1. La red se cae.
2. Cada PUT falla y se reintenta cada 2 s.
3. Cada sesión TCP sigue lanzando GETs cada 10 ms para ver si llegó el frame
   siguiente (que nunca llega).
4. Como hay 256 sesiones permitidas, las apps de fondo multiplican el problema.
5. El ThreadPool del SOCKS5 y el ConnectionPool de OkHttp se saturan, el
   battery drain se dispara, y la API ToDus reporta rate limit (429) → más
   fallos → más reintentos → bucle destructivo.

## Soluciones aplicadas

### 1. UI de Ajustes Avanzados (Tarea 1)

- **Nueva pantalla** `lib/screens/settings_screen.dart` (~ 730 LOC) con
  sliders Material 3 para todos los parámetros clave y un selector de apps
  instaladas con buscador.
- **Acceso** desde el botón de ajustes (icono `tune`) en la barra de marca de
  la pantalla de inicio.
- **Persistencia** vía `SharedPreferences` en Kotlin (`RelaySettings.prefs`).
- **Reconexión automática**: al guardar, si la VPN estaba activa, se
  desconecta y reconecta silenciosamente para aplicar los nuevos valores.

**Métodos añadidos al MethodChannel `todus.vpn/bridge`:**

- `getSettings` → `Map<String, Any>` con la snapshot actual.
- `setSettings` → persiste el mapa parcial y devuelve `{ok, needsReconnect}`.
- `listInstalledApps` → lista de apps instaladas (excluye sistema y la propia).

### 2. Filtrado de Apps (Tarea 2)

`VpnEngine.kt` ahora itera sobre `settings.allowedApps` y llama a
`builder.addAllowedApplication(pkg)` para cada una. Si la lista está vacía,
se mantiene el comportamiento original (todo el tráfico).

**Beneficios:**
- El tráfico de Google Play Services, telemetría y push bypass el túnel.
- Reduce drásticamente el número de sesiones SOCKS5 activas.
- Ahorra batería (radio menos tiempo activa) y datos móviles.

### 3. Backoff Exponencial (Tarea 3)

`TodusStore.kt` ahora implementa:
- `backoffSleep(attempt)` = `min(initial · factor^attempt, max) + jitter`
  con `initial=1 s`, `factor=2.0`, `max=30 s`, `jitter=250 ms`.
- Aplicado a PUT, GET y DELETE (antes solo PUT).
- Reintentos configurables: `httpRetries=4` (antes 2).
- Apagado del `retryOnConnectionFailure` de OkHttp (lo gestiona el código).
- Pool de conexiones reducido a `maxConcurrentHttp` (default 8) para no
  acumular sockets abiertos contra un servidor con rate limit.

### 4. Semáforo de Concurrencia (Tarea 4)

- **Sesiones SOCKS5**: `SocksServer.sessionSemaphore = Semaphore(maxSessions)`.
  Cuando se agota, la nueva conexión recibe un SOCKS general failure inmediato
  (en lugar de encolarse y agotar memoria/hilos).
- **HTTP simultáneas**: `TodusStore.httpSemaphore = Semaphore(maxConcurrentHttp)`
  con `tryAcquire(timeout=httpTimeout)`. Adquirido antes de cada
  `client.newCall(...).execute()` y liberado en `finally`.
- **Sesiones TCP**: `TcpStack.maxSessions` ahora respeta
  `settings.maxSessions` (antes estaba hardcodeado a 512). Alcanzado el tope,
  el SYN recibe un RST para que la app origen reintente más tarde.
- **Dispatcher de OkHttp**: `maxRequests` y `maxRequestsPerHost` ahora
  coherentes con `maxConcurrentHttp`.

### 5. Batching TCP (Tarea 5)

`relayClient` (SocksServer.kt) ahora acumula `input.read()` en un
`ByteArrayOutputStream` y vuelca el bloque como un único frame cuando se
cumple cualquiera de estas condiciones:

- `batch.size() >= tcpBatchMaxBytes` (default 64 KiB)
- `System.currentTimeMillis() - batchStartedAt >= tcpBatchDelayMs` (default 50 ms)
- EOF del cliente (flush final)
- `soTimeout=250 ms` del socket (revisa el búfer periódicamente)

**Beneficio cuantitativo:**
- Para tráfico de bulk (descargas, streaming): el `read()` entrega cerca de
  `chunkSize` de golpe; el búfer no añade latencia.
- Para tráfico interactivo (TLS, ACKs, SSH): convierte N PUTs de ~50-200 bytes
  en 1 PUT de hasta 64 KiB cada 50 ms → ~20× menos PUTs.
- Reducción directa del consumo de CPU, red y batería.

### 6. Defaults Estables (Tarea 6)

`RelaySettings.kt` defaults:

| Parámetro | Antes | Ahora |
|---|---|---|
| `pollInterval` | 0.01 s (10 ms) | **1.0 s** |
| `maxSessions` | 256 | **10** |
| `udpPollInterval` | 0.01 s (10 ms) | **0.1 s (100 ms)** |
| `udpBatchDelayMs` | 1 ms | **50 ms** |
| `udpBatchMaxBytes` | 256 KiB | 128 KiB |
| `udpMaxFlows` | 512 | 128 |
| `udpUploadWorkers` | 8 | 4 |
| `chunkSize` | 1 MiB | 512 KiB |
| `windowSize` | 32 | 16 |
| `ackEvery` | 8 | 4 |
| `httpRetries` | 2 | 4 |
| `queuePollInterval` | 0.05 s (50 ms) | 0.5 s (500 ms) |
| (nuevos) `maxConcurrentHttp` | — | 8 |
| `backoffInitialMs` | — | 1000 |
| `backoffMaxMs` | — | 30000 |
| `backoffFactor` | — | 2.0 |
| `backoffJitterMs` | — | 250 |
| `circuitFailThreshold` | — | 8 |
| `circuitOpenMs` | — | 15000 |
| `tcpBatchDelayMs` | — | 50 |
| `tcpBatchMaxBytes` | — | 65536 |
| `allowedApps` | — | [] |

## Extras: Circuit Breaker y Backoff en `waitForVps`

Más allá de las 6 tareas pedidas, añadí dos protecciones adicionales que
atacan directamente los bucles destructivos:

### Circuit breaker (`TodusStore.kt`)

Tras `circuitFailThreshold = 8` fallos consecutivos, el circuito se abre
durante `circuitOpenMs = 15 s`: las nuevas peticiones PUT/GET/DELETE se
rechazan inmediatamente con un log, sin agotar el timeout HTTP. Tras el
cooldown, pasa a half-open (deja pasar una petición de prueba) y se cierra
si tiene éxito.

### Backoff adaptativo en `waitForVps`

Si la VPS no publica estado tras 3 sondeos consecutivos, el intervalo se
duplica hasta `queuePollInterval` (default 500 ms). Esto evita el caso en
que una red caída generaba 2500 GETs en los 25 s de `connectTimeout`.

## Métricas nuevas (visibles en la pestaña Datos)

`VpnStats.kt` expone ahora:
- `backoffEvents` — veces que se aplicó backoff.
- `circuitOpenCount` — veces que el circuito se abrió.
- `httpSemaphoreWait` — veces que un PUT/GET tuvo que esperar al semáforo.
- `tcpBatchesSent` — bloques TCP coalescidos enviados (indirectamente, PUTs ahorrados).
- `sessionsRejectedByLimit` — sesiones rechazadas por el semáforo de concurrencia.

## Validación

### Kotlin (compilación con `kotlinc 2.0.21`)

Compilado contra stubs de Android + OkHttp 4.12.0 + JSON. **0 errores, 0
warnings** en todos los archivos Kotlin modificados:

```
RelaySettings.kt   TodusStore.kt    MainActivity.kt    VpnStats.kt
SocksServer.kt     vpn/VpnEngine.kt vpn/TcpStack.kt
```

### Flutter (Dart 3.6.0)

`flutter analyze` → **No issues found!**
`flutter test` → **All 8 tests passed!**

## Archivos modificados

### Kotlin

| Archivo | Líneas | Cambios |
|---|---|---|
| `RelaySettings.kt` | 120 → 272 | Defaults estables, persistencia, snapshot/apply, nuevos campos de resiliencia |
| `TodusStore.kt` | 258 → 419 | Backoff exponencial, circuit breaker, semáforo HTTP, reintentos en GET |
| `SocksServer.kt` | 750 → 873 | Semáforo de sesiones, batching TCP, backoff en waitForVps y downlink |
| `VpnStats.kt` | 30 → 41 | 5 contadores nuevos de resiliencia |
| `MainActivity.kt` | 182 → 223 | Métodos `getSettings`/`setSettings`/`listInstalledApps` |
| `vpn/VpnEngine.kt` | 251 → 273 | `addAllowedApplication` con lista de paquetes |
| `vpn/TcpStack.kt` | 627 → 635 | `maxSessions` respeta `settings.maxSessions` (era hardcodeado 512) |

### Flutter (Dart)

| Archivo | Líneas | Cambios |
|---|---|---|
| `lib/core/vpn_bridge.dart` | 256 → 339 | `VpnStats` extendido, métodos `getSettings`/`setSettings`/`listInstalledApps`, clase `InstalledApp` |
| `lib/screens/home_screen.dart` | 329 → 339 | Botón de ajustes en la barra de marca |
| `lib/screens/settings_screen.dart` | 0 → 729 | Nueva pantalla de Ajustes Avanzados |
| `lib/screens/stats_screen.dart` | 152 → 184 | Tarjeta de "Resiliencia" con las 5 nuevas métricas |

## Próximos pasos sugeridos

1. **Probar en dispositivo real** con red inestable (cambiar entre WiFi y
   móvil) y verificar que `backoffEvents` y `circuitOpenCount` crecen pero la
   app no se cuelga.
2. **Ajustar el circuit breaker** según el comportamiento observado: si la VPS
   tarda >15 s en recuperarse, subir `circuitOpenMs` a 30 s.
3. **Calibrar `tcpBatchDelayMs`**: 50 ms es un buen default para no añadir
   latencia perceptible en SSH/teletipo; si el uso es solo streaming,
   subirlo a 100-200 ms ahorra más PUTs.
4. **Filtrado de apps por defecto**: si el caso de uso principal es Telegram,
   preconfigurar `allowedApps = ["org.telegram.messenger"]` en la primera
   instalación para evitar que el usuario tenga que seleccionar nada.
