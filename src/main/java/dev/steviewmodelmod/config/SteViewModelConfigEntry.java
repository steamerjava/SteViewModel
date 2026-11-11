package dev.viewmodelmod.config;

import java.util.Objects;

/** A named, dated set of the four first-person transforms. */
public class ViewModelConfigEntry {
    public String name = "Default";
    /** Epoch milliseconds; shown in the Configs tab. */
    public long createdAt = System.currentTimeMillis();
    public ViewModelTransform leftHand = new ViewModelTransform();
    public ViewModelTransform rightHand = new ViewModelTransform();
    public ViewModelTransform leftItem = new ViewModelTransform();
    public ViewModelTransform rightItem = new ViewModelTransform();

    public ViewModelConfigEntry() {
    }

    public ViewModelConfigEntry(String name) {
        this.name = name;
    }

    public void sanitize(String fallbackName) {
        this.name = this.name == null || this.name.isBlank() ? fallbackName : this.name.strip();
        if (this.createdAt <= 0L) {
            this.createdAt = System.currentTimeMillis();
        }
        this.leftHand = Objects.requireNonNullElseGet(this.leftHand, ViewModelTransform::new);
        this.rightHand = Objects.requireNonNullElseGet(this.rightHand, ViewModelTransform::new);
        this.leftItem = Objects.requireNonNullElseGet(this.leftItem, ViewModelTransform::new);
        this.rightItem = Objects.requireNonNullElseGet(this.rightItem, ViewModelTransform::new);
        this.leftHand.sanitize();
        this.rightHand.sanitize();
        this.leftItem.sanitize();
        this.rightItem.sanitize();
    }

    public void resetTransforms() {
        this.leftHand.reset();
        this.rightHand.reset();
        this.leftItem.reset();
        this.rightItem.reset();
    }

    /** Deep copy; the copy keeps the original creation date. */
    public ViewModelConfigEntry copy() {
        ViewModelConfigEntry copy = new ViewModelConfigEntry(this.name);
        copy.createdAt = this.createdAt;
        copy.leftHand = new ViewModelTransform(this.leftHand);
        copy.rightHand = new ViewModelTransform(this.rightHand);
        copy.leftItem = new ViewModelTransform(this.leftItem);
        copy.rightItem = new ViewModelTransform(this.rightItem);
        return copy;
    }

    /** Deep copy with a new name and a fresh creation date. */
    public ViewModelConfigEntry copyAs(String newName) {
        ViewModelConfigEntry copy = this.copy();
        copy.name = newName;
        copy.createdAt = System.currentTimeMillis();
        return copy;
    }
}
