package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NOptional;

public class PsDirective extends AbstractDirective {
    public PsDirective() {
        super("ps","general", "show used model/VRAM");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
        //http://localhost:11434/api/ps

        String url = session.agent().env().get("ollama" + ".url").flatMap(x -> x.asStringValue()).orElse("http://localhost:11434");
        url =url.replaceAll("/$", "");
        NElement nElement = NWebCli.of().GET(url + "/api/ps")
                .run()
                .contentAsJson();
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", NText.ofCode("tson", NElementWriter.ofPlain().formatPlain(nElement))));
    }
}
