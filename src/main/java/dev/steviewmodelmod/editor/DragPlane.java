package dev.viewmodelmod.editor;

public enum DragPlane {
    XY("XY"),
    XZ("XZ"),
    ZY("ZY");

    private final String label;

    DragPlane(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public DragPlane next() {
        return values()[(this.ordinal() + 1) % values().length];
    }
}
