package dev.buizz.cobbleventure.ai.api;

public interface WinProbabilityModel {
    WinEstimate estimate(WinProbabilityInput input);
}
