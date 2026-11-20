package net.thevpc.naru.api.store;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * Backend-neutral durable storage for one logical namespace.
 *
 * <p>Feature extensions use this contract and do not choose files, SQL, or a database
 * dialect themselves. Each successful write is durable before it returns. Related writes
 * that must be atomic belong in one {@link #transaction} call.
 */
public interface NaruDataStore extends AutoCloseable {
    NOptional<NElement> get(String key);

    void put(String key, NElement value);

    boolean delete(String key);

    /** Lists keys beginning with {@code prefix}, in stable lexical order. */
    List<String> keys(String prefix);

    /** Runs the callback atomically and durably. Nested transactions join the outer one. */
    <T> T transaction(NaruDataStoreWork<T> work);

    @Override
    default void close() {
    }
}
