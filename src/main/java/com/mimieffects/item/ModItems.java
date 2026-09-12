package com.mimieffects.item;

import com.mimieffects.MimiEffectsMod;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MimiEffectsMod.MOD_ID);

    /** Never craftable — see NoteScrollItem's javadoc for why (obtained via villager trade or creative only). */
    public static final DeferredItem<NoteScrollItem> NOTE_SCROLL =
            ITEMS.register("note_scroll", () -> new NoteScrollItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
