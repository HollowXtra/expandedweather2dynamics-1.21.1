# Changelog

What changed in this release, written for the people who play it rather than for the people who read the
source. The long version of every one of these - what was wrong, what it was measured against, and what is
still an honest unknown - is in the README.

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
