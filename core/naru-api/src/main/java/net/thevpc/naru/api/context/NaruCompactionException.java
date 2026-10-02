package net.thevpc.naru.api.context;

/**
 * Thrown when a context could not be compacted.
 *
 * <p>Exists as its own type so a caller can tell "compaction failed" apart from any other
 * failure, and so it can be handled without string matching.
 *
 * <p>Its message is required to say what was attempted and why it did not work, because
 * the common cause -- no summarizer model is available, or every candidate failed -- is
 * something the user can fix and should not have to reverse engineer from a stack trace.
 */
public class NaruCompactionException extends RuntimeException {

    private final boolean sourceTouched;

    public NaruCompactionException(String message) {
        this(message, null, false);
    }

    public NaruCompactionException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public NaruCompactionException(String message, Throwable cause, boolean sourceTouched) {
        super(message, cause);
        this.sourceTouched = sourceTouched;
    }

    /**
     * Whether the source task was modified before the failure.
     *
     * <p>A correct compactor never sets this, and the test suite asserts it: compaction is
     * non-destructive by construction, and a caller that sees this flag knows the guarantee
     * was broken rather than guessing.
     */
    public boolean sourceTouched() {
        return sourceTouched;
    }

    /** The error raised when a caller needs compaction and no implementation is installed. */
    public static NaruCompactionException notInstalled() {
        return new NaruCompactionException(
                "compaction is not installed: no NaruContextCompactor implementation is on the "
                        + "classpath. Add the naru-tools-compact extension to enable /compact.");
    }
}