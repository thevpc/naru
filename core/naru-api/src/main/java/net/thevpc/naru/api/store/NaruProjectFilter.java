package net.thevpc.naru.api.store;

/**
 * Session-query filter over the nullable {@code session.projectId}: any project (the
 * default), no project ({@code projectId == null}), or one specific project by id.
 *
 * <p>Matching is by id only, never by project name: the session store does not know
 * projects exist, and a name would have to be resolved by a component that can see both
 * stores ({@code NaruProjectService}).
 */
public final class NaruProjectFilter {

    private enum Mode {ANY, NONE, ID}

    private final Mode mode;
    private final String projectId;

    private NaruProjectFilter(Mode mode, String projectId) {
        this.mode = mode;
        this.projectId = projectId;
    }

    /** No constraint: sessions with or without a project. */
    public static NaruProjectFilter any() {
        return new NaruProjectFilter(Mode.ANY, null);
    }

    /** Standalone sessions only: {@code projectId == null}. */
    public static NaruProjectFilter none() {
        return new NaruProjectFilter(Mode.NONE, null);
    }

    /** Sessions of exactly one project. */
    public static NaruProjectFilter ofId(String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) {
            return none();
        }
        return new NaruProjectFilter(Mode.ID, projectId.trim());
    }

    public boolean matches(String sessionProjectId) {
        switch (mode) {
            case ANY:
                return true;
            case NONE:
                return sessionProjectId == null;
            default:
                return projectId.equals(sessionProjectId);
        }
    }

    public boolean isNone() {
        return mode == Mode.NONE;
    }

    public boolean isId() {
        return mode == Mode.ID;
    }

    public String idValue() {
        return projectId;
    }

    @Override
    public String toString() {
        return mode == Mode.ID ? "project(" + projectId + ')' : mode.name();
    }
}
