# adb: installing Android apps from this machine (WSL)

Tested 2026-11-03 with a Galaxy S21 Ultra (SM-G998U1) and platform-tools 37.0.1.

## What works: Windows adb over wireless debugging

WSL's adb (`~/Android/Sdk/platform-tools/adb`) sees the phone over USB once it is
attached with usbipd, but stays `unauthorized`: the phone never showed the
"Allow USB debugging?" prompt for it. Wireless pairing from WSL also failed (TLS
handshake rejected). Windows' own adb, run from WSL, pairs and installs fine.

Windows platform-tools live in `C:\Users\dwils\android-platform-tools\platform-tools\`.

```bash
A=/mnt/c/Users/dwils/android-platform-tools/platform-tools/adb.exe
```

1. Phone: Developer options -> Wireless debugging -> on -> **Pair device with
   pairing code**. Use the IP:port shown **in that dialog**, not the one on the
   Wireless debugging screen: that one is the connect port, and pairing against
   it fails with `protocol fault (couldn't read status message)`.
   ```bash
   "$A" pair <ip>:<pairing-port> <code>
   ```
2. adb finds the connect port by mDNS and connects by itself; check with
   `"$A" devices -l`. Pairing is remembered, so later sessions only need wireless
   debugging switched on.
3. adb.exe takes Windows paths, so copy the APK somewhere under `C:` first:
   ```bash
   cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/dwils/android-platform-tools/
   "$A" install -r 'C:\Users\dwils\android-platform-tools\app-debug.apk'
   ```

## Gotchas

- `libpenguin.so not found` in an app's logcat on this Samsung is system noise, not
  the app.
- usbipd: sharing a device the first time (`usbipd bind --busid <id>`) needs an
  admin Windows prompt; attaching (`usbipd.exe attach --wsl --busid <id>`) does
  not. While attached, Windows cannot use the device.
