package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.Objects;

public class NaruModelKey implements NToElement {
    private final String provider;
    private final String model;

    public NaruModelKey(NElement element) {
        NObjectElement o = element.asObject().get();
        this.provider = o.getStringValue("provider").get();
        this.model = o.getStringValue("name").get();
    }

    public NaruModelKey(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public static NOptional<NaruModelKey> parse(String modelName) {
        if (modelName != null) {
            int i = modelName.indexOf("/");
            if (i > 0) {
                return NOptional.of(new NaruModelKey(modelName.substring(0, i), modelName.substring(i + 1)));
            } else {
                return NOptional.of(new NaruModelKey("ollama", modelName));
            }
        }
        return NOptional.ofNamedEmpty("model " + modelName);
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    @Override
    public NElement toElement() {
        return NElement.ofObjectBuilder()
                .set("model", model)
                .set("provider", provider)
                .build()
                ;
    }

    @Override
    public String toString() {
        return provider + "/" + model;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruModelKey that = (NaruModelKey) o;
        return Objects.equals(provider, that.provider) && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model);
    }

    public NMsg toMsg() {
        return NMsg.ofC(
                "%s%s%s",
                NMsg.ofStyledPrimary9(provider),
                NMsg.ofStyledSeparator("/"),
                NMsg.ofStyledPrimary6(model)
                );
    }
}
