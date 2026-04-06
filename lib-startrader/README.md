# lib-startrader

Standalone economic simulation engine for Nevet.
Models a multi-planet trade universe with NPC-driven supply and demand, population-based consumption, and price
discovery.

The module has no dependencies on lib-core, JPA, or any database.
It builds as a normal library JAR for use as a dependency in other modules.

## Swing UI Harness

lib-startrader includes a Swing UI for watching the simulation engine in motion.
The UI provides a 3D universe map with perspective projection, real-time economy graphs, and a trade panel for testing
buy/sell orders.

The 3D universe map isn't useful, it's just fun; it's not yielding meaningful data.
But visuals are fun, and in the original Pascal game that inspired this model some thirty years ago and more, the
visuals for planet placement were one of the first pieces created, after a workable calendar model (not available to me
before the implementation I created), so it felt like something this visualization had to have.

To build and run the UI harness:

```bash
./mvnw clean package -pl lib-startrader -Pstandalone
java -jar lib-startrader/target/lib-startrader-1.0-jar-with-dependencies.jar
```

The `standalone` profile bundles all dependencies into a single executable JAR.
Normal builds (without `-Pstandalone`) produce a library JAR only.

## The Economic Model

This library is based on the early BASIC game "Star Trader," as played on a CDC Cyber One back in the 1980s. I didn't
have a CDC Cyber One handy, and I was trying to move past Basic, so when I got Turbo Pascal 6, I created my own "Star
Trader" game, a multiplayer game that required everyone to sit at the same computer, since I had a slow modem and not a
lot of understanding of writing networking code.

My version followed a similar concept: you have a set of planets, with a connected economy, and sent your ship to trade
between planets; the game would stop when your ship would "land" and allow you to trade with the planet you were on.

I changed the game a bit; I developed a full Gregorian calendar library (including leap year calculations, but no
intercalation past that) and I wanted the "galaxy" to be 3D, so I added a 3D isometric projection; the economic model
also grew quite a bit, with many more commodities and, more importantly, a supply chain model, where one commidity
consumed *other* commodities as part of its production; food, for example, takes some chemicals (for pest control),
fuels (for power), machines (for harvesting), medicine (for healing of worker injuries or illness), and so forth and so
on, so the price of food had to factor in everything that went into its production.

It ends up creating a subtly complex economic market that displays emergent patterns of supply and demand.

There are 12 commodities arranged in a tiered supply chain:

- **Raw materials**: Ore, Organics, Fuel
- **Refined goods**: Alloys, Chemicals, Components
- **Advanced products**: Machines, Tech, Medicine, Textiles
- **Consumer goods**: Food, Luxury

Higher-tier goods typically require lower-tier inputs to produce, while lower-tier goods require *some* amount of
higher-tier goods; the worlds are not intended to be isolated, so even an agriculture-heavy planet should need "high
technology" or "luxury goods" for its population.
Alloys need Ore and Fuel; Machines need Alloys and Components; Luxury goods need Tech, Textiles, Chemicals, and
Machines.
No planet can be self-sufficient - the production matrix forces interdependence.

As mentioned, each planet also has a population that consumes goods every tick.
Food and Medicine are the heaviest drains; even raw materials see some population demand.
When supply runs low, production slows (no inputs means no output), prices climb, and the ripple effect propagates up
the supply chain.

### Pricing

Prices blend a universe-wide market signal with local supply conditions.
A planet sitting on a mountain of Ore will price it cheaply; a planet running dry will price it high.
Input costs feed forward - if Ore gets expensive, Alloys get expensive, which makes Machines get expensive.
Prices converge iteratively with dampening, so they drift toward equilibrium rather than jumping to it.

### NPC Dampening

NPC traders act as a stabilizing force, importing goods to planets that are running short and exporting from planets
with surplus.
They fire probabilistically - the further a planet's supply deviates from the universe average, the more likely NPCs
step in.
A minimum reference supply floor prevents the entire universe from collapsing into scarcity.
NPCs keep the economy alive but don't prevent interesting price swings.

### Events

Random economic events shake things up: plagues spike Medicine demand, famines drain Food, wars burn through Fuel, trade
booms drive Luxury consumption.
In the initial implementation, up to three events can be active at once, each lasting a handful of ticks.
Events create supply shocks and arbitrage opportunities - the interesting part of any trade economy.

## What You See When You Run It

The UI has four areas:

**Price table** (center): a grid of planets vs. commodity prices.
Cells shade green when prices drop and red when they rise, so you can watch supply and demand play out in real time.

**Trade panel** (top): lets you queue buy and sell orders on any planet.
Orders accumulate and apply on the next tick, so you can set up a multi-planet trade run and watch the consequences.

**Universe map** (split off, and optional): a rotating 3D view of the planet layout.
Planets are color-coded by their production tier, with connection lines between neighbors.
You can drag to rotate, scroll to zoom, and double-click to toggle auto-rotation.

**Event log** (bottom): a running transcript of what happened each tick - supply changes, price extremes, events
striking and expiring, and any trades you made.

Click the Tick button to advance the simulation one step (there are buttons to advance ticks in bulk as well, and you
should see stability in an economy model after a few hundred ticks).
Each tick runs production, consumption, NPC dampening, events, and price convergence in that order.
The status bar shows the current tick count, universe GDP (total value of all goods), and active event count.
