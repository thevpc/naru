package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.impl.registry.builtindirectives.*;

public class NaruBuiltinDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruBuiltinDirectiveProvider() {
        super("builtin");
        this.registerDirective(new NaruExitDirective());
        this.registerDirective(new NaruPrintDirective());
        this.registerDirective(new NaruHelpDirective());
        this.registerDirective(new NaruBufferDirective());
        this.registerDirective(new NaruAssertDirective());
        this.registerDirective(new NaruGoDirective());
    }

}