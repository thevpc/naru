package net.thevpc.naru.ext.tools.plan;

import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A durable execution plan: a directed acyclic graph of {@link NaruPlanItem}s.
 *
 * <p>Plans are owned by the session, not by a task. A plan is keyed by its own UUID
 * and referenced explicitly, because the tasks that build it, execute it and validate
 * it are all different tasks; keying by task id (as an earlier revision did) made the
 * plan vanish the moment execution moved to a spawned child.
 *
 * <p>The plan knows nothing about activation, scheduling or spawning. It is a pure
 * data structure plus the derived {@link #status()}; deciding which plan runs and when
 * belongs to {@link NaruPlanManager}.
 */
public class NaruPlan {
    /**
     * Bumped when the persisted shape changes incompatibly. Plans written by an older
     * build are skipped on load rather than misread.
     */
    public static final int SCHEMA_VERSION = 1;

    private final String id;
    private String goal;
    private Instant creationInstant;
    private Instant modificationInstant;
    private final List<NaruPlanItem> items = new ArrayList<>();
    /**
     * Declaration keys mapped to item ids, kept for the lifetime of the plan. A key is
     * the only short, stable, human-meaningful handle on an item (descriptions are prose
     * and ids are opaque), so a plan that forgets them could only ever be extended by
     * quoting a UUID, and only within the single batch that declared the item.
     */
    private final Map<String, String> keyToItemId = new LinkedHashMap<>();

    public NaruPlan(String goal) {
        this(UUID.randomUUID().toString(), goal);
    }

    public NaruPlan(String id, String goal) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.goal = goal;
        this.creationInstant = Instant.now();
        this.modificationInstant = creationInstant;
    }

    public NaruPlan(NElement element) {
        NObjectElement o = element.asObject().get();
        this.id = o.getStringValue("id").orElse(UUID.randomUUID().toString());
        this.goal = o.getStringValue("goal").orNull();
        this.creationInstant = o.getInstantValue("creationInstant").orElse(Instant.now());
        this.modificationInstant = o.getInstantValue("modificationInstant").orElse(creationInstant);
        o.getArray("items").ifPresent(arr -> {
            for (NElement e : arr.children()) {
                items.add(new NaruPlanItem(e));
            }
        });
        o.getObject("keys").ifPresent(ko -> {
            for (NElement e : ko) {
                // object members read back as pair elements, so the value is a pair's
                // value rather than the element's own string
                NPairElement pair = e.asPair().orNull();
                if (pair == null) {
                    continue;
                }
                String key = pair.key().asStringValue().orNull();
                String itemId = pair.value().asStringValue().orNull();
                // a key pointing at a missing item is dropped rather than kept, so a
                // half-written file cannot resurrect a dangling handle
                if (key != null && itemId != null && item(itemId) != null) {
                    keyToItemId.put(key, itemId);
                }
            }
        });
    }

    /**
     * The declaration key of an item, or null when it was declared without one.
     */
    public String keyOf(String itemId) {
        for (Map.Entry<String, String> e : keyToItemId.entrySet()) {
            if (e.getValue().equals(itemId)) {
                return e.getKey();
            }
        }
        return null;
    }

    public String id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public NaruPlan setGoal(String goal) {
        this.goal = goal;
        return this;
    }

    public Instant creationInstant() {
        return creationInstant;
    }

    public Instant modificationInstant() {
        return modificationInstant;
    }

    public List<NaruPlanItem> items() {
        return Collections.unmodifiableList(items);
    }

    public NaruPlanItem item(String itemId) {
        for (NaruPlanItem s : items) {
            if (s.id().equals(itemId)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Resolves an item by full id or by unique id prefix.
     * <p>
     * {@link #render()} prints 8-character prefixes because full UUIDs in a system
     * prompt are unreadable, so a model quoting the plan back at us will hand us a
     * prefix. An ambiguous prefix resolves to nothing rather than to an arbitrary item:
     * silently picking one of several matches would corrupt the wrong item.
     */
    public NaruPlanItem findItemByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String p = prefix.trim();
        NaruPlanItem exact = item(p);
        if (exact != null) {
            return exact;
        }
        NaruPlanItem match = null;
        for (NaruPlanItem s : items) {
            if (s.id().startsWith(p)) {
                if (match != null) {
                    return null;
                }
                match = s;
            }
        }
        return match;
    }

    /**
     * Appends items, resolving {@link NaruPlanItemSpec#dependsOn()} keys against the
     * keys declared in this same batch and against the keys of items added by earlier
     * batches. A dependency may also name an item id directly.
     *
     * @throws IllegalArgumentException if a key is unknown or duplicated, or if the
     *                                  resulting graph would contain a cycle
     */
    public NaruPlan addItems(List<NaruPlanItemSpec> specs) {
        Map<String, String> keyToId = new LinkedHashMap<>(keyToItemId);
        for (NaruPlanItem existing : items) {
            if (existing.id() != null) {
                keyToId.put(existing.id(), existing.id());
            }
        }
        Map<String, String> batchKeys = new LinkedHashMap<>();
        List<NaruPlanItem> batch = new ArrayList<>();
        for (NaruPlanItemSpec spec : specs) {
            NaruPlanItem item = new NaruPlanItem(spec.description());
            item.setValidator(spec.validator());
            batch.add(item);
            if (spec.key() != null && !spec.key().isBlank()) {
                String key = spec.key().trim();
                if (keyToId.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate plan item key '" + key + "'");
                }
                keyToId.put(key, item.id());
                batchKeys.put(key, item.id());
            }
        }
        for (int i = 0; i < specs.size(); i++) {
            NaruPlanItemSpec spec = specs.get(i);
            NaruPlanItem item = batch.get(i);
            for (String dep : spec.dependsOn()) {
                String depId = keyToId.get(dep);
                if (depId == null) {
                    throw new IllegalArgumentException(
                            "unknown dependency key '" + dep + "' in item '" + spec.description() + "'");
                }
                item.addDependency(depId);
            }
        }
        try {
            items.addAll(batch);
            assertAcyclic();
        } catch (RuntimeException e) {
            // a rejected batch must leave the plan exactly as it was, otherwise a bad
            // declaration from the model silently grows the graph it just failed to add
            items.removeAll(batch);
            throw e;
        }
        keyToItemId.putAll(batchKeys);
        touch();
        return this;
    }

    /**
     * Removes an item and every dependency edge pointing at it. Items that depended on
     * the removed item become unblocked only if they had no other unsatisfied edge;
     * {@link NaruPlanManager#recomputeAndFill()} decides that.
     */
    public boolean removeItem(String itemId) {
        NaruPlanItem removed = null;
        for (NaruPlanItem s : items) {
            if (s.id().equals(itemId)) {
                removed = s;
                break;
            }
        }
        if (removed == null) {
            return false;
        }
        items.remove(removed);
        // the key must go too, or a later batch would resolve a dependency onto an id
        // that is no longer in the graph
        keyToItemId.values().removeIf(itemId::equals);
        for (NaruPlanItem s : items) {
            if (s.dependsOn(itemId)) {
                s.removeDependency(itemId);
            }
        }
        touch();
        return true;
    }

    /**
     * Adds a dependency edge. The target must already exist and the edge must not
     * create a cycle.
     *
     * @throws IllegalArgumentException if the target is unknown or would create a cycle
     */
    public NaruPlan addDependency(String itemId, String dependsOnItemId) {
        NaruPlanItem item = item(itemId);
        if (item == null) {
            throw new IllegalArgumentException("unknown plan item '" + itemId + "'");
        }
        if (item(dependsOnItemId) == null) {
            throw new IllegalArgumentException("unknown plan item '" + dependsOnItemId + "'");
        }
        item.addDependency(dependsOnItemId);
        try {
            assertAcyclic();
        } catch (RuntimeException e) {
            item.removeDependency(dependsOnItemId);
            throw e;
        }
        touch();
        return this;
    }

    /**
     * Depth-first cycle check over the whole graph. Cheap enough to run on every
     * structural edit, which is the point: a cycle would otherwise strand the plan
     * with no runnable item and no obvious cause.
     */
    public void assertAcyclic() {
        Map<String, Integer> mark = new LinkedHashMap<>();
        for (NaruPlanItem s : items) {
            if (mark.getOrDefault(s.id(), 0) == 0) {
                visit(s, mark);
            }
        }
    }

    private void visit(NaruPlanItem s, Map<String, Integer> mark) {
        mark.put(s.id(), 1);
        for (String dep : s.dependsOn()) {
            Integer m = mark.getOrDefault(dep, 0);
            if (m == 1) {
                throw new IllegalArgumentException(
                        "plan dependency cycle detected at item '" + dep + "'");
            }
            if (m == 0) {
                NaruPlanItem target = item(dep);
                // an edge to a missing item cannot form a cycle; dangling edges are
                // reported by recomputeAndFill() instead
                if (target != null) {
                    visit(target, mark);
                }
            }
        }
        mark.put(s.id(), 2);
    }

    /**
     * Items with no unsatisfied dependency, in declaration order. This is the
     * candidate set a selector draws from; it does not consider status, activation or
     * concurrency.
     */
    public List<NaruPlanItem> itemsWithSatisfiedDependencies() {
        List<NaruPlanItem> out = new ArrayList<>();
        for (NaruPlanItem s : items) {
            if (isSatisfied(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private boolean isSatisfied(NaruPlanItem s) {
        for (String dep : s.dependsOn()) {
            NaruPlanItem target = item(dep);
            if (target == null) {
                return false;
            }
            if (target.status() != NaruPlanItemStatus.DONE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every item is finished. An empty plan is not complete.
     */
    public boolean isComplete() {
        if (items.isEmpty()) {
            return false;
        }
        for (NaruPlanItem s : items) {
            if (s.status() != NaruPlanItemStatus.DONE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Aggregate state, derived from the items on every call.
     */
    public NaruPlanStatus status() {
        if (items.isEmpty()) {
            return NaruPlanStatus.PENDING;
        }
        if (isComplete()) {
            return NaruPlanStatus.COMPLETED;
        }
        boolean runnable = false;
        boolean stuck = false;
        for (NaruPlanItem s : items) {
            switch (s.status()) {
                case READY:
                case RUNNING:
                case VALIDATING:
                    runnable = true;
                    break;
                case BLOCKED:
                case FAILED:
                    stuck = true;
                    break;
                default:
                    break;
            }
        }
        if (runnable) {
            return NaruPlanStatus.ACTIVE;
        }
        if (stuck) {
            return NaruPlanStatus.BLOCKED;
        }
        return NaruPlanStatus.PENDING;
    }

    public NaruPlan touch() {
        this.modificationInstant = Instant.now();
        return this;
    }

    /**
     * Compact rendering for prompt injection and {@code /plan show}.
     * <p>
     * Dependency ids are rendered as short prefixes because full UUIDs in a system
     * prompt are both unreadable and easy for a model to mistype back.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan ").append(shortId()).append(" - ").append(goal).append('\n');
        for (NaruPlanItem s : items) {
            sb.append("  ").append(s.id(), 0, Math.min(8, s.id().length()));
            if (s.validator().isGate()) {
                sb.append(" [").append(s.validator().name().toLowerCase()).append(']');
            }
            sb.append(" (").append(s.status().name().toLowerCase()).append(')');
            sb.append(' ').append(s.description());
            if (!s.dependsOn().isEmpty()) {
                sb.append("  <- after:");
                for (String d : s.dependsOn()) {
                    sb.append(' ').append(d, 0, Math.min(8, d.length()));
                }
            }
            if (s.taskId() != null) {
                sb.append("  [task ").append(s.taskId()).append(']');
            }
            if (s.notes() != null) {
                sb.append(" -- ").append(s.notes());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String shortId() {
        return id.substring(0, Math.min(8, id.length()));
    }

    public NElement toElement() {
        NArrayElementBuilder itemsArr = NArrayElementBuilder.of();
        for (NaruPlanItem s : items) {
            itemsArr.add(s.toElement());
        }
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("schemaVersion", SCHEMA_VERSION);
        b.set("id", id);
        b.set("goal", goal);
        b.set("creationInstant", NElement.ofInstant(creationInstant));
        b.set("modificationInstant", NElement.ofInstant(modificationInstant));
        b.set("items", itemsArr.build());
        if (!keyToItemId.isEmpty()) {
            NObjectElementBuilder keys = NElement.ofObjectBuilder();
            for (Map.Entry<String, String> e : keyToItemId.entrySet()) {
                keys.set(e.getKey(), e.getValue());
            }
            b.set("keys", keys.build());
        }
        return b.build();
    }
}
