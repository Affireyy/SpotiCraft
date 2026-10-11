package dev.spoticraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class WidgetConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("spoticraft.json");
	private static WidgetConfig instance = new WidgetConfig();

	public boolean visible = true;
	public Layout layout = Layout.HORIZONTAL;
	public Position position = Position.TOP_RIGHT;
	public float scale = 1.0f;
	public int margin = 8;
	public int backgroundColor = 0x14191D;
	public int accentColor = 0x1ED760;
	public int borderColor = 0x1ED760;
	public int borderThickness = 1;
	public int textColor = 0xF4F7F5;
	public int mutedColor = 0xB5C0BB;
	public int transparency = 82;
	public Typography typography = Typography.PIXEL;
	public boolean showArtwork = true;
	public boolean pixelatedCoverArt = false;
	public CoverPixelSize coverPixelSize = CoverPixelSize.PIXEL_64;
	public boolean showTitle = true;
	public boolean showArtist = true;
	public boolean showAlbum = false;
	public boolean showProgress = true;
	public boolean showElapsed = true;
	public boolean showDuration = true;
	public boolean showControls = true;
	public String clientId = "";

	public static WidgetConfig get() {
		return instance;
	}

	static WidgetConfig snapshot() {
		return GSON.fromJson(GSON.toJson(instance), WidgetConfig.class);
	}

	static void restore(WidgetConfig snapshot) {
		instance = snapshot;
	}

	public static void load() {
		if (!Files.exists(PATH)) return;
		try {
			WidgetConfig loaded = GSON.fromJson(Files.readString(PATH), WidgetConfig.class);
			if (loaded != null) {
				loaded.backgroundColor &= 0x00FFFFFF;
				loaded.accentColor &= 0x00FFFFFF;
				loaded.borderColor &= 0x00FFFFFF;
				loaded.textColor &= 0x00FFFFFF;
				loaded.mutedColor &= 0x00FFFFFF;
				instance = loaded;
			}
		} catch (IOException | RuntimeException exception) {
			System.err.println("[SpotiCraft] Could not load config: " + exception.getMessage());
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(instance));
		} catch (IOException exception) {
			System.err.println("[SpotiCraft] Could not save config: " + exception.getMessage());
		}
	}

	public enum Layout {
		HORIZONTAL,
		COMPACT,
		VERTICAL
	}

	public enum Position {
		TOP_LEFT,
		TOP_CENTER,
		TOP_RIGHT,
		MIDDLE_LEFT,
		MIDDLE_RIGHT,
		BOTTOM_LEFT,
		BOTTOM_CENTER,
		BOTTOM_RIGHT
	}

	public enum Typography {
		PIXEL,
		BOLD,
		QUIET
	}

	public enum CoverPixelSize {
		PIXEL_64("64 x 64", 64),
		PIXEL_32("32 x 32", 32),
		PIXEL_16("16 x 16", 16),
		PIXEL_8("8 x 8", 8);

		private final String displayName;
		private final int resolution;

		CoverPixelSize(String displayName, int resolution) {
			this.displayName = displayName;
			this.resolution = resolution;
		}

		public String displayName() {
			return displayName;
		}

		public int resolution() {
			return resolution;
		}
	}
}