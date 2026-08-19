package dev.buizz.cobbleventure.adventure.battleai;

import dev.buizz.cobbleventure.ai.core.SharedSearchFieldState;
import dev.buizz.cobbleventure.ai.core.SharedObservedProjectionSide;
import dev.buizz.cobbleventure.ai.core.SharedProjectionObservation;
import dev.buizz.cobbleventure.ai.core.SharedSearchPressure;
import dev.buizz.cobbleventure.ai.core.SharedSearchTimedEffect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Showdown 공개 전투 로그에서 AI가 필요로 하는 지속 전장 상태만 재구성한다. */
final class ShowdownBattleLogObservation {
    private static final Map<String, Integer> HAZARD_MAXIMUMS = Map.of(
            "stealthrock", 1,
            "spikes", 3,
            "toxicspikes", 2,
            "stickyweb", 1);

    private final Map<String, Map<String, Integer>> hazards = new HashMap<>();
    private final Map<String, Map<String, SharedSearchTimedEffect>> sideConditions = new HashMap<>();
    private final Map<String, ActivePressure> activePressure = new HashMap<>();
    private final Map<String, Integer> activeHp = new HashMap<>();
    private final Map<String, Integer> activeMaximumHp = new HashMap<>();
    private final Map<String, int[]> activeRanks = new HashMap<>();
    private final Map<String, Boolean> gimmickRemaining = new HashMap<>();
    private final Map<String, Set<String>> adapterStates = new HashMap<>();
    private SharedSearchTimedEffect weather;
    private SharedSearchTimedEffect terrain;
    private final Map<String, SharedSearchTimedEffect> pseudoWeather = new HashMap<>();
    private int currentTurn;

    static ShowdownBattleLogObservation parse(List<String> log) {
        ShowdownBattleLogObservation result = new ShowdownBattleLogObservation();
        for (String batch : log) {
            if (batch == null || batch.isBlank()) continue;
            for (String line : batch.split("\\R")) result.accept(line);
        }
        return result;
    }

    static boolean hasMoveSince(List<String> log, int fromIndex, String pokemonPosition, String moveId) {
        String expectedPosition = positionId(pokemonPosition);
        String expectedMove = cleanId(moveId);
        for (int index = Math.max(0, fromIndex); index < log.size(); index++) {
            String batch = log.get(index);
            if (batch == null) continue;
            for (String line : batch.split("\\R")) {
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 4 && parts[1].equals("move")
                        && positionId(parts[2]).equals(expectedPosition)
                        && cleanId(parts[3]).equals(expectedMove)) return true;
            }
        }
        return false;
    }

    int hazardLayers(String sideId, String conditionId) {
        return hazards.getOrDefault(sideId(sideId), Map.of()).getOrDefault(cleanId(conditionId), 0);
    }

    ActivePressure pressure(String pokemonPosition) {
        return activePressure.getOrDefault(positionId(pokemonPosition), ActivePressure.NONE);
    }

    SharedSearchFieldState fieldState() {
        return new SharedSearchFieldState(weather, terrain, Map.copyOf(pseudoWeather));
    }

    Map<String, SharedSearchTimedEffect> sideConditions(String sideId) {
        return Map.copyOf(sideConditions.getOrDefault(sideId(sideId), Map.of()));
    }

    SharedProjectionObservation projectionObservation(List<String> orderedSideIds) {
        List<SharedObservedProjectionSide> sides = new ArrayList<>();
        for (String requestedSide : orderedSideIds) {
            String side = sideId(requestedSide);
            String position = side + "a";
            ActivePressure pressure = pressure(position);
            Map<String, Integer> layers = hazards.getOrDefault(side, Map.of());
            int[] ranks = activeRanks.getOrDefault(position, new int[5]);
            sides.add(new SharedObservedProjectionSide(
                    activeHp.getOrDefault(position, -1),
                    activeMaximumHp.getOrDefault(position, -1),
                    List.of(
                            layers.getOrDefault("stealthrock", 0),
                            layers.getOrDefault("spikes", 0),
                            layers.getOrDefault("toxicspikes", 0),
                            layers.getOrDefault("stickyweb", 0)),
                    new SharedSearchPressure(
                            pressure.yawn(), pressure.yawnTurns(), pressure.saltCure(),
                            pressure.toxicCounter(), pressure.sleepTurns()),
                    Arrays.stream(ranks).boxed().toList(),
                    gimmickRemaining.get(side),
                    adapterStates.containsKey(position) ? Set.copyOf(adapterStates.get(position)) : null,
                    sideConditions(side)));
        }
        return new SharedProjectionObservation(currentTurn, sides, fieldState());
    }

    private void accept(String line) {
        if (line == null || !line.startsWith("|")) return;
        String[] parts = line.split("\\|", -1);
        if (parts.length < 2) return;
        switch (parts[1]) {
            case "turn" -> advanceTurn(parts);
            case "-sidestart" -> changeSideCondition(parts, true);
            case "-sideend" -> changeSideCondition(parts, false);
            case "-swapsideconditions" -> swapSideConditions();
            case "-weather" -> changeWeather(parts);
            case "-fieldstart" -> changeField(parts, true);
            case "-fieldend" -> changeField(parts, false);
            case "switch", "drag", "replace" -> resetForSwitch(parts);
            case "-start" -> {
                changeVolatile(parts, true);
                observeAdapterStart(parts);
            }
            case "-end" -> changeVolatile(parts, false);
            case "-status" -> applyStatus(parts);
            case "-curestatus" -> cureStatus(parts);
            case "-cureteam" -> cureTeam(parts);
            case "-damage" -> {
                updateCondition(parts, 3);
                advanceToxicCounter(parts);
            }
            case "-heal" -> updateCondition(parts, 3);
            case "-boost" -> changeRank(parts, 1);
            case "-unboost" -> changeRank(parts, -1);
            case "-setboost" -> setRank(parts);
            case "-clearboost" -> clearRanks(parts);
            case "-clearallboost" -> activeRanks.values().forEach(ranks -> Arrays.fill(ranks, 0));
            case "-terastallize", "-mega", "-burst", "-dynamax" -> markGimmickUsed(parts);
            case "-transform" -> addAdapterState(positionId(parts[2]), "transformed");
            case "-ability" -> observeAbility(parts);
            case "-activate" -> observeActivation(parts);
            case "-item" -> observeFrisk(parts);
            case "-formechange", "detailschange" -> observeFormChange(parts);
            default -> {
            }
        }
    }

    private void advanceTurn(String[] parts) {
        if (parts.length < 3) return;
        int nextTurn;
        try {
            nextTurn = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ignored) {
            return;
        }
        int elapsed = currentTurn == 0 ? 0 : Math.max(0, nextTurn - currentTurn);
        currentTurn = Math.max(currentTurn, nextTurn);
        if (elapsed == 0) return;
        for (int turn = 0; turn < elapsed; turn++) {
            weather = tick(weather);
            terrain = tick(terrain);
            pseudoWeather.replaceAll((id, effect) -> tick(effect));
            pseudoWeather.values().removeIf(java.util.Objects::isNull);
            sideConditions.values().forEach(effects -> {
                effects.replaceAll((id, effect) -> tick(effect));
                effects.values().removeIf(java.util.Objects::isNull);
            });
        }
        activePressure.replaceAll((position, pressure) -> pressure.yawn()
                ? new ActivePressure(
                        pressure.yawnTurns() - elapsed > 0,
                        Math.max(0, pressure.yawnTurns() - elapsed),
                        pressure.saltCure(),
                        pressure.toxicCounter(), pressure.sleepTurns())
                : pressure.sleepTurns() > 0
                        ? new ActivePressure(
                                pressure.yawn(), pressure.yawnTurns(), pressure.saltCure(),
                                pressure.toxicCounter(), Math.max(0, pressure.sleepTurns() - elapsed))
                        : pressure);
    }

    private void changeSideCondition(String[] parts, boolean start) {
        if (parts.length < 4) return;
        String side = sideId(parts[2]);
        String id = cleanId(parts[3]);
        if (side.isEmpty() || id.isEmpty()) return;
        Integer maximum = HAZARD_MAXIMUMS.get(id);
        if (maximum != null) {
            Map<String, Integer> layers = hazards.computeIfAbsent(side, ignored -> new HashMap<>());
            layers.put(id, start ? Math.min(maximum, layers.getOrDefault(id, 0) + 1) : 0);
            return;
        }
        Map<String, SharedSearchTimedEffect> effects =
                sideConditions.computeIfAbsent(side, ignored -> new HashMap<>());
        if (start) effects.put(id, timed(id)); else effects.remove(id);
    }

    private void swapSideConditions() {
        Map<String, Integer> first = new HashMap<>(hazards.getOrDefault("p1", Map.of()));
        Map<String, Integer> second = new HashMap<>(hazards.getOrDefault("p2", Map.of()));
        hazards.put("p1", second);
        hazards.put("p2", first);
        Map<String, SharedSearchTimedEffect> firstEffects =
                new HashMap<>(sideConditions.getOrDefault("p1", Map.of()));
        Map<String, SharedSearchTimedEffect> secondEffects =
                new HashMap<>(sideConditions.getOrDefault("p2", Map.of()));
        sideConditions.put("p1", secondEffects);
        sideConditions.put("p2", firstEffects);
    }

    private void changeWeather(String[] parts) {
        if (parts.length < 3) return;
        String id = cleanId(parts[2]);
        if (id.isEmpty() || id.equals("none")) {
            weather = null;
            return;
        }
        boolean upkeep = false;
        for (int index = 3; index < parts.length; index++) {
            if (parts[index].toLowerCase(Locale.ROOT).contains("upkeep")) upkeep = true;
        }
        if (!upkeep || weather == null || !weather.getId().equals(id)) weather = timed(id);
    }

    private void changeField(String[] parts, boolean start) {
        if (parts.length < 3) return;
        String id = cleanId(parts[2]);
        if (id.isEmpty()) return;
        if (id.endsWith("terrain")) {
            terrain = start ? timed(id) : null;
        } else if (start) {
            pseudoWeather.put(id, timed(id));
        } else {
            pseudoWeather.remove(id);
        }
    }

    private static SharedSearchTimedEffect timed(String id) {
        boolean persistent = id.equals("desolateland") || id.equals("primordialsea") || id.equals("deltastream");
        int turns = persistent ? Integer.MAX_VALUE : id.equals("tailwind") ? 4 : 5;
        return new SharedSearchTimedEffect(id, turns, persistent);
    }

    private static SharedSearchTimedEffect tick(SharedSearchTimedEffect effect) {
        if (effect == null || effect.getPersistent()) return effect;
        return effect.getTurns() > 1
                ? new SharedSearchTimedEffect(effect.getId(), effect.getTurns() - 1, false)
                : null;
    }

    private void resetForSwitch(String[] parts) {
        if (parts.length < 3) return;
        String position = positionId(parts[2]);
        if (position.isEmpty()) return;
        boolean toxic = parts.length >= 5 && containsStatus(parts[4], "tox");
        boolean sleep = parts.length >= 5 && containsStatus(parts[4], "slp");
        activePressure.put(position, new ActivePressure(false, 0, false, toxic ? 1 : 0, sleep ? 2 : 0));
        activeRanks.put(position, new int[5]);
        adapterStates.remove(position);
        updateCondition(parts, 4);
    }

    private void changeVolatile(String[] parts, boolean start) {
        if (parts.length < 4) return;
        String position = positionId(parts[2]);
        String effect = cleanId(parts[3]);
        if (position.isEmpty() || (!effect.equals("yawn") && !effect.equals("saltcure"))) return;
        ActivePressure current = pressure(position);
        activePressure.put(position, effect.equals("yawn")
                ? new ActivePressure(
                        start, start ? 2 : 0, current.saltCure(), current.toxicCounter(), current.sleepTurns())
                : new ActivePressure(
                        current.yawn(), current.yawnTurns(), start, current.toxicCounter(), current.sleepTurns()));
    }

    private void applyStatus(String[] parts) {
        if (parts.length < 4) return;
        String status = cleanId(parts[3]);
        if (!status.equals("tox") && !status.equals("slp")) return;
        String position = positionId(parts[2]);
        ActivePressure current = pressure(position);
        activePressure.put(position, new ActivePressure(
                current.yawn(), current.yawnTurns(), current.saltCure(),
                status.equals("tox") ? 1 : current.toxicCounter(),
                status.equals("slp") ? 2 : current.sleepTurns()));
    }

    private void cureStatus(String[] parts) {
        if (parts.length < 3) return;
        String position = positionId(parts[2]);
        ActivePressure current = pressure(position);
        activePressure.put(position, new ActivePressure(
                current.yawn(), current.yawnTurns(), current.saltCure(), 0, 0));
    }

    private void cureTeam(String[] parts) {
        if (parts.length < 3) return;
        String side = sideId(parts[2]);
        activePressure.replaceAll((position, current) -> position.startsWith(side)
                ? new ActivePressure(current.yawn(), current.yawnTurns(), current.saltCure(), 0, 0)
                : current);
    }

    private void advanceToxicCounter(String[] parts) {
        if (parts.length < 4) return;
        boolean toxicDamage = false;
        for (int index = 3; index < parts.length; index++) {
            String field = parts[index].toLowerCase(Locale.ROOT);
            if (field.contains("[from]") && (field.contains("psn") || field.contains("tox"))) {
                toxicDamage = true;
                break;
            }
        }
        if (!toxicDamage) return;
        String position = positionId(parts[2]);
        ActivePressure current = pressure(position);
        if (current.toxicCounter() <= 0) return;
        activePressure.put(position, new ActivePressure(
                current.yawn(), current.yawnTurns(), current.saltCure(),
                Math.min(15, current.toxicCounter() + 1), current.sleepTurns()));
    }

    private void updateCondition(String[] parts, int conditionIndex) {
        if (parts.length <= conditionIndex) return;
        String position = positionId(parts[2]);
        String token = parts[conditionIndex].trim().split("\\s+", 2)[0];
        String[] hp = token.split("/", -1);
        if (position.isEmpty() || hp.length != 2) return;
        try {
            activeHp.put(position, Integer.parseInt(hp[0]));
            activeMaximumHp.put(position, Integer.parseInt(hp[1]));
        } catch (NumberFormatException ignored) {
        }
    }

    private void changeRank(String[] parts, int direction) {
        if (parts.length < 5) return;
        String position = positionId(parts[2]);
        int index = rankIndex(parts[3]);
        if (position.isEmpty() || index < 0) return;
        try {
            int amount = Integer.parseInt(parts[4]) * direction;
            int[] ranks = activeRanks.computeIfAbsent(position, ignored -> new int[5]);
            ranks[index] = Math.max(-6, Math.min(6, ranks[index] + amount));
        } catch (NumberFormatException ignored) {
        }
    }

    private void setRank(String[] parts) {
        if (parts.length < 5) return;
        String position = positionId(parts[2]);
        int index = rankIndex(parts[3]);
        if (position.isEmpty() || index < 0) return;
        try {
            activeRanks.computeIfAbsent(position, ignored -> new int[5])[index] =
                    Math.max(-6, Math.min(6, Integer.parseInt(parts[4])));
        } catch (NumberFormatException ignored) {
        }
    }

    private void clearRanks(String[] parts) {
        if (parts.length < 3) return;
        int[] ranks = activeRanks.get(positionId(parts[2]));
        if (ranks != null) Arrays.fill(ranks, 0);
    }

    private void markGimmickUsed(String[] parts) {
        if (parts.length < 3) return;
        String side = sideId(parts[2]);
        if (!side.isEmpty()) gimmickRemaining.put(side, false);
    }

    private void observeAbility(String[] parts) {
        if (parts.length < 4) return;
        String position = positionId(parts[2]);
        String ability = cleanId(parts[3]);
        boolean trace = Arrays.stream(parts).map(ShowdownBattleLogObservation::cleanId)
                .anyMatch(value -> value.equals("trace"));
        if (trace && !ability.isEmpty()) addAdapterState(position, "traced:" + ability);
        if (ability.equals("anticipation")) addAdapterState(position, "anticipation");
        if (ability.equals("neutralizinggas")) addAdapterState(position, "neutralizinggas");
    }

    private void observeActivation(String[] parts) {
        if (parts.length < 4) return;
        String position = positionId(parts[2]);
        String effect = cleanId(parts[3]);
        if (effect.equals("forewarn") && parts.length >= 5) {
            addAdapterState(position, "forewarn:" + cleanId(parts[4]));
        }
        if (effect.equals("protosynthesis") || effect.equals("quarkdrive")) {
            addAdapterState(position, "paradox");
        }
        if (effect.equals("terashift")) {
            addAdapterState(position, "form:terapagosterastal");
        }
    }

    private void observeFrisk(String[] parts) {
        if (parts.length < 4) return;
        boolean frisk = Arrays.stream(parts).map(ShowdownBattleLogObservation::cleanId)
                .anyMatch(value -> value.equals("frisk"));
        if (!frisk) return;
        String source = Arrays.stream(parts)
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith("[of]"))
                .map(value -> positionId(value.substring(4).trim()))
                .filter(value -> !value.isEmpty())
                .findFirst().orElse("");
        addAdapterState(source, "frisked:" + cleanId(parts[3]));
    }

    private void observeAdapterStart(String[] parts) {
        if (parts.length < 4) return;
        String effect = cleanId(parts[3]);
        if (effect.startsWith("protosynthesis") || effect.startsWith("quarkdrive")) {
            addAdapterState(positionId(parts[2]), "paradox");
        }
    }

    private void observeFormChange(String[] parts) {
        if (parts.length < 4) return;
        String position = positionId(parts[2]);
        String form = cleanId(parts[3]);
        if (form.equals("terapagosterastal")) addAdapterState(position, "form:" + form);
        if (form.startsWith("castform")) addAdapterState(position, "forecast");
    }

    private void addAdapterState(String position, String state) {
        if (position.isEmpty() || state.isEmpty()) return;
        adapterStates.computeIfAbsent(position, ignored -> new java.util.HashSet<>()).add(state);
    }

    private static int rankIndex(String value) {
        return switch (cleanId(value)) {
            case "atk", "attack" -> 0;
            case "spa", "specialattack" -> 1;
            case "def", "defence", "defense" -> 2;
            case "spd", "specialdefence", "specialdefense" -> 3;
            case "spe", "speed" -> 4;
            default -> -1;
        };
    }

    private static boolean containsStatus(String condition, String status) {
        for (String token : condition.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.equals(status)) return true;
        }
        return false;
    }

    private static String sideId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(0, colon);
        return id.length() >= 2 && id.charAt(0) == 'p' && Character.isDigit(id.charAt(1))
                ? id.substring(0, 2) : "";
    }

    private static String positionId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(0, colon);
        return id.matches("p\\d[a-z]") ? id : "";
    }

    private static String cleanId(String value) {
        String id = value == null ? "" : value.toLowerCase(Locale.ROOT);
        int colon = id.lastIndexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        return id.replaceAll("[^a-z0-9]", "");
    }

    record ActivePressure(boolean yawn, int yawnTurns, boolean saltCure, int toxicCounter, int sleepTurns) {
        private static final ActivePressure NONE = new ActivePressure(false, 0, false, 0, 0);
    }
}
