# PhoneAgent — Milestone 1

A personal AI agent for Android. Type or speak a command; Claude picks a tool; the app runs it.
Messages are NEVER sent without your tap on **Confirm**.

## Architecture
```
You (text / mic)
   |
MainActivity  (chat UI, mic, STOP button, Confirm/Cancel card)
   |
Agent         (loop: send history -> Claude -> tool call -> run -> result -> Claude ...)
   |-- ClaudeClient  (HTTPS call to the Anthropic Messages API)
   |-- Tools         (open_app, web_search, prepare_message)
   `-- Actions       (Android intents + contacts lookup)
SecureStore   (API key, encrypted with Android Keystore)
```

## Files
| File | Role |
|---|---|
| app/src/main/java/com/khaled/phoneagent/MainActivity.kt | Screen, mic, settings, confirmation card |
| .../Agent.kt | Agent loop, tool definitions, emergency stop |
| .../ClaudeClient.kt | API call |
| .../Actions.kt | Open apps, search, contacts, message intents |
| .../SecureStore.kt | Encrypted API key |
| app/src/main/AndroidManifest.xml | Permissions (Internet, Contacts) |

## Build option A — Android Studio (on your PC)
1. Install Android Studio (latest stable): https://developer.android.com/studio
2. File > Open > select the `PhoneAgent` folder. Wait for "Gradle sync" to finish (first time = several minutes).
3. Build > Build Bundle(s)/APK(s) > Build APK(s).
4. APK path: `app/build/outputs/apk/debug/app-debug.apk`. Send it to your phone and install it.

## Build option B — no install, GitHub builds it for you
1. Create a free GitHub account, create a new repository, upload ALL files (keep the `.github` folder).
2. Open the repo > Actions tab > "Build APK" > Run workflow (it also runs on every push).
3. When it is green, open the run and download the artifact `phoneagent-debug-apk`, unzip it, install `app-debug.apk`.

## Install on Xiaomi
- Open the APK file > allow "Install unknown apps" for the app you opened it from (Files / Chrome).
- If Play Protect warns about an unknown app: "Install anyway" (it is your own app).
- Open PhoneAgent > paste your API key (console.anthropic.com > API keys) > Save.
- Allow Contacts when asked.

## Xiaomi settings (needed from later phases; harmless now)
- Settings > Apps > Manage apps > PhoneAgent > Other permissions > **Display pop-up windows while running in the background**: Allow
- Same page > Battery saver: **No restrictions**; enable **Autostart**
- Microphone button needs the Google app / "Speech Services by Google" (global ROMs have it).

## Test checklist (do these in order)
1. Type `Open WhatsApp` -> WhatsApp opens.
2. Tap the mic, say "Open Chrome" -> Chrome opens.
3. `Search YouTube for networking tutorial` -> YouTube results open.
4. `Send <a contact> a WhatsApp message saying I'll arrive at 5` -> card shows contact, number, message.
   Tap Cancel -> nothing happens. Repeat and tap Confirm -> WhatsApp opens with the text; you press Send.
5. Type a request, then press STOP quickly -> "Stopped".
Only after all five work, we start Phase 2.

## Honest limits
- Android has no official way to press "Send" inside WhatsApp/Telegram. The app opens the chat with the text filled; you press Send. Phase 3 can add an Accessibility Service to tap Send after your confirmation (works, but fragile and may break when WhatsApp updates).
- Local numbers without "+" get country code 216 (Tunisia). Change `DEFAULT_COUNTRY_CODE` in Actions.kt if needed.
- Model is set in Agent.kt (`MODEL`). API usage is billed to your Anthropic account.
- This code was written without an Android SDK, so it has not been compiled yet. If the build shows errors, send me the screenshot/log and I fix them.
