package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.nuts.artifact.NDefinition;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.command.NSearch;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.io.NErr;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves the declared profiles and extensions and loads them into the nuts extension
 * registry before handing back a workspace.
 *
 * <p>A <b>profile</b> is a Maven artifact whose transitive compile dependencies are naru
 * extensions. It is resolved with {@code inlineDependencies}, and every resulting
 * definition that is itself an extension is loaded; the profile artifact has no
 * components of its own, so it is never loaded directly. An <b>extension</b> is loaded
 * as-is.
 *
 * <p>What counts as an extension is decided by the descriptor marker
 * {@code naru.extension=true} when present, and otherwise by Maven coordinate: an artifact
 * under {@code net.thevpc.naru} named {@code naru-*} that is neither the api nor the impl.
 * The marker is preferred so a deliberate layout can override the convention, while the
 * convention keeps resolution working for artifacts that predate the marker.
 *
 * <p>Resolution is best-effort by design: an unresolvable profile or a non-extension
 * dependency is reported on stderr and skipped rather than aborting the run, because a
 * missing optional feature should not prevent naru from starting. When nothing is declared
 * at all the {@code dev} profile is tried quietly, so a full dev harness is loaded when it
 * is installed and a bare core agent otherwise.
 */
public class NaruWorkspaceBuilderImpl implements NaruWorkspaceBuilder {
    private static final String DEFAULT_PROFILE = "net.thevpc.naru.profiles:naru-profile-dev";
    private static final String EXTENSION_GROUP = "net.thevpc.naru";
    private static final String PROFILE_GROUP = "net.thevpc.naru.profiles";
    private static final Set<String> NON_EXTENSION_ARTIFACTS = Set.of("naru-api", "naru-impl");

    private final List<NId> profiles = new ArrayList<>();
    private final List<NId> extensions = new ArrayList<>();
    private NPath projectDirectory;

    @Override
    public NaruWorkspaceBuilder profile(NId profile) {
        profiles.add(profile);
        return this;
    }

    @Override
    public NaruWorkspaceBuilder importExtension(NId extension) {
        extensions.add(extension);
        return this;
    }

    @Override
    public <T> NaruWorkspaceBuilder configure(Class<T> api, Supplier<T> supplier) {
        return this;
    }

    @Override
    public <T> NaruWorkspaceBuilder configureSession(Class<T> api, Function<NaruSession, T> supplier) {
        return this;
    }

    @Override
    public NaruWorkspaceBuilder projectDirectory(NPath projectDirectory) {
        this.projectDirectory = projectDirectory;
        return this;
    }

    @Override
    public NaruWorkspace build() {
        // a set: a profile and an explicit --extension may name the same artifact, and it
        // must be loaded once
        Set<NId> toLoad = new LinkedHashSet<>();
        for (NId profile : profiles) {
            toLoad.addAll(expandProfile(profile, false));
        }
        for (NId extension : extensions) {
            toLoad.add(extension);
        }
        if (profiles.isEmpty() && extensions.isEmpty()) {
            // nothing declared: load the dev profile when it is installed, otherwise fall
            // back to a bare core so a slim build still runs
            toLoad.addAll(expandProfile(NId.of(DEFAULT_PROFILE), true));
        }
        for (NId id : toLoad) {
            NExtensions.of().loadExtension(id);
        }
        return new NaruWorkspaceImpl()
                .projectDirectory(projectDirectory == null ? NPath.ofUserDirectory() : projectDirectory);
    }

    /**
     * Resolves a profile artifact and returns the ids of the extensions it bundles. The
     * profile itself contributes nothing and any nested profile is skipped, so the result
     * contains only loadable extensions.
     */
    private List<NId> expandProfile(NId profile, boolean quiet) {
        List<NDefinition> defs = NSearch.of(profile).latest(true)
                .inlineDependencies(true)
                .getResultDefinitions().toList();
        if (defs.isEmpty()) {
            if (!quiet) {
                NErr.println(NMsg.ofC("profile not found %s", profile));
            }
            return List.of();
        }
        List<NId> result = new ArrayList<>();
        for (NDefinition d : defs) {
            if (isProfile(d.id())) {
                continue;
            }
            if (isExtension(d)) {
                result.add(d.id());
            }
        }
        return result;
    }

    /**
     * A profile is an artifact that only groups extensions. Identified by its group or by
     * the {@code naru-profile-} artifact prefix.
     */
    private boolean isProfile(NId id) {
        return PROFILE_GROUP.equals(id.groupId()) || id.artifactId().startsWith("naru-profile-");
    }

    /**
     * An extension is a naru feature artifact. The {@code naru.extension} descriptor marker
     * decides when present; otherwise the Maven coordinate convention applies.
     */
    private boolean isExtension(NDefinition d) {
        NOptional<NLiteral> marked = d.descriptor().getPropertyValue("naru.extension");
        if (marked.isPresent()) {
            return marked.get().asBoolean().orFalse();
        }
        NId id = d.id();
        return EXTENSION_GROUP.equals(id.groupId())
                && id.artifactId().startsWith("naru-")
                && !NON_EXTENSION_ARTIFACTS.contains(id.artifactId());
    }

    @Override
    public String toString() {
        return "NaruWorkspaceBuilder(" +
                (projectDirectory == null ? System.getProperty("user.dir") : projectDirectory.toString()) +
                ')';
    }
}
