package dev.viewmodelmod.config;

/**
 * A first-person transform for one logical element (an arm or the item held in an arm).
 * Values are intentionally small and human editable in JSON.
 */
public class ViewModelTransform {
    public float x;
    public float y;
    public float z;
    public float scale = 1.0F;
    public float rotationX;
    public float rotationY;
    public float rotationZ;

    public ViewModelTransform() {
    }

    public ViewModelTransform(ViewModelTransform other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.scale = other.scale;
        this.rotationX = other.rotationX;
        this.rotationY = other.rotationY;
        this.rotationZ = other.rotationZ;
    }

    public void reset() {
        this.x = 0.0F;
        this.y = 0.0F;
        this.z = 0.0F;
        this.scale = 1.0F;
        this.rotationX = 0.0F;
        this.rotationY = 0.0F;
        this.rotationZ = 0.0F;
    }

    public boolean isIdentity() {
        return this.x == 0.0F
            && this.y == 0.0F
            && this.z == 0.0F
            && this.scale == 1.0F
            && this.rotationX == 0.0F
            && this.rotationY == 0.0F
            && this.rotationZ == 0.0F;
    }

    /** Fixes values from old or hand-edited config files. */
    public void sanitize() {
        this.x = clamp(this.x, -1.0F, 1.0F);
        this.y = clamp(this.y, -1.0F, 1.0F);
        this.z = clamp(this.z, -1.0F, 1.0F);
        this.scale = clamp(this.scale, 0.2F, 2.5F);
        this.rotationX = clamp(this.rotationX, -180.0F, 180.0F);
        this.rotationY = clamp(this.rotationY, -180.0F, 180.0F);
        this.rotationZ = clamp(this.rotationZ, -180.0F, 180.0F);
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) {
            return min == 0.0F ? 0.0F : min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
