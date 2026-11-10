package dev.viewmodelmod.mixin;

import dev.viewmodelmod.editor.ViewModelEditorOverlay;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric's screen API intentionally has no char-typed event, so the editor gets
 * Unicode text through the keyboard's char callback. Input is only consumed
 * while one of the editor's value fields is focused.
 */
@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void viewmodelmod$onChar(long window, int codePoint, int modifiers, CallbackInfo ci) {
        if (ViewModelEditorOverlay.handleGlobalCharTyped(codePoint)) {
            ci.cancel();
        }
    }
}
