package dev.viewmodelmod.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.viewmodelmod.ViewModelMOD;
import dev.viewmodelmod.editor.HandBounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects the actual arm cuboids or item quads to screen space. Next to the
 * bounding rectangle this produces a convex outline, which the editor uses to
 * draw a contour glow that follows the real silhouette instead of tinting the
 * whole model.
 */
public final class ArmBoundsCalculator {
    private ArmBoundsCalculator() {
    }

    public static HandBounds capture(MatrixStack matrices, ModelPart... parts) {
        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        MatrixStack copy = new MatrixStack();
        copy.multiplyPositionMatrix(matrices.peek().getPositionMatrix());
        Matrix4f mvpBase = projectionBase();
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        List<float[]> points = new ArrayList<>();

        for (ModelPart part : parts) {
            part.forEachCuboid(copy, (entry, path, index, cuboid) -> {
                Matrix4f mvp = new Matrix4f(mvpBase).mul(entry.getPositionMatrix());
                // Cuboid bounds are stored in model pixels and divided by 16 before
                // the part matrix is applied (see ModelPart.Cuboid.renderCuboid).
                float minX = cuboid.minX / 16.0F;
                float minY = cuboid.minY / 16.0F;
                float minZ = cuboid.minZ / 16.0F;
                float maxX = cuboid.maxX / 16.0F;
                float maxY = cuboid.maxY / 16.0F;
                float maxZ = cuboid.maxZ / 16.0F;

                for (int corner = 0; corner < 8; corner++) {
                    float x = (corner & 1) == 0 ? minX : maxX;
                    float y = (corner & 2) == 0 ? minY : maxY;
                    float z = (corner & 4) == 0 ? minZ : maxZ;
                    projectCorner(mvp, x, y, z, screenWidth, screenHeight, bounds, points);
                }
            });
        }

        return finish(bounds, points, screenWidth, screenHeight);
    }

    /**
     * Projects the held item to screen space. Baked item geometry lives in a
     * unit cube and is transformed by the model's first-person display
     * transform, so the real quad vertices are projected for a tight outline.
     * A small proxy box is used for builtin models, which have no baked quads.
     */
    public static HandBounds captureItem(
        MatrixStack matrices,
        ItemStack stack,
        ModelTransformationMode mode,
        boolean leftHanded
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        MatrixStack item = new MatrixStack();
        item.multiplyPositionMatrix(matrices.peek().getPositionMatrix());

        boolean projected = false;
        BakedModel model = null;
        if (stack != null && !stack.isEmpty()) {
            model = client.getItemRenderer().getModel(
                stack,
                client.world,
                client.player,
                client.player == null ? 0 : client.player.getId() + mode.ordinal()
            );
            MatrixStack display = new MatrixStack();
            model.getTransformation().getTransformation(mode).apply(leftHanded, display);
            display.translate(-0.5F, -0.5F, -0.5F);
            item.multiplyPositionMatrix(display.peek().getPositionMatrix());
        }

        Matrix4f mvp = new Matrix4f(projectionBase()).mul(item.peek().getPositionMatrix());
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        List<float[]> points = new ArrayList<>();

        if (model != null && !model.isBuiltin()) {
            Random random = Random.create(0L);
            for (BakedQuad quad : model.getQuads(null, null, random)) {
                projectQuad(mvp, quad, screenWidth, screenHeight, bounds, points);
            }
            for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
                for (BakedQuad quad : model.getQuads(null, direction, random)) {
                    projectQuad(mvp, quad, screenWidth, screenHeight, bounds, points);
                }
            }
            projected = isSane(bounds, points, screenWidth, screenHeight);
        }

        if (!projected) {
            // Builtin renderers (maps, shields, ...) and any model whose quads
            // could not be projected fall back to the unit cube that every item
            // model is baked into, so the item is always outlineable.
            for (int corner = 0; corner < 8; corner++) {
                float x = (corner & 1) == 0 ? 0.25F : 0.75F;
                float y = (corner & 2) == 0 ? 0.25F : 0.75F;
                float z = (corner & 4) == 0 ? 0.25F : 0.75F;
                projectCorner(mvp, x, y, z, screenWidth, screenHeight, bounds, points);
            }
        }

        if (points.isEmpty() && model != null) {
            debugCaptureFailure(stack);
        }

        return finish(bounds, points, screenWidth, screenHeight);
    }

    private static long lastFailureLogNanos;

    /**
     * Throttled debug diagnostic. The item outline now falls back to a proxy
     * box, so this only traces cases where even that produced no points.
     */
    public static void debugCaptureFailure(net.minecraft.item.ItemStack stack) {
        long now = System.nanoTime();
        if (now - lastFailureLogNanos < 1_500_000_000L) {
            return;
        }
        lastFailureLogNanos = now;
        ViewModelMOD.LOGGER.debug("No item outline could be projected for {}", stack.getItem());
    }

    /** Rejects projections that collapsed or exploded, so the proxy box is used instead. */
    private static boolean isSane(float[] bounds, List<float[]> points, int screenWidth, int screenHeight) {
        if (points.size() < 3) {
            return false;
        }
        float width = bounds[2] - bounds[0];
        float height = bounds[3] - bounds[1];
        if (!Float.isFinite(width) || !Float.isFinite(height) || width < 1.0F || height < 1.0F) {
            return false;
        }
        return width <= screenWidth * 4.0F + 64.0F && height <= screenHeight * 4.0F + 64.0F;
    }

    private static void projectQuad(
        Matrix4f mvp,
        BakedQuad quad,
        int screenWidth,
        int screenHeight,
        float[] bounds,
        List<float[]> points
    ) {
        int[] data = quad.getVertexData();
        int stride = 8;
        for (int offset = 0; offset + 2 < data.length; offset += stride) {
            projectCorner(
                mvp,
                Float.intBitsToFloat(data[offset]),
                Float.intBitsToFloat(data[offset + 1]),
                Float.intBitsToFloat(data[offset + 2]),
                screenWidth,
                screenHeight,
                bounds,
                points
            );
        }
    }

    /**
     * The projection used for the first-person hand: the world projection
     * combined with the view rotation that the hand matrix stack cancels out.
     */
    private static Matrix4f projectionBase() {
        return new Matrix4f(RenderSystem.getProjectionMatrix())
            .mul(ViewModelRenderer.viewMatrix());
    }

    private static HandBounds finish(float[] bounds, List<float[]> points, int screenWidth, int screenHeight) {
        if (!Float.isFinite(bounds[0]) || !Float.isFinite(bounds[1])
            || !Float.isFinite(bounds[2]) || !Float.isFinite(bounds[3])) {
            return null;
        }

        float[] hull = convexHull(points);
        return new HandBounds(
            Math.max(-16.0F, bounds[0]),
            Math.max(-16.0F, bounds[1]),
            Math.min(screenWidth + 16.0F, bounds[2]),
            Math.min(screenHeight + 16.0F, bounds[3]),
            hull
        );
    }

    /** Andrew monotone chain; returns x,y pairs or null for degenerate input. */
    private static float[] convexHull(List<float[]> points) {
        if (points.size() < 3) {
            return null;
        }

        List<float[]> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> a[0] == b[0]
            ? Float.compare(a[1], b[1])
            : Float.compare(a[0], b[0]));

        List<float[]> lower = new ArrayList<>();
        for (float[] point : sorted) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), point) <= 0.0F) {
                lower.remove(lower.size() - 1);
            }
            lower.add(point);
        }

        List<float[]> upper = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            float[] point = sorted.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), point) <= 0.0F) {
                upper.remove(upper.size() - 1);
            }
            upper.add(point);
        }

        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        if (lower.size() < 3) {
            return null;
        }

        float[] hull = new float[lower.size() * 2];
        for (int i = 0; i < lower.size(); i++) {
            hull[i * 2] = lower.get(i)[0];
            hull[i * 2 + 1] = lower.get(i)[1];
        }
        return hull;
    }

    private static float cross(float[] o, float[] a, float[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static void projectCorner(
        Matrix4f mvp,
        float x,
        float y,
        float z,
        int screenWidth,
        int screenHeight,
        float[] bounds,
        List<float[]> points
    ) {
        Vector4f point = new Vector4f(x, y, z, 1.0F);
        mvp.transform(point);
        if (point.w <= 0.00001F) {
            return;
        }
        float ndcX = point.x / point.w;
        float ndcY = point.y / point.w;
        float screenX = (ndcX * 0.5F + 0.5F) * screenWidth;
        float screenY = (0.5F - ndcY * 0.5F) * screenHeight;
        if (Math.abs(screenX) > screenWidth * 8.0F || Math.abs(screenY) > screenHeight * 8.0F) {
            return;
        }
        bounds[0] = Math.min(bounds[0], screenX);
        bounds[1] = Math.min(bounds[1], screenY);
        bounds[2] = Math.max(bounds[2], screenX);
        bounds[3] = Math.max(bounds[3], screenY);
        points.add(new float[]{screenX, screenY});
    }
}
