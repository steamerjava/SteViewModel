package dev.viewmodelmod.editor;

/** Screen-space rectangle and silhouette outline of a rendered first-person element. */
public final class HandBounds {
    public final float minX;
    public final float minY;
    public final float maxX;
    public final float maxY;
    /** Convex outline as x,y pairs, or null when no usable outline was captured. */
    public final float[] hull;
    public final long capturedAtNanos;

    public HandBounds(float minX, float minY, float maxX, float maxY) {
        this(minX, minY, maxX, maxY, null);
    }

    public HandBounds(float minX, float minY, float maxX, float maxY, float[] hull) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.hull = hull;
        this.capturedAtNanos = System.nanoTime();
    }

    public boolean contains(double x, double y, double margin) {
        return x >= this.minX - margin
            && x <= this.maxX + margin
            && y >= this.minY - margin
            && y <= this.maxY + margin;
    }

    public float centerX() {
        return (this.minX + this.maxX) * 0.5F;
    }

    public float centerY() {
        return (this.minY + this.maxY) * 0.5F;
    }

    public boolean isFresh() {
        return System.nanoTime() - this.capturedAtNanos < 250_000_000L;
    }
}
