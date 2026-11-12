package net.thevpc.naru.ext.assess;

import java.util.Locale;

/** How hard a task is; also sets the default wall-clock cap of a run. */
public enum AssessTier {
    SIMPLE(5),
    NORMAL(10),
    COMPLEX(20),
    EXPERT(30);

    private final int defaultMinutes;

    AssessTier(int defaultMinutes) {
        this.defaultMinutes = defaultMinutes;
    }

    public int defaultMinutes() {
        return defaultMinutes;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AssessTier parse(String s) {
        for (AssessTier t : values()) {
            if (t.label().equalsIgnoreCase(s)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown tier '" + s + "' (expected simple|normal|complex|expert)");
    }
}
