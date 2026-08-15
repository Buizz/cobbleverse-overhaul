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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared, read-only map data packaged from the content editor. */
public final class MapContent {
    private static final String ROOT = "/data/cobbleventure_player_menu/map/";
    private static final List<MapContent> MAPS = loadAll();
    private static final MapContent INSTANCE = MAPS.getFirst();

    private final int generation;
    private final String dimension;
    private final int tileRadiusBlocks;
    private final int mapRadiusCells;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final Map<Hex, BiomeTile> tiles;
    private final List<Town> towns;
    private final List<Route> routes;
    private final List<CaveEntrance> caveEntrances;
    private final Map<String, CaveInfo> caves;
    private final List<ForestEntrance> forestEntrances;
    private final Map<String, ForestInfo> forests;
    private final Map<String, BiomeInfo> biomes;
    private final Map<Hex, BiomeInfo> tileHabitats;

    private MapContent(
        int generation,
        String dimension,
        int tileRadiusBlocks,
        int mapRadiusCells,
        int originX,
        int originY,
        int originZ,
        Map<Hex, BiomeTile> tiles,
        List<Town> towns,
        List<Route> routes,
        List<CaveEntrance> caveEntrances,
        Map<String, CaveInfo> caves,
        List<ForestEntrance> forestEntrances,
        Map<String, ForestInfo> forests,
        Map<String, BiomeInfo> biomes,
        Map<Hex, BiomeInfo> tileHabitats
    ) {
        this.generation = generation;
        this.dimension = dimension;
        this.tileRadiusBlocks = tileRadiusBlocks;
        this.mapRadiusCells = mapRadiusCells;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.tiles = Map.copyOf(tiles);
        this.towns = List.copyOf(towns);
        this.routes = List.copyOf(routes);
        this.caveEntrances = List.copyOf(caveEntrances);
        this.caves = Map.copyOf(caves);
        this.forestEntrances = List.copyOf(forestEntrances);
        this.forests = Map.copyOf(forests);
        this.biomes = Map.copyOf(biomes);
        this.tileHabitats = Map.copyOf(tileHabitats);
    }

    public static MapContent instance() {
        return INSTANCE;
    }

    public static List<MapContent> all() {
        return MAPS;
    }

    public static List<Integer> availableGenerations() {
        return MAPS.stream().map(MapContent::generation).toList();
    }

    public static MapContent forGeneration(int generation) {
        for (MapContent content : MAPS) {
            if (content.generation == generation) return content;
        }
        return null;
    }

    public int generation() { return generation; }
    public String dimension() { return dimension; }
    public int tileRadiusBlocks() { return tileRadiusBlocks; }
    public int mapRadiusCells() { return mapRadiusCells; }
    public int originY() { return originY; }
    public Map<Hex, BiomeTile> tiles() { return tiles; }
    public List<Town> towns() { return towns; }
    public List<Route> routes() { return routes; }
    public List<CaveEntrance> caveEntrances() { return caveEntrances; }
    public List<ForestEntrance> forestEntrances() { return forestEntrances; }

    public CaveInfo cave(String id) { return caves.get(id); }
    public ForestInfo forest(String id) { return forests.get(id); }

    public Town townAt(int q, int r) {
        Hex target = new Hex(q, r);
        for (Town town : towns) {
            if (townContains(town, target)) return town;
        }
        return null;
    }

    private static boolean townContains(Town town, Hex target) {
        Hex center = town.hex(); int cellCount = town.radiusCells(); String footprintShape = town.footprintShape();
        int q = target.q() - center.q();
        int r = target.r() - center.r();
        if ("custom".equals(footprintShape)) return town.customFootprint().contains(new Hex(q, r));
        if (q == 0 && r == 0) return true;
        if (cellCount == 3) {
            return switch (footprintShape == null ? "line_q" : footprintShape) {
                case "triangle_up" -> (q == 0 && r == -1) || (q == 1 && r == -1);
                case "triangle_down" -> (q == 0 && r == 1) || (q == -1 && r == 1);
                case "line_r" -> q == 0 && Math.abs(r) == 1;
                case "line_s" -> (q == -1 && r == 1) || (q == 1 && r == -1);
                default -> r == 0 && Math.abs(q) == 1;
            };
        }
        if (cellCount == 5) {
            if ("five_down".equals(footprintShape)) {
                return (r == 0 && Math.abs(q) == 1)
                    || (q == -1 && r == 1)
                    || (q == 0 && r == 1);
            }
            return (r == 0 && Math.abs(q) == 1)
                || (q == 0 && r == -1)
                || (q == 1 && r == -1);
        }
        if (cellCount == 7) return hexDistance(center, target) == 1;
        return cellCount == 19 && hexDistance(center, target) <= 2;
    }

    public BiomeTile tileAt(int q, int r) {
        return tiles.get(new Hex(q, r));
    }

    public BiomeInfo biome(String id) {
        return biomes.getOrDefault(id, new BiomeInfo(id, readableId(id), "", 0, List.of(), 0));
    }

    public BiomeInfo biome(BiomeTile tile) {
        return tile == null ? biome("") : tileHabitats.getOrDefault(tile.hex(), biome(tile.biome()));
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

    private static List<MapContent> loadAll() {
        List<MapContent> result = new ArrayList<>();
        for (int generation = 1; generation <= 9; generation++) {
            String path = "generation_" + generation + ".json";
            if (MapContent.class.getResource(ROOT + path) != null) result.add(load(generation));
        }
        if (result.isEmpty()) throw new IllegalStateException("No generation map resources found");
        return List.copyOf(result);
    }

    private static MapContent load(int generation) {
        JsonObject world = resource("generation_" + generation + ".json");
        JsonObject grid = world.getAsJsonObject("grid");
        JsonObject origin = grid.getAsJsonObject("origin");
        Map<Hex, BiomeTile> tiles = new LinkedHashMap<>();
        for (JsonElement element : world.getAsJsonArray("tiles")) {
            JsonObject tile = element.getAsJsonObject();
            Hex hex = new Hex(tile.get("q").getAsInt(), tile.get("r").getAsInt());
            tiles.put(hex, new BiomeTile(hex, tile.get("biome").getAsString()));
        }

        List<Town> towns = new ArrayList<>();
        Map<String, List<FieldMoveNpc>> fieldMoveNpcs = loadFieldMoveNpcs();
        for (JsonElement element : world.getAsJsonArray("settlements")) {
            JsonObject placed = element.getAsJsonObject();
            String id = placed.get("settlement").getAsString();
            String slug = id.substring(id.lastIndexOf('/') + 1);
            JsonObject preset = resource("settlements/generation_" + generation + "/" + slug + ".json");
            JsonObject anchor = placed.getAsJsonObject("anchor");
            JsonObject structure = preset.getAsJsonObject("structure_profile");
            JsonObject gym = structure.getAsJsonObject("gym");
            JsonObject district = structure.getAsJsonObject("special_district");
            JsonObject building = district.getAsJsonObject("building");
            List<Hex> customFootprint = new ArrayList<>();
            if (preset.has("town_footprint_cells")) {
                for (JsonElement cellElement : preset.getAsJsonArray("town_footprint_cells")) {
                    JsonObject cell = cellElement.getAsJsonObject();
                    customFootprint.add(new Hex(cell.get("q").getAsInt(), cell.get("r").getAsInt()));
                }
            }
            towns.add(new Town(
                id,
                localized(preset.getAsJsonObject("display_name"), slug),
                new Hex(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                preset.get("town_radius_cells").getAsInt(),
                preset.has("town_footprint_shape") ? preset.get("town_footprint_shape").getAsString() : "line_q",
                List.copyOf(customFootprint),
                placed.get("town_biome").getAsString(),
                gym.get("enabled").getAsBoolean(),
                gym.get("theme").getAsString(),
                gym.get("structure").getAsString(),
                building.get("enabled").getAsBoolean(),
                building.get("structure").getAsString(),
                fieldMoveNpcs.getOrDefault(id, List.of())
            ));
        }

        List<Route> routes = new ArrayList<>();
        for (JsonElement element : world.getAsJsonArray("connections")) {
            JsonObject connection = element.getAsJsonObject();
            if ("water".equals(stringValue(connection, "surface_style", "road"))) {
                continue;
            }
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

        LoadedBiomes loadedBiomes = loadBiomes(generation, tiles, world);
        LoadedCaves loadedCaves = loadCaves(generation, world, loadedBiomes.byBiome());
        LoadedForests loadedForests = loadForests(generation, world, loadedBiomes.byBiome());
        return new MapContent(
            generation,
            world.get("dimension").getAsString(),
            grid.get("tile_radius_blocks").getAsInt(),
            grid.get("map_radius_cells").getAsInt(),
            origin.get("x").getAsInt(), origin.get("y").getAsInt(), origin.get("z").getAsInt(),
            tiles, towns, routes, loadedCaves.entrances(), loadedCaves.byId(),
            loadedForests.entrances(), loadedForests.byId(),
            loadedBiomes.byBiome(), loadedBiomes.byTile()
        );
    }

    private static LoadedCaves loadCaves(
        int generation, JsonObject world, Map<String, BiomeInfo> biomes
    ) {
        if (!world.has("cave_entrances")) return new LoadedCaves(List.of(), Map.of());
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (JsonElement element : world.getAsJsonArray("cave_entrances")) {
            JsonObject entrance = element.getAsJsonObject();
            String caveId = entrance.get("cave").getAsString();
            definitions.computeIfAbsent(caveId, ignored -> {
                String slug = caveId.substring(caveId.lastIndexOf('/') + 1);
                return resource("caves/generation_" + generation + "/" + slug + ".json");
            });
        }

        Map<String, CaveInfo> caveInfos = new LinkedHashMap<>();
        Map<String, Map<String, String>> entranceNames = new HashMap<>();
        for (Map.Entry<String, JsonObject> entry : definitions.entrySet()) {
            JsonObject cave = entry.getValue();
            LinkedHashMap<String, Pokemon> pokemon = new LinkedHashMap<>();
            List<String> biomeNames = new ArrayList<>();
            JsonObject encounters = cave.has("random_encounters")
                ? cave.getAsJsonObject("random_encounters") : new JsonObject();
            String biomeId = encounters.has("pokemon_biome")
                ? encounters.get("pokemon_biome").getAsString() : "minecraft:dripstone_caves";
            BiomeInfo biome = biomes.get(biomeId);
            if (biome != null) {
                biomeNames.add(biome.name());
                Set<String> excluded = new HashSet<>();
                if (encounters.has("excluded_species")) {
                    for (JsonElement value : encounters.getAsJsonArray("excluded_species")) {
                        excluded.add(value.getAsString());
                    }
                }
                if (!encounters.has("inherit_biome") || encounters.get("inherit_biome").getAsBoolean()) {
                    for (Pokemon value : biome.pokemon()) {
                        if (!excluded.contains(value.id())) pokemon.putIfAbsent(value.id(), value);
                    }
                }
                if (encounters.has("additions")) {
                    for (JsonElement additionElement : encounters.getAsJsonArray("additions")) {
                        String species = additionElement.getAsJsonObject().get("species").getAsString();
                        biomes.values().stream().flatMap(value -> value.pokemon().stream())
                            .filter(value -> value.id().equals(species)).findFirst()
                            .ifPresent(value -> pokemon.putIfAbsent(value.id(), value));
                    }
                }
            }
            List<Pokemon> pokemonList = new ArrayList<>(pokemon.values());
            pokemonList.sort(Comparator.comparingInt(Pokemon::dexNumber));
            caveInfos.put(entry.getKey(), new CaveInfo(
                entry.getKey(),
                localized(cave.getAsJsonObject("display_name"), readableId(entry.getKey())),
                List.copyOf(biomeNames), List.copyOf(pokemonList)
            ));
            Map<String, String> names = new HashMap<>();
            if (cave.has("entrances")) {
                for (JsonElement entranceElement : cave.getAsJsonArray("entrances")) {
                    JsonObject entrance = entranceElement.getAsJsonObject();
                    names.put(entrance.get("id").getAsString(),
                        stringValue(entrance, "display_name", readableId(entrance.get("id").getAsString())));
                }
            }
            entranceNames.put(entry.getKey(), Map.copyOf(names));
        }

        List<CaveEntrance> entrances = new ArrayList<>();
        for (JsonElement element : world.getAsJsonArray("cave_entrances")) {
            JsonObject value = element.getAsJsonObject();
            String caveId = value.get("cave").getAsString();
            String entranceId = value.get("entrance").getAsString();
            JsonObject anchor = value.getAsJsonObject("anchor");
            entrances.add(new CaveEntrance(
                value.get("id").getAsString(), caveId, entranceId,
                entranceNames.getOrDefault(caveId, Map.of()).getOrDefault(entranceId, readableId(entranceId)),
                new Hex(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                stringValue(value, "facing", "")
            ));
        }
        return new LoadedCaves(List.copyOf(entrances), Map.copyOf(caveInfos));
    }

    private static LoadedForests loadForests(
        int generation, JsonObject world, Map<String, BiomeInfo> biomes
    ) {
        if (!world.has("forest_entrances")) return new LoadedForests(List.of(), Map.of());
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (JsonElement element : world.getAsJsonArray("forest_entrances")) {
            JsonObject entrance = element.getAsJsonObject();
            String forestId = entrance.get("forest").getAsString();
            definitions.computeIfAbsent(forestId, ignored -> {
                String slug = forestId.substring(forestId.lastIndexOf('/') + 1);
                return resource("forests/generation_" + generation + "/" + slug + ".json");
            });
        }

        Map<String, ForestInfo> forestInfos = new LinkedHashMap<>();
        Map<String, Map<String, String>> entranceNames = new HashMap<>();
        for (Map.Entry<String, JsonObject> entry : definitions.entrySet()) {
            JsonObject forest = entry.getValue();
            LinkedHashMap<String, Pokemon> pokemon = new LinkedHashMap<>();
            List<String> biomeNames = new ArrayList<>();
            JsonObject encounters = forest.has("random_encounters")
                ? forest.getAsJsonObject("random_encounters") : new JsonObject();
            String biomeId = encounters.has("pokemon_biome")
                ? encounters.get("pokemon_biome").getAsString() : "minecraft:forest";
            BiomeInfo biome = biomes.get(biomeId);
            if (biome != null) {
                biomeNames.add(biome.name());
                Set<String> excluded = new HashSet<>();
                if (encounters.has("excluded_species")) {
                    for (JsonElement value : encounters.getAsJsonArray("excluded_species")) {
                        excluded.add(value.getAsString());
                    }
                }
                if (!encounters.has("inherit_biome") || encounters.get("inherit_biome").getAsBoolean()) {
                    for (Pokemon value : biome.pokemon()) {
                        if (!excluded.contains(value.id())) pokemon.putIfAbsent(value.id(), value);
                    }
                }
                if (encounters.has("additions")) {
                    for (JsonElement additionElement : encounters.getAsJsonArray("additions")) {
                        String species = additionElement.getAsJsonObject().get("species").getAsString();
                        biomes.values().stream().flatMap(value -> value.pokemon().stream())
                            .filter(value -> value.id().equals(species)).findFirst()
                            .ifPresent(value -> pokemon.putIfAbsent(value.id(), value));
                    }
                }
            }
            List<Pokemon> pokemonList = new ArrayList<>(pokemon.values());
            pokemonList.sort(Comparator.comparingInt(Pokemon::dexNumber));
            forestInfos.put(entry.getKey(), new ForestInfo(
                entry.getKey(),
                localized(forest.getAsJsonObject("display_name"), readableId(entry.getKey())),
                List.copyOf(biomeNames), List.copyOf(pokemonList)
            ));
            Map<String, String> names = new HashMap<>();
            if (forest.has("entrances")) {
                for (JsonElement entranceElement : forest.getAsJsonArray("entrances")) {
                    JsonObject entrance = entranceElement.getAsJsonObject();
                    names.put(entrance.get("id").getAsString(),
                        stringValue(entrance, "display_name", readableId(entrance.get("id").getAsString())));
                }
            }
            entranceNames.put(entry.getKey(), Map.copyOf(names));
        }

        List<ForestEntrance> entrances = new ArrayList<>();
        for (JsonElement element : world.getAsJsonArray("forest_entrances")) {
            JsonObject value = element.getAsJsonObject();
            String forestId = value.get("forest").getAsString();
            String entranceId = value.get("entrance").getAsString();
            JsonObject anchor = value.getAsJsonObject("anchor");
            entrances.add(new ForestEntrance(
                value.get("id").getAsString(), forestId, entranceId,
                entranceNames.getOrDefault(forestId, Map.of()).getOrDefault(entranceId, readableId(entranceId)),
                new Hex(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                stringValue(value, "facing", "")
            ));
        }
        return new LoadedForests(List.copyOf(entrances), Map.copyOf(forestInfos));
    }

    private static LoadedBiomes loadBiomes(int generation, Map<Hex, BiomeTile> tiles, JsonObject world) {
        JsonObject profilesRoot = resource("catalogs/biome-profiles.json");
        JsonObject pokemonRoot = resource("catalogs/pokemon-habitats.json");
        int maxPerVariant = profilesRoot.has("max_pokemon_per_habitat_variant")
            ? profilesRoot.get("max_pokemon_per_habitat_variant").getAsInt() : 40;
        Map<String, JsonObject> profiles = new HashMap<>();
        for (JsonElement element : profilesRoot.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            for (JsonElement biome : profile.getAsJsonArray("minecraft_biomes")) {
                profiles.put(biome.getAsString(), profile);
            }
        }

        Map<Hex, JsonObject> environmentOverrides = new HashMap<>();
        if (world.has("environment_overrides")) {
            for (JsonElement element : world.getAsJsonArray("environment_overrides")) {
                JsonObject override = element.getAsJsonObject();
                environmentOverrides.put(new Hex(
                    override.get("q").getAsInt(), override.get("r").getAsInt()
                ), override);
            }
        }

        String series = seriesForGeneration(generation);
        Map<String, List<PokemonMatch>> matchesByBiome = new HashMap<>();
        Map<String, BiomeInfo> result = new HashMap<>();
        for (Map.Entry<String, JsonObject> entry : profiles.entrySet()) {
            JsonObject profile = entry.getValue();
            List<PokemonMatch> matching = matchingPokemon(profile, pokemonRoot, series, null);
            matchesByBiome.put(entry.getKey(), matching);
            List<Pokemon> pokemon = matching.stream().map(PokemonMatch::pokemon).toList();
            result.put(entry.getKey(), new BiomeInfo(
                entry.getKey(),
                localized(profile.getAsJsonObject("display_name"), readableId(entry.getKey())),
                profile.get("habitat").getAsString(),
                0,
                pokemon,
                pokemon.size()
            ));
        }
        aliasBiome(result, "minecraft:beach", "해변", "minecraft:ocean");
        aliasBiome(result, "minecraft:dark_forest", "어두운 숲", "minecraft:forest");
        aliasBiome(result, "minecraft:flower_forest", "꽃 숲", "minecraft:forest");
        aliasBiome(result, "minecraft:old_growth_pine_taiga", "원시 소나무 타이가", "minecraft:forest");
        aliasBiome(result, "minecraft:sparse_jungle", "성긴 정글", "minecraft:jungle");
        aliasBiome(result, "minecraft:windswept_gravelly_hills", "바람 센 자갈 언덕", "minecraft:windswept_hills");

        Map<Hex, BiomeInfo> byTile = new LinkedHashMap<>();
        Map<String, Integer> variantCursor = new HashMap<>();
        for (BiomeTile tile : tiles.values()) {
            JsonObject profile = profiles.get(tile.biome());
            BiomeInfo base = result.get(tile.biome());
            if (profile == null || base == null) continue;
            JsonObject override = environmentOverrides.get(tile.hex());
            List<PokemonMatch> matching = override == null
                ? matchesByBiome.getOrDefault(tile.biome(), List.of())
                : matchingPokemon(profile, pokemonRoot, series, override);
            List<PokemonMatch> ordinary = matching.stream().filter(match -> !match.explicit()).toList();
            int variantCount = Math.max(1, (ordinary.size() + maxPerVariant - 1) / maxPerVariant);
            int configuredVariant = intValue(override, "habitat_variant", 0);
            String profileId = profile.get("id").getAsString();
            int variant = configuredVariant > 0 ? configuredVariant
                : variantCursor.merge(profileId, 1, Integer::sum);
            variant = (variant - 1) % variantCount + 1;

            List<Pokemon> selected = new ArrayList<>();
            matching.stream().filter(PokemonMatch::explicit).map(PokemonMatch::pokemon).forEach(selected::add);
            for (int index = 0; index < ordinary.size(); index++) {
                if (index * variantCount / ordinary.size() + 1 == variant) {
                    selected.add(ordinary.get(index).pokemon());
                }
            }
            selected.sort(Comparator.comparingInt(Pokemon::dexNumber));
            byTile.put(tile.hex(), new BiomeInfo(
                base.id(), base.name(), base.habitat(), variant, List.copyOf(selected), selected.size()
            ));
        }
        return new LoadedBiomes(result, byTile);
    }

    private static List<PokemonMatch> matchingPokemon(
        JsonObject profile, JsonObject pokemonRoot, String defaultSeries, JsonObject override
    ) {
        JsonObject settings = profile.getAsJsonObject("settings");
        String series = stringValue(override, "series", defaultSeries);
        boolean includeSecondary = booleanValue(override, "include_secondary",
            booleanValue(settings, "include_secondary", true));
        Set<String> rarities = stringSet(override, "rarities");
        if (rarities.isEmpty()) rarities = stringSet(settings, "rarities");
        Set<String> forced = stringSet(profile, "forced_includes");
        Set<String> excluded = stringSet(profile, "excluded_pokemon");
        String habitat = profile.get("habitat").getAsString();
        List<PokemonMatch> result = new ArrayList<>();
        for (JsonElement element : pokemonRoot.getAsJsonArray("pokemon")) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            if (!booleanValue(entry, "implemented", false) || excluded.contains(id)) continue;
            boolean explicit = forced.contains(id);
            JsonObject preferences = entry.getAsJsonObject("preferences");
            JsonObject habitats = entry.getAsJsonObject("habitats");
            boolean habitatMatch = habitat.equals(nullableString(habitats, "primary"))
                || includeSecondary && habitat.equals(nullableString(habitats, "secondary"));
            boolean ordinary = !booleanValue(entry, "is_legendary", false)
                && !booleanValue(entry, "is_mythical", false)
                && seriesContains(entry, series)
                && rarities.contains(stringValue(preferences, "rarity", ""))
                && compatible(stringValue(override, "temperature", stringValue(settings, "temperature", "any")), stringValue(preferences, "temperature", "any"))
                && compatible(stringValue(override, "humidity", stringValue(settings, "humidity", "any")), stringValue(preferences, "humidity", "any"))
                && compatible(stringValue(override, "weather", stringValue(settings, "weather", "any")), stringValue(preferences, "weather", "any"))
                && compatible(stringValue(override, "time", stringValue(settings, "time", "any")), stringValue(preferences, "time", "any"))
                && habitatMatch;
            if (!explicit && !ordinary) continue;
            result.add(new PokemonMatch(new Pokemon(
                entry.get("dex_number").getAsInt(), id,
                localized(entry.getAsJsonObject("display_name"), readableId(id))
            ), explicit));
        }
        result.sort(Comparator.comparingInt(match -> match.pokemon().dexNumber()));
        return List.copyOf(result);
    }

    private static boolean seriesContains(JsonObject pokemon, String series) {
        if (series == null || series.isBlank()) return true;
        if (!pokemon.has("series_appearances")) return false;
        for (JsonElement value : pokemon.getAsJsonArray("series_appearances")) {
            if (series.equals(value.getAsString())) return true;
        }
        return false;
    }

    private static String seriesForGeneration(int generation) {
        return switch (generation) {
            case 1 -> "kanto";
            case 2 -> "johto";
            case 3 -> "hoenn";
            case 4 -> "sinnoh";
            case 5 -> "unova";
            case 6 -> "kalos";
            case 7 -> "alola";
            case 8 -> "galar";
            case 9 -> "paldea";
            default -> "";
        };
    }

    private static boolean compatible(String requested, String preference) {
        return "any".equals(requested) || "any".equals(preference) || requested.equals(preference);
    }

    private static String stringValue(JsonObject object, String member, String fallback) {
        return object != null && object.has(member) && !object.get(member).isJsonNull()
            ? object.get(member).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String member, int fallback) {
        return object != null && object.has(member) ? object.get(member).getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String member, boolean fallback) {
        return object != null && object.has(member) ? object.get(member).getAsBoolean() : fallback;
    }

    private static Set<String> stringSet(JsonObject object, String member) {
        if (object == null || !object.has(member) || !object.get(member).isJsonArray()) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (JsonElement element : object.getAsJsonArray(member)) result.add(element.getAsString());
        return Set.copyOf(result);
    }

    private static String nullableString(JsonObject object, String member) {
        if (object == null) return null;
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static void aliasBiome(Map<String, BiomeInfo> result, String alias, String name, String source) {
        BiomeInfo info = result.get(source);
        if (info != null) {
            result.put(alias, new BiomeInfo(
                alias, name, info.habitat(), info.habitatVariant(), info.pokemon(), info.totalPokemon()
            ));
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

    private static Map<String, List<FieldMoveNpc>> loadFieldMoveNpcs() {
        JsonObject settlements = resource("field-move-npcs.json").getAsJsonObject("settlements");
        Map<String, List<FieldMoveNpc>> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : settlements.entrySet()) {
            List<FieldMoveNpc> npcs = new ArrayList<>();
            for (JsonElement npcElement : entry.getValue().getAsJsonArray()) {
                JsonObject npc = npcElement.getAsJsonObject();
                String name = npc.get("name").getAsString();
                for (JsonElement move : npc.getAsJsonArray("moves")) {
                    npcs.add(new FieldMoveNpc(name, fieldMoveDisplayName(move.getAsString())));
                }
            }
            result.put(entry.getKey(), List.copyOf(npcs));
        }
        return Map.copyOf(result);
    }

    private static String fieldMoveDisplayName(String move) {
        return switch (move) {
            case "surf" -> "파도타기";
            case "fly" -> "공중날기";
            case "flash" -> "플래쉬";
            case "defog" -> "안개제거";
            case "rock_climb" -> "락클레임";
            case "whirlpool" -> "바다회오리";
            case "strength" -> "괴력";
            case "rock_smash" -> "바위깨기";
            default -> readableId(move);
        };
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
    public record CaveEntrance(
        String id, String caveId, String entranceId, String name, Hex hex, String facing
    ) {}
    public record CaveInfo(String id, String name, List<String> biomes, List<Pokemon> pokemon) {}
    public record ForestEntrance(
        String id, String forestId, String entranceId, String name, Hex hex, String facing
    ) {}
    public record ForestInfo(String id, String name, List<String> biomes, List<Pokemon> pokemon) {}
    public record Pokemon(int dexNumber, String id, String name) {}
    public record FieldMoveNpc(String name, String move) {}
    public record BiomeInfo(
        String id, String name, String habitat, int habitatVariant, List<Pokemon> pokemon, int totalPokemon
    ) {}
    private record PokemonMatch(Pokemon pokemon, boolean explicit) {}
    private record LoadedBiomes(Map<String, BiomeInfo> byBiome, Map<Hex, BiomeInfo> byTile) {}
    private record LoadedCaves(List<CaveEntrance> entrances, Map<String, CaveInfo> byId) {}
    private record LoadedForests(List<ForestEntrance> entrances, Map<String, ForestInfo> byId) {}
    public record Town(
        String id,
        String name,
        Hex hex,
        int radiusCells,
        String footprintShape,
        List<Hex> customFootprint,
        String biome,
        boolean gymEnabled,
        String gymTheme,
        String gymStructure,
        boolean specialBuildingEnabled,
        String specialBuildingStructure,
        List<FieldMoveNpc> fieldMoveNpcs
    ) {
        public Town {
            Objects.requireNonNull(id);
            Objects.requireNonNull(hex);
            fieldMoveNpcs = List.copyOf(fieldMoveNpcs);
        }
    }
}
