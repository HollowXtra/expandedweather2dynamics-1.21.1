# Expanded Weather2 Dynamics

Forge 1.20.1 addon for Weather, Storms & Tornadoes / Weather2. It adds expanded tornado dynamics, radar and anemometer blocks, severe-weather alert systems, SPC-style outlooks, damage surveys, and survival-safe storm damage controls.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.10
- Weather2 1.20.1-2.8.3
- CoroUtil 1.20.1 compatible Forge build

## Development

Build the mod jar:

```powershell
.\gradlew.bat build
```

Run the Forge client from Gradle:

```powershell
.\gradlew.bat runClient
```

Weather2 and CoroUtil are pulled from CurseMaven for the development runtime. The built jar declares Weather2 as a required Forge mod dependency and loads after it.

## Audio Credits

- `assets/expandedweather2dynamics/sounds/tornado_roar.ogg` uses the low-quality preview OGG of "Wind - Tornado, digital" by Vrymaa from Freesound, licensed CC0: https://freesound.org/people/Vrymaa/sounds/735349/
