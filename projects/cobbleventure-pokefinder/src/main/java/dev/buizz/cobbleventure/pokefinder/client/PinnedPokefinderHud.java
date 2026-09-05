package dev.buizz.cobbleventure.pokefinder.client;

import com.metacontent.cobblenav.client.CobblenavClient;
import com.metacontent.cobblenav.config.ClientCobblenavConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.fml.loading.FMLPaths;

/** Owns the PokéNav's built-in Pokefinder HUD state and reuses CobbleNav's renderer. */
public final class PinnedPokefinderHud {
    private static final Path STATE_FILE = stateFile();
    private static final ThreadLocal<Boolean> INTEGRATED_RENDER =
        ThreadLocal.withInitial(() -> false);
    private static State state = new State(false, load(STATE_FILE).position());
    private static volatile boolean pokenavAvailable;

    private PinnedPokefinderHud() {}

    public static boolean enabled() {
        return pokenavAvailable && state.enabled();
    }

    public static boolean pokenavAvailable() {
        return pokenavAvailable;
    }

    public static void setPokenavAvailable(boolean available) {
        pokenavAvailable = available;
        if (!available) state = new State(false, state.position());
    }

    public static void resetSession() {
        pokenavAvailable = false;
        state = new State(false, state.position());
        RadarMarkerSnapshot.clear();
    }

    public static PokefinderHudPosition position() {
        return state.position();
    }

    public static boolean toggleEnabled() {
        if (!pokenavAvailable) return false;
        state = new State(!state.enabled(), state.position());
        return state.enabled();
    }

    public static PokefinderHudPosition togglePosition() {
        state = new State(state.enabled(), state.position().opposite());
        save(STATE_FILE, state);
        return state.position();
    }

    public static void cycle() {
        if (!pokenavAvailable) return;
        state = next(state);
    }

    static State next(State current) {
        if (!current.enabled()) return new State(true, PokefinderHudPosition.LEFT);
        if (current.position() == PokefinderHudPosition.LEFT) {
            return new State(true, PokefinderHudPosition.RIGHT);
        }
        return new State(false, PokefinderHudPosition.LEFT);
    }

    public static void renderBase(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!shouldRenderPinned(minecraft)) return;

        ClientCobblenavConfig config = CobblenavClient.INSTANCE.getConfig();
        float scale = config.getPokefinderOverlayScale();
        int scaledWidth = (int) (minecraft.getWindow().getGuiScaledWidth() / scale);
        int left = config.getPokefinderOverlayOffsetX();
        int target = state.position() == PokefinderHudPosition.RIGHT
            ? scaledWidth - Cobblenav233LayoutAdapter.WIDTH - left
            : left;

        graphics.pose().pushPose();
        try {
            graphics.pose().translate((target - left) * scale, 0.0F, 0.0F);
            INTEGRATED_RENDER.set(true);
            CobblenavClient.INSTANCE.getPokefinderOverlay().render(graphics, deltaTracker);
        } finally {
            INTEGRATED_RENDER.set(false);
            graphics.pose().popPose();
        }
    }

    public static boolean isRenderingIntegratedOverlay() {
        return INTEGRATED_RENDER.get();
    }

    static boolean shouldRenderPinned(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        return pokenavAvailable && state.enabled()
            && minecraft.screen == null
            && player != null
            && CobblenavClient.INSTANCE.getPokefinderSettings() != null;
    }

    private static Path stateFile() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve("cobbleventure-pokefinder-hud.txt");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static State parseState(String raw) {
        String legacy = raw.trim().toUpperCase(Locale.ROOT);
        if (legacy.equals("LEFT")) return new State(true, PokefinderHudPosition.LEFT);
        if (legacy.equals("RIGHT")) return new State(true, PokefinderHudPosition.RIGHT);
        if (legacy.equals("OFF")) return new State(false, PokefinderHudPosition.LEFT);

        Properties properties = new Properties();
        try {
            properties.load(new java.io.StringReader(raw));
            boolean enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
            PokefinderHudPosition position = PokefinderHudPosition.valueOf(
                properties.getProperty("position", "left").trim().toUpperCase(Locale.ROOT)
            );
            return new State(enabled, position);
        } catch (IOException | IllegalArgumentException ignored) {
            return new State(false, PokefinderHudPosition.LEFT);
        }
    }

    private static State load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new State(false, PokefinderHudPosition.LEFT);
        }
        try {
            return parseState(Files.readString(path));
        } catch (IOException ignored) {
            return new State(false, PokefinderHudPosition.LEFT);
        }
    }

    private static void save(Path path, State value) {
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                path,
                "enabled=" + value.enabled() + System.lineSeparator()
                    + "position=" + value.position().name().toLowerCase(Locale.ROOT)
            );
        } catch (IOException ignored) {
            // A read-only config directory keeps the selection for this session.
        }
    }

    record State(boolean enabled, PokefinderHudPosition position) {}
}
