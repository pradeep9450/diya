# Diya — your personal voice assistant for Android

Say **"Hey Diya"** and talk in **English, Hinglish or Hindi**. Everything is free:

| Part | Uses | Cost |
|---|---|---|
| Wake word "Hey Diya" | Vosk (offline, open source) | Free, no internet needed |
| Understanding you | Android's speech recognizer (English + Hinglish, or Hindi) | Free |
| Diya's voice | Android's text-to-speech (Indian English + Hindi voices) | Free |
| Weather / News | Open-Meteo / Google News | Free, no key |
| Calls, SMS, contacts, calendar, location | Your phone's own features | Free |
| WhatsApp | Notifications (read & reply) + WhatsApp links | Free |
| Any other question | Google Gemini API, free tier | Free (free key) |

---

## Install it on your phone

The project is source code. It has to be built into an **APK** (the Android install file) once. Pick one way.

### Way 1 — Phone only, no computer (GitHub builds it for you)

1. On your phone, open **github.com** and create a free account.
2. Create a **new repository** (the **+** button → *New repository*), name it `diya`, choose **Private**, and tap *Create*. Leave it empty.
3. Tell Claude the repository name (for example `yourname/diya`). Claude uploads the project, and GitHub builds the APK automatically in about 5 minutes.
4. On your phone, open the repository → **Releases** → **Diya build …** → tap **Diya.apk** to download.
5. Tap the downloaded file. Android asks to allow *Install unknown apps* for your browser or Files app; allow it, then tap **Install**.
   If **Play Protect** shows a warning, tap *More details → Install anyway*. That warning appears for any app that isn't from the Play Store.

Every later change builds a new APK the same way, and it installs over the old one, keeping your settings.

> If you'd rather upload it yourself from a computer: unzip the project, then on the empty repository page use *uploading an existing file* and drag in **everything inside** the `Diya` folder, including the hidden `.github` folder. The build starts by itself.

### Way 2 — With a computer (Android Studio)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip the project and open the `Diya` folder (**File → Open**). Wait for the sync to finish; it can take a few minutes the first time. If it offers to upgrade Gradle or the Android plugin, accept.
3. On your phone: Settings → About phone → tap *Build number* 7 times. Then Developer options → turn on **USB debugging**.
4. Connect the phone by USB, choose it at the top of Android Studio, and press **Run ▶**.
   Or use **Build → Build APK(s)**, copy `app-release.apk` (or `app-debug.apk`) to the phone, and tap it.

---

## First launch (5 minutes, once)

1. Open Diya and tap **Turn on Diya**. Allow the microphone and the other permissions it asks for.
2. The first time only, Diya downloads its wake-word model (about 36 MB).
3. Open **Settings** (gear icon):
   - **Name** and **home city**.
   - **Language**: *English + Hinglish* (default) or *हिंदी*.
   - **Get a free Gemini key**: sign in with Google, create a key, paste it. No payment needed.
   - **Grant permissions**: allows contacts, calls, SMS, calendar and location in one go.
   - **Special access**: tap *Allow* on each and switch Diya on:
     - *Notification access*: read and reply to WhatsApp and SMS.
     - *Display over other apps*: open apps, timers and calls hands-free.
     - *Do Not Disturb control*, *Modify system settings* (brightness).
     - *Unrestricted battery*: so the phone doesn't kill Diya.
   - **Change Diya's voice → Install voice data → Hindi**, so Hindi replies sound natural.
4. Say **"Hey Diya"**, wait for the chime, and speak. Or say it in one go: *"Hey Diya, kitne baje hain?"*

> **"Restricted setting" message?** Android 13+ blocks notification access for apps installed from a file. Open Android **Settings → Apps → Diya → ⋮ (top right) → Allow restricted settings**, then try again.

---

## Things you can say

**English**
- "What's the time?" · "What's the weather tomorrow?" · "Will it rain in Pune?"
- "Set a timer for 10 minutes" · "Set an alarm for 6:30 am" · "Remind me to call Mom at 6 pm"
- "Add meeting with Rahul tomorrow at 5 pm" · "What's on my calendar today?"
- "Call Mom" · "What's Rahul's number?" · "Any missed calls?" · "Answer the call"
- "Text Dad saying dinner is ready" · "Send a WhatsApp to Rahul saying I'll be late"
- "Read my WhatsApp messages" · "Reply to Rahul saying on my way"
- "Play Arijit Singh songs" · "Open Instagram" · "Navigate to MG Road" · "Where am I?"
- "Turn on the torch" · "Volume up" · "Brightness 50 percent" · "Turn on do not disturb" · "Take a selfie"
- "Remember that my keys are in the drawer" · "What did I ask you to remember?"
- Anything else, like "Explain black holes simply", goes to Gemini.

**Hinglish**
- "Kitne baje hain?" · "Kal ka mausam batao" · "Delhi mein barish hogi kya?"
- "5 minute ka timer lagao" · "Subah 6 baje ka alarm lagao" · "Shaam 7 baje dawai ki yaad dilana"
- "Mummy ko call karo" · "Rahul ko WhatsApp karo ki main late hoon" · "Papa ko message bhejo ki khana kha liya"
- "WhatsApp message padho" · "Missed call kisne kiya?"
- "Arijit Singh ke gaane bajao" · "YouTube kholo" · "India Gate ka rasta batao" · "Main kahan hoon?"
- "Torch jalao" · "Awaaz kam karo" · "Yaad rakhna ki chabi drawer mein hai" · "Kal ka plan kya hai?"

**हिंदी**
- "कितने बजे हैं?" · "दिल्ली का मौसम कैसा है?" · "5 मिनट का टाइमर लगाओ"
- "मम्मी को कॉल करो" · "पापा को व्हाट्सएप करो कि मैं आ रहा हूँ" · "अरिजीत सिंह के गाने बजाओ"

**Switching:** "Hindi mein baat karo" / "Speak in English". "Ruko" or "Stop" stops Diya.

Diya replies in English when you speak English, and in Hindi when you speak Hindi or Hinglish. Before sending any SMS or WhatsApp reply, she reads it back and asks; say **"haan"** / **"yes"** to send.

---

## Good to know

- **WhatsApp sending:**
  - If that person messaged you recently, Diya replies hands-free through the notification.
  - Otherwise she opens the chat with your message typed in, and you tap *Send*. WhatsApp has no free API for sending directly.
- **Wi-Fi and Bluetooth:** Android doesn't let apps switch these, so Diya opens their quick panel instead.
- **Reminders:**
  - Reminders at a time go into your calendar with an alert, so you need a calendar app such as Google Calendar.
  - "Remind me in 10 minutes" uses a labelled timer instead.
- **Incoming calls:** Diya says who is calling. You can say "Hey Diya, answer the call". She pauses listening during calls.
- **Notification:** Android requires a visible notification while Diya listens. It has *Talk* and *Turn off* buttons.
- **After restarting the phone:** open Diya and tap *Turn on Diya* again. Android doesn't allow microphone apps to start themselves.
- **Gemini free tier:**
  - It has daily limits, which are plenty for one person.
  - Google may use free-tier questions to improve its products, so don't ask it anything private.
  - All the built-in commands above work without Gemini.
- **Wake word tuning:** the grey line under the orb shows what the wake-word engine heard. If "Hey Diya" shows up as something else, add that text in *Settings → Extra wake word spellings*.

## Making Diya smarter

| File | What it does |
|---|---|
| `Understand.kt` | Turns what you said into a command (all the English rules) |
| `Hindi.kt`, `Translit.kt` | Hindi / Hinglish → the English form the rules understand |
| `Brain.kt` | Carries out each command and writes the reply (English + Hindi) |
| `PhoneActions.kt` | Calls, SMS, calendar, location, timers, torch, volume… |
| `NotificationWatcher.kt` | Reads and replies to WhatsApp/SMS notifications |
| `DiyaService.kt` | The background loop: wake word → listen → think → speak |
| `WebServices.kt` | Weather, news, Gemini |
