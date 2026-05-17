package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruToolCallStmt extends NaruStatement {
    public NaruToolCall call;

    public NaruToolCallStmt(NaruToolCall call) {
        super(Type.TOOL_CALL);
        this.call = call;
    }

    public NaruToolCallStmt(NElement element) {
        super(Type.TOOL_CALL);
        String name;
        if (element.isName()) {
            name = element.asName().get().stringValue();
        } else if (element.isAnyObject()) {
            NObjectElement o = element.asObject().get();
            name = o.asNamed().get().name().get();
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
        }
        switch (NNameFormat.CONST_NAME.format(name)) {
            case "TOOL_CALL": {
                this.call = new NaruToolCall(element.asObject().get().get("call").get());
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        if (call != null) {
            a.set("call", call.toElement());
        }
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        session.log(NaruLogMode.PROGRESS, NMsg.ofC("%s Tool: %s(%s)",
                        NMsg.ofStyledPrimary9("🔧"),
                        NMsg.ofStyledPrimary1(call.getName()), call.getArguments()
                )
        );
        String result = session.registry().dispatch(call, session);
        session.log(NaruLogMode.PROGRESS, NMsg.ofC("  %s Result: %s",
                NMsg.ofStyledPrimary6("📤"),
                NaruUtils.abbreviate(result, 300)));
        session.addHistory(NaruMessage.tool(call.getName(), call.getId(), result));
    }
}
