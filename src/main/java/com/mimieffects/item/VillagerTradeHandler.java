package com.mimieffects.item;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.track.TrackConfig;
import io.github.tofodroid.mods.mimi.common.mob.villager.ModVillagers;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

import java.util.List;

/**
 * ADDED 2026-09-12 (user request): adds a Note Scroll trade to MIMI's own
 * "instrumentalist" villager profession, rather than inventing a new NPC —
 * see ModVillagers.INSTRUMENTALIST in the MIMI jar, already used for their
 * own hero-of-the-village gift. No crafting recipe exists for the scroll at
 * all (also user request) — trading (or creative) is the only way in.
 */
public final class VillagerTradeHandler {

    private static final int PRICE_EMERALDS = 6;
    private static final int MAX_USES = 4;
    private static final int XP_REWARD = 5;

    private VillagerTradeHandler() {
    }

    public static void onVillagerTrades(VillagerTradesEvent event) {
        if (event.getType() != ModVillagers.INSTRUMENTALIST) {
            return;
        }
        // Novice (level 1) so it's available immediately without needing to
        // level the villager up first — this is meant as an accessible
        // "buy the permit" trade, not a late-game reward.
        event.getTrades().get(1).add(VillagerTradeHandler::offerRandomTrackScroll);
    }

    /**
     * Picks a track fresh every time this trade is generated/rerolled (not
     * baked in once) — that way it always reflects whatever's currently in
     * TrackRegistry, including tracks added after this villager first
     * spawned. Returning null (no valid track yet) just means this
     * particular trade slot doesn't offer anything this time, which
     * VillagerTrades.ItemListing treats as normal.
     */
    private static MerchantOffer offerRandomTrackScroll(Entity trader, RandomSource random) {
        List<TrackConfig> candidates = MimiEffectsMod.TRACK_REGISTRY.all().stream()
                .filter(t -> t.track_id != null)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        TrackConfig chosen = candidates.get(random.nextInt(candidates.size()));
        ItemStack scroll = NoteScrollItem.forTrack(chosen.track_id);
        return new MerchantOffer(new ItemCost(Items.EMERALD, PRICE_EMERALDS), scroll, MAX_USES, XP_REWARD, 0.05F);
    }
}
