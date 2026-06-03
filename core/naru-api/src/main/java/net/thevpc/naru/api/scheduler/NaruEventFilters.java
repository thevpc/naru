package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
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

    public static NaruEventFilter taskId(long tid, String event) {
        return new TaskIdNaruEventFilter(tid < 0 ? -1 : tid, NStringUtils.trimToNull(event));
    }

    public static NaruEventFilter children(long parentId, long exceptTaskId, int count, String event) {
        return new ChildrenNaruEventFilter(parentId < 0 ? -1 : parentId, exceptTaskId < 0 ? -1 : exceptTaskId, count <= 0 ? 1 : count, NStringUtils.trimToNull(event));
    }

    public static NaruEventFilter parse(String tasks, String eventType,Boolean any, NaruTask task) {
        Set<Object> tids = new HashSet<>();
        for (String s : NStringUtils.split(tasks, ",:;|", true, true)) {
            switch (NNameFormat.LOWER_KEBAB_CASE.format(s)) {
                case "parent":
                case "children":
                case "child":
                case "self":
                case "sibling":
                case "siblings":
                case "any":
                default:{
                    NLiteral.of(s).asLong().ifPresent(tids::add);
                }
            }
        }
        NaruEventFilter old=null;
        if(NBlankable.isBlank(eventType) || NUtils.firstNonNull(any,false)){
            for (Object tid : tids) {
                if(tid instanceof Long){
                    old=NaruEventFilters.or(old,NaruEventFilters.taskId((Long) tid,eventType));
                }else {
                    old = getNaruEventFilter((String) tid, old, task, eventType);
                }
            }
        }else{
            for (Object tid : tids) {
                if(tid instanceof Long){
                    old=NaruEventFilters.or(old,NaruEventFilters.taskId((Long) tid,null));
                }else {
                    old = getNaruEventFilter((String) tid, old, task, null);
                }
            }
            old=NaruEventFilters.or(old,NaruEventFilters.taskId(-1,eventType));
        }
        if(old==null){
            old= NaruEventFilters.always();
        }
        return old;
    }

    private static NaruEventFilter getNaruEventFilter(String tid, NaruEventFilter old, NaruTask task, String event) {
        switch (tid){
            case "self":{
                old =NaruEventFilters.or(old,NaruEventFilters.taskId(task.id(), event));
                break;
            }
            case "parent":{
                if(task.parentId()>=0) {
                    NaruEventFilters.or(old, NaruEventFilters.taskId(task.parentId(), event));
                }
                break;
            }
            case "sibling":{
                old =NaruEventFilters.or(old,NaruEventFilters.children(
                        task.parentId(),
                        task.id(),
                        1, event
                ));
                break;
            }
            case "siblings":{
                old =NaruEventFilters.or(old,NaruEventFilters.children(
                        task.parentId(),
                        task.id(),
                        task.session().findTaskIdsByParent(task.parentId()).length-1, event
                ));
                break;
            }
            case "children":{
                old =NaruEventFilters.or(old,NaruEventFilters.children(
                        task.id(),
                        -1,
                        task.session().findTaskIdsByParent(task.id()).length-1, event
                ));
                break;
            }
            case "child":{
                old =NaruEventFilters.or(old,NaruEventFilters.children(
                        task.id(),
                        -1,
                        1, event
                ));
                break;
            }
            case "any":{
                if(NBlankable.isBlank(event)) {
                    old =NaruEventFilters.or(old,NaruEventFilters.always());
                }else{
                    old =NaruEventFilters.or(old,NaruEventFilters.taskId(-1, event));
                }
                break;
            }
        }
        return old;
    }

    private static class AndNaruEventFilter implements NaruEventFilter {
        private final LinkedHashSet<NaruEventFilter> validFilters;

        public AndNaruEventFilter(LinkedHashSet<NaruEventFilter> validFilters) {
            this.validFilters = validFilters;
        }

        @Override
        public boolean matches(NaruEvent event, List<NaruEvent> received) {
            for (NaruEventFilter filter : validFilters) {
                if (!filter.matches(event, received)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean satisfied(List<NaruEvent> received) {
            for (NaruEventFilter filter : validFilters) {
                if (!filter.satisfied(received)) {
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
        public int hashCode() {
            return Objects.hashCode(validFilters);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class AlwaysNaruEventFilter implements NaruEventFilter {
        @Override
        public boolean matches(NaruEvent event, List<NaruEvent> received) {
            return true;
        }

        @Override
        public boolean satisfied(List<NaruEvent> received) {
            received.clear();
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
        public boolean matches(NaruEvent event, List<NaruEvent> received) {
            return false;
        }

        @Override
        public boolean satisfied(List<NaruEvent> received) {
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

    private static class TaskIdNaruEventFilter implements NaruEventFilter {
        private final long tid;
        private final String event;

        public TaskIdNaruEventFilter(long tid, String event) {
            this.tid = tid;
            this.event = event;
        }

        @Override
        public boolean matches(NaruEvent event, List<NaruEvent> received) {
            if (this.event != null) {
                if (!Objects.equals(event.type(), this.event)) {
                    return false;
                }
            }
            if (tid >= 0) {
                return event.sourceTid() == tid;
            }
            return true;
        }

        @Override
        public boolean satisfied(List<NaruEvent> received) {
            for (NaruEvent event : received) {
                if(matches(event, received)){
                    event.setMarked(true);
                }
            }
            return true;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("taskId",
                    NElement.ofPair("id", tid),
                    NElement.ofPair("event", event)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            TaskIdNaruEventFilter that = (TaskIdNaruEventFilter) o;
            return tid == that.tid && Objects.equals(event, that.event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tid, event);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class ChildrenNaruEventFilter implements NaruEventFilter {
        private final long parentId;
        private final int count;
        private final long exceptTaskId;
        private final String event;

        public ChildrenNaruEventFilter(long parentId, long exceptTaskId, int count, String event) {
            this.parentId = parentId;
            this.count = count;
            this.exceptTaskId = exceptTaskId;
            this.event = event;
        }

        @Override
        public boolean matches(NaruEvent event, List<NaruEvent> received) {
            if (this.event != null) {
                if (!Objects.equals(event.type(), this.event)) {
                    return false;
                }
            }
            return (event.sourcePid() == parentId && event.sourceTid() != exceptTaskId);
        }

        @Override
        public boolean satisfied(List<NaruEvent> received) {
            Set<String> ok = new HashSet<>();
            List<NaruEvent> toMark = new ArrayList<>();
            for (NaruEvent value : received) {
                if (!value.isMarked()) {
                    if(matches(value, received)){
                        ok.add(String.valueOf(value.sourceTid()));
                        toMark.add(value);
                    }
                }
            }
            if (ok.size() >= count) {
                for (NaruEvent e : toMark) {
                    e.setMarked(true);
                }
                return true;
            }
            return false;
        }

        @Override
        public NElement toElement() {
            return NElement.ofNamedUplet("children",
                    NElement.ofPair("parentId", parentId),
                    NElement.ofPair("count", count),
                    NElement.ofPair("exceptTaskId", exceptTaskId),
                    NElement.ofPair("event", event)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            ChildrenNaruEventFilter that = (ChildrenNaruEventFilter) o;
            return parentId == that.parentId && count == that.count && exceptTaskId == that.exceptTaskId && Objects.equals(event, that.event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentId, count, exceptTaskId, event);
        }

        @Override
        public String toString() {
            return toElement().toString();
        }
    }

    private static class OrNaruEventFilter implements NaruEventFilter {
        private final LinkedHashSet<NaruEventFilter> validFilters;

        public OrNaruEventFilter(LinkedHashSet<NaruEventFilter> validFilters) {
            this.validFilters = validFilters;
        }

        @Override
        public boolean matches(NaruEvent event, List<NaruEvent> received) {
            for (NaruEventFilter filter : validFilters) {
                if (filter.matches(event, received)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean satisfied(List<NaruEvent> received) {
            for (NaruEventFilter filter : validFilters) {
                if (filter.satisfied(received)) {
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

        @Override
        public int hashCode() {
            return Objects.hashCode(validFilters);
        }
    }
}
