# SSHA 1.4.2 — HOTM tier read from the core item, per-profile grinding totals

- **HOTM tier past 6 fixed.** The tier now comes from the Heart of the Mountain item's `Level N/10` lore line, the same source SkyHanni uses, instead of tier-row names. Silent `/hotm` reads finish on page 1 at tier 4 or below with no click; at tier 5+ they right-click Next Page only when the XP line is still missing. `/ssha auto` stays nearly invisible via the existing menu-hiding mixin.
- **One-step updates.** About → Check & Install Update downloads and verifies a newer stable release in one action; a small HUD banner shows checking / failed / up-to-date status.
- **Per-profile grinding totals.** Grinding and mithril powder trackers are saved per SkyBlock profile, restored on profile switch, and persisted before every settings save plus on game quit. A new Reset All Saved Tracker Data button clears every profile's saved totals.
- **Sack counts prefer live data.** SkyHanni's live sack amounts now take priority over menu-lore deltas, which drifted between sack opens.
- **Commission claims refresh faster.** Collecting a commission (tab list shrinks) triggers a silent `/hotm` re-read so commissions-left catches up.

## Release artifact

`build/libs/ssha_1.4.2.jar`. GitHub automatically prepares the source archive when a release is published.

Requires Minecraft 26.2, Java 25, Fabric Loader/API/Language Kotlin and compatible SkyHanni 9.0.0+.

## Limits

The silent-read hiding, the right-click page turn, quit-time persistence, HUD appearance and sack numbers still need in-game verification. While the Blue Goblin Egg buff is active the displayed tier can read one high, because menu text carries no color codes. Live Hypixel packets and Skyblocker mixin compatibility are untested here.

---

# SSHA 1.4.1 — About in settings, saved session stats, silent /hotm reads

- **About in settings.** The About category shows the stable-release check, install, release notes, source and issue links directly, with no extra page.
- **Silent /hotm reads.** Commission claims, reward chat and `/ssha auto` read `/hotm` without showing the menu, then close it. At tier 5+ the XP is on page 2, so a silent read now turns the page to get it; this restores XP and commissions-left updates after a claim. A HOTM menu you open during a pending read is also hidden.
- **Session stats persist.** Commission totals, Avg. Commissions/h and Avg. HOTM XP/h are saved and restored across restarts and updates. Saves run every 10 seconds, so up to 10 seconds of activity can be lost on quit.
- **Pickaxe Ability Reminder** fires on Hypixel readiness messages (for example `Pickobulus is now available!`), not only after you use an ability. Repeats within 5 seconds are suppressed.
- **Avg. HOTM XP/h** no longer drops while the commission timer is paused.
- **Finished commissions** stay listed as a green **Completed!** row instead of disappearing.
- **Compact, more transparent widgets.** The commission ETA holds its estimate until progress changes, then counts down.
- **Faster updates.** The `/hotm` refresh throttle is 3 seconds (was 10), and commission and reward refreshes fire within 0.5 seconds.
- A completed update download shows a clickable **Click here to restart your game!** message that closes Minecraft.

## Release artifact

`build/libs/ssha_1.4.1.jar`. GitHub automatically prepares the source archive when a release is published.

Requires Minecraft 26.2, Java 25, Fabric Loader/API/Language Kotlin and compatible SkyHanni 9.0.0+.

## Limits

Silent-read hiding, quit-time persistence, pickaxe readiness message matching, HUD appearance, the page-2 turn on a real Hypixel menu and the About buttons still need in-game verification. Live Hypixel packets and Skyblocker mixin compatibility are untested here.

---

# SSHA 1.3.2 — Auto /hotm refresh, pickaxe ready banner, named compact popups

- Default-off **Pickaxe Ability Ready Banner** shows a small green **Pickaxe Ability Ready** banner once when the ability becomes available, on an availability/ready message (e.g. `Mining Speed Boost is now available!`) or on an explicit reset message. Not a countdown; does not announce from an estimated timer alone.
- **Auto /hotm read**: after a commission reward chat line or when the Commissions menu closes, SSHA quietly opens and reads `/hotm` and closes its own menu, so HOTM XP and commissions left stay current without a manual `/hotm`. It never opens while another GUI is open and never sends while one is open.
- `/ssha auto` now resumes automatic capture and reads `/hotm` immediately.
- **Compact popups** now name the target for each material: **Enchanted Gold Block**, **Enchanted Diamond Block**, **Enchanted Mithril**, **Enchanted Titanium**, **Enchanted Umber**, **Enchanted Tungsten**. Jewels say **Forge Bejewled Handles**.
- Commission claims still trigger a silent `/hotm` read. Live reward chat still updates XP left and commissions left immediately after the initial `/hotm` capture, even if another mod hides chat. Refreshed menu lore is reconciled once; stale repeats cannot overwrite newer live values. Manual progress stays fixed until `/ssha auto`.
- About checks only stable GitHub releases on demand and offers a verified install action. The replacement is scheduled for game exit; the launcher/game must be started manually afterward.
- Do not label ordinary powder during Better Together/Gone with the Wind as event powder.
- Match explicit Goblin Raid, Raffle and Mithril Gourmand end rewards, and track mining powder during 2x Powder without doubling actual deltas.
- Default-off Include Commission Powder setting in Grinding → Mithril Powder. Identified commission reward amounts are excluded from session powder/rates unless enabled.
- HOTM XP/h measures actual received XP over eligible mining-session time including claims, travel and idle time. It no longer guesses XP from partial commissions or divides bulk claims by the shorter commission-progress timer. Time outside mining/disconnected is excluded; warmup is 60 seconds.
- Report only server-confirmed pickaxe readiness; watching an estimated timer do not generate the banner.
- Do not announce readiness automatically if the tab widget does not expose the ready state and no matching chat message is seen; the banner only triggers on the accepted chat or tab-row transitions.

## Release artifact

`build/libs/ssha_1.3.2.jar`. GitHub automatically prepares the source archive when a release is published.

Requires Minecraft 26.2, Java 25, Fabric Loader/API/Language Kotlin and compatible SkyHanni 9.0.0+.

## Limits

Live Hypixel packets, original reward formatting and notifications still require in-game verification. The Pickaxe Ability Ready banner relies on the real readiness/reset chat message (e.g. `Mining Speed Boost is now available!`) or the tab widget's ready transition when that state is exposed; if the actual ready message differs from what SSHA accepts, it will not trigger. The banner appears briefly over the HUD while the HUD is enabled. Compact popup text uses the current in-game enchant names. Initial /hotm capture is still needed; manual progress stays fixed until /ssha auto.
