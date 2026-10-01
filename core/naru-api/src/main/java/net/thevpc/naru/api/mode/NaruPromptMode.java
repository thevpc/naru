package net.thevpc.naru.api.mode;

import net.thevpc.nuts.spi.NComponent;
import net.thevpc.nuts.util.NOptional;

import java.util.Set;

public interface NaruPromptMode extends NComponent {
    String DEFAULT = "default";
    String name();

    String[] aliases();

    String systemPrompt();

    boolean acceptToolTags(Set<String> tags);

    /**
     * What this mode is for, as far as a feature reacting to a mode switch is concerned.
     *
     * <p>{@link #acceptToolTags(Set)} alone cannot answer that question. A mode may
     * accept every tag simply because it has no opinion (the default mode does), which
     * looks identical to a mode that means to build things. A feature that wants to act
     * on a mode switch — picking up a plan when the user switches to an executing mode,
     * say — needs intent, not a permissions set.
     *
     * <p>Defaults to {@link ModeIntent#GENERAL} so existing modes keep working and a
     * mode that never declares an intent never triggers intent-driven behaviour.
     */
    default ModeIntent modeIntent() {
        return ModeIntent.GENERAL;
    }

    /**
     * The purpose a prompt mode declares for itself.
     */
    enum ModeIntent {
        /**
         * No particular purpose declared. The safe default: nothing about a mode switch
         * to or from this mode may be treated as a decision to act.
         */
        GENERAL,
        /**
         * The mode exists to produce a plan or design. Read-only by intent: entering it
         * must never be read as permission to execute anything.
         */
        PLANNING,
        /**
         * The mode exists to carry out decided work. Entering it is an explicit
         * "go ahead", which features may act on.
         */
        EXECUTING
    }
}