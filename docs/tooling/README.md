# Tooling Reference

CLI workflows that work for this setup, tested on this machine. Avoids re-discovering flags and patterns across projects.

| File | Tool | Key notes |
|------|------|-----------|
| [arduino-cli.md](arduino-cli.md) | arduino-cli | Always use `bin/arduino-cli`; compile + upload commands; FQBN lookup |
| [serial-monitor.md](serial-monitor.md) | Serial monitoring | Python pyserial patterns; DTR reset trick; XIAO USB CDC gotchas |
| [adb.md](adb.md) | adb (Android installs) | WSL adb stays unauthorized; use Windows adb.exe over wireless debugging; pair with the dialog's port |
| [platformio.md](platformio.md) | PlatformIO | Python 3.13 requirement; pioarduino platform pin; esptool NVS wipe |
