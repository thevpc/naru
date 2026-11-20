package net.thevpc.naru.ext.assess;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The registry of assets a model can be assessed against. */
public final class AssessAssets {
    private static final List<AssessAsset> ASSETS = new CopyOnWriteArrayList<>(List.of(new JavaRefAsset()));

    private AssessAssets() {
    }

    public static List<AssessAsset> all() {
        return new ArrayList<>(ASSETS);
    }

    public static void register(AssessAsset asset) {
        ASSETS.removeIf(a -> a.id().equals(asset.id()));
        ASSETS.add(asset);
    }

    public static Optional<AssessAsset> find(String id) {
        return ASSETS.stream().filter(a -> a.id().equals(id)).findFirst();
    }
}
