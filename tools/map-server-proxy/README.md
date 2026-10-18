# Map server proxy

A small local proxy that sits between the plugin and the map server, so you can take the server "down" and bring it back with one key. It's the easiest way to test the plugin's connection logic: what it does when the server doesn't answer, how it backs off, and whether it recovers on its own once the server is back.

It's a single Java file with no dependencies. You need JDK 11 or newer, and nothing is built.

## Running it

Start the proxy:

```
.\tools\map-server-proxy\proxy.ps1        # Windows
tools/map-server-proxy/proxy.sh           # macOS, Linux
```

Press Enter to toggle it between up and down, or type `up`, `down` or `quit`.

* Up: every request goes through to https://osrsmapreforged.online, and each one is printed with the time (apart from successful tiles, which would drown everything else out).
* Down: the port is closed, so the plugin's requests are refused, as if the server were unreachable. Nothing is printed while it's down.

Both scripts take an optional port and target, e.g. `proxy.ps1 -Port 8100 -Target http://localhost:8080` to sit in front of a map server of your own.

## Pointing the client at it

The plugin only follows `hdmap.server` in RuneLite's developer mode. Start your dev client with `--developer-mode` and add this to its JVM options:

```
-Dhdmap.server=http://127.0.0.1:8099
```

If you can't edit the JVM options, setting `JAVA_TOOL_OPTIONS=-Dhdmap.server=http://127.0.0.1:8099` in the shell that launches the client does the same. While `hdmap.server` is set, the plugin asks the server for the current map version on every start rather than once a week.

With `--debug`, failed requests show up in the client log as `Map server request failed: ...`, which is the easiest way to see the retries.

## Setting up the cache

`map-cache.ps1` sets up the plugin's cached map files under `%USERPROFILE%\.runelite\plugin-data\reimagined-world-map`. Close RuneLite first, otherwise the client keeps using the files it already read.

```
.\tools\map-server-proxy\map-cache.ps1 show                    # remembered version and cached files
.\tools\map-server-proxy\map-cache.ps1 corrupt                 # replace places.json with HTML
.\tools\map-server-proxy\map-cache.ps1 clear                   # delete every cached map version
.\tools\map-server-proxy\map-cache.ps1 remember cache-2727.35  # start on a given version
```

## Things worth testing

* Server down from the start: `clear`, take the proxy down, start the client. The map should stay black with a "Check for a new map" button, and the log should show retries 1, 2, 4... minutes apart. Bring the proxy up and the map should load at the next retry.
* A broken cached file: `corrupt` with the proxy up. The client should fetch the map files again and load. Run `show` after: `places.json` should be back to full size, nothing deleted.
* A broken cached file while the server is down: `corrupt`, proxy down. The files should stay on disk, and the map should load once the proxy is up.
* The button: with the proxy down, clicking "Check for a new map" should ask straight away and not push the next automatic retry further away.
