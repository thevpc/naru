package net.thevpc.naru.api.store;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * The keyset cursor codec shared by the page stores. Public so that a store backend in
 * any module mints cursors with the same shape — but callers must treat the value as
 * opaque: it is the store's continuation, not something to parse, compare, or hand to
 * another query.
 *
 * <p>A cursor carries the sort tag, the last row's sort key, and the row id. The sort tag
 * is what makes a cursor belong to one ordering: reusing a cursor under a different sort
 * throws rather than silently restarting the traversal, because a silently re-based
 * cursor is how a listing skips rows.
 */
public final class NaruPageCursor {

    private NaruPageCursor() {
    }

    public static String encode(String sortTag, String sortKey, String id) {
        String raw = sortTag + "\n" + sortKey + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Fixed-width so that the lexicographic order matches the chronological one. */
    public static String instantKey(Instant instant) {
        if (instant == null) {
            // the "oldest" bucket: listings put null-modification rows last, so a null
            // key sorts after every real instant under descending order
            return "00000000000000000000.000000000";
        }
        return String.format("%020d.%09d", instant.getEpochSecond(), instant.getNano());
    }

    /** Decodes a cursor minted by {@code sortTag}, or null when there is no cursor. */
    public static String[] decode(String cursor, String sortTag) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", 3);
        } catch (IllegalArgumentException e) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid page cursor: %s", cursor));
        }
        if (parts.length != 3) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid page cursor: %s", cursor));
        }
        if (!parts[0].equals(sortTag)) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "this cursor was produced by a %s listing; it cannot page a %s listing — restart with a null cursor",
                    parts[0], sortTag));
        }
        return parts;
    }

    /** True when {@code (key,id)} lies strictly after the cursor position under the given direction. */
    public static boolean isAfter(String key, String id, String[] cursor, boolean descending) {
        int c = key.compareTo(cursor[1]);
        if (c != 0) {
            return descending ? c < 0 : c > 0;
        }
        return id.compareTo(cursor[2]) > 0;
    }
}
