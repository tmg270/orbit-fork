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

- **Grok only for the cloud.** No ChatGPT, OpenRouter, Anthropic or relay server. Simple commands
  (timers, alarms, playback, calls and texts) run on the phone with no connection. An optional
  1.60 GB on-device model can be downloaded for the ones the phrase matchers miss. Anything else
  still goes only to xAI.
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

GrokLink is not on the Play Store. The first install is a file. After that, the app can fetch a
newer build itself.

1. Open the latest [groklink release](https://github.com/tmg270/orbit-fork/releases/tag/groklink)
   and download the `GrokLink-*-debug.apk`. Until that release exists, use the latest successful
   [Build GrokLink APK](https://github.com/tmg270/orbit-fork/actions/workflows/build-apk.yml) run,
   signed in to GitHub, and unzip the `GrokLink-v<version>-debug` artifact.
2. Open the APK on your phone. When Android asks, allow that app (Files, Chrome, or whichever one
   you opened the file with) to install unknown apps, then tap Install.
3. GrokLink installs as `com.tmg270.orbit`, next to the original Orbit if you have it.

Builds are signed with a fixed test key, so a newer build installs over an older one and keeps your
sign-in and chats. That key is public in this repository, so only install builds from this repository.

## Updates

A green push to the `groklink` branch publishes a release the phone can see. You do not need to
open Actions or paste a link.

1. Open GrokLink. If a newer build is published, it says so on the chat list. You can also open
   **Settings → About → Check for update** and compare the installed version with the published one.
2. Tap **Download and install**. The file stays in the app.
3. Android shows its own Install screen. Tap **Install** once. Your sign-in and chats stay.

The first sideload allowed some other app to install files. That does not allow GrokLink. The first
time GrokLink itself installs an update, Android asks you to allow **GrokLink**:

- In the app, tap **Allow unknown apps**. It opens GrokLink's page. Turn the switch on, come back,
  and tap **Download and install** again.
- Or open it yourself: **Settings → Apps → Special app access → Install unknown apps → GrokLink**.
- On Samsung (One UI): **Settings → Security and privacy → More security settings → Install unknown
  apps → GrokLink**. Some phones put the same switch on **Settings → Apps → GrokLink**.

Allowing Chrome, My Files, or Drive does not count. The switch has to be for GrokLink. Android
still asks you to tap Install. GrokLink does not install a build silently.

The check uses a public release page. There is no token in the app. If no build has been published
yet, About says so and the Actions artifact above is the way to install.

## Report a bug

**Report a bug** is on the chat list and in a chat's menu, and in the car as its own media item.
Describe the problem, hear it read back, then Send. When a GitHub token is saved on that screen,
Send files a GitHub issue on tmg270/orbit-fork, which is the path to use. A copy is still written
to Downloads/GrokLink, and you can share it if GitHub did not take the report. Without a token,
the Downloads copy is the whole result. The token is optional and stays on the phone.

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

Listening in the car uses the British speech model shipped in the app, so speech does not need a
network. If that model cannot hear you and the phone is online, GrokLink tries the phone's speech
recogniser once.

**Report a bug** works in the car the same way as on the phone: describe it, hear it back, then Send.
With a saved GitHub token it is filed as an issue. The Downloads copy is still there to share.

Say **navigate to**, **directions to**, **drive to**, or **how long to** a place. Google Maps starts
guidance, or opens the route when you asked for the time. Say **with Waze** when you want Waze.
GrokLink does not calculate the journey time itself. **Take me to** and **go to** still open an app.

Playing an album or playlist by name queues that album or playlist in Poweramp. A setlist Grok
looks up becomes a Poweramp playlist of the songs that are actually on the phone, including when
you leave the chat while Grok is still looking them up. If a title is not in the library, that
song is searched for in Poweramp instead.

Android Auto never lets third-party apps take over the steering-wheel voice button or "Hey Google".
A Google update could stop sideloaded car apps working at any time.

## Building

Push to `groklink` or run **Build GrokLink APK** manually from the Actions tab on that branch.
The workflow builds the debug APK, runs the unit tests (a failing test fails the build), and uploads
`GrokLink-v1.0.<run>-groklink-debug`. A successful push to `groklink` also publishes the
[groklink release](https://github.com/tmg270/orbit-fork/releases/tag/groklink) the phone checks.
Pull requests only build; they do not publish. Each run gets its own version (`1.0.<run>-groklink`
once the run is past 1.0.34, otherwise `1.0.35-groklink`). The version code is never below 1035.

Locally: JDK 17 and the Android SDK, then `./gradlew assembleDebug testDebugUnitTest`. On Windows,
`BUILD_GROKLINK.cmd` (a wrapper around `tools/build_groklink.ps1`) sets the tools up for you.

## Licence and credits

GrokLink is based on [Orbit Assistant](https://github.com/lpnovi/Orbit-Assistant) by lpnovi.
Both are licensed under the [Mozilla Public License 2.0](LICENSE); modified files stay under MPL-2.0
and their source is available here. See [NOTICE](NOTICE).
