package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The cache key for one summary.
 *
 * <p>SHA-256 over the covered items' content and the options that change the output.
 *
 * <p>Two properties are deliberate and both are about not serving the wrong answer:
 *
 * <ul>
 *   <li><b>The model is not in the key.</b> Any model's summary of the same content is as good
 *       as another's, so two calls differing only in which model ran should share an entry.
 *       Which model actually produced a hit is recorded on the entry.</li>
 *   <li><b>Every covered item's content is in the key.</b> This is what makes an edited item a
 *       miss instead of a stale hit. A key built from item ids alone would keep returning a
 *       summary of content that no longer exists -- the one failure mode a cache must not
 *       have here.</li>
 * </ul>
 *
 * <p>The prompt template version is included for the same reason: a summary is a function of
 * the instructions that produced it, so changing them must invalidate every entry.
 */
public final class NaruCompactCacheKey {

    private NaruCompactCacheKey() {
    }

    /**
     * The key for a set of covered items under a set of options.
     *
     * @param covered  items whose content the summary describes, in order
     * @param options  the output-affecting options
     */
    public static String of(List<NaruMessage> covered, NaruSummaryOutputKey options) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1\n");
        sb.append("prompt=").append(NaruSummaryPrompt.TEMPLATE_VERSION).append('\n');
        sb.append("options=").append(options.outputKey()).append('\n');
        for (NaruMessage m : covered) {
            if (m == null) {
                continue;
            }
            // length-prefixed so two different item sequences cannot produce the same
            // concatenation, which a bare separator would let through
            sb.append("item(").append(m.getRole().id()).append(',');
            appendBounded(sb, m.getContent());
            sb.append(',');
            appendBounded(sb, m.getToolName());
            sb.append(',');
            appendBounded(sb, m.getToolCallId());
            sb.append(',').append(m.getToolCalls() == null ? 0 : m.getToolCalls().size());
            sb.append(")\n");
        }
        return sha256(sb.toString());
    }

    private static void appendBounded(StringBuilder sb, String value) {
        if (value == null) {
            sb.append(-1);
            return;
        }
        sb.append(value.length()).append(':').append(value);
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required for the compaction cache key", e);
        }
    }

    /**
     * The digest over the covered items alone, with no options.
     *
     * <p>Recorded on the summary item as {@code coveredContentHash} and recomputed to detect
     * that a covered item was edited after the summary was written. Separate from the cache
     * key on purpose: this one must not change when the options change, or a summary would
     * look edited merely because it was re-requested at a different level.
     */
    public static String contentHash(List<NaruMessage> covered) {
        StringBuilder sb = new StringBuilder();
        sb.append("content-v1\n");
        for (NaruMessage m : covered) {
            if (m == null) {
                continue;
            }
            sb.append("item(").append(m.getRole().id()).append(',');
            appendBounded(sb, m.getContent());
            sb.append(',');
            appendBounded(sb, m.getToolName());
            sb.append(',');
            appendBounded(sb, m.getToolCallId());
            sb.append(')');
        }
        return sha256(sb.toString());
    }

    /** The options subset that participates in the key, in a stable order. */
    public static class NaruSummaryOutputKey {
        private final String outputKey;

        public NaruSummaryOutputKey(String outputKey) {
            this.outputKey = outputKey;
        }

        public String outputKey() {
            return outputKey;
        }
    }

    /**
     * A digest over a prefix of the covered items, used to find a reusable earlier summary.
     *
     * <p>Length-prefixed per prefix so a digest for 3 items can never equal one for 30.
     */
    public static String prefixKey(String contentHash, int itemCount) {
        return sha256("prefix:" + itemCount + ':' + contentHash);
    }
}