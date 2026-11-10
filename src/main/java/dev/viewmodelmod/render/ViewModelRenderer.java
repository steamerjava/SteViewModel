package dev.viewmodelmod.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.viewmodelmod.config.ViewModelTransform;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Applies stored transforms to the first-person matrix stack.
 *
 * <p>{@code GameRenderer.renderHand} starts the hand stack with
 * {@code inverse(viewRotation)}, so everything after that base is expressed in
 * a camera-relative frame: the camera transform is applied once more on the
 * way to the screen. User edits are defined in screen space, so an offset must
 * be rotated by {@code inverse(viewRotation)} before it is added to the
 * translation column. Otherwise the offset is scaled by the camera rotation and
 * the element swings across the screen whenever the player turns.</p>
 *
 * <p>The view matrix is captured by {@link dev.viewmodelmod.mixin.GameRendererMixin}
 * from the very matrix vanilla inverts for the stack base, so the conversion
 * matches the stack exactly instead of one frame behind.</p>
 */
public final class ViewModelRenderer {
    private static final Matrix4f HAND_VIEW = new Matrix4f();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();
    private static boolean handViewValid;

    private ViewModelRenderer() {
    }

    /** Called once per hand pass with the exact camera matrix vanilla uses. */
    public static void setHandView(Matrix4f view) {
        if (view != null) {
            HAND_VIEW.set(view);
            INVERSE_VIEW.set(view).invert();
            handViewValid = true;
        }
    }

    /** The camera matrix of the current hand pass, for projecting bounds. */
    public static Matrix4f viewMatrix() {
        ensureView();
        return new Matrix4f(HAND_VIEW);
    }

    /** Hand transform: translation, rotation and in-place scaling. */
    public static void applyHand(MatrixStack matrices, ViewModelTransform transform) {
        if (transform == null || transform.isIdentity()) {
            return;
        }

        applyRotation(matrices, transform);
        applyScale(matrices, transform);
        translateView(matrices, transform.x, transform.y, transform.z);
    }

    /** Item-only transform applied after the hand transform. */
    public static void applyItem(MatrixStack matrices, ViewModelTransform transform) {
        if (transform == null || transform.isIdentity()) {
            return;
        }

        applyRotation(matrices, transform);
        applyScale(matrices, transform);
        translateView(matrices, transform.x, transform.y, transform.z);
    }

    /**
     * Rotates the element around its current anchor. The camera-space rotation
     * is expressed in the stack's parent space as
     * {@code inverse(view) * rotation * view}, and the translation column is
     * restored so the element pivots in place instead of orbiting the camera.
     */
    private static void applyRotation(MatrixStack matrices, ViewModelTransform transform) {
        if (transform.rotationZ != 0.0F) {
            rotateInPlace(matrices, RotationAxis.POSITIVE_Z.rotationDegrees(transform.rotationZ));
        }
        if (transform.rotationY != 0.0F) {
            rotateInPlace(matrices, RotationAxis.POSITIVE_Y.rotationDegrees(transform.rotationY));
        }
        if (transform.rotationX != 0.0F) {
            rotateInPlace(matrices, RotationAxis.POSITIVE_X.rotationDegrees(transform.rotationX));
        }
    }

    private static void rotateInPlace(MatrixStack matrices, Quaternionf cameraRotation) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float x = matrix.m30();
        float y = matrix.m31();
        float z = matrix.m32();

        ensureView();
        Matrix4f parentRotation = new Matrix4f(INVERSE_VIEW)
            .mul(new Matrix4f().rotation(cameraRotation))
            .mul(HAND_VIEW);
        Matrix4f rotated = parentRotation.mul(matrix);
        rotated.m30(x);
        rotated.m31(y);
        rotated.m32(z);
        matrix.set(rotated);
    }

    /**
     * A right-multiplied uniform scale only touches the linear part of the
     * matrix, so the translation column (the element anchor) stays fixed and
     * the hand/item grows in place.
     */
    private static void applyScale(MatrixStack matrices, ViewModelTransform transform) {
        if (transform.scale != 1.0F) {
            matrices.scale(transform.scale, transform.scale, transform.scale);
        }
    }

    /**
     * Moves the element by a screen-space offset. Adding to the translation
     * column translates in the stack's parent space, so the offset is rotated
     * there first.
     *
     * <p>JOML stores {@code mXY} as column {@code X}, row {@code Y}: the {@code x}
     * component of a matrix-vector product is the dot product with row 0, which
     * is {@code (m00, m10, m20)}. Using {@code (m00, m01, m02)} instead would
     * apply the transpose, i.e. the camera rotation a second time, and the
     * offset would rotate with the camera.</p>
     */
    private static void translateView(MatrixStack matrices, float x, float y, float z) {
        if (x == 0.0F && y == 0.0F && z == 0.0F) {
            return;
        }

        ensureView();
        float offsetX = INVERSE_VIEW.m00() * x + INVERSE_VIEW.m10() * y + INVERSE_VIEW.m20() * z;
        float offsetY = INVERSE_VIEW.m01() * x + INVERSE_VIEW.m11() * y + INVERSE_VIEW.m21() * z;
        float offsetZ = INVERSE_VIEW.m02() * x + INVERSE_VIEW.m12() * y + INVERSE_VIEW.m22() * z;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        matrix.m30(matrix.m30() + offsetX);
        matrix.m31(matrix.m31() + offsetY);
        matrix.m32(matrix.m32() + offsetZ);
    }

    /** Fallback for the first frame, before the hand pass reports its camera. */
    private static void ensureView() {
        if (!handViewValid) {
            HAND_VIEW.set(RenderSystem.getModelViewMatrix());
            INVERSE_VIEW.set(HAND_VIEW).invert();
            handViewValid = true;
        }
    }
}
