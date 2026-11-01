package net.thevpc.naru.api.project;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

/**
 * {@code (user, name)} is unique — names compared case-insensitively after normalization,
 * and {@code user = null} is its own namespace. Creation and any patch that changes
 * {@code name} or {@code user} re-checks the pair; this is what a violation throws.
 * {@code folder} is <em>not</em> unique: two projects may point at the same folder.
 */
public class NaruProjectNameConflictException extends NIllegalArgumentException {

    private final String user;
    private final String name;
    private final String existingProjectId;

    public NaruProjectNameConflictException(String user, String name, String existingProjectId) {
        super(NMsg.ofC("project name '%s' already taken%s (by %s)",
                name, user == null ? "" : " for user " + user, existingProjectId));
        this.user = user;
        this.name = name;
        this.existingProjectId = existingProjectId;
    }

    /** The owner namespace of the clash; null means the no-user namespace. */
    public String user() {
        return user;
    }

    public String name() {
        return name;
    }

    public String existingProjectId() {
        return existingProjectId;
    }
}
