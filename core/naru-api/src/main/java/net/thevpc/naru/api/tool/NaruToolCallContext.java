package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public interface NaruToolCallContext {
    Map<String, Object> arguments();
    NaruSession session();
    NOptional<Object> arg(String name);
    NOptional<String> stringArg(String name);
    NOptional<Number> numberArg(String name);
    NOptional<Boolean> booleanArg(String name);
}
