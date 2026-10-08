# Glowing Mushroom Auto (Fabric, Minecraft Java 26.1.2)

Client-side Fabric mod for Hypixel SkyBlock's Glowing Mushroom Cave. It reads the particles
Hypixel spawns on glowing mushrooms (the signal SkyHanni's highlighters use), then walks a route you
record and breaks each mushroom that comes within reach.

> **Warning:** automating gameplay breaks Hypixel's rules and can get you banned. Use at your own risk.
> This code has **not been compiled or tested in-game**. I couldn't reach the Fabric docs while writing it,
> so Mojang-mapped names and the loader version (`gradle.properties`) may need small fixes.

## Build
Needs JDK 25 and Gradle 9.4+ (Loom 1.15, plugin `net.fabricmc.fabric-loom`, no mappings - 26.1 is unobfuscated):
`gradle build` -> `build/libs/`. Requires Fabric API `0.145.4+26.1.2`.

## Use
Needs shears and a golden sword (Rogue Sword) in the hotbar, found by item type. Every ~25 s it reads `Speed:` from the tab list (enable the Speed tab widget in SkyBlock; `/glowing speed` prints what it sees) and, if below 400, swaps to the sword, right-clicks, swaps back.
Camera movement runs every rendered frame as a damped spring with per-target stiffness, reaction delay, held aim error, hand drift and whole-pixel mouse steps (`HumanAim`).
Marker particles default to the potion set (`entity_effect`, `ambient_entity_effect`, `effect`, `instant_effect`).

1. Join the cave, stand near mushrooms, run `/glowing scan`, wait ~5 s, run `/glowing scan` again. It lists particle counts;
   set the one that only appears on mushrooms with `/glowing particle <id>` (default `minecraft:entity_effect`; `/glowing particle reset` restores it).
2. Walk your loop and `/glowing add` at each corner (`/glowing undo`, `/glowing clear`). Saved to `config/glowingmushroom_route.json`.
3. `/glowing start` / `/glowing stop`. It loops the route, stops to mine tracked mushrooms within 4.4 blocks, and stops itself if stuck.
FAH

`/glowing nuker` toggles nuker mode (default on): every tracked mushroom within 5 blocks is broken at once with no aiming; off = smooth aimed mining.
`/glowing help` lists every command. `/glowing highlight` toggles the green boxes on tracked mushrooms (yellow = current target).

## Path mode (default)
`/glowing mode path` - mushrooms found from their particles are remembered as you move. A background
thread groups them, finds the standing spot that has the most of them in nuker reach, and paths there
(A*: walking, diagonals, 1-block step ups, drops up to 3). Groups are scored by
`mushrooms / (walk cost + 6)`, so a big group a bit further away beats a single one next to you. The next
group is planned while walking to the current one. With nothing known it walks the recorded route as a
patrol. Cyan line/box = current plan. `/glowing mode route` = old route-only behaviour.

## Getting unstuck
Jumps where the planned path steps up (and when a block in the walking direction really needs climbing).
If it stops making progress toward the next path point for ~1.25 s it backs off with a hop and re-plans
around that spot. If it hasn't really moved for 6 s while trying to walk, it runs `/warp glowing`, waits 3 s
and carries on (at most once every 20 s).

## Profit tracker
Dark panel in the top left: macro time, Glowing Mushrooms (+2 per broken glowing mushroom), regular
mushrooms (+1 per broken non-glowing red/brown mushroom), coins at live Bazaar instant-sell prices (refreshed
every 5 min), total and coins/hour. A break is counted once the block stays gone for 0.4 s (server confirmed).
`/glowing hud` toggles it, `/glowing reset` zeroes it. Falling in water also triggers `/warp glowing`.
