package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.tool.NaruDirective;

public abstract class AbstractDirective implements NaruDirective {
    private String name;
    private String description;

    public AbstractDirective(String name, String description) {
        this.name = name;
        this.description = description;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
