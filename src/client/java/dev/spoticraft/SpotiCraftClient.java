package dev.spoticraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.terraformersmc.modmenu.ModMenu;
import dev.spoticraft.config.SpotiCraftModMenu;
import dev.spoticraft.config.WidgetConfig;
import dev.spoticraft.spotify.SpotifyClient;
import dev.spoticraft.widget.SpotifyHudWidget;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class SpotiCraftClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath("spoticraft", "controls")
	);
	private static final KeyMapping PLAY_PAUSE = register("play_pause", InputConstants.KEY_P);
	private static final KeyMapping NEXT = register("next", InputConstants.KEY_O);
	private static final KeyMapping PREVIOUS = register("previous", InputConstants.KEY_I);
	private static final KeyMapping CONNECT = register("connect", InputConstants.KEY_U);
	private static final KeyMapping TOGGLE_WIDGET = register("toggle_widget", InputConstants.KEY_J);
	private static final KeyMapping OPEN_OPTIONS = register("open_options", InputConstants.KEY_K);

	@Override
	public void onInitializeClient() {
		WidgetConfig.load();
		SpotifyClient.initialize();
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath("spoticraft", "spotify_widget"),
			SpotifyHudWidget::render
		);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SpotiCraftModMenu.tickPreview(client.gui.screen());
			while (PLAY_PAUSE.consumeClick()) SpotifyClient.togglePlayback();
			while (NEXT.consumeClick()) SpotifyClient.skipNext();
			while (PREVIOUS.consumeClick()) SpotifyClient.skipPrevious();
			while (CONNECT.consumeClick()) SpotifyClient.connect(WidgetConfig.get().clientId);
			while (TOGGLE_WIDGET.consumeClick()) {
				WidgetConfig.get().visible = !WidgetConfig.get().visible;
				WidgetConfig.save();
			}
			while (OPEN_OPTIONS.consumeClick()) {
				if (FabricLoader.getInstance().isModLoaded("modmenu") && ModMenu.hasConfigScreen("spoticraft")) {
					client.setScreenAndShow(ModMenu.getConfigScreen("spoticraft", client.gui.screen()));
				}
			}
		});
	}

	private static KeyMapping register(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.spoticraft." + name,
			InputConstants.Type.KEYBOARD,
			key,
			CATEGORY
		));
	}
}