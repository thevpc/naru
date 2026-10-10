package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.spawn.NaruSpawnTargets;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code delegate_to_model} — two modes.
 * <p>
 * With no {@code routine} argument the tool keeps its historical one-shot behaviour: it
 * asks the named model a single prompt in-line (with an optional image) and returns the
 * reply.
 * <p>
 * With a {@code routine} argument (a routine name/path or an agent {@code .md}) it spawns
 * a child task that runs that target under the WP3 spawn machinery: the resolved plan is
 * recorded on the {@code TaskSpawned} event, the target's contract is validated, the
 * configured named policy (session env {@code spawn.policy}) is applied, and the spawn is
 * awaited before the reply is returned. The model path is deliberately narrower than the
 * call-site path: it can only <em>revoke</em> and inherit <em>less</em> — it may never
 * request tag adds ({@code revoke_tags}, {@code inherit} below are the only narrowing), and
 * the policy comes from the session configuration, not from the call.
 */
public class ModelDelegateTool extends DefaultNaruTool {

    public ModelDelegateTool() {
        super("delegate_to_model", new String[]{NaruToolTags.AI});
    }


    @Override
    public String name() {
        return "delegate_to_model";
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Delegate a sub-task to another AI model. Use this to offload vision tasks to vision models, "
                + "or complex reasoning to larger models. Call model_list first to discover and filter the "
                + "available models (by capability, cost or provider) instead of guessing a model name.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("model_name", "The exact name of the model to use.", true).build(),
                NaruToolParameter.string("prompt", "The task description or question for the model.", true).build(),
                NaruToolParameter.string("image_path", "Optional absolute path to an image file if this is a vision task.", false).build(),
                NaruToolParameter.string("routine", "Optional routine name/path, or agent .md name, to spawn as a sub-task instead of a one-shot request.", false).build(),
                NaruToolParameter.string("revoke_tags", "Optional comma-separated tags to revoke from the spawned task (narrowing only; you may never add tags).", false).build(),
                NaruToolParameter.string("inherit", "Optional comma-separated kinds to inherit as a snapshot: tags, env, or \"none\".", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return callModel(context.task()
                , context.stringArg("model_name").orNull()
                , context.stringArg("prompt").orNull()
                , context.stringArg("image_path").orNull()
                , context.stringArg("routine").orNull()
                , context.stringArg("revoke_tags").orNull()
                , context.stringArg("inherit").orNull()
        );
    }

    public static String callModel(NaruTask task, String modelName, String prompt, String imagePath) {
        return callModel(task, modelName, prompt, imagePath, null, null, null);
    }

    public static String callModel(NaruTask task, String modelName, String prompt, String imagePath,
                                   String routine, String revokeTags, String inherit) {
        if (NBlankable.isBlank(routine)) {
            return callModelDirect(task, modelName, prompt, imagePath);
        }
        return spawnTarget(task, prompt, routine, revokeTags, inherit);
    }

    /**
     * The model path spawn: named policy from the session env, narrowing only, never a tag
     * add. Returns the awaited child's result as text.
     */
    private static String spawnTarget(NaruTask task, String prompt, String routine,
                                      String revokeTags, String inherit) {
        try {
            NaruSpawnTargets.Resolved target = NaruSpawnTargets.resolve(task.session(), task, routine)
                    .orNull();
            if (target == null) {
                return "Error: routine (or agent .md) not found: " + routine;
            }
            if (target.isAgent() && (target.contract() == null || target.contract().isEmpty())) {
                return "Error: agent file " + target.agentPath() + " has no spawn contract in its front-matter.";
            }
            // the configured named policy: the model path never picks a policy itself
            String policyName = null;
            Object configured = task.getTaskEnv().get(NaruSpawnPolicy.ENV_CONFIG_POLICY);
            if (configured != null && !NBlankable.isBlank(configured.toString())) {
                policyName = configured.toString().trim();
                if (task.session().findSpawnPolicy(policyName).isNotPresent()) {
                    return "Error: configured spawn policy '" + policyName
                            + "' (env " + NaruSpawnPolicy.ENV_CONFIG_POLICY + ") is not defined in this session.";
                }
            }
            NaruTaskSpec spec = NaruTaskSpec.of()
                    .parentId(task.id())
                    .spawnKind(target.spawnKind());
            if (target.contract() != null && !target.contract().isEmpty()) {
                spec.contract(target.contract());
            }
            if (!target.statements().isEmpty()) {
                spec.statements(target.statements());
            }
            if (policyName != null) {
                spec.policy(policyName);
            }
            // optional narrowing: revoke tags, inherit less
            if (!NBlankable.isBlank(revokeTags)) {
                spec.revokeTags(splitList(revokeTags));
            }
            if (!NBlankable.isBlank(inherit)) {
                spec.inherit(parseInherits(inherit).toArray(new NaruSpawnInherit[0]));
            }
            // the delegation prompt travels as a task variable the routine can read
            if (!NBlankable.isBlank(prompt)) {
                spec.var("delegate.prompt", prompt);
            }
            spec.resolveName();
            String warning = checkSkillsRequestedWithoutExtension(task, spec, target.contract());
            NaruTask child = task.session().newTask(spec).bg().unhold();
            child.await();
            Object done = child.getReturnResult();
            String message = done != null
                    ? "Sub-task " + child.name() + " (#" + child.id() + ") completed: " + done
                    : "Sub-task " + child.name() + " (#" + child.id() + ") completed.";
            return warning == null ? message : warning + " " + message;
        } catch (Exception e) {
            return "Error spawning " + routine + ": " + e.getMessage();
        }
    }

    /**
     * A contract that grants skills is silently useless when the skills extension is not
     * installed — the resolution records the seeds, nothing loads them. The model path's
     * only channel for this is the reply text, so the warning rides on it instead of being
     * lost.
     */
    private static String checkSkillsRequestedWithoutExtension(NaruTask task, NaruTaskSpec spec,
                                                               NaruSpawnContract contract) {
        List<String> requested = new ArrayList<>(spec.addSkills());
        if (contract != null) {
            for (String s : contract.skills()) {
                if (!requested.contains(s)) {
                    requested.add(s);
                }
            }
        }
        if (requested.isEmpty()) {
            return null;
        }
        boolean installed = task.session().registry().sessionExtensions().stream()
                .anyMatch(e -> "skills".equals(e.name()));
        if (installed) {
            return null;
        }
        return "⚠ warning: the spawn target requests skills (" + String.join(", ", requested)
                + ") but no 'skills' extension is installed; the spawn resolves and records them, "
                + "but nothing will load them into the child.";
    }

    private static List<NaruSpawnInherit> parseInherits(String value) {
        List<NaruSpawnInherit> out = new ArrayList<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String p = part.trim();
                if (p.isEmpty() || p.equalsIgnoreCase("none")) {
                    continue;
                }
                NOptional<NaruSpawnInherit> k = NaruSpawnInherit.parse(p);
                if (k.isPresent() && !out.contains(k.get())) {
                    out.add(k.get());
                }
            }
        }
        return out;
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    public static String callModelDirect(NaruTask task, String modelName, String prompt, String imagePath) {

        if (NBlankable.isBlank(modelName)) return "Error: model_name is required.";
        if (NBlankable.isBlank(prompt)) return "Error: prompt is required.";

        List<NaruMessage> messages = new ArrayList<>();
        NaruMessage msg = NaruMessage.user(prompt);

        if (!NBlankable.isBlank(imagePath)) {
            try {
                String base64 = ImageUtil.toBase64(task.resolve(imagePath).toString());
                msg.setImages(Collections.singletonList(base64));
            } catch (Exception e) {
                return "Error loading image: " + e.getMessage();
            }
        }


        messages.add(msg);
        NaruModelConfig model = task.session().findModel(modelName).orNull();
        if (model == null) {
            return "Error: Model not found : " + modelName;
        }
        NaruModelConfig oldModel = task.model();
        task.setModel(model);
        Map<String, NElement> env = task.context(NaruSource.values()).env();
        try {
            NaruResponse response = task.chat(model,
                    new NaruModelRequest(messages,
                            env
                    )
            );
            if (response.getMessage() != null) {
                return response.getMessage().getContent();
            }
            return "Error: Model returned empty response.";
        } catch (Exception e) {
            return "Error calling model " + modelName + ": " + e.getMessage();
        } finally {
            task.setModel(oldModel);
        }
    }
}