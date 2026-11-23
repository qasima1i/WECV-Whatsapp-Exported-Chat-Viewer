# WECV — WhatsApp Exported Chat Viewer

**Open any exported WhatsApp chat on Android — offline, private, exactly as it was.**

Export a chat from WhatsApp (⋮ → More → Export chat), open the ZIP (or the plain
`.txt`) in WECV, pick which sender is you, and the conversation appears in a
familiar chat view with the correct side for every message.

WECV is an independent viewer. It is **not** affiliated with WhatsApp LLC or Meta.

## Features

- Import a full export **ZIP** (with media) or a plain **.txt** file — media can be added later
- Personal and **group** chats (in-bubble sender names)
- Exact dates and times, original order — nothing added, changed or removed
- **Voice notes** play in-chat with waveform and seek (state-driven player)
- Photos and videos full-screen, swipe between photos, save / share
- **Animated stickers** animate (AnimatedImageDrawable)
- Media gallery with **Media | Voice** tabs and *Go to message*
- **Starred messages**, in-chat search, calendar date jump
- Dark / light themes, bubble colours, long-press Copy / Delete / Star
- **100% offline** — the app has no INTERNET permission and collects no data

## Privacy

WECV works fully offline. Chats are parsed and stored only in the app's private
storage on your own device. Nothing is uploaded, tracked or shared. See
`PRIVACY.md`.

## Building

Plain Java + the Android SDK command-line build tools — no Gradle, no Android
Studio required.

Requirements: JDK 17, Android SDK platform 36, build-tools 36 (`aapt2`, `d8`,
`zipalign`, `apksigner`), and `bundletool` for the Play Store bundle.

```bash
# set these to your own paths
export ANDROID_JAR=$ANDROID_SDK/platforms/android-36/android.jar
export BT=$ANDROID_SDK/build-tools/36.0.0

# resources
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/app-unsigned.apk -I $ANDROID_JAR \
    --manifest AndroidManifest.xml -R build/res.zip -A assets --auto-add-overlay

# code
javac -encoding UTF-8 -cp $ANDROID_JAR -d build/classes $(find src -name '*.java')
$BT/d8 --lib $ANDROID_JAR --min-api 24 --output build/dex build/classes.jar
# ...add classes.dex to the APK, zipalign, then sign with your own keystore:
$BT/apksigner sign --ks your.keystore --out WECV.apk build/app-aligned.apk
```

> The signing keystore is **not** included in this repository. Create your own
> (`keytool -genkeypair -keystore wecv.keystore -alias wecv`) and never commit it.

## Project layout

- `src/com/wecv/viewer/` — all Java sources (parser, chat UI, gallery, viewers)
- `res/` — launcher icons
- `assets/emoji/` — emoji images used to render chat emoji consistently
- `AndroidManifest.xml` — package `com.wecv.viewer`, minSdk 24, targetSdk 36

## License

The source code is released under the [MIT License](LICENSE).
Emoji images in `assets/emoji/` are the property of their respective owners and
are included only so the app can render chat emoji; they are not covered by the
MIT license.
