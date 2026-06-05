//package net.thevpc.naru.api.scheduler;
//
//import net.thevpc.nuts.elem.NElement;
//import net.thevpc.nuts.elem.NToElement;
//
//public class NaruEventRouting implements NToElement {
//    private static final NaruEventRouting PARENT = new NaruEventRouting(NaruEventRoutingType.PARENT, -1);
//    private static final NaruEventRouting CHILDREN = new NaruEventRouting(NaruEventRoutingType.CHILDREN, -1);
//    private static final NaruEventRouting SIBLINGS = new NaruEventRouting(NaruEventRoutingType.SIBLINGS, -1);
//    private static final NaruEventRouting SELF = new NaruEventRouting(NaruEventRoutingType.SELF, -1);
//    private static final NaruEventRouting ALL = new NaruEventRouting(NaruEventRoutingType.ALL, -1);
//    private final NaruEventRoutingType type;
//    private final long id;
//
//    public static NaruEventRouting parent() {
//        return PARENT;
//    }
//    public static NaruEventRouting self() {
//        return SELF;
//    }
//    public static NaruEventRouting children() {
//        return CHILDREN;
//    }
//    public static NaruEventRouting siblings() {
//        return SIBLINGS;
//    }
//    public static NaruEventRouting all() {
//        return ALL;
//    }
//
//    public static NaruEventRouting of(long id) {
//        return new NaruEventRouting(NaruEventRoutingType.TASK, id);
//    }
//
//    private NaruEventRouting(NaruEventRoutingType type, long id) {
//        this.type = type;
//        this.id = id;
//    }
//
//    public NaruEventRoutingType type() {
//        return type;
//    }
//
//    public long id() {
//        return id;
//    }
//
//    @Override
//    public NElement toElement() {
//        switch (type) {
//            case TASK:
//                return NElement.ofNamedUplet("task", NElement.ofLong(id));
//            default:
//                return NElement.ofName(type.name().toLowerCase());
//        }
//    }
//}
