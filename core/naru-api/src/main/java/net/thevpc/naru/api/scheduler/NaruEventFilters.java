package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.expr.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.*;

public class NaruEventFilters {

    private static final NaruEventFilter ALWAYS = new AlwaysNaruEventFilter();
    private static final NaruEventFilter NEVER = new NeverNaruEventFilter();

    public static NaruEventFilter always() {
        return ALWAYS;
    }

    public static NaruEventFilter never() {
        return NEVER;
    }

    public static NaruEventFilter and(NaruEventFilter... filters) {
        LinkedHashSet<NaruEventFilter> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruEventFilter f : filters) {
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
        return new AndNaruEventFilter(validFilters);
    }

    public static NaruEventFilter or(NaruEventFilter... filters) {
        LinkedHashSet<NaruEventFilter> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruEventFilter f : filters) {
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
        return new OrNaruEventFilter(validFilters);
    }

    public static NaruEventFilter taskId(long tid) {
        if (tid < 0) {
            return ALWAYS;
        }
        return new TaskIdNaruEventFilter(tid);
    }

    public static NaruEventFilter eventName(String eventName) {
        String z = NStringUtils.trimToNull(eventName);
        if (z == null) {
            return ALWAYS;
        }
        return new EventNameNaruEventFilter(z);
    }

    public static NaruEventFilter children(long parentId, long exceptTaskId) {
        return new ChildrenNaruEventFilter(parentId < 0 ? -1 : parentId, exceptTaskId < 0 ? -1 : exceptTaskId);
    }


    public static NOptional<NaruEventFilter> parse(String tasks, String implicitEventName,NaruTask task) {
        if (NBlankable.isBlank(tasks)) {
            return NOptional.of(ALWAYS);
        }
        NExprNode n = NExprContextBuilder
                .of()
                .declareBuiltins()
                .build()
                .parse(tasks).get();
        if(NBlankable.isBlank(implicitEventName)){
            return parseNode(n,task,NRef.of());
        }else{
            NRef<Boolean> b = NRef.of(false);
            NOptional<NaruEventFilter> e = parseNode(n, task, b);
            if(e.isPresent()){
                if(b.get()){
                    return e;
                }else{
                    return NOptional.of(and(eventName(implicitEventName),e.get()));
                }
            }
            return e;
        }
    }

    private static NOptional<NaruEventFilter> parseNode(NExprNode n, NaruTask task,NRef<Boolean> eventVisited) {
        switch (n.nodeType()) {
            case OPERATOR: {
                NExprOpNode o = (NExprOpNode) n;
                switch (o.name().toLowerCase()) {
                    case "&":
                    case "&&":
                    case "and": {
                        List<NaruEventFilter> ch = new ArrayList<>();
                        for (NExprNode nn : o.operands()) {
                            NOptional<NaruEventFilter> r = parseNode(nn,task,eventVisited);
                            if (r.isPresent()) {
                                ch.add(r.get());
                            } else {
                                return NOptional.ofError(r.getMessage());
                            }
                        }
                        return NOptional.of(and(ch.toArray(NaruEventFilter[]::new)));
                    }
                    case "|":
                    case "||":
                    case "or": {
                        List<NaruEventFilter> ch = new ArrayList<>();
                        for (NExprNode nn : o.operands()) {
                            NOptional<NaruEventFilter> r = parseNode(nn,task,eventVisited);
                            if (r.isPresent()) {
                                ch.add(r.get());
                            } else {
                                return NOptional.ofError(r.getMessage());
                            }
                        }
                        return NOptional.of(or(ch.toArray(NaruEventFilter[]::new)));
                    }
                    default: {
                        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
            case WORD: {
                switch (n.name().toLowerCase()) {
                    case "all":
                    case "any": {
                        return NOptional.of(ALWAYS);
                    }
                    case "never":
                    case "forget": {
                        return NOptional.of(NEVER);
                    }
                    case "sibling":
                    case "siblings":
                    {
                        return NOptional.of(children(task.parentId(), task.id()));
                    }
                    case "child":
                    case "children":
                    {
                        return NOptional.of(children(task.parentId(), -1));
                    }
                    case "parent":
                    {
                        return NOptional.of(taskId(task.parentId()));
                    }
                    default: {
                        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
            case LITERAL: {
                if(n instanceof NExprLiteralNode li){
                    Long ll = NLiteral.of(li.value()).asLong().orNull();
                    if(ll!=null){
                        return NOptional.of(taskId(ll));
                    }else{
                        return NOptional.ofError(NMsg.ofC("invalid task id : %s", li.value()));
                    }
                }
                return NOptional.ofError(NMsg.ofC("invalid task id : %s", n));
            }
            case FUNCTION: {
                switch (n.name().toLowerCase()) {
                    case "task":
                    case "taskid": {
                        NaruEventFilter ff = null;
                        for (NExprNode child : n.children()) {
                            if (child instanceof NExprLiteralNode li) {
                                Long e = NLiteral.of(li.value()).asLong().orNull();
                                if (e != null) {
                                    ff = or(ff, taskId(e));
                                } else {
                                    return NOptional.ofError(NMsg.ofC("invalid task id : %s", child));
                                }
                            } else {
                                return NOptional.ofError(NMsg.ofC("invalid task id : %s", child));
                            }
                        }
                        if (ff == null) {
                            return NOptional.ofError(NMsg.ofC("invalid task id : %s", n));
                        }
                        return NOptional.of(ff);
                    }
                    case "event":
                    case "eventname":
                    case "eventid":
                    {
                        eventVisited.set(true);
                        NaruEventFilter ff = null;
                        for (NExprNode child : n.children()) {
                            if (child instanceof NExprLiteralNode li) {
                                String s = NLiteral.of(li.value()).asString().orNull();
                                if (!NBlankable.isBlank(s)) {
                                    ff = or(ff, eventName(s));
                                } else {
                                    return NOptional.ofError(NMsg.ofC("invalid event name"));
                                }
                            } else if (child instanceof NExprWordNode) {
                                String s = child.name();
                                if (!NBlankable.isBlank(s)) {
                                    ff = eventName(s);
                                } else {
                                    return NOptional.ofError(NMsg.ofC("invalid event name"));
                                }
                            } else {
                                return NOptional.ofError(NMsg.ofC("invalid event name : %s", child));
                            }
                        }
                        if (ff == null) {
                            return NOptional.ofError(NMsg.ofC("invalid event name : %s", n));
                        }
                        return NOptional.of(ff);
                    }
                    case "parent": {
                        if (n.children().isEmpty()) {
                            return NOptional.of(taskId(task.parentId()));
                        }else{
                            return NOptional.ofError(NMsg.ofC("invalid parent : %s", n));
                        }
                    }
                    case "child":
                    case "children": {
                        if (n.children().isEmpty()) {
                            return NOptional.of(children(task.parentId(), task.id()));
                        } else if (n.children().size() == 1){
                            NExprNode c1 = n.children().get(0);
                            if (c1 instanceof NExprLiteralNode) {
                                Long v = NLiteral.of(((NExprLiteralNode) c1).value()).asLong().orNull();
                                if(v!=null && v>=0){
                                    return NOptional.of(children(v, -1));
                                }else{
                                    return NOptional.ofError(NMsg.ofC("invalid children count : %s", ((NExprLiteralNode) c1).value()));
                                }
                            }else{
                                return NOptional.ofError(NMsg.ofC("invalid children count : %s", c1));
                            }
                        }else{
                            return NOptional.ofError(NMsg.ofC("invalid children : %s", n));
                        }
                    }
                    case "sibling":
                    case "siblings":
                    {
                        if (n.children().isEmpty()) {
                            return NOptional.of(children(task.parentId(), task.id()));
                        }else{
                            return NOptional.ofError(NMsg.ofC("invalid siblings : %s", n));
                        }
                    }
                    default: {
                        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
        }
        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
    }

    private record AndNaruEventFilter(LinkedHashSet<NaruEventFilter> validFilters) implements NaruEventFilter {

        @Override
        public boolean test(NaruEvent event) {
            for (NaruEventFilter filter : validFilters) {
                if (!filter.test(event)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("and",
                    validFilters.stream().map(NaruEventFilter::toElement).toArray(NElement[]::new)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            AndNaruEventFilter that = (AndNaruEventFilter) o;
            return Objects.equals(validFilters, that.validFilters);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class AlwaysNaruEventFilter implements NaruEventFilter {
        @Override
        public boolean test(NaruEvent event) {
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofName("always");
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            AlwaysNaruEventFilter that = (AlwaysNaruEventFilter) o;
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

    private static class NeverNaruEventFilter implements NaruEventFilter {
        @Override
        public boolean test(NaruEvent event) {
            return false;
        }

        @Override
        public NElement toElement() {
            return NElement.ofName("never");
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            NeverNaruEventFilter that = (NeverNaruEventFilter) o;
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

    private record EventNameNaruEventFilter(String event) implements NaruEventFilter {

        @Override
        public boolean test(NaruEvent event) {
            if (this.event != null) {
                return Objects.equals(event.name(), this.event);
            }
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("event", NElement.ofString(event));
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            EventNameNaruEventFilter that = (EventNameNaruEventFilter) o;
            return Objects.equals(event, that.event);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private record TaskIdNaruEventFilter(long tid) implements NaruEventFilter {

        @Override
        public boolean test(NaruEvent event) {
            return event.sourceTid() == tid;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("taskId",
                    NElement.ofLong(tid)
            );
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private record ChildrenNaruEventFilter(long parentId, long exceptTaskId) implements NaruEventFilter {

        @Override
        public boolean test(NaruEvent event) {
            return (event.sourcePid() == parentId && event.sourceTid() != exceptTaskId);
        }


        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("children",
                    NElement.ofPair("parentId", parentId),
                    NElement.ofPair("exceptTaskId", exceptTaskId)
            );
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private record OrNaruEventFilter(LinkedHashSet<NaruEventFilter> validFilters) implements NaruEventFilter {

        @Override
        public boolean test(NaruEvent event) {
            for (NaruEventFilter filter : validFilters) {
                if (filter.test(event)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("or",
                    validFilters.stream().map(NaruEventFilter::toElement).toArray(NElement[]::new)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            OrNaruEventFilter that = (OrNaruEventFilter) o;
            return Objects.equals(validFilters, that.validFilters);
        }

    }
}
