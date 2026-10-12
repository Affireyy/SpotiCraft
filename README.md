# SpotiCraft

Keep Spotify close while you play. SpotiCraft is a client-side Fabric mod that puts the current track, cover art, and playback progress on your Minecraft HUD. You can change its layout and appearance in Mod Menu.

## Build

You’ll need Java 25 and Gradle. From the project folder, run:

```sh
gradle build
```

The mod jar is created in `build/libs/`.

## Connect Spotify

1. Create an app in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Use these details, or similar ones:
	- **App name:** `SpotiCraft`
	- **Description:** `Show Spotify playback on the Minecraft HUD.`
	- **Website:** Leave blank if Spotify allows it. If required, use a link to the project or its source repository.
3. Add this IPv4 loopback redirect URI: `http://127.0.0.1:8888/callback`. SpotiCraft chooses an available port when you connect; Spotify supports dynamic ports for loopback redirects.
4. Copy the app’s **client ID** into Mod Menu → SpotiCraft → Spotify. Don’t use a client secret; SpotiCraft uses PKCE and doesn’t need one.
5. Join a world and press **U** to connect, then approve access in your browser.

SpotiCraft stores access and refresh tokens in `.minecraft/config/spoticraft-tokens.json`. Keep that file private. Playback controls require Spotify Premium.

## Controls

| Action | Default |
| --- | --- |
| Play / pause | P |
| Next track | O |
| Previous track | I |
| Connect Spotify | U |
| Toggle widget | J |
| Open SpotiCraft options | K |
