package dev.buizz.cobbleverse.ai.api;

public interface WinProbabilityModel {
    WinEstimate estimate(WinProbabilityInput input);
}
