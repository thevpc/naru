package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NPrimitiveElement;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

public class NaruToolCallContextImpl implements NaruToolCallContext {
    private final Map<String, Object> arguments;
    private final NaruTask task;

    public NaruToolCallContextImpl(Map<String, Object> arguments, NaruTask task) {
        this.arguments = arguments;
        this.task = task;
    }

    @Override
    public Map<String, Object> arguments() {
        return arguments;
    }

    @Override
    public NaruTask task() {
        return task;
    }

    @Override
    public NOptional<Object> arg(String name) {
        return NOptional.ofNamed(arguments.get(name), name);
    }

    @Override
    public NOptional<String> stringArg(String name) {
        return arg(name).map(NaruToolCallContextImpl::asRawString);
    }

    @Override
    public NOptional<Number> numberArg(String name) {
        return arg(name).flatMap(x -> NLiteral.of(asRawLiteral(x)).asNumber());
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
        return arg(name).flatMap(x -> NLiteral.of(asRawLiteral(x)).asBoolean());
    }

    /**
     * Normalize a raw argument to a literal-compatible value.
     *
     * <p>Some response parsers may store arguments as {@link NElement}
     * wrappers instead of plain Java values. The {@code toString()} of an
     * {@code NElement} string is its <b>TSON representation</b> (delimiter
     * quotes + doubled inner quotes: {@code "pom.xml"}, {@code ""1.0""}),
     * which is not the actual value ({@code pom.xml}, {@code "1.0"}) and would
     * corrupt file paths and contents if handed to a tool. Always unwrap the
     * element first, then fall back to {@code toString()}.</p>
     */
    private static Object asRawLiteral(Object x) {
        if (x instanceof NElement) {
            String s = asRawString(x);
            return s == null ? null : s;
        }
        return x;
    }

    private static String asRawString(Object x) {
        if (x == null) {
            return null;
        }
        if (x instanceof NElement) {
            NElement e = (NElement) x;
            NOptional<String> s = e.asStringValue();
            if (s.isPresent()) {
                return s.get();
            }
            NPrimitiveElement pv = e.asPrimitive().orNull();
            if (pv != null && pv.value() != null) {
                return String.valueOf(pv.value());
            }
        }
        return x.toString();
    }
}