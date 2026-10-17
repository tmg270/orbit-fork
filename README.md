# GrokLink

**An Android assistant that talks only to xAI's Grok.** Long-press your side button (or use the
home-screen widget, Quick Settings tile or Android Auto) and ask Grok about whatever is on your
screen, have it set timers, alarms and calendar events, and keep full conversations in the app.

> GrokLink is an unofficial, modified fork of [Orbit Assistant](https://github.com/lpnovi/Orbit-Assistant)
> by lpnovi, used under the Mozilla Public License 2.0. It is **not affiliated with, endorsed by or
> supported by xAI**. "Grok" and "SuperGrok" are xAI trademarks and are used only to describe
> compatibility. Because xAI's brand guidelines do not allow "Grok" in the name of third-party apps,
> GrokLink is for personal sideloading and must not be published on an app store under this name.

## What it does

- **Grok only.** No ChatGPT, OpenRouter, Anthropic, relay server or on-device model. Every answer
  comes from xAI.
- **SuperGrok sign-in, no API key needed.** Sign in with your SuperGrok account using a short code in
  your browser, or paste an xAI developer API key from [console.x.ai](https://console.x.ai) instead.
- **Side-button assistant** over any app, with optional screen text and screenshots.
- **Phone actions** that Grok proposes and GrokLink validates: timers, alarms, calendar events,
  settings, media and more.
- **Live thinking** while Grok works, on models that send their reasoning (for example grok-4.7).
- **Android Auto (experimental):** ask Grok by voice from Auto's media apps and hear the answer
  through the car speakers.
- Conversations, Memory, Vault, Routines, widgets, tiles and Theme Studio, inherited from Orbit.

## Install (sideload)

GrokLink is not on the Play Store. Builds come from this repository's GitHub Actions.

1. Open the latest successful run of
   [Build GrokLink APK](https://github.com/tmg270/orbit-fork/actions/workflows/build-apk.yml) while
   signed in to GitHub, and download the `GrokLink-v<version>-debug` artifact.
2. Unzip it and open `GrokLink-<version>-debug.apk` on your phone. Allow installs from that app when
   Android asks.
3. GrokLink installs as `com.tmg270.orbit`, next to the original Orbit if you have it.

Builds are signed with a fixed test key, so a newer build installs over an older one and keeps your
sign-in and chats. That key is public in this repository, so only install builds from this repository.

**Notification access** is blocked by Android for sideloaded apps until you allow it: Settings >
Apps > GrokLink > ⋮ > *Allow restricted settings*, then turn notification access on again.

## Sign in with SuperGrok

1. Open GrokLink. Setup starts with **Connect Grok**. Tap **Sign in with SuperGrok**.
2. GrokLink shows a short code and opens xAI's sign-in page in your browser.
3. Approve the code there, then switch back to GrokLink. It finishes on its own within a few seconds.

Tokens are encrypted with Android Keystore and never backed up. Signing out revokes them with xAI.
Some SuperGrok plans may not be allowed to use xAI's API; if xAI refuses your plan, GrokLink says so
and an xAI API key works instead.

The sign-in reuses the public OAuth client of xAI's Grok CLI. xAI could change or block this at any
time, and using another product's client may not be what xAI's terms intend.

## Android Auto (experimental)

Android Auto only shows sideloaded apps after you turn on its developer settings:

1. On your phone open the **Android Auto** settings and tap **Version** ten times to unlock developer
   mode.
2. Open the ⋮ menu > **Developer settings** and switch on **Unknown sources**.
3. Open GrokLink on your phone once, sign in and allow the microphone.
4. In the car, open **GrokLink** from Auto's media apps and tap **Ask Grok**.

Android Auto never lets third-party apps take over the steering-wheel voice button or "Hey Google".
A Google update could stop sideloaded car apps working at any time.

## Building

Push to `main` or run **Build GrokLink APK** manually from the Actions tab. The workflow builds the
debug APK, runs the unit tests (a failing test fails the build), and uploads
`GrokLink-v1.0.<run>-groklink-debug`. Each run gets its own version (`1.0.<run>-groklink`, version
code `1000 + run`).

Locally: JDK 17 and the Android SDK, then `./gradlew assembleDebug testDebugUnitTest`. On Windows,
`BUILD_GROKLINK.cmd` (a wrapper around `tools/build_groklink.ps1`) sets the tools up for you.

## Licence and credits

GrokLink is based on [Orbit Assistant](https://github.com/lpnovi/Orbit-Assistant) by lpnovi.
Both are licensed under the [Mozilla Public License 2.0](LICENSE); modified files stay under MPL-2.0
and their source is available here. See [NOTICE](NOTICE).
