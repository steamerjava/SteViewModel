package dev.viewmodelmod.editor;

import dev.viewmodelmod.ViewModelManager;
import dev.viewmodelmod.config.ViewModelTransform;

/** Handles smooth, delta-based dragging of a selected arm or item. */
public final class MouseDragHandler {
    private static final float TRANSLATION_UNITS_PER_PIXEL = 0.005F;
    private static final float ROTATION_DEGREES_PER_PIXEL = 0.45F;

    private boolean dragging;
    private EditorTarget target;
    private double startMouseX;
    private double startMouseY;
    private ViewModelTransform startTransform = new ViewModelTransform();

    public boolean isDragging() {
        return this.dragging;
    }

    public EditorTarget target() {
        return this.target;
    }

    public void begin(EditorTarget target, ViewModelTransform source, double mouseX, double mouseY) {
        this.dragging = true;
        this.target = target;
        this.startMouseX = mouseX;
        this.startMouseY = mouseY;
        this.startTransform = new ViewModelTransform(source);
    }

    public void update(
        ViewModelManager manager,
        double mouseX,
        double mouseY,
        EditMode mode,
        DragPlane plane,
        boolean fine
    ) {
        if (!this.dragging || this.target == null) {
            return;
        }

        ViewModelTransform targetTransform = this.target.transform(manager.activeConfig());
        double dx = mouseX - this.startMouseX;
        double dy = mouseY - this.startMouseY;
        float sensitivity = manager.config().dragSensitivity * (fine ? 0.25F : 1.0F);
        float translation = TRANSLATION_UNITS_PER_PIXEL * sensitivity;
        float rotation = ROTATION_DEGREES_PER_PIXEL * sensitivity;

        if (mode == EditMode.MOVE) {
            float x = this.startTransform.x;
            float y = this.startTransform.y;
            float z = this.startTransform.z;
            switch (plane) {
                case XY -> {
                    x += (float) dx * translation;
                    y -= (float) dy * translation;
                }
                case XZ -> {
                    x += (float) dx * translation;
                    z -= (float) dy * translation;
                }
                case ZY -> {
                    z += (float) dx * translation;
                    y -= (float) dy * translation;
                }
            }
            targetTransform.x = snap(x);
            targetTransform.y = snap(y);
            targetTransform.z = snap(z);
            targetTransform.x = clamp(targetTransform.x, -1.0F, 1.0F);
            targetTransform.y = clamp(targetTransform.y, -1.0F, 1.0F);
            targetTransform.z = clamp(targetTransform.z, -1.0F, 1.0F);
        } else {
            float rotX = this.startTransform.rotationX;
            float rotY = this.startTransform.rotationY;
            float rotZ = this.startTransform.rotationZ;
            switch (plane) {
                case XY -> {
                    rotX += (float) dy * rotation;
                    rotY += (float) dx * rotation;
                }
                case XZ -> {
                    rotX += (float) dy * rotation;
                    rotZ += (float) dx * rotation;
                }
                case ZY -> {
                    rotZ += (float) dx * rotation;
                    rotY += (float) dy * rotation;
                }
            }
            targetTransform.rotationX = clamp(snap(rotX), -180.0F, 180.0F);
            targetTransform.rotationY = clamp(snap(rotY), -180.0F, 180.0F);
            targetTransform.rotationZ = clamp(snap(rotZ), -180.0F, 180.0F);
        }

        manager.markDirty();
    }

    public void end() {
        this.dragging = false;
        this.target = null;
    }

    private static float snap(float value) {
        return Math.round(value * 1000.0F) / 1000.0F;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
