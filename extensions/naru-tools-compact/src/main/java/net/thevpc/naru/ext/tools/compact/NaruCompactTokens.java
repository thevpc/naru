package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;

import java.util.List;

/**
 * Cheap local token estimate, for decisions that must be made without a provider.
 *
 * <p>Only used where a provider is not available to answer: deciding whether auto-compaction
 * should fire before the request goes out, and sizing chunks when the input is larger than
 * the summarizer's window. Everywhere a provider has reported a real count, that number is
 * used instead -- see {@code NaruTaskImpl.chat}, which already prefers provider usage.
 *
 * <p>Characters divided by four. Crude, and deliberately so: it is O(n) with no model
 * dependency, which is the property that matters when the decision has to be made on every
 * request. The bias it carries is systematic in a safe direction -- real BPE output is a
 * little larger than four characters per token for prose, so this under-estimates and
 * compaction happens slightly later than it would with a tokenizer. Erring that way is
 * correct: a compaction triggered too eagerly is expensive and visible, one triggered a
 * little late costs nothing because the provider's own count is what the next check uses.
 */
public final class NaruCompactTokens {

    private static final int CHARS_PER_TOKEN = 4;

    private NaruCompactTokens() {
    }

    /**
     * Estimated size of one message.
     *
     * <p>Counts content, tool-call arguments and tool names. Deliberately skips thinking
     * segments: they are the largest single component of a reasoning model's history and are
     * dropped from the summarizer's input anyway, so counting them would inflate every
     * covered size and make every summary look worse than it is.
     */
    public static long estimate(NaruMessage message) {
        if (message == null) {
            return 0;
        }
        long chars = len(message.getContent()) + len(message.getToolName());
        if (message.getToolCalls() != null) {
            for (NaruToolCall call : message.getToolCalls()) {
                chars += len(call.getName());
                if (call.getArguments() != null) {
                    chars += len(NElementWriter.ofJson().compact(true)
                            .formatPlain(NElement.of(call.getArguments())));
                }
            }
        }
        if (message.getImages() != null) {
            // base64 is opaque to a character heuristic; a fixed cost is the honest answer
            for (String image : message.getImages()) {
                chars += 1000;
            }
        }
        // floored at one: a message costs role markers and separators on the wire even when it
        // is empty, and a history of many empty messages is not free
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }

    public static long estimate(List<NaruMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (NaruMessage m : messages) {
            total += estimate(m);
        }
        return total;
    }

    /**
     * Estimated size of a bare string, on the same four-characters-per-token basis.
     *
     * <p>Used for the parts of a request that are not messages: the system prompt, the
     * summarizer instructions, a rendered summary block. Counting these at all is the point
     * -- they are sent to the model on every call, so a window check that ignored them would
     * accept inputs the provider then rejects.
     */
    public static long estimate(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return Math.max(1, content.length() / CHARS_PER_TOKEN);
    }

    /** Estimated size of a whole request, including the parts that are not history. */
    public static long estimateRequest(NaruModelRequest request) {
        if (request == null) {
            return 0;
        }
        long total = estimate(request.messages());
        if (request.tools() != null) {
            for (NaruToolDefinition tool : request.tools()) {
                total += len(tool.getName()) / CHARS_PER_TOKEN;
                total += len(tool.getDescription()) / CHARS_PER_TOKEN;
            }
        }
        return total;
    }

    /**
     * Rough tokens per {@code chunks} when the content is split evenly.
     *
     * <p>Used to decide how many chunks a summarizer input needs. Even by design: a chunk
     * boundary is chosen by item count, and this only has to be right to within a factor,
     * because the real loop re-chunks based on the estimate and stops when the input fits.
     */
    public static long estimatePerItem(List<NaruMessage> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return estimate(items) / items.size();
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * Formats a token count for display, using the same abbreviations the budget report uses
     * so the two are comparable at a glance.
     */
    public static String format(long tokens) {
        if (tokens < 1000) {
            return Long.toString(tokens);
        }
        if (tokens < 1_000_000) {
            return String.format("%.1fk", tokens / 1000d);
        }
        return String.format("%.1fM", tokens / 1_000_000d);
    }
}