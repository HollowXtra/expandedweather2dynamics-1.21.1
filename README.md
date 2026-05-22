# Expanded Weather2 Dynamics

NeoForge 1.21.1 addon for Weather, Storms & Tornadoes / Weather2. It adds expanded tornado dynamics, radar and anemometer blocks, severe-weather alert systems, SPC-style outlooks, damage surveys, supercell structure, and survival-safe storm damage controls.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Weather2 1.21.0-2.8.6 or newer compatible 1.21.1 build
- CoroUtil 1.21.0-1.3.7 or newer compatible 1.21.1 build

## Development

Build the mod jar:

```powershell
.\gradlew.bat build
```

Run the NeoForge client from Gradle:

```powershell
.\gradlew.bat runClient
```

Weather2 and CoroUtil are pulled from CurseMaven for the development runtime. The built jar declares Weather2 as a required NeoForge mod dependency and loads after it.

## Audio Credits

- `assets/expandedweather2dynamics/sounds/tornado_roar.ogg` uses the low-quality preview OGG of "Wind - Tornado, digital" by Vrymaa from Freesound, licensed CC0: https://freesound.org/people/Vrymaa/sounds/735349/
