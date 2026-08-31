# Erdvyn Launcher

Windows launcher for Erdvyn: The Frontier MC Server.

## Requirements

- Windows 10 or newer (Linux support soon)
- Java Development Kit 21
- Inno Setup 6 for installer packaging

## Build

```powershell
.\gradlew.bat build --no-daemon
```

Create the Windows application image, installer and portable archive:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-release.ps1 -SkipVideos
```

Build outputs are written to `build\dist`.

## Privacy

See [PRIVACY.md](PRIVACY.md).

## Code signing policy

See [CODE_SIGNING_POLICY.md](CODE_SIGNING_POLICY.md).

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
