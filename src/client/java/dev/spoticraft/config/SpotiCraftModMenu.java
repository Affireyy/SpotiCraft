package dev.spoticraft.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.AbstractConfigScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SpotiCraftModMenu implements ModMenuApi {
	private static SettingsPreview activePreview;

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SpotiCraftModMenu::createScreen;
	}

	private static Screen createScreen(Screen parent) {
		WidgetConfig config = WidgetConfig.get();
		SettingsPreview preview = new SettingsPreview();
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.literal("SpotiCraft"))
			.setDoesConfirmSave(true)
			.setSavingRunnable(() -> {
				preview.saved = true;
				WidgetConfig.save();
			});
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory spotify = builder.getOrCreateCategory(Component.literal("Spotify"));
		spotify.addEntry(entries.startStrField(Component.literal("Spotify client ID"), config.clientId)
			.setDefaultValue("")
			.setSaveConsumer(value -> config.clientId = value.trim())
			.setTooltip(Component.literal("Create an app at developer.spotify.com and add a loopback redirect URI."))
			.build());
		spotify.addEntry(entries.startTextDescription(Component.literal("Connect in game with the Connect Spotify keybind (U by default)."))
			.build());

		ConfigCategory layout = builder.getOrCreateCategory(Component.literal("Layout"));
		layout.addEntry(entries.startBooleanToggle(Component.literal("Show widget"), config.visible)
			.setDefaultValue(true).setSaveConsumer(value -> config.visible = value).build());
		layout.addEntry(entries.startEnumSelector(Component.literal("Layout"), WidgetConfig.Layout.class, config.layout)
			.setDefaultValue(WidgetConfig.Layout.HORIZONTAL).setSaveConsumer(value -> config.layout = value).build());
		layout.addEntry(entries.startEnumSelector(Component.literal("Screen position"), WidgetConfig.Position.class, config.position)
			.setDefaultValue(WidgetConfig.Position.TOP_RIGHT).setSaveConsumer(value -> config.position = value).build());
		layout.addEntry(entries.startIntSlider(Component.literal("Widget size (%)"),
			Math.max(50, Math.min(200, Math.round(config.scale * 100))), 50, 200)
			.setDefaultValue(100).setSaveConsumer(value -> config.scale = value / 100.0f).build());
		layout.addEntry(entries.startIntSlider(Component.literal("Edge margin"), config.margin, 0, 48)
			.setDefaultValue(8).setSaveConsumer(value -> config.margin = value).build());

		ConfigCategory appearance = builder.getOrCreateCategory(Component.literal("Appearance"));
		appearance.addEntry(entries.startColorField(Component.literal("Background color"), config.backgroundColor)
			.setDefaultValue(0x14191D).setSaveConsumer(value -> config.backgroundColor = value).build());
		appearance.addEntry(entries.startColorField(Component.literal("Accent color"), config.accentColor)
			.setDefaultValue(0x1ED760).setSaveConsumer(value -> config.accentColor = value).build());
		appearance.addEntry(entries.startColorField(Component.literal("Border color"), config.borderColor)
			.setDefaultValue(0x1ED760).setSaveConsumer(value -> config.borderColor = value).build());
		appearance.addEntry(entries.startIntSlider(Component.literal("Border thickness (px)"), config.borderThickness, 0, 8)
			.setDefaultValue(1).setSaveConsumer(value -> config.borderThickness = value).build());
		appearance.addEntry(entries.startColorField(Component.literal("Text color"), config.textColor)
			.setDefaultValue(0xF4F7F5).setSaveConsumer(value -> config.textColor = value).build());
		appearance.addEntry(entries.startColorField(Component.literal("Secondary text color"), config.mutedColor)
			.setDefaultValue(0xB5C0BB).setSaveConsumer(value -> config.mutedColor = value).build());
		appearance.addEntry(entries.startIntSlider(Component.literal("Background opacity"), config.transparency, 10, 100)
			.setDefaultValue(82).setSaveConsumer(value -> config.transparency = value).build());
		appearance.addEntry(entries.startEnumSelector(Component.literal("Typography"), WidgetConfig.Typography.class, config.typography)
			.setDefaultValue(WidgetConfig.Typography.PIXEL).setSaveConsumer(value -> config.typography = value).build());
		appearance.addEntry(entries.startBooleanToggle(Component.literal("Pixelated cover art"), config.pixelatedCoverArt)
			.setDefaultValue(false).setSaveConsumer(value -> config.pixelatedCoverArt = value).build());
		appearance.addEntry(entries.startEnumSelector(Component.literal("Cover pixelation size"),
			WidgetConfig.CoverPixelSize.class, config.coverPixelSize)
			.setEnumNameProvider(value -> Component.literal(((WidgetConfig.CoverPixelSize) value).displayName()))
			.setSaveConsumer(value -> config.coverPixelSize = value).build());

		ConfigCategory elements = builder.getOrCreateCategory(Component.literal("Elements"));
		elements.addEntry(entries.startBooleanToggle(Component.literal("Album artwork"), config.showArtwork)
			.setDefaultValue(true).setSaveConsumer(value -> config.showArtwork = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Track title"), config.showTitle)
			.setDefaultValue(true).setSaveConsumer(value -> config.showTitle = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Artist"), config.showArtist)
			.setDefaultValue(true).setSaveConsumer(value -> config.showArtist = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Album name"), config.showAlbum)
			.setDefaultValue(false).setSaveConsumer(value -> config.showAlbum = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Progress bar"), config.showProgress)
			.setDefaultValue(true).setSaveConsumer(value -> config.showProgress = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Elapsed time"), config.showElapsed)
			.setDefaultValue(true).setSaveConsumer(value -> config.showElapsed = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Total duration"), config.showDuration)
			.setDefaultValue(true).setSaveConsumer(value -> config.showDuration = value).build());
		elements.addEntry(entries.startBooleanToggle(Component.literal("Playback state"), config.showControls)
			.setDefaultValue(true).setSaveConsumer(value -> config.showControls = value).build());
		preview.screen = builder.build();
		activePreview = preview;
		return preview.screen;
	}

	public static void tickPreview(Screen currentScreen) {
		SettingsPreview preview = activePreview;
		if (preview == null) return;
		if (currentScreen == preview.screen) {
			if (preview.screen instanceof AbstractConfigScreen configScreen) {
				for (var categoryEntries : configScreen.getCategorizedEntries().values()) {
					for (AbstractConfigEntry<?> entry : categoryEntries) entry.save();
				}
			}
		} else if (!(currentScreen instanceof ConfirmScreen)) {
			if (!preview.saved) WidgetConfig.restore(preview.snapshot);
			activePreview = null;
		}
	}

	private static final class SettingsPreview {
		private final WidgetConfig snapshot = WidgetConfig.snapshot();
		private Screen screen;
		private boolean saved;
	}
}