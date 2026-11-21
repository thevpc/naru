package net.thevpc.naru.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a parameter of a {@link NaruToolSpec} method.
 *
 * <p>Optional: without it the parameter is named after the Java parameter, which needs the
 * class compiled with {@code -parameters}; a tool whose names are not available fails when it
 * is registered rather than offering the model {@code arg0}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface NaruArg {

    /** The argument's name as the model sees it. Defaults to the Java parameter name. */
    String name() default "";

    /** What the argument means, for the model filling it in. */
    String description() default "";

    /**
     * Whether the model must supply it. A parameter of type {@code java.util.Optional} or with a
     * {@link #defaultValue()} is optional regardless.
     */
    boolean required() default true;

    /** The value used when the model leaves the argument out; empty means none. */
    String defaultValue() default "";
}
