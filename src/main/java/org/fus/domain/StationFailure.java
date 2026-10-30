package org.fus.domain;

/** An operation failure with a stable category, stage and tag-change indication. */
public final class StationFailure extends Exception {
    public final String category;
    public final String code;
    public final String stage;
    public final boolean tagMayHaveChanged;
    public StationFailure(String category, String code, String stage, boolean changed, String detail, Throwable cause) {
        super(detail, cause);
        this.category = category; this.code = code; this.stage = stage; this.tagMayHaveChanged = changed;
    }
    public String describe() {
        return "category=" + category + " code=" + code + " stage=" + stage
                + " tagMayHaveChanged=" + tagMayHaveChanged + " detail=" + getMessage();
    }
}
