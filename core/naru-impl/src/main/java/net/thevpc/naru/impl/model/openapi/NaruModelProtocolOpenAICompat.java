package net.thevpc.naru.impl.model.openapi;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.impl.model.ollama.NaruOllamaResponseParser;

import java.util.*;

public class NaruModelProtocolOpenAICompat extends NaruModelProtocolOpenApiBase {

    public NaruModelProtocolOpenAICompat(NaruModelConfig model, String baseUrl, NaruModelCapabilities capabilities) {
        super(model, baseUrl, capabilities, new NaruOpenApiResponseParser());
    }


    protected Map<String, Object> userMessageToMap(NaruMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole().id());
        // Regular messages (system / user / assistant text)
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            List<Map<String, Object>> contentParts = new ArrayList<>();
            // text part
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", m.getContent() != null ? m.getContent() : "");
            contentParts.add(textPart);
            // image parts
            for (String img : m.getImages()) {
                Map<String, Object> imgPart = new LinkedHashMap<>();
                imgPart.put("type", "image_url");
                Map<String, Object> imgUrl = new LinkedHashMap<>();
                imgUrl.put("url", "data:image/jpeg;base64," + img);
                imgPart.put("image_url", imgUrl);
                contentParts.add(imgPart);
            }
            map.put("content", contentParts); // ← array instead of string
        } else {
            map.put("content", m.getContent() != null ? m.getContent() : "");
        }
        return map;
    }

}
