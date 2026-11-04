package net.thevpc.naru.ext.budget;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What one <em>million</em> tokens cost, broken down by billing class.
 *
 * <p>All four numbers are per 1M tokens, in an unspecified currency (NARU does not
 * do currencies): the point of the type is that money never travels as
 * {@code double}. Callers parse from strings, multiply in {@link BigDecimal}, and
 * round only when they print.
 *
 * <p>{@code cacheRead} and {@code cacheWrite} are optional. A model that has no
 * cache-write class (OpenAI's implicit prefix caching, Gemini's cached content)
 * leaves it null rather than storing the input price: null means "not stated",
 * and the cost model substitutes {@link #input} while flagging the result
 * approximate. Storing input as the write price would erase that distinction and
 * make the flag impossible to compute.
 *
 * <p>{@code source}, {@code fetchedAt} and {@code origin} are provenance. A
 * {@code USER} price has no fetch time or origin; a {@code FETCHED} one records
 * both so a stale number can be told from a deliberate one.
 */
public record NaruUnitPrice(
        BigDecimal input,
        BigDecimal output,
        BigDecimal cacheRead,
        BigDecimal cacheWrite,
        NaruPriceSource source,
        Instant fetchedAt,
        String origin) {

    /**
     * A price above this per 1M tokens (so US$1000/1M) is implausible; it almost
     * always means a value was entered in the wrong field or unit. Rejected rather
     * than stored: a silent factor-of-a-million error is worse than a refusal.
     */
    public static final BigDecimal MAX_REASONABLE = new BigDecimal("1000");

    /**
     * A nonzero price below this per 1M tokens is almost certainly the per-token
     * figure. Accepted but warned about, because some genuinely cheap models are
     * legitimately small.
     */
    public static final BigDecimal MIN_REASONABLE = new BigDecimal("0.01");

    public NaruUnitPrice {
        if (input == null || output == null) {
            throw new IllegalArgumentException("a unit price needs both an input and an output price");
        }
        if (input.signum() < 0 || output.signum() < 0
                || (cacheRead != null && cacheRead.signum() < 0)
                || (cacheWrite != null && cacheWrite.signum() < 0)) {
            throw new IllegalArgumentException("unit prices must not be negative");
        }
        if (source == null) {
            source = NaruPriceSource.USER;
        }
    }

    /** A user-entered price: no fetch time, no origin. */
    public static NaruUnitPrice user(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {
        return new NaruUnitPrice(input, output, cacheRead, cacheWrite, NaruPriceSource.USER, null, null);
    }

    /** The price a cache read is billed at, defaulting to the input price when unset. */
    public BigDecimal cacheReadPrice() {
        return cacheRead != null ? cacheRead : input;
    }

    /** The price a cache write is billed at, defaulting to the input price when unset. */
    public BigDecimal cacheWritePrice() {
        return cacheWrite != null ? cacheWrite : input;
    }

    public boolean hasExplicitCacheRead() {
        return cacheRead != null;
    }

    public boolean hasExplicitCacheWrite() {
        return cacheWrite != null;
    }

    // ── parsing / validation ─────────────────────────────────────────────────

    /**
     * Builds a price from raw command-line strings, validating and warning.
     *
     * <p>{@code input} and {@code output} are required; the cache pair is
     * optional. A value that is not a number, is negative, or is implausibly
     * large throws {@link IllegalArgumentException} with a message naming the
     * offending field. A nonzero value below {@link #MIN_REASONABLE} is appended
     * to {@code warnings} instead — it may be a real price for a cheap model.
     *
     * @param warnings collected human-readable warnings, may be null
     */
    public static NaruUnitPrice parse(NaruPriceSource source, Instant fetchedAt, String origin,
                                      String input, String output, String cacheRead, String cacheWrite,
                                      List<String> warnings) {
        return new NaruUnitPrice(
                parseField("input", input, true, warnings),
                parseField("output", output, true, warnings),
                parseField("cache-read", cacheRead, false, warnings),
                parseField("cache-write", cacheWrite, false, warnings),
                source, fetchedAt, origin);
    }

    private static BigDecimal parseField(String name, String raw, boolean required, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("price '" + name + "' is required");
            }
            return null;
        }
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("price '" + name + "' is not a number: " + raw);
        }
        if (v.signum() < 0) {
            throw new IllegalArgumentException("price '" + name + "' must not be negative: " + raw);
        }
        if (v.compareTo(MAX_REASONABLE) > 0) {
            throw new IllegalArgumentException("price '" + name + "' is " + v.toPlainString()
                    + " per million tokens, which is implausibly large for a per-million price");
        }
        if (v.signum() > 0 && v.compareTo(MIN_REASONABLE) < 0 && warnings != null) {
            warnings.add("price '" + name + "' is " + v.toPlainString()
                    + " per million tokens, which is suspiciously small; NARU prices are per 1M tokens");
        }
        return v;
    }

    // ── persistence ──────────────────────────────────────────────────────────

    /**
     * Numbers are written as strings on purpose: a tson number literal may come
     * back as a {@code double}, and money must never touch {@code double}.
     */
    public NElement toElement() {
        NObjectElementBuilder b = NObjectElementBuilder.of();
        b.set("input", input.toPlainString());
        b.set("output", output.toPlainString());
        if (cacheRead != null) {
            b.set("cache-read", cacheRead.toPlainString());
        }
        if (cacheWrite != null) {
            b.set("cache-write", cacheWrite.toPlainString());
        }
        b.set("source", source.name().toLowerCase());
        if (fetchedAt != null) {
            b.set("fetched-at", fetchedAt.toString());
        }
        if (origin != null) {
            b.set("origin", origin);
        }
        return b.build();
    }

    /** @return the parsed price, or null when the element is not a usable price */
    public static NaruUnitPrice ofElement(NElement e) {
        if (e == null || !e.isAnyObject()) {
            return null;
        }
        NObjectElement o = e.asObject().get();
        String input = o.getStringValue("input").orNull();
        String output = o.getStringValue("output").orNull();
        if (input == null || output == null) {
            return null;
        }
        String cr = o.getStringValue("cache-read").orNull();
        String cw = o.getStringValue("cache-write").orNull();
        String src = o.getStringValue("source").orNull();
        NaruPriceSource source = "fetched".equalsIgnoreCase(src) ? NaruPriceSource.FETCHED : NaruPriceSource.USER;
        String fa = o.getStringValue("fetched-at").orNull();
        Instant fetchedAt = null;
        if (fa != null && !fa.isBlank()) {
            try {
                fetchedAt = Instant.parse(fa.trim());
            } catch (Exception ignore) {
                // a malformed timestamp must not make the whole price unreadable
            }
        }
        String origin = o.getStringValue("origin").orNull();
        try {
            return new NaruUnitPrice(
                    new BigDecimal(input.trim()),
                    new BigDecimal(output.trim()),
                    cr == null ? null : new BigDecimal(cr.trim()),
                    cw == null ? null : new BigDecimal(cw.trim()),
                    source, fetchedAt, origin);
        } catch (RuntimeException ignore) {
            return null;
        }
    }
}
