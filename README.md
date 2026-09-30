# cowatch

A desktop client for watching video in sync with other people. It drives a
local [mpv](https://mpv.io) window over mpv's JSON IPC socket, relays play,
pause and seek events through a cowatch server, and can pull the media from a
[Jellyfin](https://jellyfin.org) library or from files on disk.

Playback happens in mpv itself, so you keep mpv's decoders, subtitle handling,
shaders and keybindings. cowatch is the room around it: connection, media
picking, sync, drift correction, chat, and episode tracking.

## Features

- **Synchronised playback** — play, pause and seek propagate to everyone in
  the room, with the round trip compensated so late joiners land in the right
  place rather than a second behind.
- **Three media sources** — browse a Jellyfin library by show, season and
  episode; pick a local file and let the whole folder load as a playlist; or
  attach to a [Seanime](https://seanime.app) server's library, including
  episodes its auto-downloader fetched.
- **Works with playback started outside cowatch** — mpv accepts several IPC
  clients at once, so cowatch can attach to the mpv window Seanime opened
  rather than starting a second player. Play, pause and seek stay in sync
  whichever side presses them.
- **Mixed rooms** — a `media` event carries the AniList media id and episode
  number rather than a file path, so one viewer streaming from Jellyfin and one
  playing a downloaded file can watch the same episode together without
  matching filenames.
- **Playback offset** — nudge your own stream by ±0.1s or ±1s when your copy
  has different intro padding from everyone else's. The offset applies to
  outgoing and incoming positions both, so nobody else has to compensate.
- **AniList tracking** — an episode is marked watched once you pass 80% of it,
  for both Jellyfin and local playback. Local filenames are parsed with
  AnitomyJ, and absolute episode numbering is mapped back to seasons through
  the [AniZip](https://api.ani.zip) API.
- **Chat and event log** — a running transcript of who paused, who seeked and
  where, alongside plain chat.
- **Uncluttered by default** — the window opens at 960×620 with just the media
  browser. The server, sync mode and AniList settings live in a separate
  settings window, chat is a toggle, and the transport bar appears only while
  mpv is running.

## Requirements

| | |
|---|---|
| Java | 17 or newer |
| mpv | on `PATH` (Windows) or in a standard prefix (macOS, Linux) |
| Server | a reachable cowatch sync server |
| Jellyfin | optional, only for the Jellyfin source |
| Seanime | optional, only for the Seanime source |
| AniList | optional, only for progress tracking |

Install mpv with `brew install mpv`, `sudo apt install mpv`, or
`sudo pacman -S mpv`. On Windows, put `mpv.exe` somewhere on `PATH`.

## Build and run

```
mvn javafx:run
```

To produce a self-contained jar:

```
mvn package
java -jar target/cowatch-client-1.0.0.jar
```

Pushing to `main` also builds native installers — `.exe`, `.deb` and `.dmg` —
and attaches them to a prerelease. See `.github/workflows/maven-publish.yml`.

## Using it

1. Pick a **sync mode**, enter the server host, port and your display name,
   then press **Connect**.
2. In the media panel, either connect to Jellyfin and drill down to an episode,
   browse to a local file, or connect to Seanime and attach to its player (see
   [Watching with a Seanime user](#watching-with-a-seanime-user)).
3. Press **Launch in MPV**. Everyone in the room does the same with their own
   copy.
4. Use the playback controls, or mpv's own keys. Either way the room follows.

If your copy runs ahead of or behind everyone else's, adjust the offset until
it lines up. The value is remembered across a reconnect.

## Watching with a Seanime user

Seanime's own watch-party feature (Nakama) only works between Seanime
instances, and Seanime cannot read a Jellyfin library, so a room where one
person streams from Jellyfin and the other plays downloads needs cowatch in
the middle.

On the Seanime side, one setting has to change first:

1. In Seanime, open **Settings → Media Player** and set a fixed **mpv socket**
   name. Seanime otherwise invents a new socket name for every playback
   (`pid_timestamp_counter`), which cowatch has no way to guess.
2. If the Seanime server is on another machine, set `host = 0.0.0.0` in its
   `config.toml`; it binds to `127.0.0.1` by default.

Then, in cowatch:

1. Pick the **Seanime** sync mode and enter the server address
   (default `127.0.0.1:43211`). The password field is only needed if the
   Seanime server has one set.
2. Start the episode **in Seanime**, as usual.
3. Press **Attach to Seanime playback**.

cowatch connects as a second client on that mpv socket, reads back which file
is loaded, resolves it against Seanime's library to an AniList id and episode
number, and announces that to the room. From then on play, pause and seek
propagate in both directions.

AniList progress stays with Seanime in this mode. It is already tracking the
episode it started, and two writers on one list entry would race each other.

## Settings

Settings live in the platform's per-user configuration directory:

| Platform | Location |
|---|---|
| Windows | `%APPDATA%\cowatch\` |
| macOS | `~/Library/Application Support/cowatch/` |
| Linux | `$XDG_CONFIG_HOME/cowatch/`, or `~/.config/cowatch/` |

Three files live there: `cowatch.properties` for the connection,
`cowatch-media.properties` for the Jellyfin and Seanime servers, and
`cowatch-tracking.properties` for the AniList token.

These files are **not encrypted**. Your Jellyfin password is never written to
them — logging in stores the access token Jellyfin returns instead, which
reconnects the same way and can be revoked from the server. The AniList token
is stored as-is, so treat the directory as sensitive and revoke the token from
`anilist.co/settings/developer` if the machine is shared.

## Getting an AniList token

1. Go to `anilist.co/settings/developer` and create a client.
2. Choose the **Implicit** grant.
3. Paste the token into the Tracking panel and press **Verify**.

The token is checked against AniList on every start, so an expired one shows
as invalid rather than failing quietly the first time an episode finishes.

## Server protocol

The client speaks newline-delimited JSON over a plain TCP socket, UTF-8 both
ways. Messages it sends:

```json
{"event":"join","room":"room1","username":"alice"}
{"event":"play","position":123.456}
{"event":"pause","position":123.456}
{"event":"seek","position":123.456}
{"event":"chat","data":"hello"}
{"event":"media","anilistId":21,"episode":1015,"title":"One Piece","source":"seanime"}
```

Messages it understands coming back carry the same `event` names, plus `from`
naming the sender and an optional `serverTime` in milliseconds since the
epoch, which the client uses to age the position before applying it. It also
renders `system`, `joined` and `error` events into the chat transcript.

## Layout

```
com.cowatch.client
├── Launcher            plain main class, so java -jar works
├── jellyfin            Jellyfin REST client and item model
├── model               session config and sync mode
├── mpv                 mpv process control and JSON IPC (named pipe / unix socket)
├── network             server socket and wire protocol
├── seanime             Seanime REST client, library path index, progress handoff
├── sync                clock sync, drift correction, watched-threshold detection
├── tracking            AniList and AniZip clients
├── ui                  window shell, responsive layout, motion, panels
└── util                shared prefs and background execution
```

## Known limits

- Sync assumes everyone is playing the same cut. Different releases need the
  offset panel, and it only corrects a constant shift, not a different edit.
- The Jellyfin source reports progress back to the server every 10 seconds
  while playing, so a hard crash can leave a stale "now playing" entry.
- Seanime mode needs a fixed **mpv socket** name set in Seanime, because
  Seanime otherwise picks a new one per playback. It also needs the episode to
  already be playing there: the socket exists only while its player runs.
- A `media` announcement is relayed but not acted on automatically. The other
  side is told which AniList id and episode to open and still picks its own
  copy by hand; resolving an AniList id back to a Jellyfin item is approximate
  and not attempted yet.
- Announcing from the Jellyfin source needs a verified AniList token, since
  the id comes from an AniList title search. Without one the room simply gets
  no announcement.
- There is no room password or transport encryption. Run the server somewhere
  you trust, or tunnel it.
