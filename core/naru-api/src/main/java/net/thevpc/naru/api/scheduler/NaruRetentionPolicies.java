package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprLiteralNode;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class NaruRetentionPolicies {

    public static NaruRetentionPolicy ofForever() {
        return ForeverRetentionPolicy.INSTANCE;
    }

    public static NaruRetentionPolicy ofNever() {
        return ForeverRetentionPolicy.INSTANCE;
    }

    public static NaruRetentionPolicy and(NaruRetentionPolicy... filters) {
        LinkedHashSet<NaruRetentionPolicy> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruRetentionPolicy f : filters) {
                if (f != null) {
                    if (f == ofForever()) {
                        //ignore
                    } else if (f == ofNever()) {
                        return ofNever();
                    } else {
                        validFilters.add(f);
                    }
                }
            }
        }
        if (validFilters.isEmpty()) {
            return ofForever();
        }
        if (validFilters.size() == 1) {
            return validFilters.iterator().next();
        }
        return new AllRetentionPolicy(validFilters.toArray(new NaruRetentionPolicy[0]));
    }

    public static NaruRetentionPolicy or(NaruRetentionPolicy... filters) {
        LinkedHashSet<NaruRetentionPolicy> validFilters = new LinkedHashSet<>();
        if (filters != null) {
            for (NaruRetentionPolicy f : filters) {
                if (f != null) {
                    if (f == ofForever()) {
                        return ofForever();
                    } else if (f == ofNever()) {
                        //
                    } else {
                        validFilters.add(f);
                    }
                }
            }
        }
        if (validFilters.isEmpty()) {
            return ofForever();
        }
        if (validFilters.size() == 1) {
            return validFilters.iterator().next();
        }
        return new AnyRetentionPolicy(validFilters.toArray(new NaruRetentionPolicy[0]));
    }

    private static NOptional<NaruRetentionPolicy> parseNode(NExprNode n) {
        switch (n.nodeType()) {
            case OPERATOR: {
                NExprOpNode o = (NExprOpNode) n;
                switch (o.name().toLowerCase()) {
                    case "&":
                    case "&&":
                    case "and": {
                        List<NaruRetentionPolicy> ch=new ArrayList<>();
                        for (NExprNode nn : o.operands()) {
                            NOptional<NaruRetentionPolicy> r = parseNode(nn);
                            if(r.isPresent()){
                                ch.add(r.get());
                            }else{
                                return NOptional.ofError(r.getMessage());
                            }
                        }
                        return NOptional.of(and(ch.toArray(NaruRetentionPolicy[]::new)));
                    }
                    case "|":
                    case "||":
                    case "or": {
                        List<NaruRetentionPolicy> ch=new ArrayList<>();
                        for (NExprNode nn : o.operands()) {
                            NOptional<NaruRetentionPolicy> r = parseNode(nn);
                            if(r.isPresent()){
                                ch.add(r.get());
                            }else{
                                return NOptional.ofError(r.getMessage());
                            }
                        }
                        return NOptional.of(or(ch.toArray(NaruRetentionPolicy[]::new)));
                    }
                    default: {
                        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
            case WORD: {
                switch (n.name().toLowerCase()) {
                    case "forever":
                    case "persistent":
                    {
                        return NOptional.of(ofForever());
                    }
                    case "never":
                    case "forget": {
                        return NOptional.of(ofNever());
                    }
                    case "default":{
                        return NOptional.of(ofDefault());
                    }
                    case "once":{
                        return NOptional.of(ofOnce());
                    }
                    default: {
                        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
            case FUNCTION: {
                switch (n.name().toLowerCase()) {
                    case "ttl":
                    {
                        if(n.children().size()==1) {
                            NExprNode c = n.children().get(0);
                            if(c instanceof NExprLiteralNode) {
                                NExprLiteralNode l = (NExprLiteralNode) c;
                                Object lv = l.value();
                                if(lv instanceof Number){
                                    return NOptional.of(new TtlRetentionPolicy(NDuration.ofSeconds(NLiteral.of(lv).asLong().get())));
                                }else if(lv instanceof String){
                                    return NOptional.of(new TtlRetentionPolicy(NDuration.parse((String) lv).get()));
                                }
                            }
                        }
                        throw new NIllegalArgumentException(NMsg.ofC("invalid %s", n));
                    }
                    case "max":
                    {
                        if(n.children().size()==1) {
                            NExprNode c = n.children().get(0);
                            if(c instanceof NExprLiteralNode) {
                                NExprLiteralNode l = (NExprLiteralNode) c;
                                Object lv = l.value();
                                return NOptional.of(new MaxConsumersRetentionPolicy(NLiteral.of(lv).asInt().get()));
                            }
                        }
                        return NOptional.ofError(NMsg.ofC("invalid %s", n));
                    }
                    default: {
                        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
                    }
                }
            }
        }
        return NOptional.ofError(NMsg.ofC("unknown node type %s", n.nodeType()));
    }

    private static OnceRetentionPolicy ofOnce() {
        return OnceRetentionPolicy.INSTANCE;
    }

    public static NOptional<NaruRetentionPolicy> parse(String tasks, NaruTask task) {
        if (NBlankable.isBlank(tasks)) {
            return NOptional.of(ofForever());
        }
        NExprNode n = NExprContextBuilder
                .of()
                .declareBuiltins()
                .build()
                .parse(tasks).get();
        return parseNode(n);
    }

    public static NaruRetentionPolicy ofDefault() {
        return DefaultRetentionPolicy.INSTANCE;
    }
}
