package dev.buizz.cobbleventure.playermenu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared, read-only map data packaged from the content editor. */
public final class MapContent {
    private static final String ROOT = "/data/cobbleventure_player_menu/map/";
    private static final MapContent INSTANCE = load();

    private final String dimension;
    private final int tileRadiusBlocks;
    private final int mapRadiusCells;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final Map<Hex, BiomeTile> tiles;
    private final List<Town> towns;
    private final List<Route> routes;
    private final Map<String, BiomeInfo> biomes;

    private MapContent(
        String dimension,
        int tileRadiusBlocks,
        int mapRadiusCells,
        int originX,
        int originY,
        int originZ,
        Map<Hex, BiomeTile> tiles,
        List<Town> towns,
        List<Route> routes,
        Map<String, BiomeInfo> biomes
    ) {
        this.dimension = dimension;
        this.tileRadiusBlocks = tileRadiusBlocks;
        this.mapRadiusCells = mapRadiusCells;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.tiles = Map.copyOf(tiles);
        this.towns = List.copyOf(towns);
        this.routes = List.copyOf(routes);
        this.biomes = Map.copyOf(biomes);
    }

    public static MapContent instance() {
        return INSTANCE;
    }

    public String dimension() { return dimension; }
    public int tileRadiusBlocks() { return tileRadiusBlocks; }
    public int mapRadiusCells() { return mapRadiusCells; }
    public int originY() { return originY; }
    public Map<Hex, BiomeTile> tiles() { return tiles; }
    public List<Town> towns() { return towns; }
    public List<Route> routes() { return routes; }

    public Town townAt(int q, int r) {
        Hex target = new Hex(q, r);
        for (Town town : towns) {
            if (hexDistance(town.hex(), target) <= town.radiusCells()) return town;
        }
        return null;
    }

    public BiomeTile tileAt(int q, int r) {
        return tiles.get(new Hex(q, r));
    }

    public BiomeInfo biome(String id) {
        return biomes.getOrDefault(id, new BiomeInfo(id, readableId(id), "", List.of(), 0));
    }

    public Hex worldToHex(double x, double z) {
        double localX = x - originX;
        double localZ = z - originZ;
        double qValue = (Math.sqrt(3.0D) / 3.0D * localX - localZ / 3.0D) / tileRadiusBlocks;
        double rValue = (2.0D / 3.0D * localZ) / tileRadiusBlocks;
        return roundHex(qValue, rValue);
    }

    public WorldPoint worldCenter(int q, int r) {
        int x = (int) Math.round(originX + tileRadiusBlocks * Math.sqrt(3.0D) * (q + r / 2.0D));
        int z = (int) Math.round(originZ + tileRadiusBlocks * 1.5D * r);
        return new WorldPoint(x, z);
    }

    public boolean contains(int q, int r) {
        return hexDistance(new Hex(0, 0), new Hex(q, r)) <= mapRadiusCells;
    }

    private static MapContent load() {
        JsonObject world = resource("generation_1.json");
        JsonObject grid = world.getAsJsonObject("grid");
        JsonObject origin = grid.getAsJsonObject("origin");
        Map<Hex, BiomeTile> tiles = new LinkedHashMap<>();
        for (JsonElement element : world.getAsJsonArray("tiles")) {
            JsonObject tile = element.getAsJsonObject();
            Hex hex = new Hex(tile.get("q").getAsInt(), tile.get("r").getAsInt());
            tiles.put(hex, new BiomeTile(hex, tile.get("biome").getAsString()));
        }

        List<Town> towns = new ArrayList<>();
        for (JsonElement element : world.getAsJsonArray("settlements")) {
            JsonObject placed = element.getAsJsonObject();
            String id = placed.get("settlement").getAsString();
            String slug = id.substring(id.lastIndexOf('/') + 1);
            JsonObject preset = resource("settlements/" + slug + ".json");
            JsonObject anchor = placed.getAsJsonObject("anchor");
            JsonObject structure = preset.getAsJsonObject("structure_profile");
            JsonObject gym = structure.getAsJsonObject("gym");
            JsonObject district = structure.getAsJsonObject("special_district");
            JsonObject building = district.getAsJsonObject("building");
            towns.add(new Town(
                id,
                localized(preset.getAsJsonObject("display_name"), slug),
                new Hex(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                preset.get("town_radius_cells").getAsInt(),
                preset.get("biome").getAsString(),
                gym.get("enabled").getAsBoolean(),
                gym.get("theme").getAsString(),
                gym.get("structure").getAsString(),
                building.get("enabled").getAsBoolean(),
                building.get("structure").getAsString()
            ));
        }

        List<Route> routes = new ArrayList<>();
        for (JsonElement element : world.getAsJsonArray("connections")) {
            JsonObject connection = element.getAsJsonObject();
            List<Hex> path = new ArrayList<>();
            JsonArray pathJson = connection.has("cells")
                ? connection.getAsJsonArray("cells")
                : connection.getAsJsonArray("path");
            if (pathJson != null) {
                for (JsonElement pathElement : pathJson) {
                    JsonObject point = pathElement.getAsJsonObject();
                    path.add(new Hex(point.get("q").getAsInt(), point.get("r").getAsInt()));
                }
            }
            routes.add(new Route(connection.get("id").getAsString(), List.copyOf(path)));
        }

        return new MapContent(
            world.get("dimension").getAsString(),
            grid.get("tile_radius_blocks").getAsInt(),
            grid.get("map_radius_cells").getAsInt(),
            origin.get("x").getAsInt(), origin.get("y").getAsInt(), origin.get("z").getAsInt(),
            tiles, towns, routes, loadBiomes()
        );
    }

    private static Map<String, BiomeInfo> loadBiomes() {
        JsonObject profilesRoot = resource("catalogs/biome-profiles.json");
        JsonObject pokemonRoot = resource("catalogs/pokemon-habitats.json");
        Map<String, JsonObject> profiles = new HashMap<>();
        for (JsonElement element : profilesRoot.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            for (JsonElement biome : profile.getAsJsonArray("minecraft_biomes")) {
                profiles.put(biome.getAsString(), profile);
            }
        }

        Map<String, BiomeInfo> result = new HashMap<>();
        for (Map.Entry<String, JsonObject> entry : profiles.entrySet()) {
            JsonObject profile = entry.getValue();
            String habitat = profile.get("habitat").getAsString();
            boolean secondary = profile.getAsJsonObject("settings").get("include_secondary").getAsBoolean();
            List<Pokemon> matching = new ArrayList<>();
            for (JsonElement element : pokemonRoot.getAsJsonArray("pokemon")) {
                JsonObject pokemon = element.getAsJsonObject();
                JsonObject habitats = pokemon.getAsJsonObject("habitats");
                String primaryHabitat = nullableString(habitats, "primary");
                String secondaryHabitat = nullableString(habitats, "secondary");
                boolean matches = habitat.equals(primaryHabitat)
                    || secondary && habitat.equals(secondaryHabitat);
                if (matches) {
                    matching.add(new Pokemon(
                        pokemon.get("dex_number").getAsInt(),
                        pokemon.get("id").getAsString(),
                        localized(pokemon.getAsJsonObject("display_name"), readableId(pokemon.get("id").getAsString()))
                    ));
                }
            }
            matching.sort(Comparator.comparingInt(Pokemon::dexNumber));
            int total = matching.size();
            result.put(entry.getKey(), new BiomeInfo(
                entry.getKey(),
                localized(profile.getAsJsonObject("display_name"), readableId(entry.getKey())),
                habitat,
                List.copyOf(matching),
                total
            ));
        }
        aliasBiome(result, "minecraft:beach", "해변", "minecraft:ocean");
        aliasBiome(result, "minecraft:dark_forest", "어두운 숲", "minecraft:forest");
        aliasBiome(result, "minecraft:flower_forest", "꽃 숲", "minecraft:forest");
        aliasBiome(result, "minecraft:old_growth_pine_taiga", "원시 소나무 타이가", "minecraft:forest");
        aliasBiome(result, "minecraft:sparse_jungle", "성긴 정글", "minecraft:jungle");
        aliasBiome(result, "minecraft:windswept_gravelly_hills", "바람 센 자갈 언덕", "minecraft:windswept_hills");
        return result;
    }

    private static String nullableString(JsonObject object, String member) {
        if (object == null) return null;
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static void aliasBiome(Map<String, BiomeInfo> result, String alias, String name, String source) {
        BiomeInfo info = result.get(source);
        if (info != null) {
            result.put(alias, new BiomeInfo(alias, name, info.habitat(), info.pokemon(), info.totalPokemon()));
        }
    }

    private static JsonObject resource(String path) {
        try (InputStream stream = MapContent.class.getResourceAsStream(ROOT + path)) {
            if (stream == null) throw new IllegalStateException("Missing map resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read map resource: " + path, error);
        }
    }

    private static String localized(JsonObject value, String fallback) {
        if (value == null) return fallback;
        if (value.has("ko_kr")) return value.get("ko_kr").getAsString();
        if (value.has("en_us")) return value.get("en_us").getAsString();
        return fallback;
    }

    private static String readableId(String id) {
        String value = id.substring(Math.max(id.lastIndexOf(':'), id.lastIndexOf('/')) + 1);
        return value.replace('_', ' ');
    }

    private static int hexDistance(Hex from, Hex to) {
        int dq = from.q - to.q;
        int dr = from.r - to.r;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(-dq - dr)) / 2;
    }

    private static Hex roundHex(double q, double r) {
        double s = -q - r;
        int roundedQ = (int) Math.round(q);
        int roundedR = (int) Math.round(r);
        int roundedS = (int) Math.round(s);
        double qDiff = Math.abs(roundedQ - q);
        double rDiff = Math.abs(roundedR - r);
        double sDiff = Math.abs(roundedS - s);
        if (qDiff > rDiff && qDiff > sDiff) roundedQ = -roundedR - roundedS;
        else if (rDiff > sDiff) roundedR = -roundedQ - roundedS;
        return new Hex(roundedQ, roundedR);
    }

    public record Hex(int q, int r) {}
    public record WorldPoint(int x, int z) {}
    public record BiomeTile(Hex hex, String biome) {}
    public record Route(String id, List<Hex> path) {}
    public record Pokemon(int dexNumber, String id, String name) {}
    public record BiomeInfo(String id, String name, String habitat, List<Pokemon> pokemon, int totalPokemon) {}
    public record Town(
        String id,
        String name,
        Hex hex,
        int radiusCells,
        String biome,
        boolean gymEnabled,
        String gymTheme,
        String gymStructure,
        boolean specialBuildingEnabled,
        String specialBuildingStructure
    ) {
        public Town {
            Objects.requireNonNull(id);
            Objects.requireNonNull(hex);
        }
    }
}
