package dev.viewmodelmod.mixin;

import dev.viewmodelmod.render.ViewModelRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the camera matrix of the current hand pass. {@code renderHand} starts
 * the hand matrix stack with the inverse of this exact matrix, so it is the only
 * value that can convert screen-space edits into the stack's parent space
 * without a one-frame lag or a leftover model view from the world pass.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "renderHand", at = @At("HEAD"))
    private void viewmodelmod$captureHandView(Camera camera, float tickDelta, Matrix4f view, CallbackInfo ci) {
        ViewModelRenderer.setHandView(view);
    }
}
