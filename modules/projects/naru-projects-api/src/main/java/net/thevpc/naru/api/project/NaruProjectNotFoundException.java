package net.thevpc.naru.api.project;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

/**
 * No project carries the given id or name. Raised by id-or-name resolution when nothing
 * matches, and by the store when an id-addressed write finds no row.
 */
public class NaruProjectNotFoundException extends NIllegalArgumentException {

    private final String idOrName;

    public NaruProjectNotFoundException(String idOrName) {
        super(NMsg.ofC("project not found: %s", idOrName));
        this.idOrName = idOrName;
    }

    public String idOrName() {
        return idOrName;
    }
}
