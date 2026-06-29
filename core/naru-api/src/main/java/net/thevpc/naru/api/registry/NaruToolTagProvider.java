package net.thevpc.naru.api.registry;

import java.util.List;

public interface NaruToolTagProvider {
    String name();
    List<NaruToolTag> tags();
}
