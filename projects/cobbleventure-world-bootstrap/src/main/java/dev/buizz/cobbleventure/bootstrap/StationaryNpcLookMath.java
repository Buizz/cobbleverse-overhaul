package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.util.Mth;

final class StationaryNpcLookMath {
    private StationaryNpcLookMath() {}

    static float clampHeadYaw(float bodyYaw, float desiredYaw, float maxHeadYaw) {
        return Mth.wrapDegrees(bodyYaw + Mth.clamp(
            Mth.wrapDegrees(desiredYaw - bodyYaw), -maxHeadYaw, maxHeadYaw
        ));
    }

    static float approachAngle(float current, float target, float maxChange) {
        return Mth.wrapDegrees(Mth.approachDegrees(current, target, maxChange));
    }
}
