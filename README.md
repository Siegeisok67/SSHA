# SSHA — Siege's SkyHanni Addons

An independent Fabric client addon with a Skyblocker-inspired teal HUD and SkyHanni integration. Created by Siege ([Siegeisok67](https://github.com/Siegeisok67)). No SkyHanni, Skyblocker or SkyOcean source/assets are bundled.

## Install

Download **ssha_1.1.0.jar** from [GitHub Releases](https://github.com/Siegeisok67/SSHA/releases). Replace the old SSHA jar, do not install both.

Requires Minecraft **26.2**, Java **25**, Fabric Loader **0.19.3+**, Fabric API **0.155.2+26.2**, Fabric Language Kotlin **1.13.12+kotlin.2.4.0+**, and compatible SkyHanni **9.0.0+**. Skyblocker and SkyOcean are optional, not runtime requirements.

## Features

- Compact teal HOTM summary and single-line commission name/percentage rows, without progress bars.
- Widgets appear only in SkyBlock's Dwarven Mines (including Glacite Tunnels), Crystal Hollows and Glacite Mineshafts, never the Hypixel lobby. The position editor can preview enabled widgets outside these areas.
- Skyblocker's commission widget rendering is suppressed while SSHA's HOTM HUD is enabled in a supported area. Disabling SSHA restores it automatically; Skyblocker's saved layout/config is not modified. Compatibility targets Skyblocker 6.10.4's TabHudWidget API; other versions need live verification.
- `/ssha gui` edits the HOTM widget and every enabled grinding/powder widget together. Drag to move, scroll to resize, close to save. A material's Move button also includes other enabled widgets.
- Commission timing starts/resumes only on positive progress or completion, not on first tab visibility. All trackers pause after **20 seconds** without progress. Suspended/offline gaps are excluded. Rates warm up after **60 seconds of active time**, with optional **90-second exponential smoothing**.
- Avg. Commissions/h counts observed partial commission work, not just finished commissions. Optional finish estimates use percentage gained over active time; travel, luck and coarse tab updates affect accuracy.
- Separate gold, diamond, mithril, titanium, umber, tungsten, Glacite Jewel and Mithril Powder widgets. Material widgets/popups start disabled.
- Sack gains use a **1-second** reconciliation window (rather than 10 seconds) to reject nearby withdrawals/compaction. Inventory pickups are also tracked outside container screens. Opening a sack updates holdings immediately; opening does not count existing holdings as session gain.
- Sack Limit reads each item's server lore, including stacked/upgraded capacity. Raw, enchanted and product limits are separate; SSHA never assumes the base 20.2k limit. Open each relevant sack on each profile/session to establish known totals and limits. Abbreviated lore capacities are only as precise as the server display.
- **Sack overflow!** warnings default on for enabled trackers, fire once at/above known item capacity, and rearm below it. Reaching the limit is treated as overflow risk because the server normally caps stored counts there.
- Optional **Compact to blocks!** reminder for gold/diamond at a configurable raw-equivalent threshold.
- **Est. Profit/h** uses product equivalents/hour and Bazaar instant-sell prices. Session details show product-equivalent value and observed Supercraft output/value. Refined materials/handles are estimates, not completed forge outputs; no forge costs/time, taxes or realized sales are included. Known stored totals can be partial until all relevant sacks are opened.
- Mithril Powder counts actual SkyHanni deltas without multiplying Double Powder twice. Event rewards remain classified for five seconds after an event ends; nearby already-received deltas can be reclassified without double counting. Powder itself has no coin value.
- `/ssha` opens About first: installed version, manual stable/all-release links, credits, source and issue links. No automatic jar replacement or background update checker.

## Commands

| Command | Purpose |
| --- | --- |
| `/ssha` | About and settings (Mining / Grinding categories) |
| `/ssha gui` | Move/resize enabled SSHA widgets together |
| `/ssha status` | Tier, XP left, commission estimate, event XP |
| `/ssha hud true\|false` | Toggle HOTM/commission HUD |
| `/ssha set hotmxp <xp>` | Estimated base XP per commission (default 750) |
| `/ssha set progress <tier> <earnedXp>` | Manual current unlocked tier and XP earned within it |
| `/ssha auto` | Resume automatic menu capture |
| `/ssha menu` | Diagnose last `/hotm` menu capture, no hover needed |
| `/ssha tooltip` | Diagnose last hovered `/hotm` tooltip |
| `/ssha clear-events` | Clear accumulated attributed event XP |

Open `/hotm` once to capture current tier/XP. Arabic/Roman tiers and abbreviated progress fractions are supported. Manual mode blocks automatic/live changes until `/ssha auto`. For example `/ssha set progress 6 12000` gives 138,000 XP remaining to tier 7 and ~184 commissions at 750 XP. Automatic capture of a *locked* Tier 6 item showing 12,000/100k means current tier 5 with 88,000 XP remaining.

Settings remain at `config/ssha-hotm-addon.json` for compatibility. Session rates reset on restart/profile change. Missing/untrusted sack totals and unavailable prices are never invented.

## Build and verification

Java 25: `./gradlew build releaseSource` (Windows: `gradlew.bat build releaseSource`).

- Installable client jar: `build/libs/ssha_1.1.0.jar`
- Buildable source archive: `build/release/ssha_1.1.0-source.zip`

Automated tests cover parsing, command dispatch/settings persistence, rates/pause/resume, completion deduplication, sack lore/capacities, reconciliation, event reward classification and renderer bounds. A build cannot prove live Hypixel packet behavior, Skyblocker mixin compatibility, editor mouse interactions or appearance; these still need in-game verification. Never include personal Minecraft config, credentials, logs or caches in source releases.
