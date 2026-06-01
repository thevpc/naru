package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NToElement;

import java.util.Map;

public interface NaruEventFilter extends NToElement {
    // should this event be collected?
    boolean matches(NaruEvent event, Map<String, NaruEvent> received);
    // should the task unblock now?
    boolean satisfied(Map<String, NaruEvent> received);
}