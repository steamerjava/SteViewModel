package dev.viewmodelmod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.viewmodelmod.ViewModelMOD;
import dev.viewmodelmod.ViewModelManager;
import dev.viewmodelmod.config.ViewModelConfigEntry;
import dev.viewmodelmod.editor.EditorTarget;
import dev.viewmodelmod.editor.HandBounds;
import dev.viewmodelmod.render.ArmBoundsCalculator;
import dev.viewmodelmod.render.ViewModelRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies the hand and item transforms directly around the item draw call,
 * where vanilla has already positioned the hand. This is what keeps scaling
 * anchored to the hand instead of the camera origin.
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
    @WrapOperation(
        method = "renderFirstPersonItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem("
                + "Lnet/minecraft/entity/LivingEntity;"
                + "Lnet/minecraft/item/ItemStack;"
                + "Lnet/minecraft/client/render/model/json/ModelTransformationMode;"
                + "Z"
                + "Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/render/VertexConsumerProvider;"
                + "I)V"
        )
    )
    private void viewmodelmod$renderItem(
        HeldItemRenderer instance,
        LivingEntity entity,
        ItemStack stack,
        ModelTransformationMode renderMode,
        boolean leftHanded,
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        int light,
        Operation<Void> original
    ) {
        ViewModelManager manager = ViewModelMOD.getManager();
        if (manager == null) {
            original.call(instance, entity, stack, renderMode, leftHanded, matrices, vertexConsumers, light);
            return;
        }

        Arm arm = leftHanded ? Arm.LEFT : Arm.RIGHT;
        ViewModelConfigEntry config = manager.activeConfig();
        EditorTarget handTarget = EditorTarget.forArm(arm, false);
        EditorTarget itemTarget = EditorTarget.forArm(arm, true);

        matrices.push();
        // The hand transform moves the whole hand; the item transform then
        // fine-tunes the item on top of it.
        ViewModelRenderer.applyHand(matrices, handTarget.transform(config));
        ViewModelRenderer.applyItem(matrices, itemTarget.transform(config));

        if (manager.isEditorActive()) {
            HandBounds bounds = ArmBoundsCalculator.captureItem(matrices, stack, renderMode, leftHanded);
            if (bounds != null) {
                manager.updateHandBounds(itemTarget, bounds);
            } else {
                ArmBoundsCalculator.debugCaptureFailure(stack);
            }
        }

        original.call(instance, entity, stack, renderMode, leftHanded, matrices, vertexConsumers, light);
        matrices.pop();
    }
}
