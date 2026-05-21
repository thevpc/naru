package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public class NaruToolCallContextImpl implements NaruToolCallContext {
    private final Map<String, Object> arguments;
    private final NaruSession session;

    public NaruToolCallContextImpl(Map<String, Object> arguments, NaruSession session) {
        this.arguments = arguments;
        this.session = session;
    }

    @Override
    public Map<String, Object> arguments() {
        return arguments;
    }

    @Override
    public NaruSession session() {
        return session;
    }

    @Override
    public NOptional<Object> arg(String name) {
        return NOptional.ofNamed(arguments.get(name), name);
    }

    @Override
    public NOptional<String> stringArg(String name) {
        return arg(name).map(x -> x.toString());
    }

    @Override
    public NOptional<Number> numberArg(String name) {
        return arg(name).flatMap(x -> NLiteral.of(x).asNumber());
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
        return arg(name).flatMap(x -> NLiteral.of(x).asBoolean());
    }
}
