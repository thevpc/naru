package net.thevpc.naru.api.project;

import java.util.List;

/**
 * The value of a service operation plus the warnings that did not fail it.
 *
 * <p>Today the standard warning is "folder does not exist": a project may legally point
 * at an unmounted directory, so the write succeeds and the caller is told. A warning never
 * replaces a value; it travels beside it.
 */
public record NaruProjectResult<T>(T value, List<String> warnings) {

    public NaruProjectResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static <T> NaruProjectResult<T> of(T value) {
        return new NaruProjectResult<>(value, List.of());
    }

    public static <T> NaruProjectResult<T> of(T value, List<String> warnings) {
        return new NaruProjectResult<>(value, warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
