package dev.viewmodelmod.editor;

import dev.viewmodelmod.config.ViewModelConfigEntry;
import dev.viewmodelmod.config.ViewModelTransform;
import net.minecraft.util.Arm;

/** The four independently editable elements. */
public enum EditorTarget {
    LEFT_HAND(Arm.LEFT, false, "LEFT ARM"),
    RIGHT_HAND(Arm.RIGHT, false, "RIGHT ARM"),
    LEFT_ITEM(Arm.LEFT, true, "LEFT ITEM"),
    RIGHT_ITEM(Arm.RIGHT, true, "RIGHT ITEM");

    private final Arm arm;
    private final boolean item;
    private final String displayName;

    EditorTarget(Arm arm, boolean item, String displayName) {
        this.arm = arm;
        this.item = item;
        this.displayName = displayName;
    }

    public Arm arm() {
        return this.arm;
    }

    public boolean isItem() {
        return this.item;
    }

    public String displayName() {
        return this.displayName;
    }

    public ViewModelTransform transform(ViewModelConfigEntry entry) {
        return switch (this) {
            case LEFT_HAND -> entry.leftHand;
            case RIGHT_HAND -> entry.rightHand;
            case LEFT_ITEM -> entry.leftItem;
            case RIGHT_ITEM -> entry.rightItem;
        };
    }

    public EditorTarget withArm(Arm newArm) {
        return switch (newArm) {
            case LEFT -> this.item ? LEFT_ITEM : LEFT_HAND;
            case RIGHT -> this.item ? RIGHT_ITEM : RIGHT_HAND;
        };
    }

    public static EditorTarget forArm(Arm arm, boolean item) {
        return switch (arm) {
            case LEFT -> item ? LEFT_ITEM : LEFT_HAND;
            case RIGHT -> item ? RIGHT_ITEM : RIGHT_HAND;
        };
    }
}
