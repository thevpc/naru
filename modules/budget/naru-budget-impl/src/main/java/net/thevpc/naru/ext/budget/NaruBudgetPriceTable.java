package net.thevpc.naru.ext.budget;

import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prices keyed by <em>provider</em> or <em>provider/model</em>.
 *
 * <p>Key shape is the whole contract. A key is either a bare provider
 * ({@code anthropic}) or a provider and a model separated by the <em>first</em>
 * slash ({@code openrouter/anthropic/claude-x} is provider {@code openrouter},
 * model {@code anthropic/claude-x}). Resolution walks from most to least
 * specific: the exact {@code provider/model} key wins, then the bare
 * {@code provider} key, then nothing — and "nothing" is {@code null}, never a
 * zero price. NARU must not claim a price the user did not set or explicitly
 * fetch.
 *
 * <p>Thread-safe: prices are read on the actor thread during every tracked call
 * and written from the directive thread.
 */
public class NaruBudgetPriceTable {

    private final Map<String, NaruUnitPrice> entries = new ConcurrentHashMap<>();

    /**
     * Called after every mutation, so an owner can persist the table. A no-op by
     * default: a table with no owner (tests, a transient table) simply does not save.
     */
    private volatile Runnable onChange = () -> {
    };

    public void onChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {
        } : onChange;
    }

    /**
     * Resolves the price for a transaction's {@code provider}/{@code model}
     * labels. Returns null when neither key is known.
     */
    public NaruUnitPrice priceFor(Labels labels) {
        if (labels == null) {
            return null;
        }
        Map<String, String> m = labels.asMap();
        return priceFor(m.get("provider"), m.get("model"));
    }

    /**
     * Resolves the price for a provider and model, exact key before provider key.
     */
    public NaruUnitPrice priceFor(String provider, String model) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        if (model != null && !model.isBlank()) {
            NaruUnitPrice exact = entries.get(provider.trim() + "/" + model.trim());
            if (exact != null) {
                return exact;
            }
        }
        return entries.get(provider.trim());
    }

    /** The entry stored under exactly this key, or null. */
    public NaruUnitPrice get(String key) {
        return key == null ? null : entries.get(key.trim());
    }

    public void set(String key, NaruUnitPrice price) {
        if (key == null || key.isBlank() || price == null) {
            return;
        }
        entries.put(key.trim(), price);
        onChange.run();
    }

    /**
     * Stores a fetched price unless the key already holds a user entry, which
     * refresh must never overwrite.
     *
     * @return the price now stored under the key
     */
    public NaruUnitPrice setFetched(String key, NaruUnitPrice price) {
        if (key == null || key.isBlank() || price == null) {
            return null;
        }
        NaruUnitPrice before = entries.get(key.trim());
        NaruUnitPrice result = entries.compute(key.trim(), (k, existing) ->
                existing != null && existing.source() == NaruPriceSource.USER ? existing : price);
        if (!java.util.Objects.equals(before, result)) {
            onChange.run();
        }
        return result;
    }

    /** @return the removed price, or null when the key was unknown */
    public NaruUnitPrice unset(String key) {
        if (key == null) {
            return null;
        }
        NaruUnitPrice removed = entries.remove(key.trim());
        if (removed != null) {
            onChange.run();
        }
        return removed;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** A snapshot of every entry, sorted by key for stable output. */
    public Map<String, NaruUnitPrice> entries() {
        return Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    // ── persistence ──────────────────────────────────────────────────────────

    public NElement toElement() {
        NArrayElementBuilder arr = NElement.ofArrayBuilder();
        for (Map.Entry<String, NaruUnitPrice> e : entries().entrySet()) {
            NObjectElementBuilder row = NObjectElementBuilder.of();
            row.set("key", e.getKey());
            row.set("price", e.getValue().toElement());
            arr.add(row.build());
        }
        return arr.build();
    }

    public static NaruBudgetPriceTable ofElement(NElement e) {
        NaruBudgetPriceTable table = new NaruBudgetPriceTable();
        if (e == null || !e.isAnyArray()) {
            return table;
        }
        for (NElement child : e.asArray().get().children()) {
            NObjectElement row = child.asObject().orNull();
            if (row == null) {
                continue;
            }
            String key = row.getStringValue("key").orNull();
            NaruUnitPrice price = NaruUnitPrice.ofElement(row.get("price").orNull());
            if (key != null && price != null) {
                table.set(key, price);
            }
        }
        return table;
    }
}
