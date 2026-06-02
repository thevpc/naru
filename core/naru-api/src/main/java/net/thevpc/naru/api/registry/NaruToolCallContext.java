package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public interface NaruToolCallContext {
    Map<String, Object> arguments();
    NaruTask task();
    NOptional<Object> arg(String name);
    NOptional<String> stringArg(String name);
    NOptional<Number> numberArg(String name);
    NOptional<Integer> intArg(String name);
    NOptional<Long> longArg(String name);
    NOptional<Boolean> booleanArg(String name);
}
