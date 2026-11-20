package net.thevpc.naru.api.store;

@FunctionalInterface
public interface NaruDataStoreWork<T> {
    T run(NaruDataStore store);
}
