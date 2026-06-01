package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public interface NaruRegistry {


    Map<String, NaruTool> tools();

    Map<String, NaruDirective> directives();

    NaruRegistry registerTool(NaruTool tool);

    NaruRegistry registerDirective(NaruDirective tool);

    NaruRegistry registerModelProvider(NaruModelProvider tool);

    String dispatch(String name, Map<String, Object> arguments, NaruTask context);

    String dispatch(NaruToolCall toolCall, NaruTask context);

    NOptional<NaruDirective> findDirective(String name);

    void dispatchSlash(String name, String argument, NaruTask task);

    boolean isEmpty();

    Set<String> names();

    Map<String, NaruModelProvider> modelProviders();

    List<NaruModelInfo> modelsInfos(NaruSession session);

    List<NaruModelKey> modelsKeys(NaruSession session);

    NOptional<NaruModelKey> findModel(String keyOrName, NaruSession session);

    NOptional<NaruModelProvider> provider(String provider);

    NOptional<NaruModelProtocol> protocol(NaruModelConfig model, NaruSession session);

    List<NaruPromptMode> modes();

    List<String> modeNames();
    List<String> modeNamesAndAliases();

    void declareMode(NaruPromptMode mode);

    NOptional<NaruPromptMode> mode(NaruStandardMode mode);

    NOptional<NaruPromptMode> mode(String mode);
}
