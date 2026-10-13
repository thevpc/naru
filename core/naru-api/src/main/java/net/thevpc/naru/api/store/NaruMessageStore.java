package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;

import java.util.List;

/** Message bodies owned by one task. Implementations must preserve duplicate messages. */
public interface NaruMessageStore {
    NaruStoredMessage append(NaruMessage message, String idempotencyKey);
    NaruStoredMessage insertAfter(String id, NaruMessage message, String idempotencyKey);
    void delete(List<String> ids);
    List<NaruStoredMessage> get(List<String> ids);
    NaruMessagePage query(NaruMessageQuery query);
}
