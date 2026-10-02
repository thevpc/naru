package net.thevpc.naru.api.context;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;

import java.util.List;

/**
 * One request to compact a context view.
 *
 * <p>Built by the caller and passed to {@link NaruContextCompactor#compact}, so every
 * decision the caller made is visible to the compactor rather than implied by session
 * state. That is what lets a compactor be tested without a session, and what makes
 * {@link #apply()} a property of the call rather than a mode it was configured in.
 */
public final class NaruCompactionRequest {

    /**
     * The items a summary would cover, in order: the context view before the cut.
     *
     * <p>Not the raw history. A compactor must not see excluded items -- summarizing
     * content that a summary already stands in for would produce a summary of a summary
     * that says nothing new, and it is the clearest possible way to get compaction wrong.
     */
    private final List<NaruMessage> sourceView;

    /** The source task, for model selection, metering and the event. May be null in tests. */
    private final NaruTask sourceTask;

    private final NaruSession session;

    private final NaruContextSpec spec;

    /** Whether the summary should be written into the source task. */
    private final boolean apply;

    private final String trigger;

    /**
     * Index into the session store's ids where a summary item should be inserted, so the
     * item lands at the position of the content it replaces rather than at the end.
     *
     * <p>Plumbing rather than something a compactor computes: only the caller knows the
     * store's id layout.
     */
    private final String insertBeforeStoreId;

    /** First covered store id, inclusive. */
    private final String coversFromStoreId;

    /** Last covered store id, inclusive. */
    private final String coversToStoreId;

    private NaruCompactionRequest(Builder b) {
        this.sourceView = b.sourceView;
        this.sourceTask = b.sourceTask;
        this.session = b.session;
        this.spec = b.spec;
        this.apply = b.apply;
        this.trigger = b.trigger;
        this.insertBeforeStoreId = b.insertBeforeStoreId;
        this.coversFromStoreId = b.coversFromStoreId;
        this.coversToStoreId = b.coversToStoreId;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The context view a summary would cover. Never includes excluded items. */
    public List<NaruMessage> sourceView() {
        return sourceView;
    }

    public NaruTask sourceTask() {
        return sourceTask;
    }

    public NaruSession session() {
        return session;
    }

    public NaruContextSpec spec() {
        return spec;
    }

    /**
     * Whether to write the result into the source task: insert the summary item and flag the
     * covered items.
     *
     * <p>False for a caller that only wants the summary -- a fork building a child's
     * starting context, or a preview. A compactor that applied regardless would be
     * modifying a task it was asked to read.
     */
    public boolean apply() {
        return apply;
    }

    /** Why compaction was asked for; recorded on the item. */
    public String trigger() {
        return trigger;
    }

    public String insertBeforeStoreId() {
        return insertBeforeStoreId;
    }

    public String coversFromStoreId() {
        return coversFromStoreId;
    }

    public String coversToStoreId() {
        return coversToStoreId;
    }

    public static final class Builder {
        private List<NaruMessage> sourceView = List.of();
        private NaruTask sourceTask;
        private NaruSession session;
        private NaruContextSpec spec;
        private boolean apply;
        private String trigger;
        private String insertBeforeStoreId;
        private String coversFromStoreId;
        private String coversToStoreId;

        public Builder sourceView(List<NaruMessage> v) {
            this.sourceView = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder sourceTask(NaruTask v) {
            this.sourceTask = v;
            if (v != null) {
                this.session = v.session();
            }
            return this;
        }

        public Builder session(NaruSession v) {
            this.session = v;
            return this;
        }

        public Builder spec(NaruContextSpec v) {
            this.spec = v;
            return this;
        }

        public Builder apply(boolean v) {
            this.apply = v;
            return this;
        }

        public Builder trigger(String v) {
            this.trigger = v;
            return this;
        }

        public Builder insertBeforeStoreId(String v) {
            this.insertBeforeStoreId = v;
            return this;
        }

        public Builder coversFromStoreId(String v) {
            this.coversFromStoreId = v;
            return this;
        }

        public Builder coversToStoreId(String v) {
            this.coversToStoreId = v;
            return this;
        }

        public NaruCompactionRequest build() {
            if (spec == null) {
                throw new IllegalStateException("compaction request needs a spec");
            }
            return new NaruCompactionRequest(this);
        }
    }
}