package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.annotations.NaruToolSpec;
import net.thevpc.nuts.elem.NObjectElement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds tools from {@link net.thevpc.naru.api.annotations.NaruToolSpec} methods.
 *
 * <pre>{@code
 * session.registry().registerToolsetProviderType(OrderTools.class);            // the usual way
 * session.registry().registerToolsetProvider(NaruTools.provider("orders", () -> new OrderTools(db)));
 * NaruToolset shared = NaruTools.toolset("orders", orderTools);                // one instance, as is
 * }</pre>
 *
 * <p>Everything that can be wrong with a tool class is reported when its tools are built, not
 * when the model first calls one: an annotated method that is not public, a parameter with no
 * name, two tools with one name, a tool no task could ever be offered.
 */
public final class NaruTools {
    private NaruTools() {
    }

    /**
     * A provider for {@code type}: static methods are called directly; instance methods on an
     * instance created, with the no-argument constructor, the first time one is called in each
     * session. This is what {@link NaruRegistry#registerToolsetProviderType(Class)} registers.
     */
    public static NaruToolsetProvider provider(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return new AnnotatedToolsetProvider(type.getName(), type, () -> newInstance(type));
    }

    /**
     * A provider whose tools run on an instance from {@code factory}, made anew for each session
     * so state such as a cache or a connection is never shared between sessions.
     */
    public static NaruToolsetProvider provider(String id, Supplier<?> factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        return new AnnotatedToolsetProvider(id, null, factory);
    }

    /** The tools of {@code instance}'s class, all running on that one instance. */
    public static NaruToolset toolset(String id, Object instance) {
        Objects.requireNonNull(instance, "instance");
        return new DefaultNaruToolset(id, tools(instance.getClass(), () -> instance));
    }

    /**
     * The tools declared by {@code type}, instance methods running on what {@code instance}
     * supplies. The supplier is asked only when an instance method is called, so a class of
     * static tools is never instantiated.
     */
    public static List<NaruTool> tools(Class<?> type, Supplier<?> instance) {
        rejectHiddenTools(type);
        Supplier<?> once = memoize(instance);
        List<NaruTool> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Method method : type.getMethods()) {
            NaruToolSpec annotation =
                    method.getAnnotation(NaruToolSpec.class);
            if (annotation == null) {
                continue;
            }
            NaruMethodTool tool = new NaruMethodTool(method, annotation,
                    Modifier.isStatic(method.getModifiers()) ? null : once);
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException(type.getName() + " declares two tools named '"
                        + tool.name() + "'");
            }
            out.add(tool);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(type.getName() + " has no public method annotated with @NaruToolSpec");
        }
        // getMethods() has no defined order; the model should see the same list every time
        out.sort(Comparator.comparing(NaruTool::name));
        return out;
    }

    private static void rejectHiddenTools(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(NaruToolSpec.class)
                        && !Modifier.isPublic(m.getModifiers())) {
                    throw new IllegalArgumentException("@NaruToolSpec method " + c.getName() + "." + m.getName()
                            + " must be public");
                }
            }
        }
    }

    private static Supplier<?> memoize(Supplier<?> supplier) {
        return new Supplier<>() {
            private volatile Object value;

            @Override
            public Object get() {
                Object v = value;
                if (v == null) {
                    synchronized (this) {
                        v = value;
                        if (v == null) {
                            v = Objects.requireNonNull(supplier.get(), "tool instance");
                            value = v;
                        }
                    }
                }
                return v;
            }
        };
    }

    private static Object newInstance(Class<?> type) {
        try {
            Constructor<?> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getName()
                    + " has instance @NaruToolSpec methods but no no-argument constructor;"
                    + " make them static or register it with NaruTools.provider(id, factory)", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("could not create " + type.getName() + ": " + cause, cause);
        }
    }

    /** One toolset per session, built from the class (or the factory's instance) when registered. */
    private static final class AnnotatedToolsetProvider implements NaruToolsetProvider {
        private final String id;
        private final Class<?> type;
        private final Supplier<?> factory;

        AnnotatedToolsetProvider(String id, Class<?> type, Supplier<?> factory) {
            this.id = id;
            this.type = type;
            this.factory = factory;
            if (type != null) {
                // fail at registration, not when the first session asks for the toolset
                tools(type, factory);
            }
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public List<String> supportedTypes() {
            return List.of(id);
        }

        @Override
        public boolean accept(String toolsetId, NObjectElement config) {
            // ids are class names, which kebab-case normalisation would mangle
            return id.equals(toolsetId);
        }

        @Override
        public NaruToolset createToolset(String toolsetId, NObjectElement config) {
            if (type != null) {
                return new DefaultNaruToolset(toolsetId, tools(type, factory));
            }
            Object instance = factory.get();
            return new DefaultNaruToolset(toolsetId, tools(instance.getClass(), () -> instance));
        }
    }
}
