package dev.viewmodelmod.mixin;

import dev.viewmodelmod.ViewModelMOD;
import dev.viewmodelmod.ViewModelManager;
import dev.viewmodelmod.editor.EditorTarget;
import dev.viewmodelmod.editor.HandBounds;
import dev.viewmodelmod.render.ArmBoundsCalculator;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the hand transform to the first-person arm and draws the optional
 * rim glow. These methods are only called by the first-person held item
 * renderer, so third-person players are never affected.
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
    @Inject(method = "renderRightArm", at = @At("HEAD"))
    private void viewmodelmod$beforeRightArm(
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        AbstractClientPlayerEntity player,
        CallbackInfo ci
    ) {
        this.viewmodelmod$applyHand(matrices, Arm.RIGHT);
    }

    @Inject(method = "renderLeftArm", at = @At("HEAD"))
    private void viewmodelmod$beforeLeftArm(
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        AbstractClientPlayerEntity player,
        CallbackInfo ci
    ) {
        this.viewmodelmod$applyHand(matrices, Arm.LEFT);
    }

    @Inject(method = "renderRightArm", at = @At("TAIL"))
    private void viewmodelmod$rightArm(
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        AbstractClientPlayerEntity player,
        CallbackInfo ci
    ) {
        this.viewmodelmod$afterArm(matrices, vertexConsumers, Arm.RIGHT);
    }

    @Inject(method = "renderLeftArm", at = @At("TAIL"))
    private void viewmodelmod$leftArm(
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        AbstractClientPlayerEntity player,
        CallbackInfo ci
    ) {
        this.viewmodelmod$afterArm(matrices, vertexConsumers, Arm.LEFT);
    }

    @Unique
    private void viewmodelmod$applyHand(MatrixStack matrices, Arm arm) {
        ViewModelManager manager = ViewModelMOD.getManager();
        if (manager != null) {
            manager.applyArmTransform(matrices, arm);
        }
    }

    @Unique
    private void viewmodelmod$afterArm(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Arm arm) {
        ViewModelManager manager = ViewModelMOD.getManager();
        if (manager == null || !manager.isEditorActive()) {
            return;
        }

        PlayerEntityModel<AbstractClientPlayerEntity> model = ((PlayerEntityRenderer) (Object) this).getModel();
        ModelPart armPart = arm == Arm.RIGHT ? model.rightArm : model.leftArm;
        ModelPart sleevePart = arm == Arm.RIGHT ? model.rightSleeve : model.leftSleeve;

        HandBounds bounds = ArmBoundsCalculator.capture(matrices, armPart, sleevePart);
        if (bounds != null) {
            manager.updateHandBounds(EditorTarget.forArm(arm, false), bounds);
        }
    }
}
