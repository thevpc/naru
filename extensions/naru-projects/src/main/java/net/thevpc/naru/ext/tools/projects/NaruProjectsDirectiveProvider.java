package net.thevpc.naru.ext.tools.projects;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

/**
 * Registers {@code /project}: management of the <b>project entity</b> (the grouping label
 * that supplies initial values to new sessions). Distinct from {@code /sessiondir}, which
 * changes the current session's anchor directory — the name hazard the two must not be
 * confused by: {@code /project} never touches any session's directory.
 */
public class NaruProjectsDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruProjectsDirectiveProvider() {
        super("projects");
        this.registerDirective(new NaruProjectDirective());
    }
}
