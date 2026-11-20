package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.nuts.app.NApplication;
import net.thevpc.nuts.artifact.*;
import net.thevpc.nuts.command.NSearch;
import net.thevpc.nuts.io.NErr;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.reflect.NMutableClassLoader;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
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

    private final List<NId> extensions = new ArrayList<>();
    private NPath projectDirectory;

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
        return new NaruWorkspaceImpl(extensions.toArray(new NId[0]))
                .projectDirectory(projectDirectory == null ? NPath.ofUserDirectory() : projectDirectory);
    }

    @Override
    public String toString() {
        return "NaruWorkspaceBuilder(" +
                (projectDirectory == null ? System.getProperty("user.dir") : projectDirectory.toString()) +
                ')';
    }
}
