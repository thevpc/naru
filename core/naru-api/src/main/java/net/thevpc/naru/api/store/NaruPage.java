package net.thevpc.naru.api.store;

import java.util.List;

/**
 * One page of a keyset traversal: the items and an opaque cursor to resume from.
 *
 * <p>{@code nextCursor} is null when the traversal is exhausted. A cursor encodes the
 * sort position of the last item returned plus its id, so resuming neither repeats nor
 * skips items that existed for the whole traversal, even under concurrent inserts — the
 * same contract the message pages already follow.
 */
public record NaruPage<T>(List<T> items, String nextCursor) {

    public NaruPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> NaruPage<T> of(List<T> items) {
        return new NaruPage<>(items, null);
    }
}
