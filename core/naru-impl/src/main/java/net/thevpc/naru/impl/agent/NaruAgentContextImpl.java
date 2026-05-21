//package net.thevpc.naru.impl.agent;
//
//import net.thevpc.naru.api.agent.NaruAgentContext;
//import net.thevpc.naru.api.agent.NaruAgent;
//import net.thevpc.naru.api.agent.NaruScriptManager;
//import net.thevpc.naru.impl.script.NaruScriptManagerImpl;
//import net.thevpc.nuts.io.NPath;
//import net.thevpc.nuts.util.NBlankable;
//
///**
// * Per-run mutable context shared with all tools.
// *
// * <p>Stores state that persists across tool calls within a single agent run
// * (e.g. project directory, any discovered metadata).
// */
//public class NaruAgentContextImpl implements NaruAgentContext {
//
//    /**
//     * Root directory of the project being worked on.
//     */
//    private NPath projectDir;
//
//    /**
//     * Optional: additional context the user wants to share with every tool.
//     */
//    private String extraContext;
//
//    /**
//     * Manages scripts for the session.
//     */
//    private NaruScriptManager scriptManager = new NaruScriptManagerImpl();
//
//    public NaruAgentContextImpl(NaruAgent runner, NPath projectDir) {
//        this.projectDir = projectDir != null ? projectDir : NPath.ofUserDirectory();
//        this.runner = runner;
//    }
//
//    public NaruAgentContextImpl(NaruAgent runner, String projectDir) {
//        this(runner,projectDir != null ? NPath.of(projectDir).toAbsolute() : null);
//    }
//
//    /**
//     * Resolve a path against the project directory.
//     * If {@code path} is absolute it is returned as-is.
//     */
//    @Override
//    public NPath resolve(String path) {
//        if (NBlankable.isBlank(path)) return projectDir;
//        NPath p = NPath.of(path);
//        return p.isAbsolute() ? p : projectDir.resolve(p).normalize();
//    }
//
//    @Override
//    public NPath getProjectDir() {
//        return projectDir;
//    }
//
//    @Override
//    public void setProjectDir(NPath projectDir) {
//        this.projectDir = projectDir;
//    }
//
//    @Override
//    public String getExtraContext() {
//        return extraContext;
//    }
//
//    @Override
//    public void setExtraContext(String extraContext) {
//        this.extraContext = extraContext;
//    }
//
//    @Override
//    public NaruScriptManager getScriptManager() {
//        return scriptManager;
//    }
//}
