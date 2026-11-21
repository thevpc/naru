package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

/**
 * A model call made by a task: the session's context, plus the task's own env first in the
 * configuration chain and its history as the place notices go.
 */
final class NaruTaskModelContext extends NaruSessionModelContext {
    final NaruTask task;

    NaruTaskModelContext(NaruTask task) {
        super(task.session());
        this.task = task;
    }

    @Override
    public NOptional<NElement> config(String key) {
        return NaruTaskConfig.find(task, key);
    }

    @Override
    public void notice(NMsg message) {
        task.addHistory(NaruMessage.engine(message));
    }

    @Override
    public long taskId() {
        return task.id();
    }

    @Override
    public String taskName() {
        return task.name();
    }
}
