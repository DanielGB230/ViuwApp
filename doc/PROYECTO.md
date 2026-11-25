# ViuwApp — Documentación Técnica del Proyecto

> **Última actualización:** 24/11/2026 (Fase 20 completada)  
> **Versión Android:** `app-debug.apk` 63.6 MB — JDK 21 — `minSdk 24` / `targetSdk 34`  
> **Backend:** .NET 10 — SignalR Hub + REST API — SQL Server + Firebase Admin  
> **URL de señalización:** `https://781xtrpg-5000.brs.devtunnels.ms` (túnel dev)

---

## 1. Estructura de Carpetas

```
ViuwApp/
├── android/                    # App Android (Kotlin + Compose + WebRTC)
│   ├── app/
│   │   ├── build.gradle.kts    # Config Gradle, SIGNALING_BASE_URL
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/example/streamapp/
│   │       │   ├── MainActivity.kt
│   │       │   ├── MainApplication.kt
│   │       │   ├── AppSessionViewModel.kt      # Estado global + navegación
│   │       │   ├── AuthRepository.kt           # FirebaseAuth + REST + DataStore
│   │       │   ├── SessionPreferences.kt       # DataStore (rol persistido)
│   │       │   ├── data/                       # Modelos locales
│   │       │   ├── network/
│   │       │   │   └── SignalRClient.kt        # Cliente SignalR (WebRTC signaling)
│   │       │   ├── receiver/
│   │       │   │   ├── ReceiverViewModel.kt    # Lógica Receptor (join, timeout 25s)
│   │       │   │   └── ReceiverScreen.kt       # UI Receptor (SurfaceViewRenderer)
│   │       │   ├── service/
│   │       │   │   ├── StreamingForegroundService.kt   # FGS principal (SENDER)
│   │       │   │   ├── StreamSessionCoordinator.kt     # Núcleo sesión WebRTC (Fase 20)
│   │       │   │   ├── PresenceSyncWorker.kt           # WorkManager presencia (Fase 17)
│   │       │   │   ├── BootReceiver.kt                 # BOOT_COMPLETED, MY_PACKAGE_REPLACED
│   │       │   │   └── ViuwFirebaseMessagingService.kt # FCM data messages (START_STREAM)
│   │       │   ├── ui/
│   │       │   │   ├── roles/RoleSetupScreen.kt
│   │       │   │   ├── sender/SenderStandbyScreen.kt   # Chips batería + autostart Xiaomi
│   │       │   │   └── devices/ReceiverDevicesScreen.kt
│   │       │   └── webrtc/WebRtcClient.kt      # PeerConnection, tracks OFF por defecto
│   │       └── res/                            # Recursos, temas, iconos
│   └── gradle/libs.versions.toml             # Catálogo de versiones (WorkManager 2.9.1, etc.)
│
├── backend/
│   └── StreamSignaling.Api/                  # ASP.NET Core 10
│       ├── Controllers/
│       │   └── DevicesController.cs          # POST /register, GET /mine, POST /{id}/request-stream
│       ├── Hubs/
│       │   └── SignalingHub.cs               # SignalR Hub (CreateSession, JoinSession, Offer/Answer, ICE, Presence)
│       ├── Services/
│       │   └── FcmNotificationService.cs     # FCM data message HIGH priority (START_STREAM)
│       ├── Data/AppDbContext.cs              # EF Core + SQL Server (LinkedDevice, StreamSession, AppUser)
│       ├── Models/                           # LinkedDevice, StreamSession, AppUser
│       └── DTOs/                             # DeviceDtos, SignalingDtos, RequestStreamResponse
│
└── doc/
    └── PROYECTO.md                           # (este archivo)
```

---

## 2. Comandos Principales

### Android (desde `D:\ViuwApp\android`)

```powershell
# Variables de entorno JDK 21 (obligatorio)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# Compilar debug (sin daemon para logs limpios)
.\gradlew.bat assembleDebug --no-daemon

# Verificar URL de señalización en BuildConfig
Select-String -Path .\app\build\generated\source\buildConfig\debug\com\viuwapp\stream\BuildConfig.java -Pattern SIGNALING_BASE_URL

# APK generado
D:\ViuwApp\android\app\build\outputs\apk\debug\app-debug.apk
```

### Backend (desde `D:\ViuwApp\backend\StreamSignaling.Api`)

```powershell
# Compilar Release
dotnet build --configuration Release

# Ejecutar (requiere appsettings.json con conexión SQL + Firebase credentials)
dotnet run

# Endpoints principales:
# POST   /api/devices/register          → registrar dispositivo SENDER + FCM token
# GET    /api/devices/mine              → listar dispositivos del usuario (filtrado por hardwareId local)
# POST   /api/devices/{id}/request-stream → generar sessionId + SignalR StartStream + FCM
# Hub    /signaling                     → SignalR WebSocket (CreateSession, JoinSession, Offer, Answer, ICE, Presence, Controles)
```

### Git

```powershell
cd D:\ViuwApp
git status --short
git add -A android/app/src/main/java/com/example/streamapp android/app/src/main/AndroidManifest.xml backend/StreamSignaling.Api/Services/FcmNotificationService.cs
git commit -m "Mensaje descriptivo de la fase"
git push origin main
```

---

## 3. Servicios Android (Resumen por Fase)

| Servicio / Clase | Rol | Fases Clave |
|------------------|-----|-------------|
| **StreamingForegroundService** | FGS principal del Emisor (SENDER). Mantiene notificación, SignalR, WebRTC. `android:stopWithTask="false"`. | 11–20 |
| **StreamSessionCoordinator** | Núcleo reutilizable de sesión WebRTC (SignalR + PeerConnection + controles). Usado por FGS, StandbySignalR, FCM fallback. | 20 |
| **PresenceSyncWorker** | `CoroutineWorker` WorkManager. Se ejecuta al haber red (`NetworkType.CONNECTED`). Registra presencia + inicia standby. Backoff exponencial 10s + periódico 15 min. | 17, 20 |
| **BootReceiver** | `BroadcastReceiver`: `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `USER_PRESENT`, `MY_PACKAGE_REPLACED`. Programa `PresenceScheduler`. | 14, 17, 18 |
| **ViuwFirebaseMessagingService** | `FirebaseMessagingService`. `onNewToken` actualiza FCM en backend. `onMessageReceived`: `START_STREAM` / `WAKE_AND_STREAM` → intenta FGS; si falla → `StreamSessionCoordinator` directo. | 14, 17, 18, 20 |
| **SignalRClient** | Wrapper Kotlin sobre Microsoft SignalR Java client. Métodos: `connect/disconnect`, `createSession/joinSession`, `sendOffer/Answer/ICE`, `registerDevicePresence`, `setCameraMode`, `setMicrophoneEnabled`, `stopStream`. Eventos: `onStartStream`, `onCameraModeChanged`, `onStreamStopped`, etc. | 11–20 |
| **WebRtcClient** | PeerConnection + tracks de vídeo/audio **pre-creadas y deshabilitadas** (`setEnabled(false)`). `setCameraMode(BACK/FRONT/OFF)` abre/cierra `Camera2Enumerator` + `SurfaceTextureHelper`; `setAudioEnabled` solo habilita/deshabilita track de audio. Sin `addTransceiver` — usa `addTrack` desde el inicio (Fase 16). | 12, 15, 16, 20 |
| **ReceiverViewModel / ReceiverScreen** | Receptor: `joinSession` → `onSessionJoined` → timeout 25s (`isWaitingForEmitter` → `emitterTimeout`). UI: `SurfaceViewRenderer` siempre montado, overlay opaco según estado. Texto timeout: *"El dispositivo no respondió a tiempo. Verifica que tenga permisos en segundo plano."* | 14, 16, 17, 19 |
| **AppSessionViewModel** | Estado global `AppUiState` (Login, RoleSetup, SenderStandby, ReceiverDevices, ActiveReceiver). `exitToRoleSelection()` detiene FGS, limpia rol, va a `RoleSetup` (sin sign-out Google). `signOut()` cierra todo a `Login`. `refreshDevices()` filtra el propio hardwareId. | 14, 16, 17 |
| **AuthRepository** | FirebaseAuth + Google Sign-In + DataStore (rol). REST OkHttp a `/api/devices/register` y `/api/devices/mine`. | 11–17 |
| **SessionPreferences** | DataStore `device_role` (`SENDER`/`RECEIVER`/`null`). `clearRole()` solo borra la clave. | 16 |

---

## 4. Flujo de Señalización (SignalR Hub)

```
Emisor (SENDER)                              Receptor
     │                                           │
     ├── RegisterDevicePresence(deviceId) ──────►│  (grupos: hardwareId + deviceId GUID)
     │                                           │
     ├── CreateSession(code) ◄──────────────────┤  (opcional: receptor llega primero)
     │                                           │
     │◄─── SessionCreated(code) ────────────────┤
     │                                           │
     │◄─── ReceiverJoined / OnPeerJoined ───────┤  (receptor hizo JoinSession)
     │                                           │
     ├── createOffer() → SendOffer(sdp) ───────►│
     │                                           │
     │◄─── SendAnswer(sdp) ─────────────────────┤
     │                                           │
     ├── ICE candidates ◄──────────────────────►│
     │                                           │
     ├── (WebRTC CONNECTED, tracks OFF)          │
     │                                           │
     │◄─── SetCameraMode("BACK") ───────────────┤  (Receptor pulsa "Activar Cámara")
     ├── setCameraMode("BACK") → track.enabled=true
     │                                           │
     │◄─── StopStream ──────────────────────────┤  (Receptor desconecta)
     ├── returnToStandby() (libera WebRTC)
```

**Eventos clave del Hub (`SignalingHub.cs`):**
- `RegisterDevicePresence(deviceId)` → `Groups.AddToGroup(ConnectionId, hardwareId)` + `Groups.AddToGroup(ConnectionId, deviceGuid)` + `UpdatePresenceAsync(IsOnline=true)`
- `CreateSession(code?)` → `SessionCreated(code)` + si receptor ya en sala → `ReceiverJoined/OnPeerJoined/PeerJoined`
- `JoinSession(code, deviceId)` → `SessionJoined(code)` + si emisor activo → `ReceiverJoined/OnPeerJoined/PeerJoined`
- `SendOffer/Answer/IceCandidate` → reenvío al otro extremo
- `SetCameraMode(code, mode)` / `SetMicrophoneEnabled(code, enabled)` → `Clients.OthersInGroup(code)`
- `StopStream(code)` → `OnStreamStopped` al emisor
- `OnDisconnectedAsync` → `UpdatePresenceAsync(IsOnline=false)` + limpieza sesiones

---

## 5. FCM (Firebase Cloud Messaging)

**Backend → `FcmNotificationService.SendStartStreamAsync`:**
```json
Data: {
  "action": "START_STREAM",
  "type": "START_STREAM",
  "sessionCode": "ABC123",
  "sessionId": "ABC123"
}
AndroidConfig: { Priority: High, TTL: 10min, CollapseKey: "viuw_start_stream" }
```

**Android → `ViuwFirebaseMessagingService.onMessageReceived`:**
- Lee `action = data["action"] ?: data["type"]`
- Lee `sessionCode = data["sessionCode"] ?: data["sessionId"]`
- Si `START_STREAM`/`WAKE_AND_STREAM` + rol `SENDER`:
  1. `ContextCompat.startForegroundService(ACTION_START_STREAM, EXTRA_SESSION_CODE)`
  2. Si `SecurityException` (Android 12+ background) → `StreamSessionCoordinatorProvider.get(context).startSession(sessionCode)`

> **Nota:** Los mensajes FCM de alta prioridad tienen exención oficial para `startForegroundService` en Android 12+, pero algunos fabricantes (Xiaomi, Huawei) la bloquean; el fallback cubre ese caso.

---

## 6. WorkManager — Presencia y Reconexión

**`PresenceSyncWorker` (OneTime + Periodic 15 min):**
- Constraints: `NetworkType.CONNECTED`
- Backoff: `EXPONENTIAL 10s`
- Lógica:
  1. `FirebaseAuth.currentUser` + `SessionPreferences.getSavedRole() == "SENDER"`
  2. `user.getIdToken(true)` + `FirebaseMessaging.token`
  3. `AuthRepository.registerDevice(hardwareId, deviceName, fcmToken, "SENDER")` → backend actualiza `FcmToken` + `IsOnline=true`
  4. `StreamingForegroundService.ACTION_STANDBY` (si permitido) **o** `StreamSessionCoordinatorProvider.get(context)` (standby directo)

**`PresenceScheduler.scheduleWhenOnline(context)`** encola ambos workers únicos (`REPLACE` / `UPDATE`).

**Disparadores:**
- `BootReceiver` (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, USER_PRESENT, MY_PACKAGE_REPLACED)
- `StreamingForegroundService.onTaskRemoved` → `AlarmManager.setAndAllowWhileIdle(2s)` → `BootReceiver`
- `ConnectivityManager.NetworkCallback.onAvailable` (en FGS) → re-registerPresence

---

## 7. Ciclo de Vida del Emisor (SENDER)

| Evento | Acción |
|--------|--------|
| App instalada / usuario elige rol **Emisor** | `AppSessionViewModel.becomeSender()` → `StreamingForegroundService.ACTION_STANDBY` |
| Dispositivo reiniciado / APK actualizada | `BootReceiver` → `PresenceScheduler` → worker al haber red |
| Usuario cierra app de recientes | `onTaskRemoved` → `AlarmManager 2s` → `BootReceiver` → worker |
| Receptor pulsa "Ver cámara" (ReceptorDevices) | `DevicesController.RequestStream` → SignalR `StartStream/OnStartStreamRequested` + FCM |
| FCM recibido / SignalR `OnStartStreamRequested` | `StreamSessionCoordinator.startSession(sessionCode)` → `JoinSession` → WebRTC offer |
| Receptor activa cámara (`SetCameraMode BACK`) | `WebRtcClient.setCameraMode("BACK")` → `Camera2Enumerator.startCapture` → `track.enabled=true` |
| Receptor desactiva cámara (`OFF`) | `setCameraMode("OFF")` → `track.enabled=false` + `stopCapture()` + `dispose()` (libera cámara, apaga punto verde) |
| Receptor desconecta (`StopStream`) | `onStreamStopped` → `returnToStandby()` (libera WebRTC, mantiene SignalR + presencia) |

---

## 8. Configuración Clave (No Tocar)

| Archivo | Valor Crítico |
|---------|---------------|
| `android/app/build.gradle.kts` | `SIGNALING_BASE_URL = "\"https://781xtrpg-5000.brs.devtunnels.ms\""` |
| `AndroidManifest.xml` | `<service android:name=".StreamingForegroundService" android:stopWithTask="false" android:foregroundServiceType="camera|microphone|specialUse">` + property `parental_control_presence` |
| `gradle/libs.versions.toml` | `work = "2.9.1"` → `androidx.work.runtime-ktx` |
| `backend/appsettings.json` | ConnectionStrings (SQL Server), Firebase credentials path, `AllowedHosts` |

---

## 9. Logs de Diagnóstico (Etiquetas Unificadas)

| Componente | Tag |
|------------|-----|
| `StreamingForegroundService` | `ViuwStream` (Fase 19/20), `StreamingFGS` (legacy) |
| `StreamSessionCoordinator` | `ViuwStream` |
| `PresenceSyncWorker` | `PresenceSyncWorker` |
| `BootReceiver` | `BootReceiver` |
| `ViuwFirebaseMessagingService` | `ViuwFCMService` |
| `WebRtcClient` | `WebRtcClient` |
| `AppSessionViewModel` | `AppSessionVM` |
| `SignalRClient` | (sin tag propio, usa `Log.d/w/e` genérico) |
| `ReceiverViewModel` | (sin tag propio) |

**Filtro logcat útil:**
```bash
adb logcat -s ViuwStream:V PresenceSyncWorker:V BootReceiver:V ViuwFCMService:V WebRtcClient:V AppSessionVM:V *:S
```

---

## 10. Checklist para Nuevo Desarrollador / Agente

1. **Clonar y compilar:**
   ```powershell
   git clone https://github.com/DanielGB230/ViuwApp.git
   cd ViuwApp\android
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   .\gradlew.bat assembleDebug --no-daemon
   ```
2. **Backend:** Configurar `appsettings.json` (SQL Server + Firebase Admin SDK JSON) → `dotnet run` en `backend/StreamSignaling.Api`.
3. **Verificar URL túnel:** `BuildConfig.SIGNALING_BASE_URL` debe coincidir con el túnel activo (`devtunnels.ms` o `ngrok`).
4. **Probar flujo completo:**
   - Abrir app → Login Google → Rol **Emisor** → queda "En línea" (standby).
   - Segundo dispositivo → Login misma cuenta → Rol **Receptor** → ve dispositivo en lista → "Conectar" → código 6 chars.
   - Emisor recibe FCM + SignalR → negocia WebRTC → Receptor ve "Despertando..." → "Activar Cámara" → vídeo en vivo.
5. **No ejecutar `adb install` ni manipular emuladores** — la CI/CD o el usuario final lo harán.

---

## 11. Historial de Fases (Resumen)

| Fase | Cambios Principales |
|------|---------------------|
| 11–13 | Fundación: SignalR, WebRTC, roles, FGS básico |
| 14 | Controles independientes cámara/mic, `StopStream`, `EndSession`, `RegisterDevicePresence` |
| 15 | `addTransceiver(SEND_ONLY)` → negociación early media |
| 16 | **Fix pantalla negra**: tracks pre-creadas `enabled=false` + `addTrack`; `setCameraMode`/`setAudioEnabled` simplificados; `clearRole()` + `exitToRoleSelection()` |
| 17 | `PresenceSyncWorker` + `PresenceScheduler` + `BootReceiver` + `NetworkCallback` + timeout 25s Receptor + chips Xiaomi |
| 18 | FCM `START_STREAM` claves alineadas (`action`, `type`, `sessionCode`, `sessionId`); `onTaskRemoved` + `AlarmManager` + `stopWithTask=false` |
| 19 | Logging `ViuwStream`, `SPECIAL_USE` standby, `ensureSignalRReady` siempre registra presencia |
| 20 | **`StreamSessionCoordinator`** unifica sesión WebRTC (FGS + StandbySignalR + FCM fallback); `SPECIAL_USE` siempre en standby; deduplicación por `sessionCode` |

---

## 12. Contacto / Referencias

- **Repo:** https://github.com/DanielGB230/ViuwApp
- **Backend SignalR Hub:** `backend/StreamSignaling.Api/Hubs/SignalingHub.cs`
- **Android WebRTC:** `android/app/src/main/java/com/example/streamapp/webrtc/WebRtcClient.kt`
- **Documentación oficial WebRTC Android:** https://webrtc.org/getting-started/android
- **SignalR Java Client:** https://github.com/SignalR/SignalR/tree/main/clients/java

---

> **Mantenido por:** Agente OpenCode / Equipo ViuwApp  
> **Formato:** Markdown — colocar en `doc/PROYECTO.md` junto a `android/` y `backend/`