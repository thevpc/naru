package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.log.NMsgIntent;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.logging.Level;

class NormalizedMsg implements NToElement {
    private NText text;
    private Level level;
    private NMsgIntent intent;
    private NDuration duration;

    public NormalizedMsg(NMsg text) {
        this.text = NText.of(text);
        this.level = text.level();
        this.intent = text.intent();
        this.duration = text.duration();
    }

    public static NOptional<NormalizedMsg> of(NElement text) {
        if (text == null) {
            return NOptional.ofNamedEmpty("normalized msg");
        }
        if (text instanceof NStringElement) {
            return NOptional.of(new NormalizedMsg(NMsg.ofP(text.asStringValue().orNull())));
        }
        if (text instanceof NPrimitiveElement) {
            Object value = ((NPrimitiveElement) text).value();
            if (value == null) {
                return NOptional.ofNamedEmpty("normalized msg");
            }
            return NOptional.of(new NormalizedMsg(NMsg.ofP(text.asStringValue().orNull())));
        }
        if (!(text instanceof NObjectElement o)) {
            return NOptional.ofNamedEmpty("normalized msg");
        }
        String stext = o.get("text").map(x -> x.asStringValue().orNull()).orNull();
        Level level = o.get("level").map(x -> {
            String sl = x.asStringValue().orNull();
            if (NBlankable.isBlank(sl)) {
                return null;
            }
            try {
                return Level.parse(sl);
            } catch (Exception ex) {
                return null;
            }
        }).orNull();
        NMsgIntent intent = o.get("intent").map(x -> {
            String sl = x.asStringValue().orNull();
            if (NBlankable.isBlank(sl)) {
                return null;
            }
            try {
                return NMsgIntent.of(sl);
            } catch (Exception ex) {
                return null;
            }
        }).orNull();
        NDuration dur = o.get("duration").map(x -> {
            String sl = x.asStringValue().orNull();
            if (NBlankable.isBlank(sl)) {
                return null;
            }
            try {
                return NDuration.of(sl).orNull();
            } catch (Exception ex) {
                return null;
            }
        }).orNull();
        return NOptional.of(new NormalizedMsg(
                        NMsg.ofNtf(stext)
                                .withDuration(dur)
                                .withIntent(intent)
                                .withLevel(level)
                )
        );
    }

    public NMsg toMsg(){
        boolean plain = (intent == null && duration == null
                && (level == null || level == java.util.logging.Level.INFO));
        if (plain) {
            return NMsg.ofP(text == null ? "" : text.toString());
        }
        return NMsg.ofNtf(text)
                .withDuration(duration)
                .withIntent(intent)
                .withLevel(level);
    }

    @Override
    public NElement toElement() {
        // A plain message (no intent, no duration, and the default level) serializes as a
        // bare string so that session files written before NormalizedMsg existed stay
        // byte-identical and readable as `{"content":"hello"}`.
        boolean plain = (intent == null && duration == null
                && (level == null || level == java.util.logging.Level.INFO));
        if (plain) {
            return NElement.ofString(text == null ? "" : text.toString());
        }
        NObjectElementBuilder b = NObjectElementBuilder.of();
        b.add("text", text == null ? "" : text.toString());
        if (level != null) {
            b.add("level", level.getName());
        }
        if (intent != null) {
            b.add("intent", intent.name());
        }
        if (duration != null) {
            b.add("duration", duration.toString());
        }
        return b.build();
    }
}
