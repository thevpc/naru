package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.util.NaruSetting;

/**
 * The human-side edit of one plan item: each field is either {@link NaruSetting#unset()
 * unset} (leave alone) or {@link NaruSetting#set(Object) set}, and a set {@code null} is an
 * explicit clear.
 *
 * <p>An item carries three editable fields and the caller usually means one of them, so a
 * bare {@code null} would make "I only wanted to change the notes" read as "erase the
 * description". Same reasoning as {@code NaruProjectPatch}, whose type this reuses rather
 * than duplicating.
 *
 * <p>Status is deliberately absent. Readiness is derived from the dependency graph and
 * completion needs a validator verdict, so no patch may assert either —
 * {@link NaruPlanManager#reportItem} and {@code completeItem} own those transitions.
 */
public final class NaruPlanItemPatch {

    private NaruSetting<String> description = NaruSetting.unset();
    private NaruSetting<String> notes = NaruSetting.unset();
    private NaruSetting<NaruPlanValidatorKind> validator = NaruSetting.unset();

    private NaruPlanItemPatch() {
    }

    public static NaruPlanItemPatch of() {
        return new NaruPlanItemPatch();
    }

    public NaruSetting<String> description() {
        return description;
    }

    public NaruPlanItemPatch description(String description) {
        this.description = NaruSetting.set(description);
        return this;
    }

    public NaruSetting<String> notes() {
        return notes;
    }

    public NaruPlanItemPatch notes(String notes) {
        this.notes = NaruSetting.set(notes);
        return this;
    }

    /** Equivalent to {@code notes(null)}: the note is being removed, not left out. */
    public NaruPlanItemPatch clearNotes() {
        this.notes = NaruSetting.set(null);
        return this;
    }

    public NaruSetting<NaruPlanValidatorKind> validator() {
        return validator;
    }

    public NaruPlanItemPatch validator(NaruPlanValidatorKind validator) {
        this.validator = NaruSetting.set(validator);
        return this;
    }

    public boolean isEmpty() {
        return !description.isSet() && !notes.isSet() && !validator.isSet();
    }
}
