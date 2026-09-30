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
- **Two media sources** — browse a Jellyfin library by show, season and
  episode, or pick a local file and let the whole folder load as a playlist.
- **Playback offset** — nudge your own stream by ±0.1s or ±1s when your copy
  has different intro padding from everyone else's. The offset applies to
  outgoing and incoming positions both, so nobody else has to compensate.
- **AniList tracking** — an episode is marked watched once you pass 80% of it,
  for both Jellyfin and local playback. Local filenames are parsed with
  AnitomyJ, and absolute episode numbering is mapped back to seasons through
  the [AniZip](https://api.ani.zip) API.
- **Chat and event log** — a running transcript of who paused, who seeked and
  where, alongside plain chat.
- **Responsive layout** — three columns on a desktop, a two-pane split on a
  tablet, and a single column behind a bottom tab bar on a narrow window.

## Requirements

| | |
|---|---|
| Java | 17 or newer |
| mpv | on `PATH` (Windows) or in a standard prefix (macOS, Linux) |
| Server | a reachable cowatch sync server |
| Jellyfin | optional, only for the Jellyfin source |
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
   or browse to a local file.
3. Press **Launch in MPV**. Everyone in the room does the same with their own
   copy.
4. Use the playback controls, or mpv's own keys. Either way the room follows.

If your copy runs ahead of or behind everyone else's, adjust the offset until
it lines up. The value is remembered across a reconnect.

## Settings

Settings live in the platform's per-user configuration directory:

| Platform | Location |
|---|---|
| Windows | `%APPDATA%\cowatch\` |
| macOS | `~/Library/Application Support/cowatch/` |
| Linux | `$XDG_CONFIG_HOME/cowatch/`, or `~/.config/cowatch/` |

Three files live there: `cowatch.properties` for the connection,
`cowatch-media.properties` for Jellyfin, and `cowatch-tracking.properties`
for the AniList token.

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
- There is no room password or transport encryption. Run the server somewhere
  you trust, or tunnel it.
