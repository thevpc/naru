package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprLiteralNode;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.*;

public class NaruEventTargets {

    private static final NaruEventTarget ALL = new AlwaysNaruEventTarget();
    private static final NaruEventTarget NONE = new NeverNaruEventTarget();

    public static NaruEventTarget ofEveryone() {
        return ALL;
    }

    public static NaruEventTarget ofNone() {
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

    private static NOptional<NaruEventTarget> parse(NExprNode n, NaruTask candidate) {
        switch (n.nodeType()) {
            case OPERATOR: {
                NExprOpNode o = (NExprOpNode) n;
                switch (o.name().toLowerCase()) {
                    case "&":
                    case "&&":
                    case "and": {
                        List<NaruEventTarget> ch = new ArrayList<>();
                        for (NExprNode nn : o.operands()) {
                            NOptional<NaruEventTarget> r = parse(nn, candidate);
                            if (r.isPresent()) {
                                ch.add(r.get());
                            } else {
                                return NOptional.ofError(r.getMessage());
                            }
                        }
                        return NOptional.of(new AndNaruEventTarget(ch));
                    }
                    case "|":
                    case "||":
                    case "or": {
                        List<NaruEventTarget> ch = new ArrayList<>();
                        for (NExprNode nn : o.operands()) {
                            NOptional<NaruEventTarget> r = parse(nn, candidate);
                            if (r.isPresent()) {
                                ch.add(r.get());
                            } else {
                                return NOptional.ofError(r.getMessage());
                            }
                        }
                        return NOptional.of(new OrNaruEventTarget(ch));
                    }
                    default: {
                        throw new NIllegalArgumentException(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
            case WORD: {
                switch (n.name().toLowerCase()) {
                    case "true":
                    case "any":
                    case "all":
                    case "everyone": {
                        return NOptional.of(ofEveryone());
                    }
                    case "false":
                    case "none":
                    case "noone": {
                        return NOptional.of(ofNone());
                    }
                    case "child":
                    case "children": {
                        return NOptional.of(new ChildrenNaruEventTarget(candidate.id()));
                    }
                    case "sibling":
                    case "siblings": {
                        return NOptional.of(new SiblingNaruEventTarget(candidate.id(), candidate.parentId()));
                    }
                    case "parent": {
                        if (candidate.parentId() < 0) {
                            return NOptional.of(new NeverNaruEventTarget());
                        }
                        return NOptional.of(new TaskIdNaruEventTarget(candidate.parentId()));
                    }
                }
                break;
            }
            case LITERAL: {
                NExprLiteralNode li = (NExprLiteralNode) n;
                Object v = li.value();
                Long nbr = NLiteral.of(v).asLong().orNull();
                if (nbr != null) {
                    return NOptional.of(new TaskIdNaruEventTarget(nbr));
                }
                break;
            }
        }
        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
    }

    public static NOptional<NaruEventTarget> parse(String tasks, NaruTask task) {
        if (NBlankable.isBlank(tasks)) {
            return NOptional.of(ofEveryone());
        }
        NExprNode n = NExprContextBuilder
                .of()
                .declareBuiltins()
                .build()
                .parse(tasks).get();
        return parse(n, task);
    }


    private static class AndNaruEventTarget implements NaruEventTarget {
            LinkedHashSet<NaruEventTarget> validFilters;
            private AndNaruEventTarget(Collection<NaruEventTarget> validFilters) {
                this.validFilters = new LinkedHashSet<>(validFilters);
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

    private record TaskIdNaruEventTarget(long tid) implements NaruEventTarget {
            private TaskIdNaruEventTarget(long tid) {
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
            public String toString() {
                return toElement().toString();
            }
        }

    private record ChildrenNaruEventTarget(long taskId) implements NaruEventTarget {

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
            public String toString() {
                return toElement().toString();
            }
        }

    private record SiblingNaruEventTarget(long taskId, long parentId) implements NaruEventTarget {

        public boolean test(NaruTask candidate) {
                return candidate.parentId() == parentId && candidate.id() != taskId;
            }

            @Override
            public NElement toElement() {
                return NElement.ofNamedUplet("sibling",
                        NElement.ofPair("taskId", taskId),
                        NElement.ofPair("parentId", parentId)
                );
            }

        @Override
            public String toString() {
                return toElement().toString();
            }
        }

    private static class OrNaruEventTarget implements NaruEventTarget {
            private LinkedHashSet<NaruEventTarget> validFilters;
            private OrNaruEventTarget(Collection<NaruEventTarget> validFilters) {
                this.validFilters = new LinkedHashSet<>(validFilters);
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

    }
}
