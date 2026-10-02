package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * The default summarizer: an LLM call through the existing provider abstraction.
 *
 * <p>No HTTP of its own. It builds a one-message request and hands it to
 * {@link NaruTask#chat}, so a summarizer run is an ordinary model call on the same providers,
 * the same protocol handling and the same retry/audit path as any other. A summary that
 * failed is therefore as diagnosable as a failed chat, and adding a provider makes it
 * available for summarization with no change here.
 *
 * <p>The call carries no tools. A summarizer that could call tools could act on the
 * filesystem while reading a conversation, which is a capability this operation has no
 * business having.
 *
 * <h2>Too much input</h2>
 *
 * <p>An input larger than the summarizer's window is folded in chunks: summary-so-far plus
 * the next chunk, repeatedly. Chunk size is {@link #CHUNK_FRACTION} of the model's window,
 * leaving room for the running summary and the instructions. The final pass enforces
 * {@code maxTokens}, because a running summary does not naturally converge on a budget.
 */
public class LlmNaruSummarizer implements NaruSummarizer {

    /**
     * Fraction of the model's window a single chunk may occupy.
     *
     * <p>Sixty percent leaves room for the accumulated summary, the prompt and the model's
     * own output. A higher fraction would be cheaper in call count and would start failing
     * on the last chunk, where the running summary is longest and there is least slack.
     */
    public static final double CHUNK_FRACTION = 0.60d;

    private final NaruTask task;
    private final NaruModelKey model;
    private final long contextWindow;

    /** Set per call, so a reused summarizer reports the right thing. */
    private boolean lastCallFolded;

    /**
     * @param model the model to summarize with
     * @param contextWindow that model's usable window, used to size chunks
     */
    public LlmNaruSummarizer(NaruTask task, NaruModelKey model, long contextWindow) {
        this.task = task;
        this.model = model;
        this.contextWindow = contextWindow;
    }

    @Override
    public String summarize(String content, String focus, NaruSummaryLevel level, long maxTokens)
            throws Exception {
        if (content == null || content.isBlank()) {
            lastCallFolded = false;
            return "";
        }
        List<String> chunks = chunk(content);
        lastCallFolded = chunks.size() > 1;
        String summary;
        if (chunks.size() == 1) {
            summary = call(NaruSummaryPrompt.instruction(focus, level.name(), maxTokens), content);
        } else {
            summary = foldChunks(chunks, focus, maxTokens);
        }
        return enforceLength(summary, focus, level, maxTokens);
    }

    /**
     * Splits an input into chunks that fit the model.
     *
     * <p>Split on item boundaries where possible. The rendered text is a sequence of
     * {@code ## item N [role]} blocks, so a cut can land between two of them and each chunk
     * stays a coherent slice of the conversation rather than half an exchange.
     */
    List<String> chunk(String content) {
        long budget = chunkBudgetTokens();
        if (budget <= 0 || NaruCompactTokens.estimate(content) <= budget) {
            return List.of(content);
        }
        String[] blocks = content.split("(?=^## item )", -1);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (block.isEmpty()) {
                continue;
            }
            long blockTokens = NaruCompactTokens.estimate(block);
            if (current.length() > 0
                    && NaruCompactTokens.estimate(current.toString()) + blockTokens > budget) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(block);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        // A single item larger than the budget still goes through alone: it cannot be split
        // without destroying a message, and truncating it here would silently lose content
        // that the length-enforcement pass would rather see.
        return out.isEmpty() ? List.of(content) : out;
    }

    private long chunkBudgetTokens() {
        if (contextWindow <= 0) {
            return -1;
        }
        long budget = (long) (contextWindow * CHUNK_FRACTION);
        // below the floor a chunk is smaller than the prompt that describes it, and the
        // summarizer would be summarizing its own instructions
        return Math.max(budget, 512L);
    }

    /** Rolling summary: each pass folds one more chunk into what has been summarized so far. */
    private String foldChunks(List<String> chunks, String focus, long maxTokens) throws Exception {
        String running = null;
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            StringBuilder input = new StringBuilder();
            if (running != null) {
                input.append("## summary so far\n").append(running).append("\n\n");
            }
            input.append(chunks.get(i));
            String instruction = running == null
                    ? NaruSummaryPrompt.instruction(focus, NaruSummaryLevel.NORMAL.name(), maxTokens)
                    : NaruSummaryPrompt.chunkInstruction(focus, maxTokens, i + 1, total);
            running = call(instruction, input.toString()).trim();
        }
        return running;
    }

    /**
     * Brings a summary under the cap.
     *
     * <p>Three steps, in increasing destructiveness, because each gives up something:
     * ask once more with a stricter instruction, then compact harder at the next level, and
     * only then cut the text and say so. Truncating is last because it silently loses the end
     * of the summary, which is usually the most recent work -- exactly the part an agent
     * cannot afford to lose.
     */
    private String enforceLength(String summary, String focus, NaruSummaryLevel level, long maxTokens)
            throws Exception {
        if (maxTokens <= 0 || summary == null) {
            return summary;
        }
        long actual = NaruCompactTokens.estimate(summary);
        if (actual <= maxTokens) {
            return summary;
        }
        // 1. one retry with an explicit instruction to shorten
        String shortened = call(NaruSummaryPrompt.shortenInstruction(maxTokens), summary).trim();
        if (NaruCompactTokens.estimate(shortened) <= maxTokens) {
            return shortened;
        }
        // 2. re-summarize the already-summarized text at the next stricter level, which drops
        //    more detail by design rather than by cutting mid-sentence
        NaruSummaryLevel stricter = level.stricter();
        if (stricter != level) {
            String harder = call(
                    NaruSummaryPrompt.instruction(focus, stricter.name(), maxTokens), shortened);
            if (NaruCompactTokens.estimate(harder) <= maxTokens) {
                return harder;
            }
            shortened = harder;
        }
        // 3. cut, and record that we did
        task.log(NaruLogMode.PROGRESS, NMsg.ofC(
                "compaction summary was %s tokens, over the %s target; truncating it and marking "
                        + "the summary as incomplete. The covered history is still intact.",
                NaruCompactTokens.format(actual), NaruCompactTokens.format(maxTokens)).asWarning());
        return truncateTo(shortened, maxTokens);
    }

    private static String truncateTo(String value, long maxTokens) {
        int maxChars = (int) Math.max(1, maxTokens) * 4;
        if (value.length() <= maxChars) {
            return value;
        }
        // cut at a line boundary near the limit: a summary truncated mid-sentence reads as
        // corruption, one truncated at a heading reads as an unfinished list
        int cut = value.lastIndexOf('\n', maxChars);
        if (cut < maxChars / 2) {
            cut = maxChars;
        }
        return value.substring(0, cut) + "\n[summary truncated at the token limit]";
    }

    /**
     * One summarizer request.
     *
     * <p>Built as system instruction plus user content, with no tools and no conversation
     * history: the content is passed in as the user turn rather than replayed, so the
     * summarizer's context is exactly what this call put there and nothing from the source
     * task leaks in.
     */
    private String call(String instruction, String content) throws Exception {
        NaruModelRequest request = new NaruModelRequest(
                List.of(
                        NaruMessage.system(instruction),
                        NaruMessage.user(content)
                ),
                List.of(),
                new java.util.LinkedHashMap<>()
        );
        NaruResponse response = task.chat(new NaruModelConfig(model), request);
        NaruMessage message = response == null ? null : response.getMessage();
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            throw new IllegalStateException(
                    "summarizer model " + model.provider() + "/" + model.model() + " returned no text");
        }
        return message.getContent();
    }

    /** The policy a call would render its input with, for reporting. */
    public static NaruToolOutputPolicy defaultPolicy(NaruSummaryLevel level) {
        return level.toolOutputs();
    }

    /** Whether the most recent {@link #summarize} needed more than one call to converge. */
    public boolean lastCallFolded() {
        return lastCallFolded;
    }
}