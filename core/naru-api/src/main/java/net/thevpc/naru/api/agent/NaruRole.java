package net.thevpc.naru.api.agent;

public enum NaruRole {
    assistant, user, system, tool;

    public String id() {
        return name().toLowerCase();
    }
}
