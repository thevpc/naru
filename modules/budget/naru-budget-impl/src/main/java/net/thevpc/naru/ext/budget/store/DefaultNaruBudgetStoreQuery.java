package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.ext.budget.Labels;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NCopiable;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class DefaultNaruBudgetStoreQuery implements NaruBudgetStoreQuery {
    private Map<String, String> labels = new HashMap<>();
    private Map<String, String> prefixLabels = new HashMap<>();
    private Instant from;
    private Instant to;

    public DefaultNaruBudgetStoreQuery() {
    }

    public DefaultNaruBudgetStoreQuery(NaruBudgetStoreQuery other) {
        if (other != null) {
            this.from = other.from();
            this.to = other.to();
            labels.putAll(other.labels().asMap());
            prefixLabels.putAll(other.labelPrefixes());
        }
    }

    @Override
    public NaruBudgetStoreQuery copy() {
        return new  DefaultNaruBudgetStoreQuery(this);
    }

    public NaruBudgetStoreQuery whereModel(NaruModelKey model) {
        if (model != null) {
            whereLabel("model", model.model());
            whereLabel("provider", model.provider());
        }
        return this;
    }

    @Override
    public NaruBudgetStoreQuery whereUser(String user) {
        if (!NBlankable.isBlank(user)) {
            whereLabel("user", user);
        }
        return this;
    }

    public NaruBudgetStoreQuery whereLabels(Labels labels) {
        if (labels != null) {
            for (Map.Entry<String, String> e : labels.asMap().entrySet()) {
                whereLabel(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    public NaruBudgetStoreQuery whereLabel(String key, String value) {
        if (key != null && value != null) {
            labels.put(key, value);
        }
        return this;
    }

    @Override
    public NaruBudgetStoreQuery whereLabelStartsWith(String key, String prefix) {
        if (key != null && prefix != null) {
            prefixLabels.put(key, prefix);
        }
        return this;
    }

    @Override
    public Map<String, String> labelPrefixes() {
        return prefixLabels;
    }

    public NaruBudgetStoreQuery wherePeriod(Instant from, Instant to) {
        this.from = from;
        this.to = to;
        return this;
    }

    @Override
    public Labels labels() {
        return Labels.of(labels);
    }

    @Override
    public Instant from() {
        return from;
    }

    @Override
    public Instant to() {
        return to;
    }
}
