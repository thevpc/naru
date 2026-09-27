package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Objects;

/**
 * The delimiters a model wraps its reasoning in, when it puts reasoning in the
 * answer text instead of a field of its own.
 *
 * <p>There is no standard for this. One vendor emits {@code <think>}, another
 * {@code <reasoning>}, a third relies on a tool call. A model configured through
 * a custom or generic provider may use anything, so the pair is configurable and
 * the protocol supplies a default.
 *
 * <p>{@link #NATIVE_ONLY} exists for models that do not delimit reasoning at
 * all: parsing tags on those is not merely useless but actively harmful, since
 * a model that happens to discuss {@code <think>} in its answer would have that
 * text silently removed from the user's view.
 */
public final class NaruThinkingTags implements NToElement {

    /**
     * Do not split reasoning out of the answer text; expect it only as a
     * provider-native field, and if none arrives there is no reasoning to show.
     */
    public static final NaruThinkingTags NATIVE_ONLY = new NaruThinkingTags(null, null, false);

    private final String openTag;
    private final String closeTag;
    private final boolean enabled;

    private NaruThinkingTags(String openTag, String closeTag, boolean enabled) {
        this.openTag = openTag;
        this.closeTag = closeTag;
        this.enabled = enabled;
    }

    public static NaruThinkingTags of(String openTag, String closeTag) {
        boolean missing = NBlankable.isBlank(openTag) || NBlankable.isBlank(closeTag);
        if (missing) {
            // half a pair is a configuration mistake, not a request to guess:
            // silently defaulting would strip whichever half matched
            if (NBlankable.isBlank(openTag) != NBlankable.isBlank(closeTag)) {
                throw new NIllegalArgumentException(
                        NMsg.ofC("thinking tags must be given as a complete pair, got open=%s close=%s", openTag, closeTag));
            }
            return NATIVE_ONLY;
        }
        if (openTag.equals(closeTag)) {
            throw new NIllegalArgumentException(
                    NMsg.ofC("thinking open and close tags must differ, both were %s", openTag));
        }
        return new NaruThinkingTags(openTag, closeTag, true);
    }

    public static NaruThinkingTags of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (!element.isAnyObject()) {
            throw new NIllegalArgumentException(
                    NMsg.ofC("thinking tags must be an object with openTag and closeTag, got %s", element));
        }
        return of(
                element.asObject().get().getStringValue("openTag").orNull(),
                element.asObject().get().getStringValue("closeTag").orNull());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String openTag() {
        return openTag;
    }

    public String closeTag() {
        return closeTag;
    }

    /**
     * Builds a parser configured with this pair, or null when tag parsing is off
     * because the model uses a native reasoning channel instead.
     */
    public NaruThinkingTagParser newParser() {
        return enabled ? new NaruThinkingTagParser(openTag, closeTag) : null;
    }

    @Override
    public NElement toElement() {
        return NElement.ofObjectBuilder()
                .set("openTag", openTag)
                .set("closeTag", closeTag)
                .set("enabled", enabled)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruThinkingTags)) {
            return false;
        }
        NaruThinkingTags that = (NaruThinkingTags) o;
        return enabled == that.enabled
                && Objects.equals(openTag, that.openTag)
                && Objects.equals(closeTag, that.closeTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(openTag, closeTag, enabled);
    }

    @Override
    public String toString() {
        return enabled ? openTag + "..." + closeTag : "native-only";
    }
}
