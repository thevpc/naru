package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NToElement;

import java.util.function.Predicate;

public interface NaruEventFilter extends Predicate<NaruEvent>, NToElement {
    boolean test(NaruEvent event);
}
