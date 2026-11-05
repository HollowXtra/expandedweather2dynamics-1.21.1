# Modrinth profile

Everything below is split so each field can be copied on its own. The description is fenced with four
backticks so the markdown inside it survives being copied out of this file - copy everything between the
fences and nothing else.

## Summary field

```
Weather2, grown up: tornadoes with a life of their own, hurricanes, volcanoes, hail, derechos, a working radar, sirens, damage surveys and spotter reports. NeoForge 1.21.1.
```

## Description field

````markdown
![A tornado at night: a black column overhead, rain lit blue across the view, and torn-up ground turning in the air around it](SWAP-THIS-FOR-YOUR-HERO-IMAGE-URL)

A **NeoForge 1.21.1 addon for Weather2**, made for people who stand outside and watch a storm instead of hiding from it.

Weather2 gives you a funnel. This gives that funnel a life: a size it earns and keeps instead of a number rolled every few seconds, a width that moves in surges rather than pulsing on a beat, an arc that widens as the storm organises and gives the width back as it ropes out, and a damage path that is a record of what the storm did rather than a draw. Around all of it are the things you need to chase one properly - a radar that draws real returns, an anemometer, sirens, spotter reports and damage surveys, photographs, a chase log, and instruments you carry.

---

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Loader** | NeoForge 21.1.x |
| **Requires** | Weather2 1.21.0-2.8.6+ and CoroUtil 1.21.0-1.3.7+ |
| **Install** | Server and clients, both |

## What it changes about a storm

**Size belongs to the storm, not to the last roll.** A storm's temperament is hashed from its own id once and never re-rolled, so a thin tornado is thin for its whole life. Its life arc widens as it organises, holds through its climax and gives width back as it ropes out. Its environment is the warmth, moisture and country it is moving through. The ladder it climbs is geometric rather than linear, so a rope, a cone, a barrel, a wedge and a monster are evenly spaced rungs instead of all the interesting width being crammed into the top few percent.

**Surges, not a breath.** The old width moved on a summed-sine wave, which read as a tornado pulsing on a beat, with every ring of the funnel moving together. It is a train of held widths now: the funnel arrives at a width, keeps it, and crosses to the next one, with the levels hashed from the storm so two funnels on screen never surge together and the sequence never repeats.

**A funnel with a foot.** Small suction vortices stand on the ground they are over instead of sinking into it, so a multi-vortex storm's whirling feet read as feet. Tornadoes and their satellites are two separate storms where that matters and one path where it does not.

**The ground decides the air.** Every biome, including modded ones, is classified into one of twelve families, and the mixture around a place sets its heat, moisture and instability. Storms, their strength, hail, snow, fog, fire risk and tornado odds all read that air, so the same afternoon is a different afternoon in different country.

**Everything is shareable and reproducible.** The storm's widths, its path, its satellite vortexes and the sea state are pure functions of the storm and the world, so the server and every client agree with nothing extra on the wire and a reload cannot disturb it.

## Hazards, and the ways to be in the middle of them

<details>
<summary><b>Severe weather</b></summary>

- **Hail** that lands on things, damages crops and makes a noise you can hear coming through a roof.
- **Derechos** - a fast-moving line that does its damage as a wall of wind rather than as a funnel, with its own audio and its own passes.
- **Squall lines** drawn as the storms that make them, along the line's own axis, instead of as a cloud this mod paints.
- **Monsoons** and the two volcanic whirls, wildfires with a burn scar you can walk through, and flammagenitus cloud over a fire.
- **Snow devils**, which only form on ground that is actually snowy, and stand down in rain or fair weather.
- **Dust devils, gustnadoes, landspouts, waterspouts and funnel clouds**, each with its own shape, colour and debris figure rather than one whirl in five colours.
- **Tropical systems**, from a disturbance to a named hurricane with a category, spiralling bands and an eye punched through a wide disc.
- **Scouring, structural pickup and tree work**, so a strong tornado leaves a path a survey can read afterwards and a town that has work to do when it is over.

</details>

<details>
<summary><b>Volcanoes</b></summary>

Weather2 grew volcanoes in 1.12.2 as a curiosity: a cone that filled its throat with lava. These run the eruption instead, and the ladder is the observed one - unrest, steam blasts, a sustained column, that column collapsing back down the mountain as a pyroclastic density current, lava afterwards, and then the years of rain on loose ash that turn into lahars.

An eruption shakes the ground, brings blocks down, throws incandescent bombs across the flanks, lays ash downwind, makes its own lightning, buries and burns what a pyroclastic current runs over, and later runs as mud. All of it feeds the same alert products as the weather does, so the sirens, the weather radio, the banner and the redstone output handle a volcano without a second system. Volcanic provinces come from the world seed, so the same ground is volcanic every session.

</details>

<details>
<summary><b>Watching it: instruments, radar and reports</b></summary>

- **Radar**: a block and a handheld, with a real returns display, storm cells listed with their size class, and a map that stays a map.
- **Anemometer** blocks that read the wind at a place and feed it to everything else that wants a reading.
- **Alerts**: siren stations with their own tones, a weather radio, banner and redstone outputs, and SPC-style outlooks for the day ahead.
- **Spotter reports**: the reports you file, the net that collects them, and how a report moves a forecast.
- **Photographs**: take one and it keeps what was in front of you, with the storm's own numbers on it.
- **Chase log**: a record of what you have stood beside, per storm.
- **Damage surveys**: walk a path afterwards and file what it did.
- **Villager work**: shelter, survey and repair, so a town recovers rather than only being destroyed.
- **Held instruments** and cabling, plus the weather balloon and its sonde, and a drone with an item that is its link.

</details>

<details>
<summary><b>Sky and sea</b></summary>

- **Volumetric rain and snow**, with rain shafts, virga, rain fog and morning mist as separate things.
- **Inside the tornado**: immersion fog, a whiteout and an inflow shader, so being in one is not the same view as watching one.
- **Ocean waves**: a wind-driven sea surface over the water, with shoaling, a surf zone, breakers and a wash that runs up the beach.
- **Foliage wind**: leaves and grass bending to the local wind, with gust bands travelling across a field and prints your own passing leaves in the grass.
- **Storm art**: the cloud deck, the funnel and the debris all draw their own sheets, and a tornado draws its own darker puff either way.

</details>

## Commands

```
/ewd help
/ewd spawn <type> [windMph] [sizeBlocks]
/ewd volcano spawn [intensity] [scale] | /ewd volcano spawn <intensity> <distanceBlocks>
/ewd volcano erupt <id> | /ewd volcano list | /ewd volcano remove <id>
```

Storm types for `/ewd spawn`: `dust_devil`, `snow_devil`, `gustnado`, `fire_whirl`, `landspout`, `waterspout`, `funnel_cloud`, `squall_line`, `wildfire`, `flammagenitus`, `cyclone`, `hurricane`.

## Configuration

Every feature has a switch, and the settings screen is searchable, with a **Simple Settings** page for the handful of dials most people actually want: how often storms happen, how violent they get, how much they can damage, and how loud they are. `Performance` holds the ceilings on block work and debris, and the compat features each have their own group.

## With other mods

- **Sodium** and an active **Iris shaderpack**: the shader-based bits stand down rather than half-applying - foliage sway with Sodium, and the custom-shader overlays, the wave surface and the foliage override with a pack active. The mod's own pack declares its overrides to Sodium as deliberate, so it is not listed as broken. The one thing that does not stand down is an eclipse: its darkening is part of the world's own light rather than a program the mod draws, so a total eclipse dims the sky and the world under a pack too, and a switch lets you ask for the mod's own black disc and corona over a pack's sky as well.
- **Distant Horizons** and **Xaero's minimap** have compatibility switches of their own. The LOD compat reaches the weather as well as the rendering: storms are spawned and kept alive out to the distance the LOD renderer draws, so the sky covers the country you can actually see.
- **Physics Mod** wind, **Immersive Vehicles**, **Immersive Aircraft**, **Valkyrien Ships** and **Tree Physics** are read when present, and do nothing when they are not.

## Credits

Weather2 and CoroUtil are by **Corosus**, and this is an addon for them: nothing here replaces Weather2's storms, it grows them. Every sound in the mod that is not generated is cut from a source under a licence that allows it, and each one is named with its source and its licence in the mod's README - if you are one of those authors and want something credited differently, say so and it will be changed.

## Reporting anything

Screenshots help more than descriptions do, and so does the config file. If a storm did something impossible, a picture of it with the world seed and the wind speed on screen is usually enough to find it.
````

## Gallery

Modrinth wants the first image set as the featured image, so lead with a hero shot and put the rest behind it.

- **Hero**: the night shot pasted in this session (`paste-1790648095698-34756.png`) - a wedge overhead, grass and torn-up ground in the air, rain lit blue. The green chunks in it are the new debris: pieces of the ground the storm is standing on rather than a grey sprite, which is the thing most worth showing.
- `run/screenshots/2026-09-28_19.43.13.png`, `2026-09-28_19.51.53.png`, `2026-09-28_19.55.06.png` - this session's storm runs.
- `run/screenshots/2026-09-27_19.02.46.png`, `2026-09-26_14.02.40.png`, `2026-09-19_16.39.06.png` - older funnel and sky shots.
- **Radar lifetime videos** already made: `~/Downloads/Ana-radar-lifetime.webm` and `~/Downloads/Adria-radar-lifetime.webm`. Modrinth takes video as a gallery item and shows it inline, and a radar lifetime is the single best thing to put next to a screenshot.

## Tags and fields to set on the project

- **Categories**: Game Mechanics, Utility, Adventure. Add World Generation only if you want volcanoes front and centre.
- **Environment**: Client and server both required.
- **Loaders**: NeoForge. **Versions**: 1.21.1.
- **License**: the project has no license yet - `neoforge.mods.toml` ships `All Rights Reserved` and there is no LICENSE file in the tree. Modrinth requires one, so pick it deliberately (All Rights Reserved is a valid choice, ARR is the default here) before publishing if you have not already.

## Version changelog for 1.07

The body of `## 1.07` in `CHANGELOG.md` is the text for the version's changelog field: a tornado you can actually see, a funnel that reaches a flat cloud base, soft clouds, a supercell with a shape, less dirt, remastered small whirls, new sirens and a new radar display.
