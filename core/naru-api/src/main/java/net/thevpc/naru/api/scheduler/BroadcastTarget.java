package net.thevpc.naru.api.scheduler;

import net.thevpc.naru.api.task.NaruTask;

public class BroadcastTarget implements NaruEventTarget{
    @Override
    public boolean matches(NaruTask candidate) {
        return true;
    }
}
