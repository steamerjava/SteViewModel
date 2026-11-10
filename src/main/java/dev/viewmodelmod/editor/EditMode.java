package dev.viewmodelmod.editor;

public enum EditMode {
    MOVE("MOVE"),
    ROTATE("ROTATE");

    private final String label;

    EditMode(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public EditMode next() {
        return this == MOVE ? ROTATE : MOVE;
    }
}
