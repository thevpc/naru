package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;

/** A message body together with its immutable store identity and creation sequence. */
public record NaruStoredMessage(String id, long creationSequence, NaruMessage message) {
}
