package net.thevpc.naru.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a method a tool the model can call.
 *
 * <pre>{@code
 * public class OrderTools {
 *     @NaruToolSpec(description = "Return the shipping status of an order.", tags = "network")
 *     public String lookupOrder(@NaruArg(description = "The order id") String orderId) { ... }
 * }
 * session.registry().registerToolsetProviderType(OrderTools.class);
 * }</pre>
 *
 * <p>The schema the model sees is derived from the signature: {@code String}, numbers,
 * {@code boolean}, enums, arrays and collections, records and maps. Parameters of type
 * {@link net.thevpc.naru.api.registry.NaruToolCallContext}, {@link net.thevpc.naru.api.task.NaruTask}
 * or {@link net.thevpc.naru.api.agent.NaruSession} are supplied by the runtime and never shown to
 * the model. A {@code String} return is passed through, {@code void} answers {@code ok}, any
 * other value is sent as JSON, and an exception becomes the engine's {@code ERROR: ...} result.
 *
 * <p>Static methods are called as they are; instance methods on an instance created once per
 * session, with the class's no-argument constructor, the first time one is needed.
 *
 * <p>A class with annotated methods never implements {@link net.thevpc.naru.api.registry.NaruTool}
 * itself: each method becomes one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NaruToolSpec {

    /** The tool's name as the model sees it. Defaults to the method name in snake_case. */
    String name() default "";

    /** What the tool does, for the model choosing which tool to call. */
    String description();

    /**
     * The capabilities the tool needs ({@code fs}, {@code network}, {@code write}, {@code exec},
     * ...). The tool gate is fail-closed: a task sees a tool only if it holds one of its tags, so
     * a tool needs at least one tag unless it is {@link #essential()}.
     */
    String[] tags() default {};

    /** Offered to every task regardless of tags. Reserve it for tools that are always safe. */
    boolean essential() default false;
}
