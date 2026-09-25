package net.thevpc.naru.api.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative form of a {@link NaruPlanItem}, used when a plan is authored in one
 * shot (for example by the {@code plan_create} tool).
 *
 * <p>Dependencies are expressed as local {@link #key() keys} rather than UUIDs,
 * because at declaration time the UUIDs do not exist yet.
 * {@link NaruPlan#addItems(List)} resolves the keys to UUIDs and rewrites
 * {@link #dependsOn()} accordingly. Keys are optional; a keyless item simply cannot
 * be referenced by later items.
 */
public class NaruPlanItemSpec {
    private String key;
    private String description;
    private final List<String> dependsOn = new ArrayList<>();
    private NaruPlanValidatorKind validator = NaruPlanValidatorKind.NONE;

    public static NaruPlanItemSpec of(String description) {
        return new NaruPlanItemSpec().description(description);
    }

    public static NaruPlanItemSpec of(String key, String description) {
        return new NaruPlanItemSpec().key(key).description(description);
    }

    public String key() {
        return key;
    }

    public NaruPlanItemSpec key(String key) {
        this.key = key;
        return this;
    }

    public String description() {
        return description;
    }

    public NaruPlanItemSpec description(String description) {
        this.description = description;
        return this;
    }

    /**
     * Local keys of the items this one waits for.
     */
    public List<String> dependsOn() {
        return Collections.unmodifiableList(dependsOn);
    }

    public NaruPlanItemSpec dependsOn(String... keys) {
        if (keys != null) {
            for (String k : keys) {
                if (k != null && !k.isBlank()) {
                    dependsOn.add(k.trim());
                }
            }
        }
        return this;
    }

    public NaruPlanItemSpec dependsOn(List<String> keys) {
        dependsOn.clear();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && !k.isBlank()) {
                    dependsOn.add(k.trim());
                }
            }
        }
        return this;
    }

    public NaruPlanValidatorKind validator() {
        return validator;
    }

    public NaruPlanItemSpec validator(NaruPlanValidatorKind validator) {
        this.validator = validator == null ? NaruPlanValidatorKind.NONE : validator;
        return this;
    }
}
