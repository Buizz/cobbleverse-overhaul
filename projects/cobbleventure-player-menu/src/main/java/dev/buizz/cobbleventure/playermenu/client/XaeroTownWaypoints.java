package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.CobbleventurePlayerMenu;
import dev.buizz.cobbleventure.playermenu.MapContent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Adds generated Cobbleventure towns and marked world objects to Xaero's active waypoint set. */
@EventBusSubscriber(modid = CobbleventurePlayerMenu.MOD_ID, value = Dist.CLIENT)
public final class XaeroTownWaypoints {
    private static final int SYNC_INTERVAL_TICKS = 100;
    private static int ticksUntilSync;
    private static boolean xaeroMissing;

    private XaeroTownWaypoints() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (xaeroMissing || minecraft.player == null || minecraft.level == null) return;
        if (ticksUntilSync-- > 0) return;
        ticksUntilSync = SYNC_INTERVAL_TICKS;

        String dimension = minecraft.level.dimension().location().toString();
        MapContent content = null;
        for (MapContent candidate : MapContent.all()) {
            if (candidate.dimension().equals(dimension)) {
                content = candidate;
                break;
            }
        }
        if (content == null) return;

        try {
            sync(content);
        } catch (ClassNotFoundException error) {
            xaeroMissing = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Xaero can still be initializing during the first client ticks; retry later.
        }
    }

    private static void sync(MapContent content) throws ReflectiveOperationException {
        Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
        Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
        if (session == null) return;
        Object manager = sessionClass.getMethod("getWaypointsManager").invoke(session);
        Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
        if (world == null) return;
        Object waypointSet = world.getClass().getMethod("getCurrentSet").invoke(world);
        if (waypointSet == null) return;

        Method getList = waypointSet.getClass().getMethod("getList");
        @SuppressWarnings("unchecked")
        List<Object> waypoints = (List<Object>) getList.invoke(waypointSet);
        Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
        Constructor<?> constructor = waypointClass.getConstructor(
            int.class, int.class, int.class, String.class, String.class, int.class
        );
        Method getX = waypointClass.getMethod("getX");
        Method getZ = waypointClass.getMethod("getZ");
        Method getName = waypointClass.getMethod("getName");

        for (MapContent.Town town : content.towns()) {
            MapContent.WorldPoint point = content.worldCenter(town.hex().q(), town.hex().r());
            if (contains(waypoints, getX, getZ, getName, point.x(), point.z(), town.name())) continue;
            Object waypoint = constructor.newInstance(
                point.x(), content.originY(), point.z(), town.name(), initials(town.name()), 6
            );
            waypoints.add(waypoint);
        }
        for (MapContent.MapObject object : content.objects()) {
            if (!object.showOnMinimap()) continue;
            MapContent.WorldPoint point = content.worldCenter(object.hex().q(), object.hex().r());
            if (contains(waypoints, getX, getZ, getName, point.x(), point.z(), object.name())) continue;
            Object waypoint = constructor.newInstance(
                point.x(), content.originY(), point.z(), object.name(), initials(object.name()), 6
            );
            waypoints.add(waypoint);
        }
    }

    private static boolean contains(
        List<Object> waypoints, Method getX, Method getZ, Method getName,
        int x, int z, String name
    ) throws ReflectiveOperationException {
        for (Object waypoint : waypoints) {
            if ((int) getX.invoke(waypoint) == x
                && (int) getZ.invoke(waypoint) == z
                && name.equals(getName.invoke(waypoint))) return true;
        }
        return false;
    }

    private static String initials(String name) {
        String compact = name.replace(" ", "");
        if (compact.isEmpty()) return "T";
        int end = compact.offsetByCodePoints(0, Math.min(2, compact.codePointCount(0, compact.length())));
        return compact.substring(0, end);
    }
}
