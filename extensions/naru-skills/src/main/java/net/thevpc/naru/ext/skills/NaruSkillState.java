package net.thevpc.naru.ext.skills;

/**
 * The per-task state of a skill in the flat, non-inheriting selection model.
 * <p>
 * Every available skill is {@link #ADVERTISED} for a task unless the task explicitly
 * {@code /skill load}s it, which moves it to {@link #LOADED}. Advertising is the
 * progressive-disclosure half of the model: the model is told a skill exists (name and
 * description) but its body is injected only when the skill is loaded.
 */
public enum NaruSkillState {
    /** Discoverable: shown to the model with name and description, body not injected. */
    ADVERTISED,

    /** Selected for this task: the full body is injected, subject to the requires gate. */
    LOADED
}