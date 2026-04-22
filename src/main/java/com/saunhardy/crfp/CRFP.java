package com.saunhardy.crfp;

import com.mojang.logging.LogUtils;
import com.saunhardy.crfp.command.CRFPCommands;
import com.saunhardy.crfp.core.ChunkloaderRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

@Mod(CRFP.MODID)
public final class CRFP {
    public static final String MODID = "crfp";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable ChunkloaderRegistry registry;

    public CRFP(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        registry = new ChunkloaderRegistry(event.getServer());
        registry.load();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (registry != null) {
            registry.saveAndShutdown();
            registry = null;
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ChunkloaderRegistry r = registry;
        if (r != null) r.tick();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CRFPCommands.register(event.getDispatcher());
    }

    public static @Nullable ChunkloaderRegistry registry() {
        return registry;
    }
}
