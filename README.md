# AI Camera — Multi-Device Shared AI Camera Workspace

A complete Android + FastAPI application that uses **physical Volume buttons** to capture photos and submit them concurrently to **Google Gemini** and **Groq** for live AI analysis, synchronised across multiple devices via Firebase.

---

## Architecture Overview

```
┌─────────────────────┐        REST / WebSocket        ┌──────────────────────┐
│   Android App       │ ◄──────────────────────────── │   FastAPI Backend    │
│   (Kotlin/Compose)  │                                │   (Python)           │
│                     │                                │                      │
│  Volume Up  → Camera│                                │  Firebase Admin SDK  │
│  Volume Down→ Submit│                                │  Gemini SDK          │
│                     │                                │  Groq SDK            │
│  Firebase Auth      │                                │  WebSocket Manager   │
│  Firestore listener │ ◄── real-time Firestore ──► │  Firestore Service   │
│  CameraX            │                                │  Storage Service     │
└─────────────────────┘                                └──────────────────────┘
         ▲ ▼                                                    ▲ ▼
   Firebase Auth                                        Firebase Auth
   Firestore                                            Firestore
   Storage                                              Storage
```

---

## Volume Button Workflow

| Press | Action |
|-------|--------|
| Vol ▲ × 1 | Opens rear camera preview — **no photo taken** |
| Vol ▲ × 2 | Captures Photo 1 |
| Vol ▲ × 3 | Captures Photo 2 |
| Vol ▲ × N | Captures Photo N-1 |
| Vol ▼     | Uploads batch → Gemini + Groq run **concurrently**, responses stream live |

---

## Project Structure

```
aicamera/
├── android/                      Android Studio project
│   ├── app/src/main/
│   │   ├── java/com/aicamera/app/
│   │   │   ├── MainActivity.kt          Volume key interception
│   │   │   ├── AICameraApplication.kt   Firebase init
│   │   │   ├── camera/
│   │   │   │   └── CameraManager.kt     CameraX wrapper
│   │   │   ├── data/
│   │   │   │   ├── model/Models.kt      Data classes + enums
│   │   │   │   └── repository/
│   │   │   │       └── SessionRepository.kt  All data ops
│   │   │   ├── network/
│   │   │   │   ├── ApiService.kt        Retrofit interface
│   │   │   │   ├── RetrofitClient.kt    HTTP client
│   │   │   │   └── WebSocketClient.kt   WS with auto-reconnect
│   │   │   ├── viewmodel/
│   │   │   │   ├── CameraViewModel.kt   Volume button state machine
│   │   │   │   └── AuthViewModel.kt     Firebase Auth
│   │   │   └── ui/
│   │   │       ├── screens/
│   │   │       │   ├── LoginScreen.kt
│   │   │       │   ├── WorkspaceScreen.kt
│   │   │       │   ├── ResultsScreen.kt
│   │   │       │   └── SettingsScreen.kt
│   │   │       ├── components/
│   │   │       │   ├── CameraPreviewComposable.kt
│   │   │       │   ├── PhotoThumbnailRow.kt
│   │   │       │   └── AiResponsePanel.kt
│   │   │       └── theme/Theme.kt
│   │   └── AndroidManifest.xml
│   └── gradle/libs.versions.toml
│
├── backend/                      FastAPI server
│   ├── main.py                   Routes + WebSocket endpoint
│   ├── config.py                 All settings from .env
│   ├── auth/firebase_auth.py     Token verification dependency
│   ├── sessions/session_manager.py
│   ├── storage/image_storage.py  Firebase Storage helpers
│   ├── ai/
│   │   ├── ai_orchestrator.py    Concurrent Gemini + Groq runner
│   │   ├── gemini_client.py      Streaming Gemini client
│   │   └── groq_client.py        Streaming Groq client (auto-batches >3 images)
│   ├── realtime/websocket_manager.py  Broadcast hub
│   ├── database/firestore_service.py  All Firestore ops
│   └── requirements.txt
│
└── firebase/
    ├── firestore.rules           Security rules
    ├── firestore.indexes.json    Composite indexes
    ├── storage.rules
    └── firebase.json
```

---

## Setup Guide

### Step 1 — Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com) and create a project.
2. **Authentication** → Enable *Email/Password* and *Google* providers.
3. **Firestore Database** → Create in production mode.
4. **Storage** → Enable with default bucket.
5. **Project Settings → Service accounts** → Generate new private key → save as `backend/firebase-service-account.json`.
6. **Project Settings → General** → Add Android app with package `com.aicamera.app` → download `google-services.json` → place at `android/app/google-services.json`.
7. Copy the **Web Client ID** from *Authentication → Sign-in method → Google → Web client ID* → paste into `android/app/src/main/res/values/strings.xml` as `default_web_client_id`.

### Step 2 — Deploy Firebase Rules

```bash
cd firebase
firebase deploy --only firestore:rules,firestore:indexes,storage
```

Or paste `firestore.rules` and `storage.rules` manually in the Firebase console.

### Step 3 — Backend Setup

```bash
cd backend
python -m venv .venv
# Windows
.venv\Scripts\activate
# macOS/Linux
source .venv/bin/activate

pip install -r requirements.txt

# Copy and fill in your secrets
copy .env.example .env
```

Edit `.env`:

```env
FIREBASE_SERVICE_ACCOUNT_PATH=firebase-service-account.json
FIREBASE_STORAGE_BUCKET=your-project-id.appspot.com

GEMINI_API_KEY=your_gemini_key          # console.cloud.google.com → AI Studio
GROQ_API_KEY=your_groq_key             # console.groq.com

GEMINI_MODEL=gemini-1.5-flash
GROQ_MODEL=meta-llama/llama-4-scout-17b-16e-instruct
```

Start the server:

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

The server starts at `http://0.0.0.0:8000`. API docs: `http://localhost:8000/docs`.

### Step 4 — Android Build

1. Open `android/` in Android Studio (Hedgehog or newer recommended).
2. Confirm `google-services.json` is at `android/app/google-services.json`.
3. Open `android/app/build.gradle.kts` and set `BACKEND_BASE_URL`:
   - **Emulator:** `http://10.0.2.2:8000` (already set as default)
   - **Physical device on same Wi-Fi:** `http://YOUR_PC_IP:8000`
   - **Production:** `https://your-backend-domain.com`
4. Build & run: `./gradlew assembleDebug` or use the Run button.

---

## Changing AI Models (No Code Edit Required)

Models are configured entirely through environment variables in `backend/.env`:

```env
# Switch Gemini model
GEMINI_MODEL=gemini-1.5-pro

# Switch Groq model
GROQ_MODEL=llava-v1.5-7b-4096-preview
```

Restart the backend — the Android app requires no changes.

---

## Multi-Device Synchronisation

1. Install the app on two or more devices.
2. Sign in with the **same Firebase account** on each device.
3. The app automatically joins the same active session.
4. Photos captured on Device A appear on Device B via:
   - Backend uploads to Firebase Storage
   - Backend broadcasts `photo_added` WebSocket event to all session subscribers
   - Device B receives the event and shows the download URL thumbnail
5. When Volume ▼ is pressed, both AI responses stream to **all connected devices simultaneously**.
6. A device that reconnects after being offline receives full session state on WebSocket connect via the `session_state` message.

---

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| POST | `/sessions` | Create or get active session |
| GET | `/sessions/{id}` | Get session + photos |
| POST | `/sessions/{id}/join` | Join an existing session |
| POST | `/sessions/{id}/photos` | Upload a photo |
| DELETE | `/sessions/{id}/photos/{photoId}` | Delete a photo |
| POST | `/batches` | Submit batch for AI analysis |
| GET | `/batches/{id}` | Poll batch + AI responses |
| WS | `/ws/{sessionId}` | Real-time updates |
| GET | `/health` | Health check |

All HTTP routes (except `/health`) require `Authorization: Bearer <Firebase ID token>`.

### WebSocket Message Types

**Client → Server**
```json
{ "type": "auth", "token": "<Firebase ID token>" }
```
Send as the **first message** after connecting. The connection is closed if this is not received within 10 seconds.

**Server → Client**
| Type | Description |
|------|-------------|
| `connected` | Auth accepted, session confirmed |
| `session_state` | Full state snapshot on reconnect |
| `photo_added` | New photo uploaded (any device) |
| `photo_deleted` | Photo removed |
| `batch_status` | Batch state changed (submitted / processing / completed / error) |
| `ai_chunk` | Incremental text from one AI provider |
| `ai_complete` | Provider finished — full text included |
| `ai_error` | Provider error — error message included |

---

## Acceptance Test Checklist

| # | Test | Expected |
|---|------|----------|
| 1 | Vol ▲ × 1 | Camera preview opens, **no** photo taken |
| 2 | Vol ▲ × 2 | Photo 1 captured, thumbnail appears |
| 3 | Vol ▲ × 3, 4, 5 | Photos 2, 3, 4 captured; counter updates; camera stays open |
| 4 | Vol ▼ | All photos submitted to both models |
| 5 | Watch panels | Gemini and Groq stream independently; both show "Analysing" then text |
| 6 | Second device (same account) | Same thumbnails, same AI responses appear in real-time |
| 7 | Close + reopen second device | Session restored; previous results displayed |
| 8 | Disconnect device, wait, reconnect | Syncs to latest state automatically |
| 9 | Gemini quota hit | Groq continues; Gemini panel shows error message |
| 10 | Submit 4 photos | All 4 appear in both AI responses; none silently dropped |

---

## Free API Tier Notes

### Google Gemini
- Free tier: Gemini 1.5 Flash — 15 requests/minute, 1 million tokens/day.
- Get key at: [Google AI Studio](https://aistudio.google.com/app/apikey).
- The backend handles `ResourceExhausted` errors with exponential back-off and retries.

### Groq
- Free tier: generous daily token limit; rate limits vary by model.
- Get key at: [console.groq.com](https://console.groq.com).
- The backend auto-batches image lists into groups of ≤ 3 when needed.
- The backend retries on `RateLimitError` with back-off.

---

## Security Notes

- API keys live **only on the backend** — they are never sent to the Android app.
- All backend routes verify Firebase ID tokens via `firebase-admin`.
- Firestore Security Rules block direct client writes to `photos`, `batches`, and `ai_responses` — all writes go through the backend which uses the Admin SDK and bypasses client rules.
- Storage rules block direct client uploads — all uploads go through the backend `/photos` endpoint.
- The `.env` file and `firebase-service-account.json` are listed in `.gitignore`.

---

## Development Stages (Completed)

- [x] Stage 1: Android camera and volume button controls
- [x] Stage 2: Multiple photo capture and thumbnail display
- [x] Stage 3: Gemini image analysis
- [x] Stage 4: Groq Qwen image analysis
- [x] Stage 5: Parallel requests and live response streaming
- [x] Stage 6: Firebase authentication and shared sessions
- [x] Stage 7: Multi-device real-time synchronisation
- [x] Stage 8: Complete testing checklist and packaging
