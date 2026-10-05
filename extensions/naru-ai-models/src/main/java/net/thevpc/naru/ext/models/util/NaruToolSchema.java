package net.thevpc.naru.ext.models.util;

import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.nuts.elem.NElement;

import java.util.List;

/**
 * Moved to {@link net.thevpc.naru.api.registry.NaruToolSchema}, which sits beside
 * {@link NaruToolParameter} in {@code naru-api}.
 *
 * <p>The writer is protocol-neutral, and a module that has to render a tool schema --
 * {@code naru-budget}'s {@code /budget} estimator prices the definitions it sends --
 * cannot be expected to depend on one extension to reach it. This forwarding shell
 * stays so that the published class keeps existing; new code should import the
 * {@code naru-api} one.
 */
@Deprecated
public class NaruToolSchema {

    private NaruToolSchema() {
    }

    public static NElement paramToSchema(NaruToolParameter p) {
        return net.thevpc.naru.api.registry.NaruToolSchema.paramToSchema(p);
    }

    public static NElement functionSchema(List<NaruToolParameter> params) {
        return net.thevpc.naru.api.registry.NaruToolSchema.functionSchema(params);
    }
}