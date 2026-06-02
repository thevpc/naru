package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.nuts.cmdline.NArgCandidate;

import java.util.List;

public abstract class AbstractDirective implements NaruDirective {
    private String name;
    private String group;
    private String description;
    private String[] aliases;

    public AbstractDirective(String name, String group,String description,String ...aliases) {
        this.name = name;
        this.group = group;
        this.description = description;
        this.aliases = aliases;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public String[] getAliases() {
        return aliases;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    protected void addCandidates(List<NArgCandidate> candidates, String prefix, String... options) {
        for (String option : options) {
            if (option.startsWith(prefix)) {
                candidates.add(new net.thevpc.nuts.cmdline.DefaultNArgCandidate(option));
            }
        }
    }
}
