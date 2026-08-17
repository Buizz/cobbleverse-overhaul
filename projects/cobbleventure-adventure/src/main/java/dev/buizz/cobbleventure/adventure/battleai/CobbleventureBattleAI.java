package dev.buizz.cobbleventure.adventure.battleai;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.RCTApi;
import com.gitlab.srcmc.rctapi.api.ai.RCTBattleAI;
import com.gitlab.srcmc.rctapi.api.ai.config.RCTBattleAIConfig;
import com.gitlab.srcmc.rctapi.api.models.Gimmicks;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;

/**
 * In-game adapter for Cobbleventure AI profiles.
 *
 * <p>RCT owns the battle response protocol while this adapter applies the authored difficulty,
 * strategy and mechanic policy. This keeps generated content on a real registered AI type and
 * gives the independent decision engine a stable Minecraft boundary for later scoring ports.</p>
 */
public final class CobbleventureBattleAI extends RCTBattleAI {
    private final CobbleventureBattleAIConfig profile;

    CobbleventureBattleAI(
            CobbleventureBattleAIConfig profile,
            RCTBattleAIConfig rctConfig
    ) {
        super(rctConfig);
        this.profile = profile;
    }

    @Override
    public ShowdownActionResponse choose(
            ActiveBattlePokemon active,
            PokemonBattle battle,
            BattleSide side,
            ShowdownMoveset moveset,
            boolean forceSwitch
    ) {
        applyMechanicPolicy(active, moveset);
        if (usesJvmSearch()) {
            try {
                CobblemonBattleSearch.PlannedResponse planned = CobblemonBattleSearch.plan(
                        active,
                        side,
                        moveset,
                        profile.difficulty(),
                        profile.strategy(),
                        forceSwitch
                );
                if (planned != null && planned.response().isValid(active, moveset, forceSwitch)) {
                    return planned.response();
                }
            } catch (RuntimeException ignored) {
                // 불완전한 타 모드 전투 상태에서는 RCT의 검증된 기본 선택기로 안전 복귀한다.
            }
        }
        return super.choose(active, battle, side, moveset, forceSwitch);
    }

    private boolean usesJvmSearch() {
        return switch (profile.difficulty()) {
            case "expert_winrate", "expert_search", "cheater" -> true;
            default -> false;
        };
    }

    private void applyMechanicPolicy(
            ActiveBattlePokemon active,
            ShowdownMoveset moveset
    ) {
        CobbleventureBattleAIConfig.Mechanics mechanics = profile.mechanics();
        if (!mechanics.allowsMegaEvolution()) {
            moveset.setCanMegaEvo(false);
            moveset.setCanUltraBurst(false);
        }
        if (!mechanics.allowsZMove()) {
            moveset.setCanZMove(null);
        }
        if (!mechanics.allowsDynamax()) {
            moveset.setCanDynamax(false);
            moveset.setMaxMoves(null);
        }
        if (!mechanics.allowsTerastallization()) {
            moveset.setCanTerastallize(null);
        } else {
            resolveAutomaticTeraType(active);
        }
    }

    private static void resolveAutomaticTeraType(ActiveBattlePokemon active) {
        if (!active.hasPokemon()) {
            return;
        }
        Pokemon pokemon = active.getBattlePokemon().getOriginalPokemon();
        RCTApi.getInstances()
                .map(entry -> entry.getValue().getTrainerRegistry().getByOT(pokemon, TrainerNPC.class))
                .filter(trainer -> trainer != null)
                .findFirst()
                .ifPresent(trainer -> {
                    Gimmicks gimmicks = trainer.getGimmicks().of(pokemon);
                    if (gimmicks.tera() == null || !"auto".equalsIgnoreCase(gimmicks.tera())) {
                        return;
                    }
                    String primaryType = pokemon.getPrimaryType().getName();
                    trainer.getGimmicks().to(
                            pokemon,
                            new Gimmicks(primaryType, gimmicks.dynamax(), gimmicks.gmax())
                    );
                });
    }
}
