package net.thevpc.naru.api.project;

import net.thevpc.naru.api.store.NaruUserFilter;

/**
 * A keyset page request over the project store.
 *
 * <p>The cursor is opaque: it encodes the sort position of the last returned row plus its
 * id, and it is minted by a store for a query with the same {@code sort}. Passing a cursor
 * produced under a different sort is a clear error, not a silent restart — a silently
 * re-based cursor is how a listing skips rows.
 *
 * @param cursor      opaque continuation token, or null for the first page
 * @param sort        ordering; {@link NaruProjectSort#UPDATED_DESC} when null
 * @param user        which owners admit rows: any, only {@code null}-user, or one specific
 * @param nameContains case-insensitive substring over the normalized name, or null
 * @param limit       rows per page; default 50, hard maximum 500 (clamped, not an error)
 */
public record NaruProjectQuery(String cursor, NaruProjectSort sort, NaruUserFilter user,
                               String nameContains, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public NaruProjectQuery {
        sort = sort == null ? NaruProjectSort.UPDATED_DESC : sort;
        user = user == null ? NaruUserFilter.any() : user;
        if (nameContains != null) {
            nameContains = nameContains.trim();
            if (nameContains.isEmpty()) {
                nameContains = null;
            }
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static NaruProjectQuery first(int limit) {
        return new NaruProjectQuery(null, null, null, null, limit);
    }

    public static NaruProjectQuery first() {
        return first(DEFAULT_LIMIT);
    }

    /** The next page of the same traversal: identical filters and sort, resumed at {@code cursor}. */
    public NaruProjectQuery after(String cursor) {
        return new NaruProjectQuery(cursor, sort, user, nameContains, limit);
    }
}
