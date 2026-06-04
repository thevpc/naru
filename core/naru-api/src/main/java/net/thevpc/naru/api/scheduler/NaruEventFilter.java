package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NToElement;

import java.util.List;

public interface NaruEventFilter extends NToElement {
    // should this event be collected?
    boolean matches(NaruEvent event);
    // should the task unblock now?
//    boolean satisfied(List<NaruEvent> received);
}
