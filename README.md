# OneUisland — Dynamic Island for One UI 9

**English** | [Русский](README.ru.md)

An LSPosed module (legacy Xposed API) for One UI 9 / Android 17. It replaces the Now Bar capsule in the status bar with a dynamic island around the front camera, in the style of ColorOS 17 and HyperOS.

Get the ready-made APK from [Releases](../../releases).

## Features

- Shows live notifications in the island, like the Now Bar. Lock-screen-only live activities (`android.ongoingActivityNoti.style ≥ 2`) are skipped.
- Shows music, calls, screen recording and casting.
- Splits the island in two with a liquid bridge. A tap opens a stack of glass cards that flow out of the camera.
- ColorOS 17 style player with the app's own buttons and a seekable progress bar.
- The equalizer in the capsule reacts to the real audio.
- Shows apps' own RemoteViews layouts in the card (for example, navigation).
- Swipe left on the capsule to minimize an activity, swipe right to bring it back.
- Closing an app flies it into the island, using the One UI Home animation.
- Status bar icons fade out smoothly when the island covers them.
- Flashlight island: tap the flashlight to switch it on or off, drag along the beam to pick one of the brightness steps or slide to the flashlight to turn it off. The same card replaces the flashlight notification in the shade.
- Samsung Voice Recorder island: a red waveform that scrolls from right to left with the recording timer, pause and stop buttons in the card, and a short "saved" check after stopping. With Voice Recorder added to the module scope in LSPosed the waveform follows the real microphone level.
- Stopwatch shows milliseconds in the island, the card and the shade. Metric notifications show the chip text the app sends.
- Short alerts: Do Not Disturb, sound / vibrate / mute, low battery (20% and 10%) and headphone battery (AirPods via LibrePods, Galaxy Buds, Fast Pair).
- Live notifications in the shade are styled as dark glass island cards: light text, a thin rim and a solid background when blur is off. App layouts, expansion and actions are kept. Regular notifications and the lock screen keep the system look.

## Settings

Open the module settings from LSPosed:

- Island length (in characters).
- Alert toggles: headphone battery (every unlock or only on connect), Do Not Disturb, sound mode, low battery.
- Island position: center or left after the clock (grows only to the right, stops before the camera). Tablets and the inner screen of foldables also get "around the front camera". Foldables have separate settings for the inner and cover screens. The left island can use a blurred background instead of black.

## Installation

1. Download and install the APK from [Releases](../../releases).
2. Enable the module in LSPosed. Scope: **System UI**.
3. Restart System UI (no reboot needed), for example:
   `adb shell su -c "pkill -f com.android.systemui"`

## Building

You need JDK 17–21 and the Android SDK (platform 36). Set the SDK path in `local.properties`, then run:

```
gradlew.bat :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

To check the shade styling logic on a connected Android 17 device without installing the APK or restarting System UI, run `./tests/device/run.ps1` (PowerShell, JDK in `JAVA_HOME`). Details and the list of visual checks are in `tests/device/README.md`.

## Structure

- `app/src/main/java/dev/flexy/dynamicisland/xposed` — entry point and System UI hooks (`SystemUiHooks`).
  - `ShadeLiveCards` — live notification styling in the shade (Samsung ongoing activity / AOSP promoted ongoing).
- `app/src/main/java/dev/flexy/dynamicisland/island` — the island:
  - `IslandView` — drawing, animations, gestures;
  - `IslandController` — data and signals;
  - data sources: `OaSource`, `MediaSource`, `CastSource`, `AlertSource`;
  - blur, equalizer and app exit animation targets.
- `stubs` — Xposed API stubs, used only at compile time.
