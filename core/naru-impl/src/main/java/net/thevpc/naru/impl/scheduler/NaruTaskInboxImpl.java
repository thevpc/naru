package net.thevpc.naru.impl.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

public class NaruTaskInboxImpl implements NaruTaskInbox {
    private final ConcurrentSkipListSet<Long> seqs = new ConcurrentSkipListSet<>();
    private final Set<Long> consumed = ConcurrentHashMap.newKeySet();
    private volatile long watermark = 0;
    private final NaruTask task;

    public NaruTaskInboxImpl(NaruTask task) {
        this.task = task;
    }

    public int size() {
        return seqs.size();
    }

    public boolean isEmpty() {
        return seqs.isEmpty();
    }

    @Override
    public void push(long seq) {
        if(seqs.add(seq)) {
            task.log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] inbox %s", task.id(), seq));
        }
    }

    @Override
    public NaruEvent peek(NaruEventFilter filter) {
        for (long seq : seqs.tailSet(watermark)) {
            if (consumed.contains(seq)) continue;
            NaruEvent event = task.session().eventLog().get(seq);
            if (event == null) {
                _markConsumed(seq);
                advanceWatermark();
                continue;
            }
            if (filter == null || filter.test(event)) {
                return event;
            }
        }
        return null;
    }

    @Override
    public NaruEvent consume(long seq) {
        if (consumed.contains(seq)) {
            return null;
        }
        NaruEvent event = task.session().eventLog().get(seq);
        if (event == null) {
            _markConsumed(seq);
            advanceWatermark();
            return event;
        }
        _markConsumed(seq);
        advanceWatermark();
        return event;
    }

    private void _markConsumed(long seq) {
        task.log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] event consumed %s", task.id(), seq));
        consumed.add(seq);
    }

    @Override
    public List<NaruEvent> drainMatching(NaruEventFilter filter) {
        List<NaruEvent> result = new ArrayList<>();
        for (long seq : seqs.tailSet(watermark)) {
            if (consumed.contains(seq)) continue;
            NaruEvent event = task.session().eventLog().get(seq);
            if (event == null) {
                consumed.add(seq);
                continue;
            }
            if (filter == null || filter.test(event)) {
                consumed.add(seq);
                result.add(event);
            }
        }
        advanceWatermark();
        return result;
    }

    @Override
    public long watermark() {
        return watermark;
    }

    private void advanceWatermark() {
        for (long seq : seqs.tailSet(watermark)) {
            if (consumed.contains(seq)) {
                watermark = seq + 1;
                consumed.remove(seq);
                seqs.remove(seq);
            } else {
                break;
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("watermark", watermark);
        NArrayElementBuilder _events = NArrayElementBuilder.of();
        for (long a : seqs) {
            _events.add(a);
        }
        NArrayElementBuilder _consumed = NArrayElementBuilder.of();
        for (long a : consumed) {
            _consumed.add(a);
        }
        o.set("events", _events.build());
        o.set("consumed", _consumed.build());
        return o.build();
    }

    public void load(NObjectElement inbox1) {
        seqs.clear();
        consumed.clear();
        watermark = 0;
        if (inbox1 != null) {
            NArrayElement _events = inbox1.getArray("events").orNull();
            if (_events != null) {
                for (NElement event : _events) {
                    Long nv = event.asLongValue().orNull();
                    if (nv != null) {
                        seqs.add(nv);
                    }
                }
            }
            NArrayElement _consumed = inbox1.getArray("consumed").orNull();
            if (_consumed != null) {
                for (NElement event : _consumed) {
                    Long nv = event.asLongValue().orNull();
                    if (nv != null) {
                        consumed.add(nv);
                    }
                }
            }
            Long _watermark = inbox1.getLongValue("watermark").orNull();
            if (_watermark != null) {
                watermark = _watermark;
            }
        }
    }
}
