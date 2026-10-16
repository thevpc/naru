package net.thevpc.naru.api;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;

public class Naru {
    public static NaruWorkspaceBuilder workspaceBuilder() {
        return NaruWorkspaceBuilder.of();
    }

    public static NaruWorkspace workspace() {
        return workspaceBuilder().build();
    }
}
