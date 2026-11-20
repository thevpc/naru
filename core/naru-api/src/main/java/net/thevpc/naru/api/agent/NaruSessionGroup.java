package net.thevpc.naru.api.agent;

import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

/**
 * A named group a new session can be created under, reduced to the three things the
 * session machinery actually consumes.
 *
 * <p>The kernel does not know what a "project" is, or that groups are stored anywhere, or
 * how they are named: it asks a {@link NaruSessionGroupResolver} to turn a reference into
 * this triple and then copies the values into the new session. A session that belongs to a
 * group keeps only the opaque {@code groupId} afterwards, never a live reference.
 *
 * @param id     the durable grouping label recorded on the session; never null
 * @param folder the directory to root the session in, or null to take the workspace default
 * @param user   the user name to seed the session with, or null to leave it unset
 */
public record NaruSessionGroup(String id, NPath folder, String user) {

    public NaruSessionGroup {
        if (id == null || id.isBlank()) {
            throw new net.thevpc.nuts.util.NIllegalArgumentException(
                    NMsg.ofC("a session group requires an id"));
        }
    }
}
