# SSHA 1.1.1 — Live mining tracking fixes

- About now checks only stable GitHub releases on demand and offers a verified install action. The replacement is scheduled for game exit; the launcher/game must be started manually afterward.
- Do not label ordinary powder during Better Together/Gone with the Wind as event powder.
- Match explicit Goblin Raid, Raffle and Mithril Gourmand end rewards, and track mining powder during 2x Powder without doubling actual deltas.
- Default-off Include Commission Powder setting in Grinding → Mithril Powder. Identified commission reward amounts are excluded from session powder/rates unless enabled.
- Default-off Pickaxe Ability Reset Title in Mining → HOTM Widget. Reads actual server cooldown values and announces only server-confirmed readiness/reset, once per cycle.
- Observe original system reward messages independently of chat hiding. Update HOTM XP left and commissions left without reopening /hotm after initial capture.
- Reconcile menu/chat rewards exactly once; reject repeated stale menu values and preserve unapplied rewards through partial menu refreshes.
- HOTM XP/h now measures actual received XP over eligible mining-session time including claims, travel and idle time. It no longer guesses XP from partial commissions or divides bulk claims by the shorter commission-progress timer. Time outside mining/disconnected is excluded; warmup is 60 seconds.

## Release artifact

`build/libs/ssha_1.1.1.jar`. GitHub automatically prepares the source archive when a release is published.

Requires Minecraft 26.2, Java 25, Fabric Loader/API/Language Kotlin and compatible SkyHanni 9.0.0+.

## Limits

Live Hypixel packets, original reward formatting and notifications still require in-game verification. Enable the server Pickaxe Ability tab widget for cooldown detection. Powder classification requires reward amount text or a visible commission claim menu; ambiguous unlabelled deltas cannot be identified reliably. Initial /hotm capture is still needed; manual progress stays fixed until /ssha auto.
