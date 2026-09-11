# OwnerLink Android agent

Open this `android` folder in Android Studio (`minSdk` 30).

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

1. Install the APK.
2. Enter the OwnerLink URL (`https://…` in production; debug may use LAN `http://`).
3. Grant photos/videos, all-files or selected folders, notifications.
4. Enable the OwnerLink accessibility service for remote screen/control.
5. Start remote access and scan the QR from the website.
