package dev.buizz.cobbleventure.pokefinder.client;

import net.neoforged.bus.api.IEventBus;

/** Client entry point for the CobbleNav Pokefinder compatibility layer. */
public final class PokefinderRadarClient {
    private PokefinderRadarClient() {}

    public static void register(IEventBus modBus) {
        // The HUD layer is registered after the CobbleNav 2.3.3 layout is verified.
    }
}
