package dev.spoticraft.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spoticraft.config.WidgetConfig;
import java.io.IOException;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;

public final class SpotifyClient {
	private static final String API = "https://api.spotify.com/v1";
	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
	private static final String SCOPES = "user-read-currently-playing user-modify-playback-state";
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "SpotiCraft-Spotify");
		thread.setDaemon(true);
		return thread;
	});
	private static final java.nio.file.Path TOKEN_PATH = FabricLoader.getInstance().getConfigDir()
		.resolve("spoticraft-tokens.json");
	private static volatile Track currentTrack = Track.empty("Not connected");
	private static volatile String accessToken = "";
	private static volatile String refreshToken = "";
	private static volatile long expiresAt;
	private static volatile long retryAfter;
	private static volatile boolean initialized;

	private SpotifyClient() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		loadTokens();
		WORKER.scheduleWithFixedDelay(SpotifyClient::pollSafely, 1, 3, TimeUnit.SECONDS);
	}

	public static Track currentTrack() {
		return currentTrack;
	}

	public static void connect(String clientId) {
		if (clientId == null || clientId.isBlank()) {
			setStatus("Add your Spotify client ID in Mod Menu settings");
			return;
		}
		WORKER.execute(() -> authorize(clientId.trim()));
	}

	public static void togglePlayback() {
		command(() -> send("PUT", API + (currentTrack.playing() ? "/me/player/pause" : "/me/player/play"), ""));
	}

	public static void skipNext() {
		command(() -> send("POST", API + "/me/player/next", ""));
	}

	public static void skipPrevious() {
		command(() -> send("POST", API + "/me/player/previous", ""));
	}

	private static void command(IoAction action) {
		WORKER.execute(() -> {
			try {
				ensureAccessToken();
				if (accessToken.isBlank()) {
					setStatus("Connect to Spotify first");
					return;
				}
				HttpResponse<String> response = action.run();
				if (response.statusCode() == 401 && refreshAccessToken()) response = action.run();
				if (response.statusCode() >= 400) setStatus("Spotify command failed (" + response.statusCode() + ")");
			} catch (Exception exception) {
				setStatus("Spotify is unavailable");
			}
		});
	}

	private static void pollSafely() {
		try {
			if (System.currentTimeMillis() < retryAfter) return;
			ensureAccessToken();
			if (accessToken.isBlank()) return;
			HttpResponse<String> response = send("GET", API + "/me/player/currently-playing", "");
			if (response.statusCode() == 401 && refreshAccessToken()) {
				response = send("GET", API + "/me/player/currently-playing", "");
			}
			if (response.statusCode() == 204) {
				currentTrack = Track.empty("Nothing is playing");
			} else if (response.statusCode() == 429) {
				retryAfter = System.currentTimeMillis() + retryDelay(response);
			} else if (response.statusCode() >= 200 && response.statusCode() < 300) {
				parseTrack(response.body());
			} else if (response.statusCode() == 401) {
				setStatus("Spotify login expired. Press Connect to sign in again");
			} else if (response.statusCode() >= 400) {
				setStatus("Spotify request failed (" + response.statusCode() + ")");
			}
		} catch (Exception exception) {
			setStatus("Spotify connection error");
		}
	}

	private static void parseTrack(String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		if (!root.has("item") || root.get("item").isJsonNull()) {
			currentTrack = Track.empty("Nothing is playing");
			return;
		}
		JsonObject item = root.getAsJsonObject("item");
		String title = item.has("name") ? item.get("name").getAsString() : "Unknown track";
		String album = "";
		String artwork = "";
		if (item.has("album") && item.get("album").isJsonObject()) {
			JsonObject albumObject = item.getAsJsonObject("album");
			album = albumObject.has("name") ? albumObject.get("name").getAsString() : "";
			JsonArray images = albumObject.has("images") ? albumObject.getAsJsonArray("images") : new JsonArray();
			long largestImageArea = -1;
			for (var imageElement : images) {
				if (!imageElement.isJsonObject()) continue;
				JsonObject image = imageElement.getAsJsonObject();
				if (!image.has("url") || image.get("url").isJsonNull()) continue;
				String imageUrl = image.get("url").getAsString();
				if (imageUrl.isBlank()) continue;
				long imageArea = image.has("width") && !image.get("width").isJsonNull()
					&& image.has("height") && !image.get("height").isJsonNull()
					? (long) image.get("width").getAsInt() * image.get("height").getAsInt() : 0;
				if (imageArea > largestImageArea) {
					largestImageArea = imageArea;
					artwork = imageUrl;
				}
			}
		}
		StringBuilder artists = new StringBuilder();
		if (item.has("artists")) {
			for (var artist : item.getAsJsonArray("artists")) {
				if (!artists.isEmpty()) artists.append(", ");
				artists.append(artist.getAsJsonObject().get("name").getAsString());
			}
		}
		long progress = root.has("progress_ms") && !root.get("progress_ms").isJsonNull()
			? root.get("progress_ms").getAsLong() : 0;
		long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
		boolean playing = root.has("is_playing") && root.get("is_playing").getAsBoolean();
		currentTrack = new Track(title, artists.toString(), album, artwork, progress, duration, playing, "",
			System.currentTimeMillis());
	}

	private static void authorize(String clientId) {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			server.setSoTimeout(180_000);
			String redirect = "http://127.0.0.1:" + server.getLocalPort() + "/callback";
			String verifier = randomToken(64);
			String state = randomToken(32);
			String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
			);
			String authorizationUrl = "https://accounts.spotify.com/authorize?" + form(Map.of(
				"client_id", clientId,
				"response_type", "code",
				"redirect_uri", redirect,
				"scope", SCOPES,
				"state", state,
				"code_challenge_method", "S256",
				"code_challenge", challenge
			));
			setStatus("Waiting for Spotify authorization");
			openBrowser(URI.create(authorizationUrl));
			String requestLine;
			try (Socket socket = server.accept()) {
				socket.setSoTimeout(15_000);
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
				requestLine = reader.readLine();
				String header;
				while ((header = reader.readLine()) != null && !header.isEmpty()) { }
				byte[] response = ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n"
					+ "<title>SpotiCraft</title><p>You can return to Minecraft.</p>").getBytes(StandardCharsets.UTF_8);
				socket.getOutputStream().write(response);
				socket.getOutputStream().flush();
			}
			if (requestLine == null || !requestLine.startsWith("GET ")) throw new IllegalStateException("Invalid callback");
			URI callback = URI.create("http://127.0.0.1" + requestLine.split(" ")[1]);
			Map<String, String> params = queryParameters(callback.getRawQuery());
			if (!state.equals(params.get("state"))) throw new IllegalStateException("OAuth state mismatch");
			if (params.containsKey("error")) throw new IllegalStateException("Authorization was declined");
			String code = params.get("code");
			if (code == null) throw new IllegalStateException("Spotify did not return an authorization code");
			HttpResponse<String> response = tokenRequest(Map.of(
				"client_id", clientId,
				"grant_type", "authorization_code",
				"code", code,
				"redirect_uri", redirect,
				"code_verifier", verifier
			));
			if (response.statusCode() >= 400) throw new IllegalStateException("Token exchange failed (" + response.statusCode() + ")");
			applyTokens(response.body());
		} catch (Exception exception) {
			setStatus("Spotify sign-in failed: " + safeMessage(exception));
		}
	}

	private static void openBrowser(URI uri) throws Exception {
		if (Desktop.isDesktopSupported()) {
			try {
				Desktop desktop = Desktop.getDesktop();
				if (desktop.isSupported(Desktop.Action.BROWSE)) {
					desktop.browse(uri);
					return;
				}
			} catch (IOException | UnsupportedOperationException ignored) {
			}
		}

		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> command = os.contains("mac")
			? List.of("open", uri.toString())
			: os.contains("win")
				? List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString())
				: List.of("xdg-open", uri.toString());
		new ProcessBuilder(command).start();
	}

	private static HttpResponse<String> send(String method, String url, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(12))
			.header("Authorization", "Bearer " + accessToken);
		if (body.isEmpty()) request.method(method, HttpRequest.BodyPublishers.noBody());
		else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> tokenRequest(Map<String, String> values) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create(TOKEN_URL))
			.timeout(Duration.ofSeconds(12))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form(values)))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static boolean refreshAccessToken() {
		if (refreshToken.isBlank()) return false;
		try {
			HttpResponse<String> response = tokenRequest(Map.of(
				"client_id", WidgetConfig.get().clientId,
				"grant_type", "refresh_token",
				"refresh_token", refreshToken
			));
			if (response.statusCode() >= 400) return false;
			applyTokens(response.body());
			return true;
		} catch (Exception exception) {
			return false;
		}
	}

	private static void ensureAccessToken() {
		if (accessToken.isBlank() || System.currentTimeMillis() >= expiresAt - 30_000) refreshAccessToken();
	}

	private static void applyTokens(String body) throws Exception {
		JsonObject json = JsonParser.parseString(body).getAsJsonObject();
		accessToken = json.get("access_token").getAsString();
		if (json.has("refresh_token")) refreshToken = json.get("refresh_token").getAsString();
		expiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000;
		saveTokens();
		setStatus("Connected to Spotify");
	}

	private static void loadTokens() {
		try {
			if (!Files.exists(TOKEN_PATH)) return;
			JsonObject json = JsonParser.parseString(Files.readString(TOKEN_PATH)).getAsJsonObject();
			refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : "";
			accessToken = json.has("access_token") ? json.get("access_token").getAsString() : "";
			expiresAt = json.has("expires_at") ? json.get("expires_at").getAsLong() : 0;
		} catch (Exception exception) {
			setStatus("Saved Spotify login could not be read");
		}
	}

	private static void saveTokens() throws Exception {
		Files.createDirectories(TOKEN_PATH.getParent());
		JsonObject json = new JsonObject();
		json.addProperty("access_token", accessToken);
		json.addProperty("refresh_token", refreshToken);
		json.addProperty("expires_at", expiresAt);
		Files.writeString(TOKEN_PATH, json.toString());
	}

	private static String randomToken(int bytes) {
		byte[] entropy = new byte[bytes];
		new SecureRandom().nextBytes(entropy);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
	}

	private static String form(Map<String, String> values) {
		return values.entrySet().stream()
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(java.util.stream.Collectors.joining("&"));
	}

	private static Map<String, String> queryParameters(String query) {
		HashMap<String, String> values = new HashMap<>();
		if (query == null) return values;
		for (String pair : query.split("&")) {
			String[] parts = pair.split("=", 2);
			values.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
		}
		return values;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static long retryDelay(HttpResponse<?> response) {
		try {
			return Math.max(1000, Long.parseLong(response.headers().firstValue("Retry-After").orElse("5")) * 1000);
		} catch (NumberFormatException exception) {
			return 5000;
		}
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null ? "check your Spotify app settings" : message;
	}

	private static void setStatus(String status) {
		Track previous = currentTrack;
		currentTrack = new Track(previous.title(), previous.artist(), previous.album(), previous.artworkUrl(),
			previous.progressMs(), previous.durationMs(), previous.playing(), status, previous.sampledAt());
	}

	@FunctionalInterface
	private interface IoAction {
		HttpResponse<String> run() throws Exception;
	}

	public record Track(String title, String artist, String album, String artworkUrl, long progressMs,
		long durationMs, boolean playing, String status, long sampledAt) {
		static Track empty(String status) {
			return new Track("", "", "", "", 0, 0, false, status, System.currentTimeMillis());
		}

		public long displayedProgressMs() {
			if (!playing) return progressMs;
			return Math.min(durationMs, progressMs + Math.max(0, System.currentTimeMillis() - sampledAt));
		}
	}
}