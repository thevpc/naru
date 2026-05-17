package net.thevpc.naru.api.agent;

public enum NaruSource {
    SYSTEM, MD, DOTMD, USER;

    public String id() {
        return name().toLowerCase();
    }
}
