package net.thevpc.naru.api.spawn;

import java.util.Objects;

/**
 * A value resolved for a spawn together with the {@link NaruSpawnSource source} that
 * produced it, so the {@code TaskSpawned} event and {@code /start --explain} can show
 * where every item of the resolved set came from.
 *
 * @param <T> the item type (tag names, exclusions, skill names, env values, ...)
 */
public final class NaruSpawnSeed<T> {

    private final T value;
    private final NaruSpawnSource source;

    private NaruSpawnSeed(T value, NaruSpawnSource source) {
        this.value = value;
        this.source = Objects.requireNonNull(source, "source");
    }

    public static <T> NaruSpawnSeed<T> of(T value, NaruSpawnSource source) {
        return new NaruSpawnSeed<>(value, source);
    }

    public T value() {
        return value;
    }

    public NaruSpawnSource source() {
        return source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruSpawnSeed<?> that)) {
            return false;
        }
        return Objects.equals(value, that.value) && source == that.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, source);
    }

    @Override
    public String toString() {
        return String.valueOf(value) + " (" + source + ")";
    }
}