package dev.spoticraft.widget;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.spoticraft.config.WidgetConfig;
import dev.spoticraft.spotify.SpotifyClient;
import dev.spoticraft.spotify.SpotifyClient.Track;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpotifyHudWidget {
	private static final Logger LOGGER = LoggerFactory.getLogger("SpotiCraft/Artwork");
	private static final Identifier COVER_ID = Identifier.fromNamespaceAndPath("spoticraft", "spotify_cover");
	private static final ExecutorService ARTWORK_WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "SpotiCraft-Cover");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient IMAGE_HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final AtomicBoolean COVER_REQUEST = new AtomicBoolean();
	private static volatile String loadedCoverUrl = "";
	private static volatile String requestedCoverUrl = "";
	private static volatile String failedCoverUrl = "";
	private static volatile long coverRetryAfter;
	private static volatile boolean coverReady;
	private static volatile int loadedPixelSize = -1;
	private static volatile int requestedPixelSize = -1;
	private static volatile byte[] loadedCoverBytes;
	private static volatile int coverWidth = 1;
	private static volatile int coverHeight = 1;
	private static SmoothDynamicTexture coverTexture;

	private SpotifyHudWidget() { }

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		WidgetConfig config = WidgetConfig.get();
		if (!config.visible) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.gui.hud.isHidden()) return;

		Track track = SpotifyClient.currentTrack();
		int pixelSize = config.pixelatedCoverArt ? config.coverPixelSize.resolution() : 0;
		requestArtwork(track.artworkUrl(), pixelSize);
		if (coverTexture != null) coverTexture.setPixelated(config.pixelatedCoverArt);
		float scale = Math.max(0.5f, Math.min(2.0f, config.scale));
		int coverSize = Math.round((config.layout == WidgetConfig.Layout.VERTICAL ? 52 : 42) * scale);
		int width = Math.round((config.layout == WidgetConfig.Layout.COMPACT ? 174 : 252) * scale);
		int height = Math.round((config.layout == WidgetConfig.Layout.VERTICAL ? 162 : 80) * scale);
		int margin = Math.max(0, config.margin);
		int guiWidth = minecraft.getWindow().getGuiScaledWidth();
		int guiHeight = minecraft.getWindow().getGuiScaledHeight();
		int x = switch (config.position) {
			case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> margin;
			case TOP_CENTER, BOTTOM_CENTER -> (guiWidth - width) / 2;
			case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> guiWidth - width - margin;
		};
		int y = switch (config.position) {
			case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> margin;
			case MIDDLE_LEFT, MIDDLE_RIGHT -> (guiHeight - height) / 2;
			case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> guiHeight - height - margin;
		};
		drawPanel(graphics, x, y, width, height, config);

		int inset = Math.round(10 * scale);
		int contentX = x + inset;
		int contentY = y + inset;
		int artworkX = config.layout == WidgetConfig.Layout.VERTICAL ? x + (width - coverSize) / 2 : contentX;
		int detailsX = config.showArtwork && config.layout != WidgetConfig.Layout.VERTICAL
			? contentX + coverSize + Math.round(10 * scale) : contentX;
		if (config.showArtwork) drawArtwork(graphics, minecraft, track, artworkX, contentY, coverSize);

		if (config.layout == WidgetConfig.Layout.VERTICAL) {
			int textY = contentY + (config.showArtwork ? coverSize + 8 : 0);
			drawMetadata(graphics, minecraft, track, config, x, width, contentX, textY, scale);
			drawProgress(graphics, track, config, x + inset, y + height - Math.round(18 * scale), width - inset * 2, scale);
		} else {
			drawMetadata(graphics, minecraft, track, config, x, width, detailsX, contentY, scale);
			drawProgress(graphics, track, config, x + inset, y + height - Math.round(15 * scale), width - inset * 2, scale);
		}
	}

	private static void drawArtwork(GuiGraphicsExtractor graphics, Minecraft minecraft, Track track,
		int x, int y, int size) {
		if (coverReady && track.artworkUrl().equals(loadedCoverUrl)) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, COVER_ID, x, y, 0, 0, size, size,
				coverWidth, coverHeight, coverWidth, coverHeight);
		} else {
			graphics.fill(x, y, x + size, y + size, 0xFF28332E);
			graphics.text(minecraft.font, "♪", x + size / 2 - 3, y + size / 2 - 4, 0xFFB8C6BF, false);
		}
	}

	private static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, WidgetConfig config) {
		int backgroundAlpha = Math.max(12, Math.min(255, config.transparency * 255 / 100));
		int color = (backgroundAlpha << 24) | (config.backgroundColor & 0x00FFFFFF);
		graphics.fill(x, y, x + width, y + height, color);
		int border = Math.max(0, Math.min(8, config.borderThickness));
		if (border > 0) {
			int borderColor = config.borderColor | 0xFF000000;
			graphics.fill(x, y, x + width, y + border, borderColor);
			graphics.fill(x, y + height - border, x + width, y + height, borderColor);
			graphics.fill(x, y + border, x + border, y + height - border, borderColor);
			graphics.fill(x + width - border, y + border, x + width, y + height - border, borderColor);
		}
	}

	private static void drawMetadata(GuiGraphicsExtractor graphics, Minecraft minecraft, Track track,
		WidgetConfig config, int x, int width, int detailsX, int contentY, float scale) {
		int textColor = config.textColor | 0xFF000000;
		int mutedColor = config.mutedColor | 0xFF000000;
		int maxWidth = Math.max(50, x + width - detailsX - Math.round(12 * scale));
		int line = Math.max(9, Math.round(11 * scale));
		int currentY = contentY;
		if (config.showTitle) {
			String title = track.title().isBlank() ? track.status() : track.title();
			Component titleComponent = Component.literal(ellipsize(minecraft, title, maxWidth))
				.withStyle(style -> style.withBold(config.typography == WidgetConfig.Typography.BOLD));
			graphics.text(minecraft.font, titleComponent, detailsX, currentY,
				config.typography == WidgetConfig.Typography.QUIET ? mutedColor : textColor, false);
			currentY += line + 2;
		}
		if (config.showArtist && !track.artist().isBlank()) {
			graphics.text(minecraft.font, ellipsize(minecraft, track.artist(), maxWidth), detailsX, currentY, mutedColor, false);
			currentY += line;
		}
		if (config.showAlbum && !track.album().isBlank()) {
			graphics.text(minecraft.font, ellipsize(minecraft, track.album(), maxWidth), detailsX, currentY, mutedColor, false);
			currentY += line;
		}
		if (config.showControls && track.status().isBlank()) {
			String state = track.playing() ? "Playing" : "Paused";
			graphics.text(minecraft.font, ellipsize(minecraft, state, maxWidth), detailsX, currentY,
				config.accentColor | 0xFF000000, false);
		}
	}

	private static void drawProgress(GuiGraphicsExtractor graphics, Track track, WidgetConfig config,
		int x, int y, int width, float scale) {
		long progress = track.displayedProgressMs();
		long duration = track.durationMs();
		int color = config.accentColor | 0xFF000000;
		int labelY = y - Math.round(9 * scale);
		if (config.showElapsed) {
			graphics.text(Minecraft.getInstance().font, time(progress), x, labelY, config.mutedColor | 0xFF000000, false);
		}
		if (config.showDuration) {
			String durationText = duration > 0 ? time(duration) : "--:--";
			int durationWidth = Minecraft.getInstance().font.width(durationText);
			graphics.text(Minecraft.getInstance().font, durationText, x + width - durationWidth, labelY,
				config.mutedColor | 0xFF000000, false);
		}
		if (config.showProgress) {
			int barHeight = Math.max(2, Math.round(2 * scale));
			graphics.fill(x, y, x + width, y + barHeight, 0x667D8882);
			int filled = duration > 0 ? (int) (width * Math.min(1.0, (double) progress / duration)) : 0;
			if (filled > 0) graphics.fill(x, y, x + filled, y + barHeight, color);
		}
	}

	private static void requestArtwork(String url, int pixelSize) {
		if (url == null || url.isBlank()) return;
		if (url.equals(loadedCoverUrl) && pixelSize == loadedPixelSize) return;
		if (url.equals(requestedCoverUrl) && pixelSize == requestedPixelSize) return;
		if (url.equals(failedCoverUrl) && System.currentTimeMillis() < coverRetryAfter) return;
		if (!COVER_REQUEST.compareAndSet(false, true)) return;
		requestedCoverUrl = url;
		requestedPixelSize = pixelSize;
		ARTWORK_WORKER.execute(() -> {
			try {
				byte[] imageBytes = loadedCoverUrl.equals(url) ? loadedCoverBytes : null;
				if (imageBytes == null) {
					URI imageUri = URI.create(url);
					if (!"https".equalsIgnoreCase(imageUri.getScheme())) {
						throw new IllegalArgumentException("Artwork URL is not HTTPS");
					}
					HttpRequest request = HttpRequest.newBuilder(imageUri).timeout(Duration.ofSeconds(10)).GET().build();
					HttpResponse<byte[]> response = IMAGE_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
					if (response.statusCode() != 200) {
						throw new IllegalStateException("Image server returned HTTP " + response.statusCode());
					}
					imageBytes = response.body();
				}
				NativeImage image = decodeArtwork(imageBytes, pixelSize);
				byte[] sourceBytes = imageBytes;
				Minecraft.getInstance().execute(() -> {
					try {
						coverWidth = image.getWidth();
						coverHeight = image.getHeight();
						SmoothDynamicTexture texture = new SmoothDynamicTexture(image, pixelSize > 0);
						Minecraft.getInstance().getTextureManager().register(COVER_ID, texture);
						coverTexture = texture;
						loadedCoverUrl = url;
						loadedPixelSize = pixelSize;
						loadedCoverBytes = sourceBytes;
						failedCoverUrl = "";
						coverReady = true;
					} catch (RuntimeException exception) {
						image.close();
						markArtworkFailed(url, exception);
					}
				});
			} catch (Exception ignored) {
				markArtworkFailed(url, ignored);
			} finally {
				COVER_REQUEST.set(false);
			}
		});
	}

	private static NativeImage decodeArtwork(byte[] bytes, int pixelSize) throws IOException {
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
		if (decoded == null) throw new IOException("Unsupported artwork image format");
		try {
			int sourceWidth = decoded.getWidth();
			int sourceHeight = decoded.getHeight();
			if (sourceWidth <= 0 || sourceHeight <= 0 || (long) sourceWidth * sourceHeight > 16_777_216) {
				throw new IOException("Artwork dimensions are invalid or too large");
			}
			int width = pixelSize > 0 ? pixelSize : sourceWidth;
			int height = pixelSize > 0 ? pixelSize : sourceHeight;
			NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
			try {
			ByteBuffer rgbaPixels = image.getPixelBytes();
				for (int y = 0; y < height; y++) {
					int sourceY = pixelSize > 0 ? Math.min(sourceHeight - 1,
						(int) ((long) y * sourceHeight / height)) : y;
					for (int x = 0; x < width; x++) {
						int sourceX = pixelSize > 0 ? Math.min(sourceWidth - 1,
							(int) ((long) x * sourceWidth / width)) : x;
						int argb = decoded.getRGB(sourceX, sourceY);
						rgbaPixels.put((byte) (argb >>> 16));
						rgbaPixels.put((byte) (argb >>> 8));
						rgbaPixels.put((byte) argb);
						rgbaPixels.put((byte) (argb >>> 24));
					}
				}
			return image;
			} catch (RuntimeException exception) {
				image.close();
				throw exception;
			}
		} finally {
			decoded.flush();
		}
	}

	private static final class SmoothDynamicTexture extends DynamicTexture {
		private boolean pixelated;

		private SmoothDynamicTexture(NativeImage image, boolean pixelated) {
			super(() -> "spoticraft_cover", image);
			this.pixelated = !pixelated;
			setPixelated(pixelated);
		}

		private void setPixelated(boolean pixelated) {
			if (this.pixelated == pixelated) return;
			this.pixelated = pixelated;
			sampler = RenderSystem.getSamplerCache().getClampToEdge(pixelated ? FilterMode.NEAREST : FilterMode.LINEAR);
		}
	}

	private static void markArtworkFailed(String url, Exception exception) {
		requestedCoverUrl = "";
		failedCoverUrl = url;
		coverRetryAfter = System.currentTimeMillis() + 15_000;
		LOGGER.warn("Could not load Spotify album artwork", exception);
	}

	private static String ellipsize(Minecraft minecraft, String value, int maxWidth) {
		if (minecraft.font.width(value) <= maxWidth) return value;
		String shortened = value;
		while (!shortened.isEmpty() && minecraft.font.width(shortened + "...") > maxWidth) {
			shortened = shortened.substring(0, shortened.length() - 1);
		}
		return shortened + "...";
	}

	private static String time(long millis) {
		long seconds = Math.max(0, millis / 1000);
		return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
	}
}