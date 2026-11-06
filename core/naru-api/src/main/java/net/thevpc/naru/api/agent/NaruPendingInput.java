package net.thevpc.naru.api.agent;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

/**
 * Public description of one pending task input.
 */
public record NaruPendingInput(
        @NProperty("taskId") long taskId,
        @NProperty("requestId") String requestId,
        @NProperty("prompt") String prompt
) implements NToElement {
    public static NOptional<NaruPendingInput> of(NElement any) {
        NOptional<NaruPendingInput> mapped = NElement.defaultFromElement(any, NaruPendingInput.class);
        if (!mapped.isPresent()) {
            return mapped;
        }
        // A field the element does not carry is not a mapping failure: the mapper leaves
        // taskId at 0, and a pending input addressed to task 0 would be delivered to whatever
        // task happens to hold that id. Only an input that names its task is an input.
        NElement taskId = any.asObject().orNull() == null ? null : any.asObject().get().get("taskId").orNull();
        if (taskId == null || taskId.isNull()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("pending input has no taskId"));
        }
        return mapped;
    }

    @Override
    public NElement toElement() {
        return NElement.defaultToNamedElement(this);
    }

}
