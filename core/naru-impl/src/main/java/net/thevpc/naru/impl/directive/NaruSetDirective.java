package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.expr.NExprContext;
import net.thevpc.nuts.expr.NExprNode;
import net.thevpc.nuts.expr.NExprOpNode;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

public class NaruSetDirective extends AbstractDirective {
    public NaruSetDirective() {
        super("set", "routine", "set variable value");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        String raw = context.argument();
        NExprContext b = context.task().expressionBuilder().build();
        NOptional<NExprNode> n = b.parse(raw);
        if (n.isPresent()) {
            NExprNode a = n.get();
            if (a instanceof NExprOpNode && a.name().equals("=") && a.children().size() == 2) {
                //this will assign var using varResolver (and store it into runcontext
                a.eval(b).get();
                return;
            }
        }
        context.task().throwError(NMsg.ofC("expected var = <expr>"));

    }
}
