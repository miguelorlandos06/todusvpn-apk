# ToDus Relay VPN — Flutter (versión optimizada)

Versión **Flutter** de [ToDus Relay VPN](https://github.com/Carlosdev-cod/todus-relay-vpn).
Hace **exactamente lo mismo** que la APK Kotlin: una VPN de sistema (interfaz
TUN mediante `VpnService`) que transporta el tráfico IP del teléfono a través
del relay de objetos ToDus, igual que `MOBILE/movil_proxy.py`.

## Novedades de esta versión (estabilidad + eficiencia)

Esta rama aplica 6 optimizaciones de ingeniería para redes inestables con
rate limits, sin tocar el protocolo binario con la VPS:

| # | Optimización | Dónde |
|---|---|---|
| 1 | **Ajustes Avanzados** en la UI (Poll interval, Chunk size, Max sessions, Batching, Backoff, Circuit breaker, Filtrado de apps) | `lib/screens/settings_screen.dart` + puente `getSettings`/`setSettings`/`listInstalledApps` en `MainActivity.kt` |
| 2 | **Filtrado de apps** con `VpnService.Builder.addAllowedApplication` — solo las apps elegidas atraviesan la VPN; el resto va por la red directa (ahorro de batería/datos y evita tráfico de fondo ruidoso) | `vpn/VpnEngine.kt` |
| 3 | **Backoff exponencial** con jitter y tope configurable (1 s → 2 s → 4 s → 8 s → tope 30 s); aplicado a PUT, GET y DELETE | `TodusStore.kt` |
| 4 | **Semáforo de concurrencia** — tope de sesiones SOCKS5 (`maxSessions`) y de peticiones HTTP simultáneas (`maxConcurrentHttp`); los excedentes se rechazan en lugar de saturar el motor | `SocksServer.kt`, `TodusStore.kt`, `vpn/TcpStack.kt` |
| 5 | **Batching TCP** — los `read()` pequeños del cliente se acumulan hasta `tcpBatchDelayMs` o `tcpBatchMaxBytes` y se publican como un único frame más grande; reduce drásticamente el número de peticiones PUT en tráfico interactivo (TLS, keystrokes) | `SocksServer.kt` (relayClient) |
| 6 | **Defaults estables** — `pollInterval 1 s` (antes 10 ms), `maxSessions 10` (antes 256), `udpPollInterval 100 ms` (antes 10 ms), `udpBatchDelayMs 50 ms` (antes 1 ms), `chunkSize 512 KiB` (antes 1 MiB), `windowSize 16` (antes 32) | `RelaySettings.kt` |

### Circuit breaker y backoff en `waitForVps`

- Si `waitForVps` no encuentra estado tras 3 sondeos consecutivos, duplica el
  intervalo hasta `queuePollInterval` (default 500 ms), evitando bucles
  destructivos cuando la VPS no responde.
- En la bajada, si no llegan frames, se aplica el mismo backoff (tope 2 s).
- Tras `circuitFailThreshold` fallos consecutivos de PUT/GET, el circuito se
  abre durante `circuitOpenMs`: las nuevas peticiones se rechazan rápido en
  lugar de agotar el timeout HTTP.

### Métricas nuevas en la pestaña Datos

- Backoffs aplicados
- Veces que el circuito se abrió
- Sesiones rechazadas por el límite de concurrencia
- Esperas en el semáforo HTTP
- Bloques TCP coalescidos (cantidad de PUTs ahorrados)

```text
 apps del teléfono
   │  (rutas 0.0.0.0/0, o solo apps filtradas si se configura)
   ▼
 interfaz TUN  (android.net.VpnService + Builder.establish)
   │
   ▼
 VpnEngine ──► TcpStack (pila TCP del lado TUN: SYN-ACK, ventana, RTO, FIN)
   │      └──► UdpTunnel (UDP ASSOCIATE contra el SOCKS local; QUIC sin tocar)
   ▼
 SOCKS5 local 127.0.0.1:1080  (SocksServer.kt = puerto fiel de movil_proxy.py)
   │
   ▼
 ToDus (PUT/GET de objetos con sobre TDS2 + HMAC-SHA256, Host virtual, UA curl)
   │  (los sockets HTTP van con VpnService.protect() para no regresar por la VPN)
   ▼
 VPS worker (VPS/vps_proxy.py, sin cambios) ──► Internet real
```

## Qué es idéntico a la APK original (mismo procedimiento, mismo todo)

El **núcleo Kotlin se portó byte a byte** — no se cambió ni una línea de
protocolo:

| Archivo | Papel |
|---|---|
| `SocksServer.kt` | Saludo SOCKS5, `CONNECT`, `UDP ASSOCIATE`, ensamblador FRAG, lotes `UDP_BATCH_*`, ventana `WINDOW_SIZE` + `ACK_EVERY`, `FIN`/`CLOSE`, `TODUS_DELETE_AFTER_READ`, respuestas de error SOCKS idénticas (05 01/04/07) |
| `RelayProtocol.kt` | Cabecera de frame `TDV2` (21 B), lote `TDU2` (15 B), ítem (8 B), claves `_v2_*` con secuencia a 16 dígitos, JSON compacto, contadores ASCII |
| `TodusStore.kt` | Sobre `TDS2` + HMAC-SHA256 sobre `clave \0 datos`, `User-Agent: curl/8.23.0`, cabecera `Host`, reintentos `min(0.2·2^n, 2 s)`, GET 200/403/404/5xx, DELETE opcional |
| `RelaySettings.kt` | Perfil blindado hardcodeado (secreto, URL, Host, DNS, ventana, lotes UDP…) — idéntico |
| `vpn/VpnEngine.kt`, `vpn/TcpStack.kt`, `vpn/UdpTunnel.kt`, `vpn/Ip.kt` | Motor TUN completo (pila TCP propia, túnel UDP, construcción/parseo de paquetes IPv4/IPv6) |
| `vpn/TodusVpnService.kt` | Servicio en primer plano (specialUse en Android 14+), wakelock, notificación, revocación limpia |
| `AppLog.kt`, `VpnStats.kt` | Registro en memoria (búfer de 400 líneas) y contadores en vivo |

También se conservan: `applicationId` (`cu.todus.relayvpn`) y el **mismo
keystore de firma** (`app/release.jks`), así que la APK Flutter se instala como
**actualización directa** encima de la APK Kotlin original.

## Qué cambia: la interfaz (Flutter)

La vieja actividad con XML/Views se sustituye por una interfaz Flutter
oscura y moderna estilo VPN comercial, con Material 3 y sin dependencias
externas de pub.dev:

- **Orbe de conexión** grande con animaciones de estado (respiración teal al
  conectar, arco giratorio mientras negocia, halo rojo en error) y respuesta
  táctil (hápica + escala).
- **Pantalla de inicio** de un vistazo: estado, duración de la sesión,
  velocidades en vivo (calculadas con los contadores reales) y datos de sesión.
- **Pestaña Datos**: todas las métricas reales del servicio agrupadas por capa
  (tráfico, TCP, UDP, relay ToDus) con cifras tabulares.
- **Pestaña Registro**: la consola “tipo Termux” de la original
  (`[+] CONNECT …`, `[ToDus PUT] …`), en monoespaciada con niveles coloreados,
  anclada al final y con copiar/borrar.
- **Acerca de**: recorrido del tráfico y límites conocidos (sin exponer ningún
  dato privado del perfil interno).
- Respeta el ajuste del sistema “quitar animaciones”.

Comunicación Flutter ↔ Kotlin: `MainActivity` (ahora una `FlutterActivity`)
expone un **MethodChannel** (`todus.vpn/bridge`) con `connect`, `disconnect`,
`getStatus` (estado + estadísticas + registro) y `clearLog`. El flujo de
permisos es el mismo de la original: notificaciones (Android 13+) → diálogo de
VPN del sistema → servicio en primer plano. La interfaz sondea el estado cada
500 ms, igual que el `Handler.postDelayed(500)` de la APK.

## Estructura

```text
lib/
├── main.dart                  # Arranque, tema y shell con pestañas
├── core/
│   ├── vpn_bridge.dart        # MethodChannel + modelos (estado/estadísticas/registro)
│   ├── vpn_ui.dart            # Vocabulario visual del estado
│   └── formatters.dart        # Duración/bytes/velocidades (mismo formato que la original)
├── theme/app_theme.dart       # Design tokens (dark premium, acento teal, M3)
├── screens/                   # Inicio, Datos (estadísticas), Registro, Acerca de
└── widgets/                   # Orbe de conexión, chips, filas y tarjetas
android/app/src/main/java/cu/todus/relayvpn/
├── MainActivity.kt            # NUEVO: puente Flutter (permisos + canal)
└── (resto: núcleo idéntico a la APK Kotlin)
```

## Compilar

Requisitos: Flutter 3.27+ y JDK 17.

```bash
flutter pub get
flutter analyze        # 0 issues
flutter test           # pruebas de la interfaz y del puente
flutter build apk --release --target-platform android-arm64 --split-per-abi
```

El resultado es `build/app/outputs/flutter-apk/app-arm64-v8a-release.apk`:
**release AOT, solo arm64-v8a**. No se distribuyen builds de debug — una APK
 debuggable ejecuta todo el proceso (interfaz y motor del túnel) sin
optimizaciones y con JIT, lo que se nota sobre todo en streaming. Para ver
videos usa siempre la APK `arm64-v8a-release`.

La APK se firma con `app/release.jks` (contraseñas en
`app/keystore.properties`, incluidos porque el repo es privado — igual que la
original). Para distribución seria, rota ese keystore.

## Compilación automática (GitHub Actions) y Releases

`.github/workflows/build.yml` hace todo en GitHub (nada que instalar):

1. **Valida**: `flutter analyze` + `flutter test`.
2. **Compila** la APK release firmada, **solo arm64-v8a** (JDK 17, Gradle 8.9,
   AGP 8.7). No se genera ninguna APK de debug.
3. La sube como artifact de Actions en cada push a `main`.
4. En cada **tag `v*`** crea un **Release** con la APK adjunta
   (`ToDusRelayVPN-Flutter-vX.Y.Z-arm64-v8a-release.apk`).

## Uso

1. Arranca la VPS como siempre (`VPS/start.sh` en el proyecto original).
2. Abre **ToDus VPN**, toca el orbe y acepta el diálogo de VPN de Android.
3. Estado, velocidades, estadísticas y registro técnico se actualizan en vivo.

La VPS **no se toca**: mismo secreto (hardcodeado, sincronizado), mismas claves
de objeto, mismos lotes UDP, mismas ventanas/ACK/FIN/CLOSE.

## Límites conocidos (heredados del diseño del relay)

- **ICMP no funciona** (ping): no hay canal ICMP hacia la VPS. TCP/UDP funcionan
  con normalidad.
- La latencia la impone ToDus (publicar/consultar objetos). QUIC/UDP/443 pasa
  sin modificar; HTTP/3 puede caer a TCP/2 en rutas lentas.
- UDP se demultiplexa por dirección remota; si dos apps escriben al mismo
  destino, la respuesta llega a ambos sockets locales.
- IPv6 de salida requiere que la VPS tenga IPv6.
- Datagramas UDP mayores que el MTU se descartan al entregar.

## Tipografías y licencias

- [Space Grotesk](assets/fonts/OFL-SpaceGrotesk.txt) (SIL OFL 1.1) — display y cifras.
- [JetBrains Mono](assets/fonts/OFL-JetBrainsMono.txt) (SIL OFL 1.1) — registro técnico.
