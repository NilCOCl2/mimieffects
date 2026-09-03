package com.mimieffects.mixin;

import com.mimieffects.integration.MimiNoteBridge;
import io.github.tofodroid.mods.mimi.common.api.event.note.NoteEvent;
import io.github.tofodroid.mods.mimi.server.events.note.consumer.ServerNoteConsumerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerNoteConsumerManager.class, remap = false)
public final class ServerNoteConsumerManagerMixin {
    @Inject(method = "handleEvent", at = @At("HEAD"), remap = false)
    private static void mimieffects$onNote(NoteEvent note, CallbackInfo ci) {
        MimiNoteBridge.onMimiNote(note);
    }
}
