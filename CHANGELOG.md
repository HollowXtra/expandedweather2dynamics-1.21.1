# Changelog

What changed in this release, written for the people who play it rather than for the people who read the
source. The long version of every one of these - what was wrong, what it was measured against, and what is
still an honest unknown - is in the README.

## 1.07.1

**Servers start again.** A dedicated server with this mod would not start. It stopped while loading mods, either with a `ClassNotFoundException` for `EntityRotFX` or with "Could not locate method metadata for tickClient", so nobody could join it. A server strips Weather2's client-only parts out of its storms, and some of this mod's hooks into those parts were loaded on servers as well, where there was nothing for them to attach to. They now load on the client only. A server starts, makes a new world, and lets players in while tornadoes, wildfires, fire whirls, squall lines and waterspouts run. Single-player was not affected.

**A sky mod can no longer stop the game from loading.** The hook that hides the game's own sun and moon behind this mod's round ones was required, so another mod that also changes how the sun and moon textures are drawn would have stopped the game at startup. The hook is optional now. With such a mod installed, the game's own sun and moon may show behind the round ones instead.

## 1.07

**You can see the tornado.** Several things were hiding it at once, and they are all gone. A dark cloud spawned just above your head whenever a severe storm was within 700 blocks. Weather2's deck sagged far down over the funnel. Rain curtains and scud were drawn as dark smoke right around it. The dread fog closed in to 170 blocks when the tornado was 300 away, and vanilla's clouds cut bright slabs through the underside of the storm. Rain is now drawn as rain, and the air around a funnel is kept clear.

**The funnel reaches the cloud.** It used to stop in mid-air up to a hundred blocks short. It now runs from the ground to a flat cloud base, as a slender trunk that opens out fast near the top, in new fibrous textures in the style of Weather2 Remastered. It comes down out of the cloud as a thin tip that widens behind it, and a dying tornado draws in along its whole length into a thin leaning rope.

**Size and shape change like a real funnel's.** A tornado used to reach most of any new width within a second or two, and the funnel you saw crept along at its own fixed pace no matter what the storm was doing. The width now gathers pace into a change and eases out of it, never faster than about 3% of itself a second, and the funnel is drawn at exactly that width. Shapes are no longer random rolls that snapped in three seconds and squeezed the funnel to a tenth of its width. A funnel's shape follows how wide it is against how tall: a rope when thin or dying, a cone or plain funnel in between, a stovepipe or bowl when broad. Each storm keeps its own character, a change morphs over about 30 seconds, and a shape is held for at least a minute. The `tornadoWidthChangeSeconds` and `shapeChangeSeconds` settings set the pace.

**Rain-wrap and dust-wrap.** In a rain-heavy storm a curved curtain of falling rain now wraps round the back of the tornado, swept round with it and open on the inflow side, and a classic supercell gets a lighter one. A violent tornado, from the top of EF3 up, is wrapped in a turning shroud of dust up the lower part of its funnel. A tornado's dust spreads as wide as its wind field rather than piling up round a thin funnel.

**A summoned tornado keeps its class.** An F4 you summon now reads as an F4, its wind moving up and down inside that class as its strength changes, with gusts on top. A natural storm can still be carried past its class by the air it is in, but no longer runs away to the top of the scale.

**Landspouts and waterspouts hang from a storm.** Each has its own shower cloud above it now, a dark flat base where the column meets it and a rounded tower over that, instead of standing in a clear sky.

**No more fake blocks.** The little block-textured cubes that circled inside the funnel were never real blocks, only particles. They are gone, along with this mod's own cube-shaped ground chunks. Blocks a tornado really picks up are untouched.

**A tornado lifts smoothly and leaves its dust on the ground.** A dying tornado now thins into a rope in its last stage, then its condensation draws up off the ground from the foot, the edge of it rising steadily up the column until it is gone back into the cloud, just before the storm lets it go. It used to stand at full strength to the end and vanish, while the funnel jerked upward a dozen blocks at a time through its last minute. When a tornado lifts, whether it is dying, floating, or lifting its foot for a multi-vortex spell, its dust cloud stays on the ground: lower and thinner, held up a while by the wind that raised it, instead of going out at once or climbing after the funnel. A multi-vortex spell's lift now eases in and out with the funnel's foot rather than thinning it the moment the spell starts.

**Rain-wrap that hides the tornado.** In a heavy-precipitation storm the rear-flank downdraft now wraps a dense curtain of rain almost all the way round the tornado, from the ground to the cloud base, with rain right up against the funnel and a grey rain foot where it meets the ground. From most sides the tornado is a ghostly shape in the rain or cannot be seen at all; it shows clearly only through the notch on the inflow side, which is what makes an HP storm the dangerous one. A classic supercell wraps only its back. The curtain is built of heavy rain sheets rather than evaporating virga, and the forward flank has a proper rain core now: a grey curtain hanging from the base to the ground, heaviest beside the updraft.

**A supercell you can recognise.** Every part of a storm's structure used to be held under the world's build limit, which pressed the tower and the anvil into the hundred blocks above the cloud base: the tallest thing in the sky was level with the deck. They now stand far above it. The updraft rises from a broad, striated, turning mesocyclone at its foot, narrows into a knuckled tower leaning downwind, and spreads at the top into a flat anvil that fans out downwind for several storm widths, with a bright overshooting dome punching through over the tower. The flanking line is a staircase of separate towers climbing to the main updraft.

**Dark under a supercell, and dark at night.** Under a supercell's rain, updraft base and wall cloud the day now goes nearly as dark as night, and the light dims well before the storm arrives. At night a storm's clouds, rain and funnel are lit by under a third of the sky light the ground is, so a storm is a dark shape in a dark sky, lit up by its lightning, instead of glowing pale against the black. The `nightStormDarkness` setting sets how dark.

**Lightning under shader packs.** With a shader pack on, the mod used to stand its lightning down and leave Weather2's plain bolt. The same forked, flickering channel is now drawn through the game's own lightning program, which the pack replaces with its own, so the pack shades the mod's bolt. The `boltThroughGameLightningProgram` setting forces this for shader mods other than Iris.

**The radar shows what is really there.** Reflectivity is now drawn from the same precipitation field the rain in the world comes from, so the forward-flank core is where the curtains hang, the hook wraps the tornado where the rain-wrap is, and the notch is clear where the inflow is, with a ragged outline, embedded cells, a hail core beside the updraft and a debris ball on a strong tornado. Most of an echo now reads as light and moderate rain round a smaller heavy core, instead of a target of concentric rings. Velocity is built from the wind in the storm and taken along the radar's beam: the storm's motion, inflow through the notch, the rear-flank and forward-flank outflow, a broad mesocyclone and the tornado tight inside it. A tornado shows as a small, gate-to-gate couplet right where it is, rather than the whole storm split into a green half and a red half, and the product is blank where there is no rain to measure.

**The TVS icon is the real one.** A tornadic vortex signature is now the red inverted triangle radar overlays use, an elevated TVS (tornadic rotation with nothing on the ground yet) the same triangle in yellow, and a mesocyclone a yellow circle. They follow the tornado detection algorithm's own thresholds, read as the velocity difference across the vortex: 36 m/s, or 25 m/s for a tornado already on the ground. Before, only a tornado over about 150 mph ever showed one, where a real radar flags most tornadoes; the TVS also now sits on the velocity couplet, which is centred on the tornado rather than on the storm. Shallow vortices get no signature at all, as on a real radar: a gustnado, dust devil, fire whirl, landspout or fair-weather waterspout spins too low and too small for the beam to resolve, so a squall line's gust front is no longer strung with TVS triangles. Switching product or range shows the new one at once, from the last scan, instead of painting it over the old one, which for a sweep showed reflectivity coloured as velocity.

**Tornadoes grow exponentially.** A forming tornado's wind and width now follow a logistic curve: slow while it organises, faster and faster as it tightens, then levelling off at the storm's strength. The old ramp was four steps end to end, so a forming tornado grew, paused, grew and paused again.

**Four more shapes.** A needle (a thin, dead-straight column), a snake (a long rope thrown into big S-curves), a barrel (a wide column with near-parallel sides) and an hourglass (wide at the ground and at the cloud, pinched between). They are chosen the way the others are, by how wide the funnel is against how tall, and each storm keeps its own. Their outlines are their own from the ground up, opening into the cloud only near the top, rather than shapes laid over the funnel's steady widening, which made all four read as plain cones.

**Horizontal and helical vortices.** Horizontal vortices now start the way real ones do: low, out in the tornado's outer circulation where the inflow rolls along the ground, and are drawn in onto the funnel, climbing and wrapping tighter as they are ingested. Some of the tubes a strong tornado carries are now helical vortices: thin tubes wound one and a half to two and a half times round the funnel from near the ground to high up the column.

**Volcanoes you can actually see erupt.** The eruption column used to be drawn at its true height in blocks - twelve thousand for a VEI 4 - so almost all of it stood past the far clip plane, and the faint puffs left below it could not be seen at all. It is now drawn on the game's sky at the scale a supercell's tower is, and drawn from photographs of real eruptions: an opaque column of cauliflower billows that boils upward from the vent and leans downwind, tan where the sun is on it and a dark grey-brown in its own shadow, flattening into an umbrella that streams away with the wind and drops grey-brown curtains of ash beneath it. A phreatic blast is the same shape in white steam. A collapsing column sends pyroclastic flows down the mountain as lobes that pour down particular flanks, hugging the ground with billowing heads and ash rising off them, rather than as a ring all round it. While there is lava the crater and the base of the column glow orange - at night that glow is most of what you see - and an explosive column flickers with volcanic lightning.

**Fire with its own flames, fire whirls, and fire that burns things.** A wildfire's front is drawn with flame of its own now - tongues of fire that flicker through their own frames, swell and die, lean with the wind, stand taller on the side the fire is running and where the fire is big, and are their own light, so a front at night is the brightest thing in the landscape. The game's own flame was a speck a few pixels across and was not drawn at all past thirty-two blocks, which is why a front read as a thin orange line or not at all. Fire whirls are columns of flame now - fire drawn up into the spin, thick and wide at the foot and tapering into the smoke, whipped round and flinging burning debris - instead of a dust devil in fire colours, and they spin up off big fires more readily, the bigger the burn the more of them. And a wildfire burns onto things: at its front it lights the trunks, canopies, wooden walls and hay bales a tree's height up the column as well as the ground, so forests and buildings catch, and anything standing in the flames is set alight. Fire whirls do the same along their path.

**More real waterspouts, landspouts and wildfires.** Checked against photographs. A waterspout and a landspout are ropes now, not cones: thin and nearly even up their height with a slow ripple, flaring like a trumpet only where they meet the cloud, and bowed - often in a gentle S - leaning back toward the cloud base as the storm drifts on. A waterspout is the pale white-grey of condensation instead of the water's blue, a little wider where its spray ring stands on the water; a landspout's foot is the tan dust it is lifting, giving way to grey condensation above. The shower cloud they hang from is a flat grey base under a tower lit by the sun, where it used to be a cluster of dark puffs that read as smoke. A wildfire has a front of flame along the edge it is burning out into, with embers popping off it, and puts up a proper smoke column - dense and tan-brown near the fire, paler as it climbs, leaning off with the wind - with its base lit orange by the flames at night; the pyrocumulus over a big fire is bright white cauliflower on top and smoky grey-tan beneath, instead of a dark grey cloud. Erupting volcanoes now throw glowing lava bombs out of the crater on ballistic arcs, and their curtains of falling ash are soft and fade toward the ground instead of hard grey stripes.

**Prettier tornadoes.** A funnel is now lit by the sun from the side it is on - pale and a little warm toward the sun, blue-grey away from it - so it reads as a solid, lit tube instead of a flat grey shape, and faint helical bands wind up it and turn with its spin, so the rotation shows even in a still frame. The random grey each funnel particle is given is narrower, so the funnel is a smooth tube with a texture on it instead of a salt-and-pepper speckle.

**Things to go and see.** Five new advancements for this release's spectacles: Totality (be under open sky as the moon covers the sun), Blood Moon (watch the moon turn copper in a lunar eclipse), Dirty Thunderstorm (be near an erupting column), Wall of Dust (be in dry country when a haboob comes through) and Front Page (take a photograph that grades Excellent). On the morning of a solar eclipse and the evening of a lunar one, players are told it is coming, so there is time to get somewhere with open sky.

**A new sun and moon, and eclipses done properly.** The moon is redrawn from the real near side: the maria where they really are - the great dark sweep of Oceanus Procellarum and Mare Imbrium joined to Serenitatis and Tranquillitatis, Crisium alone at the eastern edge - with Tycho's and Copernicus's ray systems, and terrain that catches the light along the terminator so a half moon has craters standing out on its edge. It is lit by the way the real moon reflects light, so a full moon is evenly bright to the limb instead of shaded like a ball, and the crescents carry earthshine, the ashen glow on the moon's dark side. Its halo follows the lit limb and fades along the terminator instead of being cut off by a straight line. The sun has a gentle, warm limb that runs smoothly into its glare, where it used to have a dark ring round it. In a solar eclipse the moon now crosses the sun from one side to the other instead of backing out the way it came, the diamond ring appears on the right limb going in and on the opposite limb coming out, and the corona - long streamers toward the sun's equator, fine plumes over its poles, a thin pink chromosphere and a few red prominences on the limb - only comes out in the last moments before totality, as the real one does. A lunar eclipse has a grey penumbra before the copper umbra, darkest toward its middle, with a thin turquoise band just inside its edge, and it eases in and out rather than starting with part of the moon already dimmed. The game's own sun and moon textures, which shader packs draw, carry the new art too.

**The sun and moon no longer vanish when looked at.** Whether a body had set was being read from the camera's view rather than from the sky, so looking up past the sun or the moon - putting it in the lower half of the screen - faded it out as if it were below the horizon.

**The moon is drawn at its real brightness.** The moon is one picture out of an atlas of its eight phases, and the sky program was finding where on the moon each pixel lay from the atlas's coordinates rather than the moon's own. Nearly the whole moon was therefore treated as glow and dimmed to the glow setting, except a circle round the middle of the atlas, which drew a curved edge across some phases; the full moon in particular came out a dim grey disc. The Earth's shadow in a lunar eclipse was misplaced for the same reason. Both now use the moon's own frame.

**Supercells lit like the real thing.** Checked against real photographs of supercells (NOAA and Wikimedia Commons, no edited or generated images). The storm is now lit by the sun from the side it is on: the side of the tower and cloud mass facing the sun is near white with bright edges, warming toward gold when the sun is low, and the side away from it is blue-grey, so the storm has form from a distance instead of reading as a scatter of white puffs. The anvil's flat top is the brightest cloud in the sky and its underside sits in its own shadow; the layer under the deck is the dark base. The foot of the rotating updraft is a stack of flat, flared plates, each narrower than the one below, the layered "bell-bottom" seen under real mesocyclones. The rain core under the forward flank is a lighter grey curtain than the base it hangs from, as it is in every photograph, rather than a dark mass that merged with the base. At night the directional light fades out and the night's own darkening takes over.

**Rain-wrap chance setting.** A new `rainWrapChance` setting under `supercell_precipitation` sets how often a supercell's rain wraps its tornado. Left at -1 (the default) it stays up to each storm's own moisture, as before; from 0 to 1 it is a plain chance - 0 for never, 0.25 for about one storm in four, 1 for every one. Each storm rolls once and keeps the result. A storm that rolls a wrap becomes a high-precipitation storm with its funnel hidden in rain; one that does not is held below that, so it can still have rain round its back like a classic supercell but never round the funnel.

**Squall lines have a shelf cloud.** Along a squall line's gust front there is now a long, low, tiered shelf cloud, lowest and lightest at its leading lip where it faces the open sky, with the dark, smooth underside behind it, dust rolling out ahead of it in a strong line, and the line's convective towers standing over the deck. Over the towers the anvil spreads, a little forward over the warm air as thin cirrus and most of it back over the trailing rain, and ahead of the line small cumulus float in the air it has not reached yet. Over dry ground (desert, savanna, badlands) a strong line's outflow raises a haboob, a brown wall of dust running along the gust front ahead of the rain. All of this - and anything else the client laid out from the ground under it rather than from a storm - used to be placed a hundred-odd blocks underground: the height map it read the ground from is one the server never sends, so on the client it answered the bottom of the world. It reads the surface map the client is actually kept up to date with now, and past the loaded chunks it measures from the ground under the camera. The rain behind it hangs from the cloud base rather than standing up through the deck as a grey dome over the whole line, and is split into the heavy, dark core of the convective line and the lighter trailing rain that keeps falling long after the line has passed.

**Squall lines are lines on radar.** A squall line used to show as one round echo at its middle. It is now drawn as the band it is: a narrow line of heavy cells, sharpest on its leading edge and bowed forward in the middle just as its rain and gust front are bowed in the world; a weaker transition zone behind it; the broad, light trailing stratiform rain behind that, heaviest just behind the transition zone and lighter back to a ragged rear edge, deepest behind the middle of the line and thinning toward its ends; a rear inflow notch cut into the stratiform behind the apex of the bow; and a comma head curling round behind the line's cyclonic end, the end on the left of its track (north, for a line moving east). On velocity the line moves with its wind, its outflow surges ahead of the leading edge, and the rear inflow jet runs forward into the back of the bow. The line's own cells are small cores embedded in it now, rather than a round storm-sized shield each, which drew the line as a string of beads.

**New sounds.** The tornado roar was a flat hiss; it is now a deep, surging roar with a low rumble, a growling body and the whine of the wind in a strong one. The far roar has lost its highs to the air, the hum is a deeper drone, and the electronic siren is a proper rising and falling wail. The far roar is mono now, so the wind bed that plays it is positioned and fades with distance as it was meant to; as a stereo file it was heard at full strength wherever it played.

**Calmer, cleaner seas.** Whitecaps need wind now, so a calm sea is no longer flecked white. The swash on a beach is a thin sheet of water with a line of foam along its edge, sized to the swell, instead of a grey slab several blocks up the sand in any weather. On a level beach, which is most beaches in the game, it runs a few blocks in and stops, with a ragged edge where each stretch of shore arrives on its own part of the wave train, rather than lying over the whole beach at once with a ruler-straight edge. The wave surface settles into the plain water at the edge of where it is drawn rather than ending on a line.

**It bends properly.** The funnel used to be bent by a formula, so when a storm turned the whole column swung round at once like a stick. Its centreline now has a history: the foot moves first and the top follows later, so a tall funnel curves through a turn and a kink climbs the column.

**Clouds are soft.** These are still Weather2's particles, with no volumetric rendering. They are now drawn back to front instead of cutting straight lines through each other, they feather into the ground, and they fade as you fly through them. Deck puffs face you rather than turning into black needles when seen from the side. The `softPuffs` setting under `storm_look` turns all of this off.

**A supercell has a shape.** Its parts were placed for a much lower cloud, so the tower and anvil stood inside the deck and could not be seen. There is now a flat rain-free base, a wall cloud directly over the tornado, a banded rotating tower above it, an anvil streaming downwind, a stair of flanking towers, an inflow tail, and rain shafts that look like rain.

**Less dirt, and it moves.** Dust used to fly off in straight lines like an explosion that never ended. It now goes round the foot, is drawn in along the ground and climbs a little way up the wall, in puffs sized to the funnel rather than up to ninety blocks across. There is much less of it, along with fewer specks and clods, and the brown on the funnel's foot is shorter and greyer. Wildfire smoke is thinner too, and Weather2's ring of little domes of ground fog is gone.

**Flying debris has real textures.** The sprites were a few small grey shards in an otherwise empty square. There is now a splintered plank, a handful of soil clods and a torn roof shingle.

**Dust devils, landspouts and waterspouts remastered.** They are thin and lighter, and you can see through the dust. A dust devil takes the colour of the ground it is on instead of being a dark pillar, and a spout's top dissolves rather than ending at a flat lid. Waterspout spray is bright.

**The sirens are machines.** Each one now stands on a creosoted utility pole with a control cabinet, conduit and a ground wire. The mechanical siren has a louvred drum and two flared bells, the electronic one a four-sided horn array with a solar panel and Yagi antenna, and the two-tone a yellow head with a big and a small bell. They are lit by the world, carry a real texture, and the item is the same model as the block.

**The portable radar is a real display.** It has a new rugged case, the NWS reflectivity and velocity colours, a map under the data, range labels, a colour key, and a sweep that paints each new scan in behind the beam. The weather radar block's face uses the same picture.

**The weather forecaster is a projector.** The "?" cube is now a console with a lens on top, and it throws the actual radar composite over a map up on beams of light, instead of a pile of rain icons.

**New textures.** The machine and handheld item textures were regenerated at higher resolution by `tools/TextureGen.java`.

**The anemometer, weather vane and alert siren dish were rebuilt** the same way as the sirens: three real cups, a copper arrow over lettered arms, and a dish that now curves the right way, toward its feed horn.

**Bugs fixed.**
- Every new world had a total eclipse at its first noon, which dimmed the world to half for four minutes.
- Waterspouts hovered above the water for their whole life, because the client never knew how long they had left.
- Landspouts floated at least five blocks off the ground.
- The meteorologist villager had no texture and drew as a magenta checker.
- The charred log item had no model.
- The `weatherDrone` config switch did nothing. It now blocks new launches, and a drone already flying can still be recalled.
- The portable radar drew its title twice and let long storm names run off the screen.
- Parts of a storm's structure over unloaded chunks were placed from the bottom of the world, which put whole sheets of cloud at head height.
- The server reloaded every storm's saved state from a copy of itself every tick, before it had moved on. That froze a tornado's width and wind on the server: its damage path stayed at its starting width, and its wind sat near its starting value, which is why a new tornado could read as little as 77 mph. Tornadoes now grow and strengthen on the server as they always should have, and their damage path is as wide as they look.
- No tornado that died on its own ever roped out. The test for a dying storm needed Weather2's `isGrowing` to go false, and Weather2 never sets it after a storm is made.
- A weakening tornado's foot faded off the ground over each stage and dropped back the moment the stage changed, because it was drawn from the stage's own intensity, which starts again at full in every stage.
- During a multi-vortex spell the tornado's own dust cloud stopped spawning altogether, because the spell's vortices filled the particle queue past the point where dust is allowed. The vortices' own dust now keeps to that limit, and the tornado's ground dust has more room than the other loose effects.
- A dust puff that reached the funnel wall went on climbing for the whole of its life, so round a thin funnel the dust climbed in a column all the way to the cloud.

## 1.06.3

**The eclipse works under a shader pack now.** Before, the moment a pack was active the mod handed the whole sky over and the eclipse went with it - no dimming, nothing at all. The darkening is not part of the sky the pack draws, though: it is the same reading a storm's deck already produces, the value the game's own lightmap is built from, so the eclipse's is folded into it. The sky's colour, the cloud deck, the fog and the world's own light all dim together as the moon crosses the sun, and because that reading is one the game itself consumes rather than a program the mod has to draw, it arrives under a pack the same as without one. The throw of the sun and the light the world is drawn with still compose under a storm's own deck, so standing in a storm during an eclipse is darker than either alone.

**Your sun stays yours.** The mod's own sun and moon still stand down under a pack, because there is only ever one sun. The eclipse's own black disc, its corona and its diamond ring are still drawn by the mod's own sky, and there is a new switch - off by default - that asks the mod to try drawing them over a pack's sky as well. It ships off because a pack that does not provide a program for the mod's celestial shader draws these with its fallback instead, which may not appear or may look wrong; the dimming is the half that is guaranteed, and it happens either way.

**Storms out over the country you can actually see.** Weather2 spawns its storms inside a box around the players and forgets one that has no player near it, so with Distant Horizons or Voxy the sky tended to stop over terrain that was plainly on screen. The box now widens to the LOD renderer's own distance while one is installed - storms are still being born out over the drawn country, and still alive out there - and the mod's own storm state follows them, so a distant funnel arrives with its real circulation instead of a sliver. It never narrows Weather2's own box, does nothing at all without an LOD mod, and the two dials are a switch and a distance that follows the renderer by default.

**Whirls out over LOD terrain.** A dust devil or a snow devil used to be refused the moment its chunk was not loaded, which meant never over terrain an LOD renderer was drawing. Over an unloaded chunk the ground is read from the biome instead of from blocks - dry country for a dust devil, cold country for a snow devil, the level's own rain flag for the sky - so the small whirls can form out there too. A loaded chunk is judged exactly as it always was, and nothing forces a chunk to load just to ask the question.

## 1.06.2

**Ten thousand downloads.** Thank you - that is ten thousand worlds this mod has been dropped into, and the reports, screenshots and complaints that came off them are why most of this list exists. The sea is the headline here.

**New cloud and funnel art.** The storm deck and the funnel each draw a single authored puff now, instead of
eight-frame strips with four phase siblings per role. The old sheets were animated by uploading every frame
into the same atlas region, which meant every puff in a storm showed the same frame at the same instant and the
whole sky read as one large texture flipping. A still has nothing to phase, so the extra sheets, their atlas
entries and their animation clocks are gone - along with the setting that used to stagger them, which now does
nothing but is kept, whole, as the way back.

**The tornado draws its own darker puff.** A funnel is a few large, close surfaces where a cloud deck is
hundreds of soft ones, so the same art that sums into an overcast summed into a haze on a column - which is why
a wider funnel used to read as *more* transparent rather than less. The funnel now has its own sheet,
deliberately darker and denser than the cloud's, and it is not governed by the art switch: a tornado draws its
own art either way, falling back to the selected puff rather than to nothing.

**The wash finally reaches the sand.** Waves now run up the beach the way they should: a crest arrives, water
sheets up onto the row of sand past the waterline, and it drains back between crests. Before this, the water
could only ever wet the row of sand sitting at the water's own level, because the run-up was measured from the
waterline while the first row of sand stands a whole block higher than it. In practice that meant the swash
appeared on an ordinary beach only in winds above about 47 mph. It starts climbing at around 30 now, and a
strong storm wets the row above the waterline right across a crest, with that row dry again behind it. Small
seas behave as before: a calm day washes the waterline and nothing above it.

**No more whitewater painted on the seabed.** Water that had run up was also being drawn on ground below the
waterline - a film of foam on sand that was already underwater, visible through the shallows. Only ground above
the waterline takes a wash now.

**The Surf switch actually switches it off.** Turning Surf off leaves no breakers, no whitewater and no wash on
the sand, and setting Surf Run Up Blocks to 0 leaves the water entirely. The wash used to keep running up the
beach whatever those two settings said.

**The cliff in the sea around a beach is gone.** The wave surface is only told how deep the water is for a band
of cells around land, and that band was sized in advance for a seabed that drops a whole block per block. On the
gentler bottom most shores actually have, the band ended while the water under it was still shallow enough for
the swell to stand up and break, so beyond it the waves went back to their full open-water height: a ring of
raised water, up to a block and a half tall in a big storm, running around every beach. The band is now grown
outward until the water at its own edge is deep enough that the waves cannot feel the bottom - two cells on a
cliff coast, eight on a gently sloping one. Measured on a shore sloping one block down per two blocks out, the
step went from 0.64 of a block to none at all.

**The sea no longer shimmers when you move.** Each cell of the wave surface shows one window of the water
texture, and that window was tied to the surface's own grid rather than to the world. The grid is rebuilt every
time you swim or sail across a cell, so the whole sea's texture re-rolled as you moved - a pop with nothing
visible causing it. A patch of sea now keeps the same texture wherever you are standing.

**One knob means something slightly different now.** Surf Run Up Blocks is measured from the top of the block
of sand the water is standing against rather than from the still-water line, because a beach is made of blocks
and that first one is a whole block above the water. The default is unchanged and was chosen against this
reading; if you have set your own value, it is now worth about a row more wash than it used to be.

**Satellite tornadoes no longer sink into the ground.** Small suction vortices now hug the ground they are
standing on instead of burying their lower rings in it, so a multi-vortex tornado's whirling feet read as
standing on the surface rather than clipping through it.

**Storm debris is made of the ground.** What a tornado throws used to be a vanilla stick or a twig sprite tinted
by how dry the ground under it was, so every debris field was the same grey and a scoured field, a desert and a
gravel path all threw the same thing. Loose debris is now a real piece of the surface block it was cut from -
grass, dirt, sand, gravel, terracotta, whatever is under it - cut from a random face of that block and carrying
its colour, so a debris field tells you what the storm has been tearing up. The pieces are deliberately smaller
than the twigs they replace, so the field gets lighter as it gets solid rather than heavier.

**Chase drones survive their storm.** Fixed the drone being lost to the weather it was launched into.

**The chase log counts chases.** A fire whirl, a dust devil, a snow devil, a gustnado or a waterspout is not a
tornado and has no EF rating, and a funnel cloud puts nothing on the ground - so none of them is filed any more.
The log was reading the same list of vortices a radar draws, which filed a 108 mph fire whirl as an EF1 chase
next to the computer's own note that a chase log is about damage. Only tornadoes count now, whatever their wind.

**The world has a calendar.** Spring, summer, autumn and winter differ now, and the difference is the sky rather than a colour: spring raises instability, shear and the tornado odds, summer piles up heat and moisture while taking the shear away (the pulse season - many storms, few tornadoes), autumn puts the flow back and lifts the fronts, and winter takes the instability away and leaves the weather to its fronts. A year is 120 days by default and can be set to 365, a new world opens in March, and `/ewd season` prints the day, what the season is doing to the air, and whether a tornado could be born where you are standing. With Serene Seasons installed its season is read and used instead. Modpacks can set a strength per season and a multiplier per month in the config, or drop a small JSON file into a datapack to name the dangerous months and even the dangerous days - and a datapack can shorten or lengthen the year while it is there.

**Tornadoes are born over plains.** A land tornado is the one vortex here that belongs to the ground rather than to the air, so it now needs plains country under it: a biome that reads as plains, or one a pack has put in the `#expandedweather2dynamics:tornado_genesis` tag. Forests, jungles, mountains, oceans and the rest no longer get funnels, landspouts included; waterspouts are unaffected, a tornado already on the ground is not sent back over a border, and `tornado_genesis.plainsOnly` turns the whole rule off.

**Both of those are one dial apiece now.** The simple page gained the seasons and the ground a tornado may form
over as its eighth and ninth dials. The seasons one says how much of the year is in force - 1 is the shipped
calendar, 0 is a year that turns without the sky noticing, 2 puts every season twice as far from the neutral day
- and it scales the configured strengths, the month multipliers and any datapack's own together, so a world that
wants a gentler year changes one number instead of nine. The plains switch can only take the rule away, never
add it, so a world owner who has already allowed funnels anywhere keeps the last word.

**Fixes and polish** across the radar panel, the settings screen, the spotter net, photographs and the chase
log, mostly small.
