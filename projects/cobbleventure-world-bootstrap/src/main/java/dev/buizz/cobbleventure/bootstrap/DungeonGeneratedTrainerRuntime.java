package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.RCTApi;
import com.gitlab.srcmc.rctapi.api.ai.RCTBattleAI;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerBag;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;

/** Registers a run-scoped TBCS trainer assembled from a dungeon Pokemon pool. */
final class DungeonGeneratedTrainerRuntime {
    private static final String TBCS_REGISTRY = "tbcs";

    private DungeonGeneratedTrainerRuntime() {}

    static String register(
        UUID runId,
        String encounterId,
        int opponentIndex,
        String displayName,
        DungeonGeneratedTrainer.Result generated,
        LivingEntity entity
    ) {
        RCTApi api = RCTApi.getInstance(TBCS_REGISTRY);
        if (api == null) {
            throw new IllegalStateException("TBCS trainer registry is not available");
        }
        Pokemon[] team = generated.team().stream().map(member ->
            PokemonProperties.Companion.parse(
                member.species() + " level=" + member.level()
            ).create()
        ).toArray(Pokemon[]::new);
        String safeEncounter = encounterId.replaceAll("[^a-z0-9_.-]", "_");
        String trainerId = "cobbleventure:dungeon_generated/"
            + runId.toString().replace("-", "") + "/" + safeEncounter
            + "/" + opponentIndex;
        TrainerNPC trainer = new TrainerNPC(
            displayName, team, new TrainerBag(), new RCTBattleAI(), entity
        );
        api.getTrainerRegistry().registerNPC(trainerId, trainer);
        return trainerId;
    }

    static void unregister(String trainerId) {
        RCTApi api = RCTApi.getInstance(TBCS_REGISTRY);
        if (api != null) api.getTrainerRegistry().unregisterById(trainerId);
    }
}
