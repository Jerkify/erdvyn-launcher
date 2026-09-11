# 2.1.21 — ERDVYN branding

- Transparent amber ERDVYN symbol; no black circular badge added to Windows icons.
- Bundled Julius Sans One display font, inspired by the supplied thin geometric STAR reference (not a claim to be the identical font). License: SIL OFL, bundled with the font. Source: https://github.com/google/fonts/tree/main/ofl/juliussansone
- Retains the 2.1.20 notification-install hit testing and interactive local map bridge.
- Build packaging now clears only the validated staging-input directory so old launcher jars cannot enter a new installer.

Validation: Gradle tests; launcher interaction self-test (navigation, world map, settings, boot and launch terminal); home-screen capture. This release build does not install or upgrade the user's local launcher.

The outline logo was developed with image generation from the supplied ERDVYN reference. Direction: transparent, amber-only outline symbol combining an E/cube and transmitter arcs, no black backing or wordmark. Production bitmap is bundled locally; no generation or network call is made at runtime.
