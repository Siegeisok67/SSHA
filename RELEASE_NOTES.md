# SSHA 1.1.0 — First public release

Independent SkyHanni addon with a compact Skyblocker-inspired look.

- Mining-only widgets; no lobby HUD.
- Single-line commission percentages, shorter Avg. Commissions/h and Avg. HOTM XP/h labels.
- Shared `/ssha gui` for enabled widgets and runtime-only Skyblocker commission suppression.
- Progress-driven timing with 20-second idle pauses, partial-progress estimates and stronger smoothing.
- Faster one-second sack gain reconciliation, immediate open-sack snapshots and stacked capacities from lore.
- Default-on sack overflow warnings for enabled trackers and optional Compact to blocks! reminders.
- Product-equivalent/session value, observed Supercraft value and estimated Bazaar profit/hour.
- Actual powder deltas with a short post-event reward attribution window, without double multiplication.
- About-first `/ssha` menu with version, manual update stream, credits and links.

## Downloads

Install `ssha_1.1.0.jar`. `ssha_1.1.0-source.zip` contains buildable source plus the Gradle wrapper. Replace older SSHA jars.

Minecraft 26.2 / Java 25 / Fabric Loader 0.19.3+ / Fabric API 0.155.2+26.2 / Fabric Language Kotlin / SkyHanni 9.0.0+. Skyblocker/SkyOcean are optional.

## Known limitations

Live Hypixel behavior, visual layout, editor mouse input and optional Skyblocker compatibility still require in-game verification. Open each relevant sack to establish capacity/holdings; abbreviated capacities reflect server precision. Profit is gross product-equivalent Bazaar value, not realized sales or completed forge production. Updates are manual GitHub downloads.
