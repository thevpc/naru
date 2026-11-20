package net.thevpc.naru.api.store;

/**
 * A keyset page request over session headers.
 *
 * <p>The cursor is opaque and encodes the sort position of the last returned row plus its
 * id, so a traversal resumed after concurrent writes neither repeats rows nor skips rows
 * that existed for the whole traversal. Cursors belong to the sort that minted them;
 * handing a cursor to a differently-sorted query is an error, not a restart.
 *
 * @param cursor        opaque continuation token, or null for the first page
 * @param sort          ordering; {@link NaruSessionSort#UPDATED_DESC} when null
 * @param projectFilter which sessions admit rows by their {@code projectId}
 * @param user          which sessions admit rows by their opaque {@code user}
 * @param limit         rows per page; default 50, hard maximum 500 (clamped)
 */
public record NaruSessionQuery(String cursor, NaruSessionSort sort,
                               NaruProjectFilter projectFilter, NaruUserFilter user, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public NaruSessionQuery {
        sort = sort == null ? NaruSessionSort.UPDATED_DESC : sort;
        projectFilter = projectFilter == null ? NaruProjectFilter.any() : projectFilter;
        user = user == null ? NaruUserFilter.any() : user;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    public static NaruSessionQuery first(int limit) {
        return new NaruSessionQuery(null, null, null, null, limit);
    }

    public static NaruSessionQuery first() {
        return first(DEFAULT_LIMIT);
    }

    /** The next page of the same traversal: identical filters and sort, resumed at {@code cursor}. */
    public NaruSessionQuery after(String cursor) {
        return new NaruSessionQuery(cursor, sort, projectFilter, user, limit);
    }
}
