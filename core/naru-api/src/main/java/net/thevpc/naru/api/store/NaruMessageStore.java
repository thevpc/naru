package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/** Message bodies owned by one task. Implementations must preserve duplicate messages. */
public interface NaruMessageStore {
    NaruStoredMessage append(NaruMessage message, String idempotencyKey);
    NaruStoredMessage insertAfter(String id, NaruMessage message, String idempotencyKey);
    void delete(List<String> ids);
    List<NaruStoredMessage> get(List<String> ids);
    NaruMessagePage query(NaruMessageQuery query);

    /**
     * Search message bodies in creation order without requiring the caller to load every page.
     * Implementations with an index may override this method; the default scans keyset pages.
     * The returned cursors can be passed to this method
     * again with the same search text and direction.
     */
    default NaruMessagePage search(String text, NaruMessageQuery query) {
        if (text == null || text.isBlank()) return query(query);
        String needle = text.trim().toLowerCase(Locale.ROOT);
        String cursor = query.cursor();
        List<NaruStoredMessage> matches = new ArrayList<>();
        boolean more = false;
        boolean exhausted = false;
        while (!exhausted && !more) {
            NaruMessageQuery pageQuery = new NaruMessageQuery(cursor, query.direction(),
                    Math.max(64, Math.min(512, query.limit())), query.roles(),
                    query.createdFrom(), query.createdTo());
            NaruMessagePage page = query(pageQuery);
            if (page.items().isEmpty()) break;
            for (NaruStoredMessage item : page.items()) {
                if (matchesText(item.message(), needle)) {
                    if (matches.size() == query.limit()) { more = true; break; }
                    matches.add(item);
                }
            }
            exhausted = !page.hasMore();
            if (more || exhausted) break;
            cursor = page.nextCursor();
        }
        String next = matches.isEmpty() ? null : matches.get(matches.size() - 1).id();
        String previous = matches.isEmpty() ? null : matches.get(0).id();
        return new NaruMessagePage(matches, next, previous, more);
    }

    private static boolean matchesText(net.thevpc.naru.api.model.NaruMessage message, String needle) {
        if (message == null) return false;
        String content = message.getFilteredContent();
        if (content != null && content.toLowerCase(Locale.ROOT).contains(needle)) return true;
        String name = message.getToolName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        for (var part : message.getParts()) {
            if (part.type().toLowerCase(Locale.ROOT).contains(needle)
                    || part.payload().toString().toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        if (message.getToolCalls() != null) {
            for (var call : message.getToolCalls()) {
                if (call.toElement().toString().toLowerCase(Locale.ROOT).contains(needle)) return true;
            }
        }
        return false;
    }
}
