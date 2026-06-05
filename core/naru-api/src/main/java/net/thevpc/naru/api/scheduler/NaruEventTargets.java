package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public class NaruEventTargets {

    private static final NaruEventTarget ALL = new AlwaysNaruEventTarget();
    private static final NaruEventTarget NONE = new NeverNaruEventTarget();

    public static NaruEventTarget always() {
        return ALL;
    }

    public static NaruEventTarget never() {
        return NONE;
    }

    public static NaruEventTarget and(NaruEventTarget... filters) {
        LinkedHashSet<NaruEventTarget> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruEventTarget f : filters) {
                if (f != null) {
                    if (f == ALL) {
                        //ignore
                    } else if (f == NONE) {
                        return NONE;
                    } else {
                        validFilters.add(f);
                    }
                }
            }
        }
        if (validFilters.isEmpty()) {
            return ALL;
        }
        if (validFilters.size() == 1) {
            return validFilters.iterator().next();
        }
        return new AndNaruEventTarget(validFilters);
    }

    public static NaruEventTarget or(NaruEventTarget... filters) {
        LinkedHashSet<NaruEventTarget> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruEventTarget f : filters) {
                if (f != null) {
                    if (f == ALL) {
                        return ALL;
                    } else if (f == NONE) {
                        //
                    } else {
                        validFilters.add(f);
                    }
                }
            }
        }
        if (validFilters.isEmpty()) {
            return ALL;
        }
        if (validFilters.size() == 1) {
            return validFilters.iterator().next();
        }
        return new OrNaruEventTarget(validFilters);
    }

    public static NaruEventTarget taskId(long tid) {
        return new TaskIdNaruEventTarget(tid);
    }

    public static NaruEventTarget children(long taskId) {
        return new ChildrenNaruEventTarget(taskId);
    }

    private static NaruEventTarget parse(NExprNode n, NaruTask candidate) {
        switch (n.nodeType()) {
            case OPERATOR: {
                NExprOpNode o = (NExprOpNode) n;
                switch (o.name().toLowerCase()) {
                    case "&":
                    case "&&":
                    case "and": {
                        return new AndNaruEventTarget(
                                new LinkedHashSet<>(
                                        o.operands().stream().map(x -> parse(x, candidate)).toList()
                                )
                        );
                    }
                    case "|":
                    case "||":
                    case "or": {
                        return new OrNaruEventTarget(
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
                        return new AlwaysNaruEventTarget();
                    }
                    case "none":
                    case "noone": {
                        return new NeverNaruEventTarget();
                    }
                    case "child":
                    case "children": {
                        return new ChildrenNaruEventTarget(candidate.id());
                    }
                    case "sibling":
                    case "siblings": {
                        return new SiblingNaruEventTarget(candidate.id(), candidate.parentId());
                    }
                    case "parent": {
                        if (candidate.parentId() < 0) {
                            return new NeverNaruEventTarget();
                        }
                        return new TaskIdNaruEventTarget(candidate.parentId());
                    }
                    default: {
                        throw new NIllegalArgumentException(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
        }
        throw new NIllegalArgumentException(NMsg.ofC("unknown node type %s", n.nodeType()));
    }

    public static NaruEventTarget parse(String tasks, NaruTask task) {
        if (NBlankable.isBlank(tasks)) {
            return ALL;
        }
        NExprNode n = NExprContextBuilder
                .of()
                .declareBuiltins()
                .build()
                .parse(tasks).get();
        return parse(n, task);
    }


    private static class AndNaruEventTarget implements NaruEventTarget {
        private final LinkedHashSet<NaruEventTarget> validFilters;

        public AndNaruEventTarget(LinkedHashSet<NaruEventTarget> validFilters) {
            this.validFilters = validFilters;
        }

        @Override
        public boolean test(NaruTask event) {
            for (NaruEventTarget filter : validFilters) {
                if (!filter.test(event)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("and",
                    validFilters.stream().map(NaruEventTarget::toElement).toArray(NElement[]::new)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            AndNaruEventTarget that = (AndNaruEventTarget) o;
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

    private static class AlwaysNaruEventTarget implements NaruEventTarget {
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
            AlwaysNaruEventTarget that = (AlwaysNaruEventTarget) o;
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

    private static class NeverNaruEventTarget implements NaruEventTarget {
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
            NeverNaruEventTarget that = (NeverNaruEventTarget) o;
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

    private static class TaskIdNaruEventTarget implements NaruEventTarget {
        private final long tid;

        public TaskIdNaruEventTarget(long tid) {
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
            TaskIdNaruEventTarget that = (TaskIdNaruEventTarget) o;
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

    private static class ChildrenNaruEventTarget implements NaruEventTarget {
        private final long taskId;

        public ChildrenNaruEventTarget(long taskId) {
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
            ChildrenNaruEventTarget that = (ChildrenNaruEventTarget) o;
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

    private static class SiblingNaruEventTarget implements NaruEventTarget {
        private final long parentId;
        private final long taskId;

        public SiblingNaruEventTarget(long taskId, long parentId) {
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
            SiblingNaruEventTarget that = (SiblingNaruEventTarget) o;
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

    private static class OrNaruEventTarget implements NaruEventTarget {
        private final LinkedHashSet<NaruEventTarget> validFilters;

        public OrNaruEventTarget(LinkedHashSet<NaruEventTarget> validFilters) {
            this.validFilters = validFilters;
        }

        @Override
        public boolean test(NaruTask candidate) {
            for (NaruEventTarget filter : validFilters) {
                if (filter.test(candidate)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("or",
                    validFilters.stream().map(NaruEventTarget::toElement).toArray(NElement[]::new)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            OrNaruEventTarget that = (OrNaruEventTarget) o;
            return Objects.equals(validFilters, that.validFilters);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(validFilters);
        }
    }
}
