 Remnants of the Precursors

Remnants of the Precursors is a Java-based modernization of the original Master of Orion game from 1993. <br/>

### Fusion version
### Mixt of of Xilmi Fusion with Modnar new races
### With BrokenRegistry Options Manager. <br/>
... and some more features

Summary of the differences of Fusion-Mod to the base-game:
        [https://www.reddit.com/r/rotp/comments/x2ia8x/differences_between_fusionmod_and_vanillarotp/](https://www.reddit.com/r/rotp/comments/x2ia8x/differences_between_fusionmod_and_vanillarotp/) <br/>

Description of the different AI-options in Fusion-Mod:
        [https://www.reddit.com/r/rotp/comments/xhsjdr/some_more_details_about_the_different_aioptions/](https://www.reddit.com/r/rotp/comments/xhsjdr/some_more_details_about_the_different_aioptions/) <br/>

The decription of the additions/changes by Modnar can be found there: <br/>
	[https://github.com/modnar-hajile/rotp/releases](https://github.com/modnar-hajile/rotp/releases) <br/>


### To build and run locally:

On Debian / Ubuntu:

```
sudo apt install vorbis-tools
sudo apt install webp ffmpeg
mvn clean package -Dmaven.javadoc.skip=true
java -jar target/rotp-<timestamp>-mini.jar
```

On Fedora:

```
sudo dnf install libwebp-tools vorbis-tools
# Headless video export and its tests also require ffmpeg with libx264 and ffprobe.
mvn clean package -Dmaven.javadoc.skip=true
java -jar target/rotp-<timestamp>-mini.jar
```

### Headless AI matches

Run the full or mini JAR with `--headless` for a small Fusion AI duel. The simulation runs to victory and prints the final turn, victory condition, and winners. It does not require a display or write game saves.

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --headless --galaxy-size small --opponents 3 --ais fusion,base,modnar,rookie
```

Use the filename produced by your build. A single AI name applies to every empire. A comma-separated list assigns one AI per empire, starting with empire zero. Run with `--headless --help` for supported names and sizes. Exit codes are 0 for success, 2 for invalid arguments, and 1 for a simulation error.

### Run a match with LLM leaders

To play SHODAN against Fusion using your Codex CLI login, put `codex` on PATH, sign in with `codex login`, and run:

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --headless --match examples/codex-duel.json --record codex-duel.rotpreplay
```

A seat with `"type": "codex"` uses `gpt-5.6-terra` with `low` reasoning by default. Set `model` or `reasoningEffort` on a seat to override either setting independently. The game runs a fresh `codex exec` for the race draft and each briefing, sends the persona and that empire’s information on stdin, and validates the JSON reply against the legal choices. The draft journal records the model and effort used. Codex seats use the installed CLI's credentials and can share a match with HTTP-backed or Fusion seats.

To play SHODAN (GPT-5.6 Terra Low) against GLaDOS (local Gemma 4), start Ollama with `mo-shakib/gemma4-e4b-uncensored:q4_k_m` installed and run the mixed match:

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --headless --match examples/shodan-vs-glados.json --record shodan-vs-glados.rotpreplay
```

The example connects to Ollama at `127.0.0.1:11434` and sets `structuredOutput` on that endpoint so Gemma receives the JSON schema for each decision. If your installation uses another Gemma 4 tag, change the GLaDOS seat's `model` to the name shown by `ollama list`.

For an OpenAI-compatible HTTP endpoint, copy `examples/leader-match.json` and set each seat's model to an identifier served by its endpoint. The example includes five leaders. For a duel, keep two seats. Use `{"type":"fusion"}` for a stock opponent.

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --headless --match examples/leader-match.json --record leaders.rotpreplay
```

Each named endpoint supplies a `baseUrl` for the OpenAI-compatible chat completions API and an optional `apiKeyEnv` naming an environment variable. Each LLM seat specifies a name, endpoint, model and one persona source. Use `preset` for one of the five included voices, `promptFile` for a text file relative to the match JSON, or `prompt` for inline text. The draft seed resolves competing race preferences. Galaxy generation uses the game's normal settings.

The legacy scheduler briefs leaders on turn one, every fifth turn after that, and when they meet an empire, enter a war, lose a colony, or receive mail. They can allocate research, select an available technology, assign a fleet, and negotiate with known empires. Mail arrives on the next turn. Trade, pacts, alliances and peace require agreement. Fusion handles routine empire operations. After two failed requests, a leader keeps its existing orders or takes an available race during the draft.

Set the top-level match field `"scheduler": "event"` to try persistent objectives. The engine follows research allocations, technology goals and named fleet missions across turns. A leader can retain or release an objective, set a reassessment date, or wait for its objective to complete or become blocked. War, first contact, colony loss and a visible threat at a mission target prompt a review. Offers prompt review on delivery. Ordinary mail waits up to three turns from the first unread delivery, and its recipient can shorten that interval. Each review includes the empire’s private memory and permits one batched lookup from the same turn’s observations.

Each completed leader match writes a sibling `.metrics.json` with per-seat and combined attempts, retries, failures, provider-reported input and output tokens, and time spent on requests. Missing usage counts indicate partial coverage. Simulation and video export times are separate. To compare schedulers, run the same match file once with `"legacy"` and once with `"event"`, using distinct recording names. The draft seed controls race selection priority. Independently generated galaxies and model decisions can produce different game lengths, so compare per-turn costs alongside totals.

A completed `--match` run also writes two UTF-8 text files beside the recording. For `duel.rotpreplay`, open `duel-diplomacy.txt` for diplomatic messages and `duel-journal.txt` for turn-by-turn in-character summaries, orders, outcomes, and engine fallbacks. Regenerate both files offline from a compatible archive with:

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --export-replay-logs replays/duel.rotpreplay
```

The desktop replay journal includes the draft, strategy summaries, orders and diplomatic exchanges. The **Empire roster** tab shows the race, player, persona, and model or built-in AI for every empire throughout playback. The MP4 territory legend labels each controller and identifies the winners on the final frame. Older compatible recordings display “Not recorded” for missing controller metadata. Opening the replay or exporting its video again works offline.

### Record and replay a match

Headless matches save the recording and a silent 1080p H.264 territory video in `replays/` under the working directory by default. Use `--record duel.rotpreplay` to name both files, or supply a path with a directory to choose another location. Video export requires FFmpeg with the libx264 encoder on PATH.

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --headless --ais fusion,xilmi --record duel.rotpreplay
```

After the match finishes, start the desktop game from the same build and select **Open Replay** on the game menu. Inspect systems, fleets, and empires on the galaxy map or in the object list. The turn journal contains orders, diplomatic exchanges, battles, and galaxy events. **Previous turn** and **Next turn** step while paused. **Play** advances automatically at the selected speed. Closing the viewer returns to the game menu.

Select **Territory** in the replay window to view the same historical borders. Each star has a fixed region colored by its current owner. Uncolonized regions remain neutral. The legend counts current colonies. A gold ring marks a battle, bombardment, or invasion, and a double ring marks a change of colony owner. Rings fade over five turns. The final territory frame shows the victory condition and every winning empire. The exported MP4 also includes that result in its comment metadata. The video plays at 10 turns per second with one frame per turn, using the last snapshot when a turn has multiple entries.

To regenerate a video with the same game build after a match or an encoder failure:

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --export-replay replays/duel.rotpreplay
```

Recordings include a compressed game snapshot and observer data for each turn. Playback reads the recorded observer data directly and requires no simulation or AI calls.

# Other Links
[Official website](https://www.remnantsoftheprecursors.org/) <br/>
[Community subreddit](https://www.reddit.com/r/rotp/) <br/>
[Download build](https://rayfowler.itch.io/remnants-of-the-precursors)


## What's New

26-11-26 (BR)
- Galaxy setup: Cover over the start button when "Advanced Galaxy Configuration" is open.
  - Clicking "start" without getting any result can be frustrating...

26-11-25 (BR)
- In addition to the "Connect the Empires" option, you can now set the maximum hostility level for the star systems that connect the empires.
  - The random generation of a planet is determined by the parameters set in other options (galaxy age, planet quality, etc.). However, if the result does not meet the requirements of this option, new planets will be generated until a valid one is obtained.
- Fix for a potential error related to concurrent access to the transport list during landing.

26-11-24 (BR)
- New galaxy option to prevent an empire from becoming overly isolated from others due to inadequate fuel cells, one can ensure the existence of a path of colonizable star systems with spacing compatible with the fuel cells easily available. The maximum distance can be defined using the "Maximum connection distance" option.
  - Connect empires only.
  - Connects empires and Orion.
  - Connect all star systems, but asteroids may still limit access to less significant stars.
  - This feature is disabled by default.
- New option to select the maximum distance between two stellar systems that creates a path, allowing two empires to be connected.
- New option to minimize the number of added stars when connecting empires.
  - The connection procedure involves linking the nearest stars until all required connections have been established. Not all of the new stellar systems generated in this process will be used to form the final, required links. You may therefore choose to remove these "unnecessary" additions. (It is generally better to keep them, so that the paths are not too obvious.)
- New option to preview the warranted path in yellow. (In the galaxy preview of the setup panel only)
- Fixed an issue that prevented the generation of the "Bulls Eye - Ring" galaxy when empire spreading exceeded 700%.
- Standardization of the star location  in the GalaxyShape class to allow empire connection.


### [Features Historic](FeaturesChanges.md)

### [Reverse Chronological Historic](DetailedChanges.md)


## [To-Do list](TodoList.md)

[How To](doc/HowTo.md)



### Watch recorded matches from a browser

Start the replay library on the machine that holds the recordings.

```sh
java -jar target/rotp-Fusion-2026-11-27.jar --serve-replays --dir replays --bind 0.0.0.0 --port 8080
```

Use your build's JAR filename. The directory, bind address, and port shown above are the defaults. Open the printed LAN address from a browser on the same trusted network. The server provides read-only access without a login. To restrict viewing to the host, use `--bind 127.0.0.1`. Stop the server with Ctrl+C.

Select a recording to start paused on its first frame. Step through the map, jump to a turn, or select a history entry to see the frame where it was recorded. Filter by empire, diplomacy, leader journal, or battles. By default the timeline reveals events through the selected frame. Select **Reveal full history** for the complete timeline and result. The MP4 and downloadable text logs, when available, contain the whole match. Each browser controls its own playback. Refresh the library after another simulation finishes.

The replay library opens newest first and shows completion times in your browser’s time zone. Older archives use a date labeled **File modified**. Use **Sort by** to order games by time, race, model, or persona in either direction. Refresh keeps your selection. Games without the selected roster field appear last.
