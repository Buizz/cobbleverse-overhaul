package dev.buizz.cobbleventure.playermenu;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.logging.LogUtils;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/** Idempotent CVES money mutation journal stored with the affected player. */
public final class EventMoneyTransaction {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String JOURNAL_KEY = "cobbleventureEventMoneyTransactions";

    private EventMoneyTransaction() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventMoneyTransaction::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_money_transaction")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("operation", StringArgumentType.string())
                        .then(Commands.argument("delta", StringArgumentType.word())
                            .then(Commands.argument("allowDebt", BoolArgumentType.bool())
                                .executes(context -> transact(
                                    EntityArgument.getPlayer(context, "player"),
                                    StringArgumentType.getString(context, "operation"),
                                    StringArgumentType.getString(context, "delta"),
                                    BoolArgumentType.getBool(context, "allowDebt")
                                ))))))
        );
    }

    private static int transact(
        ServerPlayer player, String operationId, String deltaValue, boolean allowDebt
    ) {
        if (operationId.isBlank()) return 0;
        final BigInteger delta;
        try {
            delta = new BigInteger(deltaValue);
        } catch (NumberFormatException error) {
            return 0;
        }
        if (delta.signum() == 0) return 0;

        ListTag journal = player.getPersistentData().getList(JOURNAL_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < journal.size(); index++) {
            CompoundTag entry = journal.getCompound(index);
            if (!operationId.equals(entry.getString("OperationId"))) continue;
            if (!delta.toString().equals(entry.getString("Delta"))) {
                LOGGER.error(
                    "CVES money operation was reused with another delta: player={}, operation={}",
                    player.getGameProfile().getName(), operationId
                );
                return 0;
            }
            if (entry.contains("AllowDebt")
                && allowDebt != entry.getBoolean("AllowDebt")) {
                LOGGER.error(
                    "CVES money operation was reused with another debt policy: player={}, operation={}",
                    player.getGameProfile().getName(), operationId
                );
                return 0;
            }
            return entry.getBoolean("Success") ? 1 : 0;
        }

        BigInteger before = PlayerExtensionKt.getCobbleDollars(player);
        boolean success = delta.signum() > 0 || allowDebt
            || before.compareTo(delta.negate()) >= 0;
        BigInteger after = success ? before.add(delta) : before;
        if (success) {
            PlayerExtensionKt.setCobbleDollars(player, after);
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("OperationId", operationId);
        entry.putString("Delta", delta.toString());
        entry.putBoolean("AllowDebt", allowDebt);
        entry.putBoolean("Success", success);
        entry.putString("Before", before.toString());
        entry.putString("After", after.toString());
        journal.add(entry);
        player.getPersistentData().put(JOURNAL_KEY, journal);
        return success ? 1 : 0;
    }
}
