package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.annotations.NaruToolSpec;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.annotations.NaruArg;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NElements;
import net.thevpc.nuts.text.NText;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One {@link net.thevpc.naru.api.annotations.NaruToolSpec} method, as a tool.
 */
final class NaruMethodTool extends DefaultNaruTool {
    private enum Source {MODEL, CONTEXT, TASK, SESSION}

    private record Arg(Source source, String name, Type type, boolean optional, boolean required,
                       String defaultValue, NaruToolParameter schema) {
    }

    private final Method method;
    private final String description;
    private final boolean essential;
    private final Supplier<?> instance;
    private final List<Arg> args;
    private final NaruToolDefinition definition;

    NaruMethodTool(Method method, NaruToolSpec annotation, Supplier<?> instance) {
        super(annotation.name().isBlank() ? snakeCase(method.getName()) : annotation.name().strip(), annotation.tags());
        this.method = method;
        this.description = annotation.description();
        this.essential = annotation.essential();
        this.instance = instance;
        if (annotation.tags().length == 0 && !essential) {
            throw new IllegalArgumentException("tool '" + name() + "' (" + where() + ") has no tags and is not"
                    + " essential: the tool gate is fail-closed, so no task would ever be offered it. Give it the"
                    + " tags of the capabilities it needs (fs, network, write, exec, ...) or mark it essential = true");
        }
        this.args = new ArrayList<>();
        List<NaruToolParameter> schema = new ArrayList<>();
        Parameter[] params = method.getParameters();
        Type[] types = method.getGenericParameterTypes();
        for (int i = 0; i < params.length; i++) {
            Arg arg = arg(params[i], types[i], i);
            args.add(arg);
            if (arg.schema() != null) {
                schema.add(arg.schema());
            }
        }
        this.definition = new NaruToolDefinitionFunction(name(), NText.ofPlain(description), schema);
    }

    private String where() {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    private Arg arg(Parameter p, Type type, int index) {
        Class<?> raw = rawClass(type);
        if (NaruToolCallContext.class.isAssignableFrom(raw)) {
            return new Arg(Source.CONTEXT, null, type, false, false, "", null);
        }
        if (NaruTask.class.isAssignableFrom(raw)) {
            return new Arg(Source.TASK, null, type, false, false, "", null);
        }
        if (NaruSession.class.isAssignableFrom(raw)) {
            return new Arg(Source.SESSION, null, type, false, false, "", null);
        }
        NaruArg a = p.getAnnotation(NaruArg.class);
        String name = a != null && !a.name().isBlank() ? a.name().strip() : (p.isNamePresent() ? p.getName() : null);
        if (name == null) {
            throw new IllegalArgumentException("parameter #" + (index + 1) + " of " + where() + " has no name the model"
                    + " can use: compile with -parameters or annotate it with @NaruArg(name = ...)");
        }
        boolean optional = raw == Optional.class;
        String defaultValue = a == null ? "" : a.defaultValue();
        boolean required = !optional && defaultValue.isEmpty() && (a == null || a.required());
        Type valueType = optional ? typeArgument(type, 0) : type;
        NaruToolParameter.Builder b = schema(name, a == null ? "" : a.description(), valueType, required);
        if (!defaultValue.isEmpty()) {
            b.defaultValue(defaultValue);
        }
        return new Arg(Source.MODEL, name, type, optional, required, defaultValue, b.build());
    }

    // ── schema ───────────────────────────────────────────────────────────────

    private static NaruToolParameter.Builder schema(String name, String description, Type type, boolean required) {
        Class<?> raw = rawClass(type);
        if (raw == String.class || CharSequence.class.isAssignableFrom(raw) || raw == char.class || raw == Character.class) {
            return NaruToolParameter.string(name, description, required);
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class
                || raw == BigInteger.class) {
            return NaruToolParameter.integer(name, description, required);
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == BigDecimal.class || raw == Number.class) {
            return NaruToolParameter.number(name, description, required);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return NaruToolParameter.bool(name, description, required);
        }
        if (raw.isEnum()) {
            return NaruToolParameter.string(name, description, required)
                    .enumValues(Arrays.stream(raw.getEnumConstants()).map(x -> ((Enum<?>) x).name()).toArray());
        }
        if (raw.isArray()) {
            return NaruToolParameter.array(name, description, required,
                    schema("item", "", raw.getComponentType(), true).build());
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return NaruToolParameter.array(name, description, required,
                    schema("item", "", typeArgument(type, 0), true).build());
        }
        if (raw.isRecord()) {
            List<NaruToolParameter> properties = new ArrayList<>();
            for (RecordComponent c : raw.getRecordComponents()) {
                boolean optional = c.getType() == Optional.class;
                Type t = optional ? typeArgument(c.getGenericType(), 0) : c.getGenericType();
                properties.add(schema(c.getName(), "", t, !optional).build());
            }
            return NaruToolParameter.object(name, description, required,
                    properties.toArray(new NaruToolParameter[0])).additionalProperties(false);
        }
        // maps, NElement and anything else nuts can map: an open object
        return NaruToolParameter.object(name, description, required);
    }

    // ── call ─────────────────────────────────────────────────────────────────

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain(description);
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return definition;
    }

    @Override
    public boolean isEssential() {
        return essential;
    }

    @Override
    public String execute(NaruToolCallContext context) {
        Object[] values = new Object[args.size()];
        for (int i = 0; i < values.length; i++) {
            Arg arg = args.get(i);
            switch (arg.source()) {
                case CONTEXT -> values[i] = context;
                case TASK -> values[i] = context.task();
                case SESSION -> values[i] = context.task() == null ? null : context.task().session();
                case MODEL -> {
                    NElement e = context.arg(arg.name()).orNull();
                    if ((e == null || e.isNull()) && !arg.defaultValue().isEmpty()) {
                        e = NElement.ofString(arg.defaultValue());
                    }
                    if (e == null || e.isNull()) {
                        if (arg.optional()) {
                            values[i] = Optional.empty();
                        } else if (arg.required()) {
                            return "ERROR: missing required argument '" + arg.name() + "' for " + name();
                        } else {
                            values[i] = zero(rawClass(arg.type()));
                        }
                        continue;
                    }
                    try {
                        values[i] = convert(e, arg.type());
                    } catch (RuntimeException ex) {
                        return "ERROR: invalid value for argument '" + arg.name() + "' of " + name() + ": "
                                + message(ex);
                    }
                }
            }
        }
        Object target;
        try {
            target = instance == null ? null : instance.get();
        } catch (RuntimeException ex) {
            return "ERROR: " + name() + " is unavailable: " + message(ex);
        }
        try {
            Object result = method.invoke(target, values);
            return render(result);
        } catch (InvocationTargetException ex) {
            return "ERROR: " + message(ex.getCause() == null ? ex : ex.getCause());
        } catch (IllegalAccessException ex) {
            return "ERROR: " + name() + " cannot be called: " + message(ex);
        }
    }

    private String render(Object result) {
        if (method.getReturnType() == void.class) {
            return "ok";
        }
        if (result == null) {
            return "";
        }
        if (result instanceof CharSequence || result instanceof Number || result instanceof Boolean
                || result instanceof Character || result instanceof Enum<?>) {
            return String.valueOf(result);
        }
        return NElementWriter.ofJson().formatPlain(result);
    }

    private static String message(Throwable t) {
        return t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
    }

    // ── conversion ───────────────────────────────────────────────────────────

    static Object convert(NElement e, Type type) {
        if (e == null || e.isNull()) {
            return null;
        }
        Class<?> raw = rawClass(type);
        if (NElement.class.isAssignableFrom(raw)) {
            return e;
        }
        if (raw == Optional.class) {
            return Optional.ofNullable(convert(e, typeArgument(type, 0)));
        }
        String text = e.asStringValue().orElse(null);
        if (raw == String.class || raw == CharSequence.class) {
            return text != null ? text : NElementWriter.ofJson().formatPlain(e);
        }
        String scalar = (text != null ? text : e.toString()).strip();
        if (raw == char.class || raw == Character.class) {
            if (scalar.length() != 1) {
                throw new IllegalArgumentException("expected a single character, got '" + scalar + "'");
            }
            return scalar.charAt(0);
        }
        if (raw == int.class || raw == Integer.class) {
            return new BigDecimal(scalar).intValueExact();
        }
        if (raw == long.class || raw == Long.class) {
            return new BigDecimal(scalar).longValueExact();
        }
        if (raw == short.class || raw == Short.class) {
            return new BigDecimal(scalar).shortValueExact();
        }
        if (raw == byte.class || raw == Byte.class) {
            return new BigDecimal(scalar).byteValueExact();
        }
        if (raw == BigInteger.class) {
            return new BigDecimal(scalar).toBigIntegerExact();
        }
        if (raw == double.class || raw == Double.class || raw == Number.class) {
            return Double.parseDouble(scalar);
        }
        if (raw == float.class || raw == Float.class) {
            return Float.parseFloat(scalar);
        }
        if (raw == BigDecimal.class) {
            return new BigDecimal(scalar);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return switch (scalar.toLowerCase()) {
                case "true", "yes", "1" -> true;
                case "false", "no", "0" -> false;
                default -> throw new IllegalArgumentException("expected true or false, got '" + scalar + "'");
            };
        }
        if (raw.isEnum()) {
            for (Object c : raw.getEnumConstants()) {
                if (((Enum<?>) c).name().equalsIgnoreCase(scalar)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("expected one of " + Arrays.toString(raw.getEnumConstants())
                    + ", got '" + scalar + "'");
        }
        if (raw.isArray() || Collection.class.isAssignableFrom(raw)) {
            List<NElement> items = e.asArray().map(x -> x.children()).orElse(List.of(e));
            Type itemType = raw.isArray() ? raw.getComponentType() : typeArgument(type, 0);
            if (raw.isArray()) {
                Object array = Array.newInstance(raw.getComponentType(), items.size());
                for (int i = 0; i < items.size(); i++) {
                    Array.set(array, i, convert(items.get(i), itemType));
                }
                return array;
            }
            Collection<Object> out = Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
            for (NElement item : items) {
                out.add(convert(item, itemType));
            }
            return out;
        }
        if (raw.isRecord()) {
            return convertRecord(e, raw);
        }
        return NElements.of().fromElement(e, type);
    }

    private static Object convertRecord(NElement e, Class<?> raw) {
        RecordComponent[] components = raw.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Map<String, NElement> fields = new java.util.LinkedHashMap<>();
        e.asObject().ifPresent(o -> o.namedPairs().forEach(p ->
                fields.put(p.key().asStringValue().orElse(String.valueOf(p.key())), p.value())));
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            NElement v = fields.get(c.getName());
            if (v == null || v.isNull()) {
                values[i] = c.getType() == Optional.class ? Optional.empty() : zero(c.getType());
            } else {
                values[i] = convert(v, c.getGenericType());
            }
        }
        try {
            var ctor = raw.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(values);
        } catch (InvocationTargetException ex) {
            throw new IllegalArgumentException(message(ex.getCause() == null ? ex : ex.getCause()), ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("cannot build " + raw.getSimpleName() + ": " + message(ex), ex);
        }
    }

    private static Object zero(Class<?> raw) {
        return raw.isPrimitive() ? Array.get(Array.newInstance(raw, 1), 0) : null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        return Object.class;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return Object.class;
    }

    static String snakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && (Character.isLowerCase(name.charAt(i - 1)) || Character.isDigit(name.charAt(i - 1))
                        || (i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1))
                        && Character.isUpperCase(name.charAt(i - 1))))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
