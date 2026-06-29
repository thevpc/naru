package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.impl.registry.builtindirectives.*;
import net.thevpc.naru.impl.registry.builtindirectives.ai.*;
import net.thevpc.naru.impl.registry.builtindirectives.fs.NaruCatDirective;
import net.thevpc.naru.impl.registry.builtindirectives.fs.NaruFileDirective;
import net.thevpc.naru.impl.registry.builtindirectives.fs.NaruLsDirective;
import net.thevpc.naru.impl.registry.builtindirectives.fs.NaruPwdDirective;
import net.thevpc.naru.impl.registry.builtindirectives.general.*;
import net.thevpc.naru.impl.registry.builtindirectives.routine.*;
import net.thevpc.naru.impl.registry.builtindirectives.session.*;
import net.thevpc.naru.impl.registry.builtindirectives.task.*;

import java.util.*;

public class NaruBuiltinDirectiveProvider implements NaruDirectiveProvider {
    private final Map<String,NaruDirective> availableDirectives = new LinkedHashMap<>();
    private final Map<String, String> directiveAliases = new LinkedHashMap<>();

    public NaruBuiltinDirectiveProvider() {
        this.registerDirective(new NaruRoutineDirective());
        this.registerDirective(new NaruExitDirective());
        this.registerDirective(new NaruPrintDirective());
        this.registerDirective(new NaruHelpDirective());
        this.registerDirective(new NaruToolsDirective());
        this.registerDirective(new NaruStatsDirective());
        this.registerDirective(new NaruModelDirective());
        this.registerDirective(new NaruModeDirective());
        this.registerDirective(new NaruPwdDirective());
        this.registerDirective(new NaruCdDirective());
        this.registerDirective(new NaruCatDirective());
        this.registerDirective(new NaruBufferDirective());
        this.registerDirective(new NaruHistoryDirective());
        this.registerDirective(new NaruSessionDirective());
        this.registerDirective(new NaruShDirective());
        this.registerDirective(new NaruLsDirective());
        this.registerDirective(new NaruFileDirective());
        this.registerDirective(new NaruSetDirective());
        this.registerDirective(new NaruUseDirective());
        this.registerDirective(new NaruSkillDirective());
        this.registerDirective(new NaruSystemDirective());
//        this.registerDirective(new NaruWhileDirective());
//        this.registerDirective(new NaruForDirective());
//        this.registerDirective(new NaruIfDirective());
//        this.registerDirective(new NaruElseDirective());
//        this.registerDirective(new NaruElseIfDirective());
//        this.registerDirective(new NaruEndDirective());
//        this.registerDirective(new NaruCallDirective());
        this.registerDirective(new NaruReloadDirective());
        this.registerDirective(new NaruNewDirective());
        this.registerDirective(new NaruRestoreDirective());
        this.registerDirective(new NaruSaveDirective());
        this.registerDirective(new NaruResetDirective());
        this.registerDirective(new NaruContextDirective());
        this.registerDirective(new NaruGoDirective());
        this.registerDirective(new NaruOnDirective());
        this.registerDirective(new NaruFireDirective());
        this.registerDirective(new NaruSourceDirective());
        this.registerDirective(new NaruStartDirective());
        this.registerDirective(new NaruTaskDirective());
        this.registerDirective(new NaruSleepDirective());
        this.registerDirective(new NaruWaitDirective());
    }

    private NaruBuiltinDirectiveProvider registerDirective(NaruDirective tool) {
        availableDirectives.put(tool.name(), tool);
        for (String alias : tool.getAliases()) {
            String old = directiveAliases.get(alias);
            if (old != null && !old.equals(tool.name())) {
                throw new IllegalArgumentException("alias " + alias + " is already used by " + old);
            }
            directiveAliases.put(alias, tool.name());
        }
        return this;
    }


    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<NaruDirective> directives() {
        return Collections.unmodifiableList(new ArrayList<>(availableDirectives.values()));
    }
}