package net.thevpc.naru.api.project;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.List;

/**
 * An id-or-name reference matched several projects by name (names are unique only inside
 * one user namespace, and {@code idOrName} lookup searches all users). The candidate ids
 * are carried so the caller can disambiguate: directives print them, the UI offers them.
 */
public class NaruProjectAmbiguousException extends NIllegalArgumentException {

    private final String idOrName;
    private final List<String> candidateIds;

    public NaruProjectAmbiguousException(String idOrName, List<String> candidateIds) {
        super(NMsg.ofC("project name '%s' is ambiguous, matches projects: %s",
                idOrName, candidateIds), null, true, true);
        this.idOrName = idOrName;
        this.candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }

    public String idOrName() {
        return idOrName;
    }

    public List<String> candidateIds() {
        return candidateIds;
    }
}
