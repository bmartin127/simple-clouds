package dev.nonamecrackers2.simpleclouds.client.event;

import java.util.List;

import dev.nonamecrackers2.simpleclouds.SimpleCloudsMod;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.client.event.impl.ConfigMenuButtonEvent;
import nonamecrackers2.crackerslib.client.event.impl.RegisterConfigScreensEvent;
import nonamecrackers2.crackerslib.client.gui.ConfigHomeScreen;
import nonamecrackers2.crackerslib.client.gui.title.TextTitle;

public class SimpleCloudsClientEvents
{
	public static void registerReloadListeners(RegisterClientReloadListenersEvent event)
	{
		SimpleCloudsRenderer.initialize();
		event.registerReloadListener(SimpleCloudsRenderer.getInstance());
	}
	
	public static void registerConfigMenu(RegisterConfigScreensEvent event)
	{
		event.builder(ConfigHomeScreen.builder(TextTitle.ofModDisplayName(SimpleCloudsMod.MODID))
				.crackersDefault().build()
		).addSpec(ModConfig.Type.CLIENT, SimpleCloudsConfig.CLIENT_SPEC).register();
	}
	
	public static void registerConfigMenuButton(ConfigMenuButtonEvent event)
	{
		event.defaultButtonWithSingleCharacter('S', 0xFFADF7FF);
	}
	
	@SubscribeEvent
	public static void onRenderDebugOverlay(CustomizeGuiOverlayEvent.DebugText event)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.renderDebug)
		{
			List<String> text = event.getRight();
			text.add("");
			text.add(ChatFormatting.GREEN + SimpleCloudsMod.MODID + ": " + SimpleCloudsMod.getModVersion());
			text.add("Enabled: " + (SimpleCloudsRenderer.isEnabled() ? "True" : ChatFormatting.RED + "False"));
			text.add("Triangles: " + SimpleCloudsRenderer.getInstance().getTotalSides() * 2);
		}
	}
}