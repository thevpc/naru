package net.thevpc.naru.api.model;

import net.thevpc.naru.api.registry.NaruToolParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NaruToolDefinitionFunction extends NaruToolDefinition{
    private List<NaruToolParameter> params;
    public NaruToolDefinitionFunction(String name, String description,NaruToolParameter... params) {
        this(name,description, Arrays.asList(params));
    }

    public NaruToolDefinitionFunction(String name, String description,List<NaruToolParameter> params) {
        super(name, description);
        this.params = new ArrayList<>(params);
    }

    public List<NaruToolParameter> getParams() {
        return params;
    }
}
