# 2.1.18 map distribution

The production pack manifest distributes Erdvyn UI 0.2.2 with Xaero World Map 1.44.2 and Minimap 26.4.2. Add the managed overworld survey bounds config (-20000 to +20000 on both axes) so existing clients receive the same bounds as fresh installs. Preserve player waypoints and explored terrain.

Launcher self-update remains download-first: GitHub latest release installer is downloaded and SHA-256 checked; installation is initiated from notifications. The map is a pack update, not embedded in the launcher executable.

Use tools/MapUpdateProbe.java with a disposable LOCALAPPDATA path containing map-rollout-probe and an older launcher classpath to test real downloads without installing. Do not run the installer in this probe.

This rollout does not implement the deferred map zoom-out/small-pan request.
