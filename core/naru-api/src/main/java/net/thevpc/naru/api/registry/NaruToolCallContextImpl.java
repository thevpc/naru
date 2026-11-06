package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public class NaruToolCallContextImpl implements NaruToolCallContext {
    private final Map<String, NElement> arguments;
    private final NaruTask task;

    public NaruToolCallContextImpl(Map<String, NElement> arguments, NaruTask task) {
        this.arguments = arguments;
        this.task = task;
    }

    @Override
    public Map<String, NElement> arguments() {
        return arguments;
    }

    @Override
    public NaruTask task() {
        return task;
    }

    @Override
    public NOptional<NElement> arg(String name) {
        return NOptional.ofNamed(arguments.get(name), name);
    }

    @Override
    public NOptional<String> stringArg(String name) {
        return arg(name).map(NaruTaskConfig::toText);
    }

    @Override
    public NOptional<Number> numberArg(String name) {
        return arg(name).flatMap(x -> NLiteral.of(NaruUtils.plainValue(x)).asNumber());
    }

    @Override
    public NOptional<Integer> intArg(String name) {
        return numberArg(name).map(Number::intValue);
    }

    @Override
    public NOptional<Long> longArg(String name) {
        return numberArg(name).map(Number::longValue);
    }

    @Override
    public NOptional<Boolean> booleanArg(String name) {
        return arg(name).flatMap(x -> NLiteral.of(NaruUtils.plainValue(x)).asBoolean());
    }
}