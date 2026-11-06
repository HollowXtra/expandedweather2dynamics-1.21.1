# Changelog

What changed in this release, written for the people who play it rather than for the people who read the
source. The long version of every one of these - what was wrong, what it was measured against, and what is
still an honest unknown - is in the README.

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
