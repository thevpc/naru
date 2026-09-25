package net.thevpc.naru.ext.tools.plan;

import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A single node of a {@link NaruPlan} dependency graph.
 *
 * <p>Items are addressed by a stable UUID rather than by position, so that
 * reordering or inserting items never invalidates a reference held by a task, a
 * validator, or a persisted session.
 */
public class NaruPlanItem {
    private final String id;
    private String description;
    private final List<String> dependsOn = new ArrayList<>();
    private NaruPlanItemStatus status = NaruPlanItemStatus.PENDING;
    private NaruPlanValidatorKind validator = NaruPlanValidatorKind.NONE;
    private String notes;
    private Long taskId;
    private int attempts;

    public NaruPlanItem(String description) {
        this(UUID.randomUUID().toString(), description);
    }

    public NaruPlanItem(String id, String description) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.description = description;
    }

    public NaruPlanItem(NElement element) {
        NObjectElement o = element.asObject().get();
        this.id = o.getStringValue("id").orElse(UUID.randomUUID().toString());
        this.description = o.getStringValue("description").orNull();
        o.getArray("dependsOn").ifPresent(arr -> {
            for (NElement e : arr.children()) {
                String dep = e.asStringValue().orNull();
                if (dep != null && !dep.isBlank()) {
                    dependsOn.add(dep.trim());
                }
            }
        });
        this.status = NaruPlanItemStatus.parse(o.getStringValue("status").orNull())
                .orElse(NaruPlanItemStatus.PENDING);
        this.validator = NaruPlanValidatorKind.parse(o.getStringValue("validator").orNull())
                .orElse(NaruPlanValidatorKind.NONE);
        this.notes = o.getStringValue("notes").orNull();
        this.taskId = o.getLongValue("taskId").orNull();
        this.attempts = o.getIntValue("attempts").orElse(0);
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public NaruPlanItem setDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * UUIDs of the items that must be {@link NaruPlanItemStatus#DONE} before this
     * one becomes {@link NaruPlanItemStatus#READY}.
     */
    public List<String> dependsOn() {
        return Collections.unmodifiableList(dependsOn);
    }

    public NaruPlanItem addDependency(String itemId) {
        if (itemId != null && !itemId.isBlank() && !dependsOn.contains(itemId.trim())) {
            dependsOn.add(itemId.trim());
        }
        return this;
    }

    public boolean dependsOn(String itemId) {
        return dependsOn.contains(itemId);
    }

    public NaruPlanItem removeDependency(String itemId) {
        dependsOn.remove(itemId);
        return this;
    }

    public NaruPlanItemStatus status() {
        return status;
    }

    public NaruPlanItem setStatus(NaruPlanItemStatus status) {
        this.status = status == null ? NaruPlanItemStatus.PENDING : status;
        return this;
    }

    public NaruPlanValidatorKind validator() {
        return validator;
    }

    public NaruPlanItem setValidator(NaruPlanValidatorKind validator) {
        this.validator = validator == null ? NaruPlanValidatorKind.NONE : validator;
        return this;
    }

    public String notes() {
        return notes;
    }

    public NaruPlanItem setNotes(String notes) {
        this.notes = (notes == null || notes.isBlank()) ? null : notes;
        return this;
    }

    /**
     * The task spawned to execute this item, if one has been spawned.
     * <p>
     * Held as a bare id on purpose: a task is unregistered from the session when it
     * terminates, so a live {@code NaruTask} reference would dangle exactly when the
     * plan needs to read the outcome.
     */
    public Long taskId() {
        return taskId;
    }

    public NaruPlanItem setTaskId(Long taskId) {
        this.taskId = taskId;
        return this;
    }

    /**
     * How many times this item has been handed to an executor. Guards the retry
     * budget; an item is never re-run automatically once this reaches the caller's
     * {@code maxAttempts}.
     */
    public int attempts() {
        return attempts;
    }

    public NaruPlanItem setAttempts(int attempts) {
        this.attempts = Math.max(0, attempts);
        return this;
    }

    public NaruPlanItem incrementAttempts() {
        this.attempts++;
        return this;
    }

    public NElement toElement() {
        NArrayElementBuilder deps = NArrayElementBuilder.of();
        for (String d : dependsOn) {
            deps.add(NElement.ofString(d));
        }
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("id", id);
        b.set("description", description);
        b.set("dependsOn", deps.build());
        b.set("status", status.name().toLowerCase());
        b.set("validator", validator.name().toLowerCase());
        b.set("attempts", attempts);
        if (notes != null) {
            b.set("notes", notes);
        }
        if (taskId != null) {
            b.set("taskId", taskId);
        }
        return b.build();
    }
}
