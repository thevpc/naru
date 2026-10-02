package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCopiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A single message in a chat conversation.
 * Roles: "system", "user", "assistant", "tool"
 */
public class NaruMessage implements NToElement, NCopiable,Cloneable {

    private String sourceName;
    private NaruSource source=NaruSource.USER;
    private NaruRole role;
    private String content;
    /**
     * Base64-encoded images (for multimodal/vision messages)
     */
    private List<String> images;
    /**
     * Tool call ID (used when role == "tool")
     */
    private String toolCallId;
    /**
     * Tool name (used when role == "tool")
     */
    private String toolName;
    /**
     * Tool calls requested by the assistant
     */
    private List<NaruToolCall> toolCalls;
    /**
     * Model reasoning/thinking channel content (e.g. reasoning_content, <think> blocks),
     * kept separate from the user-visible content.
     *
     * <p>Superseded by {@link #thinkingSegments}, which additionally records how
     * the reasoning was delimited, where it came from, and whether it finished.
     * Retained because a session written before segments existed carries its
     * reasoning here, and that history has to stay readable.
     */
    private String thinking;
    /**
     * Structured reasoning, one entry per contiguous stretch the model thought
     * for, in the order produced.
     *
     * <p>Null rather than empty for the overwhelmingly common case of a
     * non-reasoning model, so persisting an assistant turn that never thought
     * adds nothing to a session file.
     */
    private List<NaruThinkingSegment> thinkingSegments;
    /**
     * Marks the first message of a user turn.
     *
     * <p>Exists so the conversation can be cut into immutable segments for
     * prompt caching. A cacheable segment must never grow after it is created,
     * or its content hash changes and the provider is told to discard a prefix
     * it is still holding. Grouping on "starts a user turn" gives boundaries
     * that are stable across turns and survive a session reload, which
     * grouping on message position or count would not.
     *
     * <p>Not part of the wire format and not part of the cache content hash: it
     * describes how messages are grouped, not what the provider receives.
     */
    private boolean turnBoundary;

    /**
     * Store id of the summary item that stands in for this one, or null when the item is
     * part of the context view.
     *
     * <p>Exclusion is a flag on the covered item rather than a separate list of covered
     * ranges, and that is the whole design: writing an item is a local change to one
     * message, so inserting a summary costs one new file plus one rewrite per covered
     * message, whereas a range stored on the summary would need the summary's file to be
     * consulted to know what is in scope -- and the covered items could not be recognised
     * as excluded without it.
     *
     * <p>It also makes undo exact. Clearing the flags restores the previous context view
     * with no reconstruction step, because nothing was ever removed from the history.
     *
     * <p>Not part of the wire format and not part of the cache content hash: an excluded
     * message is not sent, so its content cannot affect what the provider caches. It is
     * persisted so that a reloaded session rebuilds the same context view without having to
     * re-derive it, and so that the exclusion survives being read by a build that does not
     * have the compaction extension installed.
     */
    private String excludedBy;
    /**
     * Summary metadata, non-null exactly when {@link #role} is
     * {@link NaruRole#summary}.
     */
    private NaruSummaryInfo summary;

    public NaruMessage() {
    }

    public NaruMessage(String sourceName, NaruSource source, NaruRole role, String content, List<String> images, String toolCallId, String toolName, List<NaruToolCall> toolCalls) {
        this.sourceName = sourceName;
        this.source = source;
        this.role = role;
        this.content = content;
        this.images = images;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolCalls = toolCalls;
    }
    /**
     * The same message with different visible content.
     *
     * <p>Everything else survives: the reasoning, its segmentation, the turn boundary and
     * the images. The Mistral protocol uses this to merge streamed deltas into one message
     * ({@code NaruModelProtocolMistral:151,171}), and the message it produces is still a
     * participant in the same conversation — dropping {@code turnBoundary} there would
     * silently move a prompt-cache boundary, and dropping the reasoning would lose the
     * model's own account of how it got to that content.
     *
     * <p>Tool calls are copied rather than shared, so the returned message is independent
     * of the original and cannot be mutated through it.
     */
    public NaruMessage withContent(String content) {
        NaruMessage m = new NaruMessage(sourceName, source, role, content,
                images == null ? null : new ArrayList<>(images),
                toolCallId,
                toolName, toolCalls == null ? null : toolCalls.stream()
                        .map(NaruToolCall::copy).collect(Collectors.toList()));
        m.thinking = thinking;
        m.thinkingSegments = thinkingSegments == null ? null
                : thinkingSegments.stream().map(NaruThinkingSegment::copy).collect(Collectors.toList());
        m.turnBoundary = turnBoundary;
        // deliberately shared, not copied: NaruSummaryInfo is immutable, and copying it per
        // message would be pure waste on the hot path that copies the whole context view
        m.excludedBy = excludedBy;
        m.summary = summary;
        return m;
    }

    public String getSourceName() {
        return sourceName;
    }

    public NaruMessage setSourceName(String sourceName) {
        this.sourceName = sourceName;
        return this;
    }

    public NaruSource getSource() {
        return source;
    }

    public NaruMessage setSource(NaruSource source) {
        this.source = source;
        return this;
    }

    @Override
    public NaruMessage copy() {
        return clone();
    }

    @Override
    protected NaruMessage clone() {
        NaruMessage e = null;
        try {
            e = (NaruMessage) super.clone();
            if(e.images!=null){
               e.images=new ArrayList<>(images);
            }
            if(e.toolCalls!=null){
               e.toolCalls=toolCalls.stream().map(x->x.copy()).collect(Collectors.toList());
            }
            if (e.thinkingSegments != null) {
                e.thinkingSegments = thinkingSegments.stream()
                        .map(NaruThinkingSegment::copy)
                        .collect(Collectors.toList());
            }
        } catch (CloneNotSupportedException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    public static NaruMessage of(NElement element) {
        if(element==null){
            return null;
        }
        if(element.isNull()){
            return null;
        }
        return new NaruMessage(element);
    }
    public NaruMessage(NElement element) {
        NObjectElement o = element.asObject().get();
        this.role = NaruRole.valueOf(o.getStringValue("role").get());
        this.content = o.getStringValue("content").orNull();
        this.toolCallId = o.getStringValue("toolCallId").orNull();
        this.toolName = o.getStringValue("toolName").orNull();
        this.sourceName = o.getStringValue("sourceName").orNull();
        // `source` is written only when it differs from the role-derived default, so a
        // session file written by an older version -- which never wrote it -- still reads
        // back with the same source the factory helpers would have given the message.
        String source1 = o.getStringValue("source").orNull();
        this.source = source1 == null ? defaultSourceFor(role) : NaruSource.valueOf(source1);
        this.turnBoundary = o.getBooleanValue("turnBoundary").orElse(false);
        this.excludedBy = o.getStringValue("excludedBy").orNull();
        NElement summary1 = o.get("summary").orNull();
        if (summary1 != null) {
            this.summary = NaruSummaryInfo.of(summary1);
        }
        NElement images1 = o.get("images").orNull();
        if (images1 != null && images1.isAnyArray()) {
            images = new ArrayList<>();
            for (NElement nElement : images1.asArray().get()) {
                images.add(nElement.asStringValue().orNull());
            }
        }
        NElement toolCalls1 = o.get("toolCalls").orNull();
        if (toolCalls1 != null && toolCalls1.isAnyArray()) {
            toolCalls = new ArrayList<>();
            for (NElement nElement : toolCalls1.asArray().get()) {
                toolCalls.add(new NaruToolCall(nElement));
            }
        }
        this.thinking = o.getStringValue("thinking").orNull();
        NElement thinkingSegments1 = o.get("thinkingSegments").orNull();
        if (thinkingSegments1 != null && thinkingSegments1.isAnyArray()) {
            thinkingSegments = new ArrayList<>();
            for (NElement nElement : thinkingSegments1.asArray().get()) {
                thinkingSegments.add(NaruThinkingSegment.of(nElement));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("role", role.name());
        o.set("content", content);
        o.set("toolName", toolName);
        if (toolCallId != null) {
            // omitted when null so existing session files stay byte-identical; without it
            // a reloaded tool message cannot be matched to the call that produced it
            o.set("toolCallId", toolCallId);
        }
        if (images != null) {
            o.set("images", NElement.ofStringArray(images.toArray(new String[0])));
        }
        if (sourceName != null) {
            o.set("sourceName", sourceName);
        }
        if (source != defaultSourceFor(role)) {
            // only a deviation is stored, so a message built by one of the factory helpers
            // below serializes exactly as it did before this field became persistable
            o.set("source", source.name());
        }
        if (toolCalls != null) {
            NArrayElementBuilder _toolCalls = NArrayElementBuilder.of();
            for (NaruToolCall call : toolCalls) {
                _toolCalls.add(call.toElement());
            }
            o.set("toolCalls", _toolCalls.build());
        }
        if (thinkingSegments != null && !thinkingSegments.isEmpty()) {
            // structured form wins when present: writing both would store the
            // reasoning text twice, and it is the larger of the two fields
            NArrayElementBuilder _thinkingSegments = NArrayElementBuilder.of();
            for (NaruThinkingSegment segment : thinkingSegments) {
                _thinkingSegments.add(segment.toElement());
            }
            o.set("thinkingSegments", _thinkingSegments.build());
        } else {
            // legacy sessions hold a single thinking string, and a model that
            // never reasoned must not add the key at all
            o.set("thinking", thinking);
        }
        if (turnBoundary) {
            // omitted when false so existing session files stay byte-identical
            o.set("turnBoundary", true);
        }
        if (excludedBy != null) {
            // omitted when null, for the same byte-compatibility reason as every other
            // optional key above: a message nobody has compacted is written exactly as it
            // was before excludedBy existed
            o.set("excludedBy", excludedBy);
        }
        if (summary != null) {
            o.set("summary", summary.toElement());
        }
        return o.build();
    }

    private NaruMessage(NaruRole role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * The source a message of this role has unless something said otherwise.
     *
     * <p>Mirrors the factory helpers below. It is what makes {@code source} omittable:
     * storing it unconditionally would add a key to every message ever persisted, and
     * storing nothing at all would lose the attribution of the roles that deviate.
     */
    private static NaruSource defaultSourceFor(NaruRole role) {
        if (role == null) {
            return NaruSource.USER;
        }
        switch (role) {
            case assistant:
                return NaruSource.ASSISTANT;
            case tool:
                return NaruSource.AGENT;
            case system:
                return NaruSource.SYSTEM;
            case summary:
                // A summary is derived from the conversation rather than authored in it, and
                // attributing it to the agent would read as "the agent wrote this", which is
                // not what happened.
                return NaruSource.SYSTEM;
            case user:
            default:
                return NaruSource.USER;
        }
    }

    // ── factory helpers ──────────────────────────────────────────────────────

    public static NaruMessage system(String content) {
        return new NaruMessage(NaruRole.system, content);
    }

    public static NaruMessage user(NMsg content) {
        return new NaruMessage(NaruRole.user, content.toString());
    }

    public static NaruMessage user(String content) {
        return new NaruMessage(NaruRole.user, content);
    }

    public static NaruMessage userWithImages(String content, List<String> base64Images) {
        NaruMessage m = new NaruMessage(NaruRole.user, content);
        m.images = new ArrayList<>(base64Images);
        return m;
    }

    public static NaruMessage assistant(String content) {
        return new NaruMessage(NaruRole.assistant, content).setSource(NaruSource.ASSISTANT);
    }

    public static NaruMessage assistantWithToolCalls(String content, List<NaruToolCall> calls) {
        NaruMessage m = new NaruMessage(NaruRole.assistant, content).setSource(NaruSource.ASSISTANT);
        m.toolCalls = new ArrayList<>(calls);
        return m;
    }

    public static NaruMessage tool(String toolName, String callId, String result) {
        NaruMessage m = new NaruMessage(NaruRole.tool, result).setSource(NaruSource.AGENT);
        m.toolName = toolName;
        m.toolCallId = callId;
        return m;
    }

    /**
     * A summary item: the text is the message content, the metadata says what it replaced.
     */
    public static NaruMessage summary(String text, NaruSummaryInfo summary) {
        NaruMessage m = new NaruMessage(NaruRole.summary, text);
        m.summary = summary;
        return m;
    }

    // ── getters / setters ────────────────────────────────────────────────────

    public NaruRole getRole() {
        return role;
    }

    public void setRole(NaruRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public List<NaruToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<NaruToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    /**
     * All reasoning as one string, regardless of how it was stored.
     *
     * <p>Derives from {@link #getThinkingSegments()} when segments are present,
     * so callers written against the original single-string field keep working
     * against messages produced by the structured path. Returns null when there
     * was no reasoning at all, which callers already treat as "did not think".
     */
    public String getThinking() {
        if (thinking != null) {
            return thinking;
        }
        if (thinkingSegments == null || thinkingSegments.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (NaruThinkingSegment segment : thinkingSegments) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(segment.getText());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Structured reasoning, or null when the model did not think.
     */
    public List<NaruThinkingSegment> getThinkingSegments() {
        return thinkingSegments == null || thinkingSegments.isEmpty()
                ? null
                : Collections.unmodifiableList(thinkingSegments);
    }

    /**
     * Whether this message carries any reasoning at all, in either form.
     */
    public boolean hasThinking() {
        return thinking != null || (thinkingSegments != null && !thinkingSegments.isEmpty());
    }

    public NaruMessage setThinkingSegments(List<NaruThinkingSegment> thinkingSegments) {
        this.thinkingSegments = thinkingSegments == null || thinkingSegments.isEmpty()
                ? null
                : new ArrayList<>(thinkingSegments);
        // the two forms are alternatives, not a pair; leaving a stale legacy
        // string behind would make the message serialize whichever it checks first
        this.thinking = null;
        return this;
    }

    /**
     * Appends one reasoning segment, assigning it the next position.
     */
    public NaruMessage addThinkingSegment(NaruThinkingSegment segment) {
        if (segment == null) {
            return this;
        }
        if (thinkingSegments == null) {
            thinkingSegments = new ArrayList<>();
        }
        int next = thinkingSegments.stream().mapToInt(NaruThinkingSegment::getIndex).max().orElse(-1) + 1;
        if (segment.getIndex() != next) {
            segment = new NaruThinkingSegment(next, segment.getText(), segment.getExtraction(),
                    segment.getProvider(), segment.getThinkingTokens(), segment.isComplete());
        }
        thinkingSegments.add(segment);
        this.thinking = null;
        return this;
    }

    public boolean isTurnBoundary() {
        return turnBoundary;
    }

    public NaruMessage setTurnBoundary(boolean turnBoundary) {
        this.turnBoundary = turnBoundary;
        return this;
    }

    /**
     * Store id of the summary item standing in for this one, or null when this item is
     * part of the context view.
     */
    public String getExcludedBy() {
        return excludedBy;
    }

    public NaruMessage setExcludedBy(String excludedBy) {
        this.excludedBy = excludedBy;
        return this;
    }

    /**
     * Whether this item has been folded into a summary and should not be sent to the model.
     *
     * <p>The item is still in the history, which is what every display, version and export
     * view reads. Only the context view consults this.
     */
    public boolean isExcluded() {
        return excludedBy != null;
    }

    /**
     * Summary metadata, non-null exactly when this is a {@code summary}-role item.
     *
     * <p>Defined on {@code naru-api} rather than in the compaction extension so that the
     * core can read and render a summary item in a session opened by a build that does not
     * have that extension installed.
     */
    public NaruSummaryInfo getSummary() {
        return summary;
    }

    public NaruMessage setSummary(NaruSummaryInfo summary) {
        this.summary = summary;
        return this;
    }

    public boolean isSummary() {
        return role == NaruRole.summary;
    }

    /**
     * Whether this item is a summary that is still standing in for what it covers.
     *
     * <p>Only an active summary is sent to the model, so this is the test that decides
     * whether a summary participates in the context view.
     */
    public boolean isActiveSummary() {
        return role == NaruRole.summary && summary != null && summary.isActive();
    }

    public NaruMessage setThinking(String thinking) {
        this.thinking = (thinking == null || thinking.isBlank()) ? null : thinking;
        return this;
    }

    /**
     * This message as plain user content, for the provider wire.
     *
     * <p>Used for {@link NaruRole#summary} items in the context view. A summary is a NARU
     * storage role, not one a provider's chat schema knows: a serializer that maps roles by
     * name would send {@code "role": "summary"} and be rejected with a message that mentions
     * nothing about compaction. So at the boundary the role becomes {@code user} and the
     * summary's own metadata is dropped -- the rendered text already carries the item count,
     * the trigger and the truncation flag, so keeping the structured copy would put the same
     * information on the wire twice.
     *
     * <p>Tool identity is cleared as well. It is meaningless for a summary, and a message
     * carrying a tool call id without the assistant turn that made the call is an orphaned
     * tool result, which several providers reject.
     *
     * <p>{@code turnBoundary} is kept as-is, so a summary does not silently move a
     * prompt-cache boundary: it sits at the position in the conversation where the compacted
     * span was.
     */
    public NaruMessage asPlainUserContent(String plainContent) {
        NaruMessage m = new NaruMessage(sourceName, NaruSource.USER, NaruRole.user, plainContent,
                images == null ? null : new ArrayList<>(images),
                null,
                null,
                null);
        m.turnBoundary = turnBoundary;
        return m;
    }

    /**
     * Drops both forms of reasoning from this message, leaving the answer.
     *
     * <p>Exists for the summarizer's input, where reasoning is cost with no benefit: it is
     * not reproduced in the summary, so including it only inflates the input and the number
     * of chunks needed. Deliberately a method rather than something callers do by hand,
     * because the two forms are alternatives -- clearing one and not the other would leave
     * the message still serializing the form that was not cleared.
     */
    public NaruMessage clearThinking() {
        this.thinking = null;
        this.thinkingSegments = null;
        return this;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "[" + role + "] " + (content != null ? content : "(tool-calls=" + toolCalls + ")");
    }
}
