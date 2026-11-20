# FIXES y mejoras aplicadas — APK Flutter (todus-relay-vpn-flutter)

Versión revisada: **1.4.3+8** (mismo versionCode; los cambios son internos).

## 1. Compatibilidad 100% con el servidor Go mejorado

### 1.1 Soporte del sobre TDS3 + AES-GCM (`TodusStore.kt`)
**Problema:** la APK solo escribía y leía sobres `TDS2` (HMAC + payload en claro).
El servidor Go mejorado escribe por defecto sobres `TDS3` (HMAC + AES-GCM
cifrados). Sin este fix, la APK **no podía leer** los datos `s2c`, los `ack`,
los `fin` ni el `status` que el servidor publica → el túnel no funcionaba.

**Fix:**
- `deriveAesKey(secret)`: HMAC-SHA256(secret, "todus-relay-v1/encryption-key")
  → 32 bytes. **Idéntico** al `deriveAESKey()` del servidor Go.
- `encryptAesGcm(plaintext)` → `nonce(12) || ciphertext||tag` (AES-256-GCM,
  128-bit tag). Mismo formato que el servidor.
- `decryptAesGcm(blob)` → inverso.
- `seal()` ahora escribe `TDS3` cuando `encryptionEnabled=true` (default),
  `TDS2` legacy cuando `false`.
- `open()` ahora **lee ambos** `TDS2` y `TDS3` (incluso con
  `encryptionEnabled=false`, porque la clave AES se deriva siempre del
  `sharedSecret`). Esto permite migración gradual de clientes.

**Verificado:** prueba de interoperabilidad bidireccional Java(Kotlin mirror)
↔ Go(servidor):
- Java cifra → Go descifra: ✓ MATCH
- Go cifra → Java descifra: ✓ MATCH
- Clave AES derivada idéntica en ambos lados.
- Round-trip local hasta 256 KiB.

### 1.2 Nuevo ajuste `encryptionEnabled` (`RelaySettings.kt`)
- Campo `val encryptionEnabled: Boolean` (default `true`, igual que el
  servidor Go).
- Persistencia en `SharedPreferences` clave `encryption_enabled`.
- Expuesto en `snapshot()` para que la UI Flutter lo lea/escriba.
- Se puede añadir un toggle en Ajustes → «Conexión y seguridad».

## 2. Bugs de red y concurrencia

### 2.1 `TodusStore.get()` — 5xx no reintentaba (`TodusStore.kt`)
**Problema:** el `return when {...}` dentro de `client.newCall(...).execute().use { }`
hacía non-local return desde `get()` en TODAS las ramas, incluida la 5xx que
decía «dejar que el bucle reintente con backoff». El comentario mentía: el 5xx
retornaba `null` inmediatamente sin reintentar.

**Fix:** reestructurado a un `when` donde solo 200/403/404/4xx hacen `return`;
el 5xx no retorna → el `use` termina → el `for` reintenta con backoff.

### 2.2 `TcpStack` — parse de opciones SYN ignoraba NOP (`TcpStack.kt`)
**Problema:** el parser de opciones TCP del SYN trataba cada opción como
`kind(1) + length(1)`, pero NOP (kind=1) y EOL (kind=0) ocupan 1 byte sin
campo length. Cuando un NOP precedía a Window Scale (caso habitual en
Linux/Angstrom), `peerWindowScale` se quedaba en 0 → la ventana efectiva se
truncaba a 65535 → **throughput colapsado**.

**Fix:** `if (kind == 1) { i++; continue }` antes de leer `length`.

### 2.3 `TcpStack` — FIN retransmitido no se ACKeaba (`TcpStack.kt`)
**Problema:** una vez `peerFinReceived=true`, un FIN retransmitido (el par no
recibió nuestro ACK) caía fuera del `if (pkt.fin && !peerFinReceived)` y no se
re-ACKeaba → el par seguía retransmitiendo el FIN hasta que su RTO expiraba,
**estancando el cierre** de la conexión.

**Fix:** nueva rama `else if (peerFinReceived && finEnd == ((rxSeq - 1) and 0xFFFFFFFFL))`
que reenvía el ACK.

### 2.4 `TcpStack.lastActivity` sin `@Volatile` (`TcpStack.kt`)
Escrito en `onSegment` (hilo lector) y leído en `tick` (hilo temporizador).
Sin `@Volatile` el temporizador podía leer un valor stale y retrasar el
cierre por inactividad. **Fix:** `@Volatile`.

### 2.5 `Ip.writeTcpHeader` — `dataOffset` truncaba opciones (`Ip.kt`)
**Problema:** `(20 + optLen) / 4` truncaba. Con las opciones del SYN-ACK
(MSS(4) + WS(3) + SACK(2) = 9 bytes), `dataOffset` claimaba 28 bytes pero las
opciones ocupaban 9 → el último byte de SACK-Permitted se interpretaba como
payload → **cabecera TCP corrupta**, peer descartaba el SYN-ACK o malinterpretaba
SACK.

**Fix:** `paddedOptLen = (optLen + 3) / 4 * 4` + relleno con NOP (0x01).
`buildTcpPacket` ajustado para reservar el tamaño con padding y no solapar
payload.

### 2.6 `SocksServer` — fugas de socket en rechazo (`SocksServer.kt`)
**Problema:** `DiscardPolicy` descartaba la tarea `handleClient(client)` en
silencio cuando la cola del pool se saturaba; el socket aceptado **quedaba
abierto para siempre** (fuga de FD). Además el `catch (e: IOException)` no
capturaba `RejectedExecutionException` (RuntimeException).

**Fix:** eliminado `DiscardPolicy` (default `AbortPolicy`) + `try/catch
RejectedExecutionException` en `acceptLoop` que cierra el socket rechazado.

### 2.7 `SocksServer.waitForVps` — tight-poll si status corrupto (`SocksServer.kt`)
**Problema:** `missedPolls` (que controla el backoff) solo se incrementaba si
`raw == null`. Si el status llegaba corrupto (`JSONException`), el bucle
**tight-polleaba** a `pollInterval` sin backoff hasta el deadline.

**Fix:** distinguir «respuesta válida pero no ready» (la VPS responde, seguir
rápido) de «ausente o corrupta» (no hay progreso, aplicar backoff).

### 2.8 `SocksServer.waitWindowC2s` — bloqueaba indefinidamente (`SocksServer.kt`)
**Problema:** no consultaba `stopped` ni tenía límite de polls. Si la sesión
se cerraba mientras esperábamos ventana (ack nunca llega), el hilo de subida
**bloqueaba para siempre**.

**Fix:** parámetro `stopped: AtomicBoolean` + check en cada iteración +
salvaguarda de 600 polls (~10 min). Firma y call-site actualizados.

### 2.9 `SocksServer.relayClient` — `finKey` final ignorado (`SocksServer.kt`)
El `store.put(finKey)` final ignoraba su resultado. Si fallaba, la VPS nunca
sabría que el cliente terminó. **Fix:** log de warning en false.

### 2.10 `UdpTunnel` — evicción de flujos por posición, no por actividad (`UdpTunnel.kt`)
**Problema:** `if (list.size >= 8) list.removeAt(0)` expulsaba el flujo
insertado primero aunque fuera el más activo → **pérdida del tráfico de
retorno** de un flujo que seguía vivo.

**Fix:** evict por `lastSeen` más viejo (búsqueda lineal del mínimo).

### 2.11 `UdpTunnel` — respuestas UDP con hostname descartadas en silencio (`UdpTunnel.kt`)
**Problema:** si el relay devolvía un hostname (atyp=3) en vez de IP, el
datagrama se descartaba sin log → tráfico perdido sin diagnóstico.

**Fix:** `AppLog.w` con el hostname antes de descartar.

### 2.12 `UdpTunnel` — socket de control sin `protect()` (`UdpTunnel.kt`)
**Problema:** el socket TCP de control a 127.0.0.1 no se protegía. Defensivo:
si `socksHost` se reconfigura a una dirección enrutable, el tráfico entraría
en la VPN (bucle).

**Fix:** `engine.protect(socket)` antes de `connect`. Añadido helper
`VpnEngine.protect(Socket)`.

### 2.13 `VpnEngine.sendRstFor` — ACK mal para SYN/FIN (`VpnEngine.kt`)
**Problema:** `ack = pkt.seq + pkt.payloadLen` ignoraba que SYN y FIN consumen
un número de secuencia. Un RST respondido a un SYN llevaba `ACK=seq` en vez de
`seq+1` → los stacks estrictos lo descartaban → la sesión huérfana permanecía
media-abierta hasta timeout.

**Fix:** `+ (if (pkt.syn) 1 else 0) + (if (pkt.fin) 1 else 0)`.

### 2.14 `TodusVpnService` — `engine`/`wakeLock` sin `@Volatile` (`TodusVpnService.kt`)
Escritos en el hilo «vpn-start», leídos desde `stopVpn()`/`onDestroy()` (hilo
UI). Sin `@Volatile` el hilo de stop podía ver `null` y saltarse la limpieza
→ sockets y bombas vivas tras parar la VPN.

**Fix:** `@Volatile` en ambos.

## 3. No se ha tocado (deliberadamente)

- **`RelayProtocol.kt`**: ya era un puerto fiel del protocolo (frames TDV2,
  lotes TDU2, claves `_v2_*`, JSON compacto). Sin cambios.
- **`AppLog.kt`, `VpnStats.kt`, `MainActivity.kt`**: clean según auditoría.
- **`VpnEngine` blocking TUN write**: el agente sugirió un writer thread
  dedicado para evitar que un TUN write lento stalleara el temporizador.
  Es un refactor mayor con riesgo de regresión; se deja para una iteración
  posterior. El comportamiento actual es correcto (solo throughput subóptimo
  bajo TUN congestionado), no es un bug de corrección.
- **`SocksServer.relayUdpAssociation` finalCount tracking**: revisado, la
  lógica de «primera secuencia fallida» ya es correcta (toma el mínimo).

## 4. Build

No hay cambios en `pubspec.yaml`, `build.gradle.kts`, ni `AndroidManifest.xml`.
Build normal: `flutter build apk --release` (requiere Flutter SDK + Android SDK,
no incluidos en este zip).

## 5. Compatibilidad con el servidor

Esta APK es **100% compatible** con el servidor Go mejorado (`todus_relay_vps`
v2) cuando ambos usan el mismo `PROXY_SHARED_SECRET`:
- Frames TDV2, lotes TDU2, claves `_v2_*`: idénticos.
- Sobres: APK lee TDS2+TDS3, escribe TDS3 (default) o TDS2 (legacy).
- Servidor lee TDS2+TDS3, escribe TDS3 (default) o TDS2 (legacy).
- HMAC: `HMAC-SHA256(secret, key || 0x00 || blob)` en ambos.
- AES: AES-256-GCM, nonce 12 bytes, tag 128 bits, clave derivada vía
  `HMAC-SHA256(secret, "todus-relay-v1/encryption-key")` en ambos.

Para compatibilidad con servidores antiguos (solo TDS2): poner
`encryption_enabled=false` en Ajustes (o `SharedPreferences`).
