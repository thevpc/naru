package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NToElement;

public interface NaruEventTarget extends NToElement {
    boolean test(NaruTask candidate);
}
