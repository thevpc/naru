package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.*;
import java.util.stream.Collectors;

public class NaruModelConfig implements NToElement {
    private final String name;
    private final String provider;
    private final String model;
    private final Long contextLength;
    private final Float temperature;
    //top_p
    private final Float nucleusThreshold;
    //top_p
    private final Integer candidateCount;
    private final Integer maxTokens;   // num_predict in Ollama
    private final List<String> stop;

    public static NOptional<NaruModelConfig> of(NElement element) {
        if (element != null) {
            if (element.isListContainer()) {
                String provider = null;
                String model = null;
                String name = null;
                Long contextLength = null;
                Float temperature = null;
                Float nucleusThreshold = null;
                Integer candidateCount = null;
                Integer maxTokens = null;   // num_predict in Ollama
                List<String> stop = null;
                for (NPairElement namedPair : element.asListContainer().get().namedPairs()) {
                    switch (namedPair.key().asStringValue().orElse("")) {
                        case "name": {
                            name = namedPair.value().asStringValue().orElse(null);
                            break;
                        }
                        case "provider": {
                            provider = namedPair.value().asStringValue().orElse(null);
                            break;
                        }
                        case "model": {
                            model = namedPair.value().asStringValue().orElse(null);
                            break;
                        }
                        case "contextLength": {
                            contextLength = namedPair.value().asLongValue().orElse(null);
                            break;
                        }
                        case "temperature": {
                            temperature = namedPair.value().asFloatValue().orElse(null);
                            break;
                        }
                        case "nucleusThreshold": {
                            nucleusThreshold = namedPair.value().asFloatValue().orElse(null);
                            break;
                        }
                        case "candidateCount": {
                            candidateCount = namedPair.value().asIntValue().orElse(null);
                            break;
                        }
                        case "maxTokens": {
                            maxTokens = namedPair.value().asIntValue().orElse(null);
                            break;
                        }
                        case "stop": {
                            stop = namedPair.value().asArray().map(x -> x.children().stream().map(y -> y.asStringValue().orNull()).filter(y -> y != null).collect(Collectors.toList())).orElse(null);
                            break;
                        }
                    }
                }
                return NOptional.of(new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop));
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("config from %s", element));
    }

    public NaruModelConfig(NaruModelKey provider) {
        this(null, provider.provider(), provider.model(), null, null, null, null, null, null);
    }

    public NaruModelConfig(String provider, String model) {
        this(null, provider, model, null, null, null, null, null, null);
    }

    public NaruModelConfig(String name, String provider, String model, Long contextLength, Float temperature, Float nucleusThreshold, Integer candidateCount, Integer maxTokens, List<String> stop) {
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.contextLength = contextLength;
        this.temperature = temperature;
        this.nucleusThreshold = nucleusThreshold;
        this.candidateCount = candidateCount;
        this.maxTokens = maxTokens;
        this.stop = stop == null ? Collections.emptyList() : Collections.unmodifiableList(stop.stream().filter(x -> x != null).distinct().collect(Collectors.toList()));
    }

    public NaruModelConfig(NElement element) {
        if (element.isAnyStringOrName()) {
            NOptional<NaruModelKey> parse = NaruModelKey.parse(element.asStringValue().get());
            this.provider = parse.get().provider();
            this.model = parse.get().model();
            this.name = null;
            this.contextLength = null;
            this.temperature = null;
            this.nucleusThreshold = null;
            this.candidateCount = null;
            this.maxTokens = null;
            this.stop = Collections.emptyList();
        } else if (element.isListContainer()) {
            NListContainerElement o = element.asListContainer().get();
            this.name = o.getStringValue("name").orNull();
            this.provider = o.getStringValue("provider").get();
            this.model = o.getStringValue("model").get();
            this.contextLength = o.getLongValue("contextLength").orNull();
            this.temperature = o.getFloatValue("temperature").orNull();
            this.nucleusThreshold = o.getFloatValue("nucleusThreshold").orNull();
            this.candidateCount = o.getIntValue("candidateCount").orNull();
            this.maxTokens = o.getIntValue("maxTokens").orNull();
            this.stop = Collections.unmodifiableList(o.getArray("stop").map(x -> x.children().stream().map(y -> y.asStringValue().orNull()).filter(y -> y != null).collect(Collectors.toList())).orElse(new ArrayList<>()));
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid config element %s", element));
        }
    }


    public NaruModelConfig withName(String name) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withProvider(String provider) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withModel(String model) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withContextLength(Long contextLength) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withTemperature(Float temperature) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withNucleusThreshold(Float nucleusThreshold) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withCandidateCount(Integer candidateCount) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withMaxTokens(Integer maxTokens) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    public NaruModelConfig withStop(List<String> stop) {
        return new NaruModelConfig(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }

    @Override
    public NElement toElement() {
        return NElement.ofObjectBuilder()
                .set("name", name)
                .set("model", model)
                .set("provider", provider)
                .set("contextLength", contextLength)
                .set("temperature", temperature)
                .set("nucleusThreshold", nucleusThreshold)
                .set("candidateCount", candidateCount)
                .set("maxTokens", maxTokens)
                .build()
                ;
    }

    public String name() {
        return name;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public Long contextLength() {
        return contextLength;
    }

    public Float temperature() {
        return temperature;
    }

    public Float nucleusThreshold() {
        return nucleusThreshold;
    }

    public Integer candidateCount() {
        return candidateCount;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public List<String> stop() {
        return stop;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (!NBlankable.isBlank(name)) {
            sb.append(name);
            sb.append(" as ");
        }
        sb.append(provider).append("/").append(model);
        if (contextLength != null) {
            sb.append(" contextLength=").append(contextLength);
        }
        if (temperature != null) {
            sb.append(" temperature=").append(temperature);
        }
        if (nucleusThreshold != null) {
            sb.append(" nucleusThreshold=").append(nucleusThreshold);
        }
        if (candidateCount != null) {
            sb.append(" candidateCount=").append(candidateCount);
        }
        if (maxTokens != null) {
            sb.append(" maxTokens=").append(maxTokens);
        }
        if (stop != null && !stop.isEmpty()) {
            sb.append(" stop=").append(stop);
        }
        return sb.toString();
    }


    public NText toText() {
        NTextBuilder b = NTextBuilder.of();
        if (!NBlankable.isBlank(name)) {
            b.append(name, NTextStyle.primary1());
            b.append(" as ");
        }
        b.append(provider, NTextStyle.primary9())
                .append("/", NTextStyle.separator())
                .append(model, NTextStyle.primary6());

        if (contextLength != null) {
            b.append(NText.ofStyled(", ", NTextStyle.separator()));
            b.append(NText.ofStyled("contextLength", NTextStyle.primary3()));
            b.append(NText.ofStyled("=", NTextStyle.separator()));
            b.append(NText.ofStyled(String.valueOf(contextLength), NTextStyle.number()));
        }
        if (temperature != null) {
            b.append(NText.ofStyled(", ", NTextStyle.separator()));
            b.append(NText.ofStyled("temperature", NTextStyle.primary3()));
            b.append(NText.ofStyled("=", NTextStyle.separator()));
            b.append(NText.ofStyled(String.valueOf(temperature), NTextStyle.number()));
        }

        if (nucleusThreshold != null) {
            b.append(NText.ofStyled(", ", NTextStyle.separator()));
            b.append(NText.ofStyled("nucleusThreshold", NTextStyle.primary3()));
            b.append(NText.ofStyled("=", NTextStyle.separator()));
            b.append(NText.ofStyled(String.valueOf(nucleusThreshold), NTextStyle.number()));
        }
        if (candidateCount != null) {
            b.append(NText.ofStyled(", ", NTextStyle.separator()));
            b.append(NText.ofStyled("candidateCount", NTextStyle.primary3()));
            b.append(NText.ofStyled("=", NTextStyle.separator()));
            b.append(NText.ofStyled(String.valueOf(candidateCount), NTextStyle.number()));
        }
        if (maxTokens != null) {
            b.append(NText.ofStyled(", ", NTextStyle.separator()));
            b.append(NText.ofStyled("maxTokens", NTextStyle.primary3()));
            b.append(NText.ofStyled("=", NTextStyle.separator()));
            b.append(NText.ofStyled(String.valueOf(maxTokens), NTextStyle.number()));
        }
        if (stop != null && !stop.isEmpty()) {
            b.append(NText.ofStyled(", ", NTextStyle.separator()));
            b.append(NText.ofStyled("maxTokens", NTextStyle.primary3()));
            b.append(NText.ofStyled("=", NTextStyle.separator()));
            b.append(NText.ofStyled(String.valueOf(stop), NTextStyle.number()));
        }
        return b.build();
    }

    public NaruModelKey key() {
        return new NaruModelKey(provider, model);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruModelConfig that = (NaruModelConfig) o;
        return Objects.equals(name, that.name) && Objects.equals(provider, that.provider) && Objects.equals(model, that.model) && Objects.equals(contextLength, that.contextLength) && Objects.equals(temperature, that.temperature) && Objects.equals(nucleusThreshold, that.nucleusThreshold) && Objects.equals(candidateCount, that.candidateCount) && Objects.equals(maxTokens, that.maxTokens) && Objects.equals(stop, that.stop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, provider, model, contextLength, temperature, nucleusThreshold, candidateCount, maxTokens, stop);
    }
}
