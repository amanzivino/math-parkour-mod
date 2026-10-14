# Math Parkour (science + hub + shop prototype)

A Minecraft Java/Fabric prototype where arithmetic and simple science facts are the game: answer doors control the route, wrong doors drop you into lava, a hub with pressure-plate triggers starts courses, persistent FSRS spaced repetition decides which facts are due, and an in-game points/shop lets players buy pets and toys.

Quick commands
- /mathparkour hub — build an in-world hub with pressure plates to start modes or open the shop.
- /mathparkour start [mode] — start a course. Modes: math, squares, science, mixed (default mixed).
- /mathparkour hint — show hint for current question.
- /mathparkour stats — show progress and points.
- /mathparkour shop — list shop items.
- /mathparkour buy <itemId> — purchase an item with points.
- /mathparkour summon <itemId> — summon an owned pet or receive a bought toy.
- /mathparkour pets — list owned items/pets.

Build
- Requires Java 21 and Gradle 8.12.1 (or use the bootstrap build script).
- Run `gradle build` from the project root to produce `build/libs/math-parkour-<version>.jar`.

Notes
- Facts: multiplication & division from 2..15, perfect squares 2²..15², and a small set of numeric science facts.
- Learning improvements: interleaved selection, short explanation after feedback, FSRS scheduling.
- Hub: place builds a small platform with colored pressure plates — stand on one to start that mode or open the shop.
- Shop: points currency earned during play (correct answers, first-try bonuses, course completion). Purchases persist per world per player.

This is a source project. Run a local Gradle build and test in a disposable Creative world first — the generator clears some airspace above the terrain and writes blocks.
