# Status Gallery

A local Android gallery for available WhatsApp status photos and videos. Built with Java and Android platform APIs, with no runtime dependencies or network permission.

## Features

- Adaptive launcher icon, including Android themed-icon support.
- Rounded thumbnail grid with All, Photos, and Videos filters.
- Full-screen photo/video viewing, photo swipes, and previous/next navigation.
- Save a copy through Android's file picker.
- Long-press multi-selection and persistent, manually assigned person groups.
- Local baseline comparison to highlight new or changed cached files.
- Folder access through Android's system picker; no WhatsApp login or contacts permission.

## Use

Open **More > Choose folder**, then select `Android/media/com.whatsapp/WhatsApp/Media` or its `.Statuses` folder. Some installations use `WhatsApp/Media` at shared-storage root. Enable hidden files in the picker if available.

Tap a thumbnail to view it. Long-press to select statuses and assign a name. Labels apply to those files only; new posts start **Unassigned**. Select **More > Help** for the baseline experiment.

## Build on Windows

Requirements: Android SDK platform 36, Build Tools 36.0.0, and a JDK (the Android Studio bundled JDK works).

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

The script uses `ANDROID_HOME` and `JAVA_HOME` if set, otherwise standard Windows Android SDK/Android Studio locations. Override them when needed:

```powershell
.\build.ps1 -SdkPath 'C:\path\to\Android\Sdk' -JdkPath 'C:\path\to\jdk'
```

Output: `build/status-probe.apk`. The existing application ID is retained for in-place upgrades. The build creates a local debug signing key; keep that key to install future updates over the same app. Build output, keys, local device data, and pairing credentials are excluded from Git.

```powershell
adb -s DEVICE install --no-incremental --no-streaming -r build/status-probe.apk
```

## Scope and limitations

Android 8+; version 0.4. This is a sideloaded prototype targeting API 32, not a Play Store release. The grid currently creates views for every available item, so very large caches may need a recycled grid in a future version.

The app reads media already exposed by the selected folder. It cannot fetch missing statuses, determine WhatsApp's viewed state, automatically identify posters, or guarantee anonymity. A NEW badge means the file changed since a local baseline, not that the status is unseen. Names are manual labels tied to file URI, size, and modification time. Text-only statuses are not supported. WhatsApp may remove expired cached media; save a copy to retain it.

## Verification

The version 0.4 APK was compiled, signature-verified, installed over the earlier prototype, and launched on a connected Android phone. On-device inspection confirmed gallery layout, photo filtering, loaded media thumbnails, and the full-screen viewer. Full automated device coverage is not included.

## Implementation references

- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [AAPT2 packaging](https://developer.android.com/tools/aapt2)

Not affiliated with WhatsApp or Meta.
