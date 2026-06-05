package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.LinkedHashSet;
import java.util.Objects;

public class NaruRetentionPolicies {

    private static final NaruRetentionPolicy ALWAYS = new AlwaysNaruRetentionPolicy();
    private static final NaruRetentionPolicy NEVER = new NeverNaruRetentionPolicy();

    public static NaruRetentionPolicy always() {
        return ALWAYS;
    }

    public static NaruRetentionPolicy never() {
        return NEVER;
    }

    public static NaruRetentionPolicy and(NaruRetentionPolicy... filters) {
        LinkedHashSet<NaruRetentionPolicy> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruRetentionPolicy f : filters) {
                if (f != null) {
                    if (f == ALWAYS) {
                        //ignore
                    } else if (f == NEVER) {
                        return NEVER;
                    } else {
                        validFilters.add(f);
                    }
                }
            }
        }
        if (validFilters.isEmpty()) {
            return ALWAYS;
        }
        if (validFilters.size() == 1) {
            return validFilters.iterator().next();
        }
        return new AndNaruRetentionPolicy(validFilters);
    }

    public static NaruRetentionPolicy or(NaruRetentionPolicy... filters) {
        LinkedHashSet<NaruRetentionPolicy> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruRetentionPolicy f : filters) {
                if (f != null) {
                    if (f == ALWAYS) {
                        return ALWAYS;
                    } else if (f == NEVER) {
                        //
                    } else {
                        validFilters.add(f);
                    }
                }
            }
        }
        if (validFilters.isEmpty()) {
            return ALWAYS;
        }
        if (validFilters.size() == 1) {
            return validFilters.iterator().next();
        }
        return new OrNaruRetentionPolicy(validFilters);
    }

    public static NaruRetentionPolicy taskId(long tid) {
        return new TaskIdNaruRetentionPolicy(tid);
    }

    public static NaruRetentionPolicy children(long taskId) {
        return new ChildrenNaruRetentionPolicy(taskId);
    }

    private static NaruRetentionPolicy parse(NExprNode n, NaruTask candidate) {
        switch (n.nodeType()) {
            case OPERATOR: {
                NExprOpNode o = (NExprOpNode) n;
                switch (o.name().toLowerCase()) {
                    case "&":
                    case "&&":
                    case "and": {
                        return new AndNaruRetentionPolicy(
                                new LinkedHashSet<>(
                                        o.operands().stream().map(x -> parse(x, candidate)).toList()
                                )
                        );
                    }
                    case "|":
                    case "||":
                    case "or": {
                        return new OrNaruRetentionPolicy(
                                new LinkedHashSet<>(
                                        o.operands().stream().map(x -> parse(x, candidate)).toList()
                                )
                        );
                    }
                    default: {
                        throw new NIllegalArgumentException(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
            case WORD: {
                NExprNode w = (NExprNode) n;
                switch (w.name().toLowerCase()) {
                    case "all":
                    case "everyone": {
                        return new AlwaysNaruRetentionPolicy();
                    }
                    case "none":
                    case "noone": {
                        return new NeverNaruRetentionPolicy();
                    }
                    case "child":
                    case "children": {
                        return new ChildrenNaruRetentionPolicy(candidate.id());
                    }
                    case "sibling":
                    case "siblings": {
                        return new SiblingNaruRetentionPolicy(candidate.id(), candidate.parentId());
                    }
                    case "parent": {
                        if (candidate.parentId() < 0) {
                            return new NeverNaruRetentionPolicy();
                        }
                        return new TaskIdNaruRetentionPolicy(candidate.parentId());
                    }
                    default: {
                        throw new NIllegalArgumentException(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
        }
        throw new NIllegalArgumentException(NMsg.ofC("unknown node type %s", n.nodeType()));
    }

    public static NaruRetentionPolicy parse(String tasks, NaruTask task) {
        if (NBlankable.isBlank(tasks)) {
            return ALWAYS;
        }
        NExprNode n = NExprContextBuilder
                .of()
                .declareBuiltins()
                .build()
                .parse(tasks).get();
        return parse(n, task);
    }


    private static class AndNaruRetentionPolicy implements NaruRetentionPolicy {
        private final LinkedHashSet<NaruRetentionPolicy> validFilters;

        public AndNaruRetentionPolicy(LinkedHashSet<NaruRetentionPolicy> validFilters) {
            this.validFilters = validFilters;
        }

        @Override
        public boolean test(NaruTask event) {
            for (NaruRetentionPolicy filter : validFilters) {
                if (!filter.test(event)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("and",
                    validFilters.stream().map(NaruRetentionPolicy::toElement).toArray(NElement[]::new)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            AndNaruRetentionPolicy that = (AndNaruRetentionPolicy) o;
            return Objects.equals(validFilters, that.validFilters);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(validFilters);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class AlwaysNaruRetentionPolicy implements NaruRetentionPolicy {
        @Override
        public boolean test(NaruTask event) {
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofName("always");
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            AlwaysNaruRetentionPolicy that = (AlwaysNaruRetentionPolicy) o;
            return true;
        }

        @Override
        public int hashCode() {
            return 17;
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class NeverNaruRetentionPolicy implements NaruRetentionPolicy {
        @Override
        public boolean test(NaruTask event) {
            return false;
        }

        @Override
        public NElement toElement() {
            return NElement.ofName("never");
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            NeverNaruRetentionPolicy that = (NeverNaruRetentionPolicy) o;
            return true;
        }

        @Override
        public int hashCode() {
            return 19;
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class TaskIdNaruRetentionPolicy implements NaruRetentionPolicy {
        private final long tid;

        public TaskIdNaruRetentionPolicy(long tid) {
            this.tid = tid < 0 ? -1 : tid;
        }

        public boolean test(NaruTask candidate) {
            return candidate.id() == tid;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("taskId",
                    NElement.ofPair("id", tid)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            TaskIdNaruRetentionPolicy that = (TaskIdNaruRetentionPolicy) o;
            return tid == that.tid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(tid);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class ChildrenNaruRetentionPolicy implements NaruRetentionPolicy {
        private final long taskId;

        public ChildrenNaruRetentionPolicy(long taskId) {
            this.taskId = taskId;
        }

        public boolean test(NaruTask candidate) {
            return candidate.parentId() == taskId;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("children",
                    NElement.ofPair("taskId", taskId)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            ChildrenNaruRetentionPolicy that = (ChildrenNaruRetentionPolicy) o;
            return taskId == that.taskId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class SiblingNaruRetentionPolicy implements NaruRetentionPolicy {
        private final long parentId;
        private final long taskId;

        public SiblingNaruRetentionPolicy(long taskId, long parentId) {
            this.taskId = taskId;
            this.parentId = parentId;
        }

        public boolean test(NaruTask candidate) {
            return candidate.parentId() == parentId;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("sibling",
                    NElement.ofPair("taskId", taskId),
                    NElement.ofPair("parentId", parentId)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            SiblingNaruRetentionPolicy that = (SiblingNaruRetentionPolicy) o;
            return parentId == that.parentId && taskId == that.taskId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, parentId);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class OrNaruRetentionPolicy implements NaruRetentionPolicy {
        private final LinkedHashSet<NaruRetentionPolicy> validFilters;

        public OrNaruRetentionPolicy(LinkedHashSet<NaruRetentionPolicy> validFilters) {
            this.validFilters = validFilters;
        }

        @Override
        public boolean test(NaruTask candidate) {
            for (NaruRetentionPolicy filter : validFilters) {
                if (filter.test(candidate)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("or",
                    validFilters.stream().map(NaruRetentionPolicy::toElement).toArray(NElement[]::new)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            OrNaruRetentionPolicy that = (OrNaruRetentionPolicy) o;
            return Objects.equals(validFilters, that.validFilters);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(validFilters);
        }
    }
}
