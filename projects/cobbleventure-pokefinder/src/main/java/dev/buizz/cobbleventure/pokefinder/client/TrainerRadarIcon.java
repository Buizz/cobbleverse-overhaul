package dev.buizz.cobbleventure.pokefinder.client;

import java.util.List;

/** Cap, face and shoulders: a person silhouette distinct from Pokemon radar dots. */
final class TrainerRadarIcon {
    static final List<String> PIXELS = List.of(
        "..####...",
        "..#xx#...",
        ".######..",
        "..#xx#...",
        "..#xx#...",
        "...##....",
        "..#xx#...",
        ".#xxxx#..",
        ".######.."
    );

    private TrainerRadarIcon() {}
}
