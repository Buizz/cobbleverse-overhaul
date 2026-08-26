package dev.buizz.cobbleventure.pokefinder.client;

/** Persistent placement for the PokéNav's built-in Pokefinder HUD. */
public enum PokefinderHudPosition {
    LEFT,
    RIGHT;

    public PokefinderHudPosition opposite() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
        };
    }
}
