package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

/**
 * A typed, persistable piece of a chat message. The type is deliberately open ended so hosts
 * can register renderers for downloads, forms, choice prompts, images, and application widgets.
 * Payloads remain NARU elements and do not couple the message API to a UI toolkit.
 */
public record NaruMessagePart(String type, NElement payload) {
    public NaruMessagePart {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("message part type is required");
        payload = payload == null ? NElement.ofNull() : payload;
    }

    public NElement toElement() {
        return NObjectElementBuilder.of().set("type", type).set("payload", payload).build();
    }

    public static NaruMessagePart of(NElement element) {
        if (element == null || element.isNull()) return null;
        var object = element.asObject().get();
        return new NaruMessagePart(object.getStringValue("type").get(), object.get("payload").orNull());
    }
}
