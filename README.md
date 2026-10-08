# SSHA — Siege's SkyHanni Addons

Siege's SkyHanni Addons -- This is my addon to SkyHanni, it includes features I think would be great for SkyHanni or that add QOL additions.

Project repository: [Siegeisok67/SSHA](https://github.com/Siegeisok67/SSHA).

The current addon provides a **HOTM and commissions widget**, including an active-time commissions/hour estimate and an in-game settings menu. It is a separate Fabric 26.2 client addon that reads the current Heart of the Mountain screen and SkyHanni's public commissions tab widget. It does not change SkyHanni source code. SkyHanni is a compile-only API dependency and is also declared as a runtime requirement.

## Features

- Displays active commissions from SkyHanni's tab widget. In automatic mode, the addon reads each tier item's raw lore while `/hotm` is open, without requiring a hover. Hovered tooltips are an additional input. Arabic and Roman tiers and abbreviated progress fractions such as `12,000/100k` are supported. Bare selector names and unlock requirements are not treated as your current rank.
- `/ssha gui` opens SkyHanni's own position editor with the SSHA widget. Drag to move it, scroll to resize, and close the editor to save position/scale. The widget also registers with `/sh gui` when visible. It is displayed in the editor even when its HUD is disabled. Linked SkyHanni config navigation/reset is not available for this separate addon's settings.
- Shows XP to the next tier and a rounded-up commission estimate. Automatic capture requires an explicit remaining-XP amount or current/required fraction; it does not derive XP from tier thresholds. Manual input uses the [published per-tier XP requirements](https://hypixelskyblock.minecraft.wiki/w/Heart_of_the_Mountain).
- Renders through the same SkyHanni renderable/spacing path as its commission widget. Uses a compact gold heading, indented label/value rows, and original commission Component colors instead of recolored bullet rows.
- Shows commissions per hour based on completed commissions divided by active session time. Positive percentage/fraction increases keep the timer active; completion chat and DONE transitions count once. After **90 seconds without progress**, further idle time is excluded. Progress resumes the timer without adding the paused gap. Opening/replacing tab rows or decreasing progress does not count as activity. The rate is an observed average, not a prediction, and restarts reset the session.
- Saves `hotmxp`, widget/rate toggles, manual progress, and display position/scale to `config/ssha-hotm-addon.json`.
- In automatic mode, decreases in XP remaining within the same known tier are tracked as event/mineshaft XP unless a commission-completion chat message was observed within 60 seconds. In that case, the configured commission XP is subtracted from the observed decrease. This remains an estimate: XP bonuses or multiple changes in one sample can affect attribution.
- Default commission estimate is 750 XP, a common current Glacite Tunnel commission reward; set it to match your tier/area/daily bonus.

## Commands

- `/ssha` — open the addon settings in SkyHanni's bundled MoulConfig editor. Expand **Mining → HOTM Widget** for on/off switches, a manual tier dropdown, earned XP and commission reward inputs, position editing, reset controls, and recheck.
- `/ssha status` — print current tier, XP remaining, commission estimate and observed event XP.
- `/ssha set hotmxp <xp>` — set the estimated XP earned per commission.
- `/ssha set progress <tier> <earnedXp>` — save a manual tier and XP earned **within that tier**, not lifetime XP. This pauses automatic capture, survives restarts, and can be run again to update progress. Manual edits do not add event XP.
- `/ssha auto` — leave manual mode, clear the progress baseline, and resume menu capture.
- `/ssha tooltip` — print the last hovered `/hotm` item's text to chat for troubleshooting; hover the item first, close the menu, then run this command.
- `/ssha menu` — print the tier items and parser result from the last opened `/hotm` menu, with no hover required. Open the menu once, close it, then run this command.
- `/ssha gui` — move/resize the tracker using SkyHanni's position editor; close to save.
- `/ssha hud true|false` — toggle the HUD.
- `/ssha clear-events` — clear the locally accumulated event/mineshaft estimate.

For example, **HOTM 6 with 12,000 XP earned toward HOTM 7**:

```text
/ssha set progress 6 12000
```

This shows **138,000 XP remaining**, or **184 commissions at 750 XP each**. Manual mode is marked in the HUD and `/ssha` output. Its progress remains fixed until you update it or run `/ssha auto`; it is not an automatic XP feed. Tier 10 is max tier and accepts 0 earned XP.

A tooltip titled **Tier 6** showing **LOCKED** and **12,000/100k** is progress *toward unlocking* tier 6, not 12,000 XP earned after reaching it. Automatic capture reports **HOTM 5, 88,000 XP remaining**, or **118 commissions at 750 XP each**, for that screenshot. The highest unlocked tier in the menu prevents hovering lower or future tier icons from overwriting current progress.

Version 1.0.1 and later discard old unreliable automatic tier/XP captures on load, while keeping your commission reward, HUD preference, and event counter. Replace the old addon jar rather than installing both versions.

In automatic mode, open `/hotm` to capture tier progress directly from item lore. Hovering is optional and uses Fabric's client tooltip callback. Run `/ssha auto` first if you previously enabled manual progress; manual mode deliberately blocks automatic updates. If capture is missing, close the menu and run `/ssha menu` to collect the actual item text. SkyHanni supplies commission widget data but does not expose a direct HOTM XP field. No Hypixel public profile API is used. If your game does not display a remaining-XP number/fraction in that item's tooltip, the addon cannot compute a trustworthy commission estimate for that screen format yet.

## Settings actions

Numeric inputs are validated when their **Apply** button is clicked. Invalid values leave the saved settings unchanged. Widget and hourly-rate toggles save immediately. **Recheck All Data** leaves manual mode, discards old captures, and opens `/hotm` for fresh tier/XP; commissions update from the live tab widget. It does not clear the hourly average. **Reset Hourly Average**, **Reset Event XP**, and **Reset Position** act independently. **Reset All Tracking** clears tracking values and hourly statistics but keeps reward, toggles, and widget position. HOTM capture still requires the menu to be opened; it is not a continuous profile XP feed.

## Installation

Requires Minecraft **26.2**, Java **25**, Fabric Loader **0.19.3+**, Fabric API **0.155.2+26.2**, Fabric Language Kotlin, and SkyHanni **9.0.0+** compatible with 26.2. Build the jar below and place it in your instance's `mods` folder; replace any older SSHA addon jar instead of installing both. Restart Minecraft, run `/ssha` for settings, or `/ssha auto` followed by `/hotm` for automatic capture.

SSHA is an independent addon, not an official SkyHanni release. The display and settings use SkyHanni's public APIs and bundled MoulConfig; no SkyHanni source is included or modified.

## Verification

The automated suite covers HOTM parsing, exact commission estimates, the 90-second hourly-rate pause, completion deduplication, settings validation/actions, command dispatch, and saved settings. Live Hypixel capture, visual appearance, and mouse interactions still need in-game verification. No personal Minecraft configuration, credentials, logs, or generated caches belong in this repository.

## Build

Run `./gradlew build` (Windows: `gradlew.bat build`) with Java 25. Install the produced jar from `build/libs` alongside Fabric API, Fabric Language Kotlin, and a SkyHanni version supporting Minecraft 26.2.
