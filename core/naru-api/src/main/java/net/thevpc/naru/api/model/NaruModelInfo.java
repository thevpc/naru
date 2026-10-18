package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NOptional;

import java.util.Objects;

public class NaruModelInfo implements NToElement {
    private final String provider;
    private final String model;
    private final NaruModelCapabilities capabilities;
    private final String quantization;
    private final long sizeBytes;
    private final String parameterSize;
    private final String family;

    public NaruModelInfo(String provider, String model, NaruModelCapabilities capabilities) {
        this(provider, model, capabilities, null, -1, null, null);
    }

    public NaruModelInfo(String provider, String model, NaruModelCapabilities capabilities, String quantization, long sizeBytes, String parameterSize, String family) {
        this.provider = provider;
        this.model = model;
        this.capabilities = capabilities;
        this.quantization = quantization;
        this.sizeBytes = sizeBytes;
        this.parameterSize = parameterSize;
        this.family = family;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public NaruModelCapabilities capabilities() { return capabilities; }
    public String quantization() { return quantization; }
    public long sizeBytes() { return sizeBytes; }
    public String parameterSize() { return parameterSize; }
    public String family() { return family; }

    @Override
    public NElement toElement() {
        return NElement.ofObjectBuilder()
                .set("model", model)
                .set("provider", provider)
                .set("capabilities", capabilities == null ? NElement.ofNull() : capabilities.toElement())
                .set("quantization", quantization)
                .set("sizeBytes", sizeBytes > 0 ? sizeBytes : null)
                .set("parameterSize", parameterSize)
                .set("family", family)
                .build()
                ;
    }

    @Override
    public String toString() {
        return provider + "/" + model + " " + capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruModelInfo that = (NaruModelInfo) o;
        return sizeBytes == that.sizeBytes && Objects.equals(provider, that.provider)
                && Objects.equals(model, that.model) && Objects.equals(capabilities, that.capabilities)
                && Objects.equals(quantization, that.quantization)
                && Objects.equals(parameterSize, that.parameterSize) && Objects.equals(family, that.family);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model, capabilities, quantization, sizeBytes, parameterSize, family);
    }

    public NText toText() {
        NTextBuilder b = NTextBuilder.of();
        b.append(provider, NTextStyle.primary9())
                .append("/", NTextStyle.separator())
                .append(model, NTextStyle.primary6());
        b.append(" (", NTextStyle.separator());
        b.appendJoined(NText.ofStyled(",", NTextStyle.separator()), capabilities.keys());
        b.append(")", NTextStyle.separator());
        return b.build();
    }

    public NaruModelKey key() {
        return new NaruModelKey(provider, model);
    }
}
