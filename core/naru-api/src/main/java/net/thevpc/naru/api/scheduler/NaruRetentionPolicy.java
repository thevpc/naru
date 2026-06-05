package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NToElement;

public interface NaruRetentionPolicy extends NToElement {
    boolean shouldDrop(NaruEvent event);
    long nextCheckMillis(NaruEvent event);
}
