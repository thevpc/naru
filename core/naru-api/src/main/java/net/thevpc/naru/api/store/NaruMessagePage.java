package net.thevpc.naru.api.store;

import java.util.List;

/** One keyset page. Cursors point to immutable creation positions, never offsets. */
public record NaruMessagePage(List<NaruStoredMessage> items, String nextCursor,
                              String previousCursor, boolean hasMore) {
    public NaruMessagePage {
        items = List.copyOf(items);
    }
}
