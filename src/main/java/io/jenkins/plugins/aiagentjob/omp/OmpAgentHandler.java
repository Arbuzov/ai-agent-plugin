package io.jenkins.plugins.aiagentjob.omp;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentExecutionCustomization;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTempFiles;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;

import net.sf.json.JSONObject;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OmpAgentHandler extends AiAgentTypeHandler {
    private static final Set<String> MANAGED_OPTIONS =
            Set.of(
                    "--mode",
                    "--print",
                    "-p",
                    "--session",
                    "--resume",
                    "-r",
                    "--continue",
                    "-c",
                    "--fork",
                    "--from-claude",
                    "--from-codex",
                    "--acp-terminal-auth",
                    "--export",
                    "--alias",
                    "--help",
                    "-h",
                    "--version",
                    "-v");
    private static final Set<String> APPROVAL_OPTIONS =
            Set.of("--approval-mode", "--auto-approve", "--yolo", "--plan-yolo");
    private static final Set<String> EXTENSION_OPTIONS =
            Set.of("--extension", "-e", "--hook", "--trusted-extension", "--plugin-dir");

    @DataBoundConstructor
    public OmpAgentHandler() {}

    @Override
    public String getId() {
        return "OMP";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "";
    }

    @Override
    public boolean supportsManualApprovals() {
        return true;
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max", "auto");
    }

    @Override
    public void validateExecution(AiAgentConfiguration config) {
        super.validateExecution(config);
        if (Util.fixEmptyAndTrim(config.getApiCredentialsId()) != null
                && Util.fixEmptyAndTrim(config.getEffectiveApiKeyEnvVar()) == null) {
            throw new IllegalArgumentException(
                    "OMP credentials require an API Key env var override for the selected provider, "
                            + "such as OPENAI_API_KEY or ANTHROPIC_API_KEY.");
        }
        validateExtraArgs(config, config.isRequireApprovals() && !config.isYoloMode());
        validateReasoningEffort(
                resolveModelSelection(config.getModel(), config.getReasoningEffort())
                        .getReasoningEffort());
    }

    private static void validateExtraArgs(AiAgentConfiguration config, boolean manualApprovals) {
        for (String arg : Util.tokenize(Util.fixNull(config.getExtraArgs()))) {
            String option = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if ("--".equals(option) || MANAGED_OPTIONS.contains(option)) {
                throw new IllegalArgumentException(
                        "OMP extra args cannot override the Jenkins execution mode: " + option);
            }
            if (manualApprovals
                    && (APPROVAL_OPTIONS.contains(option)
                            || EXTENSION_OPTIONS.contains(option)
                            || "--config".equals(option))) {
                throw new IllegalArgumentException(
                        "OMP extra args cannot override Jenkins manual approval policies: "
                                + option);
            }
            if (!manualApprovals
                    && !config.isYoloMode()
                    && (APPROVAL_OPTIONS.contains(option)
                            || EXTENSION_OPTIONS.contains(option)
                            || "--tools".equals(option))) {
                throw new IllegalArgumentException(
                        "OMP read-only execution cannot enable additional tools or approvals through "
                                + "extra args. Use manual approvals or YOLO mode.");
            }
        }
    }

    private void validateReasoningEffort(String effort) {
        if (!effort.isEmpty() && !getSupportedReasoningEfforts().contains(effort)) {
            throw new IllegalArgumentException("Unsupported OMP reasoning effort: " + effort);
        }
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        validateExecution(config);
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "omp",
                                "--print",
                                "--mode",
                                "json",
                                "--no-session",
                                "--no-extensions",
                                "--approval-mode",
                                config.isYoloMode() ? "yolo" : "write"));
        if (!config.isYoloMode()) {
            command.add("--tools");
            command.add("read,grep,glob");
        }
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        addOption(command, "--model", selection.getModel());
        addOption(command, "--thinking", selection.getReasoningEffort());
        // Extra args follow this positional prompt, so a separator would consume those flags.
        command.add(prompt.startsWith("-") || prompt.startsWith("@") ? "\n" + prompt : prompt);
        return command;
    }

    @Override
    public AcpExecutionSpec buildAcpExecution(AiAgentConfiguration config) {
        validateExecution(config);
        validateExtraArgs(config, true);
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        String model = selection.getModel();
        String effort = selection.getReasoningEffort();
        List<String> command =
                new ArrayList<>(List.of("omp", "acp", "--no-session", "--no-extensions"));
        String[] extraArgs = Util.tokenize(Util.fixNull(config.getExtraArgs()));
        for (int i = 0; i < extraArgs.length; i++) {
            String arg = extraArgs[i];
            if ("--model".equals(arg) || "-m".equals(arg) || "--thinking".equals(arg)) {
                if (i + 1 >= extraArgs.length || extraArgs[i + 1].startsWith("-")) {
                    throw new IllegalArgumentException("OMP extra arg requires a value: " + arg);
                }
                String value = extraArgs[++i];
                if ("--thinking".equals(arg)) effort = value;
                else model = value;
            } else if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
            } else if (arg.startsWith("--thinking=")) {
                effort = arg.substring("--thinking=".length());
            } else {
                command.add(arg);
            }
        }
        selection = resolveModelSelection(model, effort);
        validateReasoningEffort(selection.getReasoningEffort());
        command.add("--approval-mode");
        command.add("always-ask");
        return new AcpExecutionSpec(
                command,
                selection.getModel(),
                selection.getReasoningEffort(),
                Map.of(),
                List.of("agent"));
    }

    private static void addOption(List<String> command, String option, String value) {
        String normalized = Util.fixEmptyAndTrim(value);
        if (normalized != null) {
            command.add(option);
            command.add(normalized);
        }
    }

    @Override
    public AiAgentExecutionCustomization prepareExecution(
            AiAgentConfiguration config, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
        if (!config.isRequireApprovals() || config.isYoloMode()) return customization;
        FilePath override =
                AiAgentTempFiles.tempRoot(workspace)
                        .child("ai-agent-omp-approval-" + UUID.randomUUID() + ".yml");
        try {
            // Also enforce the mode here: a dangling extra flag can consume the CLI override.
            override.write("tools:\n  approvalMode: always-ask\n  approval: null\n", "UTF-8");
            override.chmod(0600);
        } catch (IOException | InterruptedException e) {
            try {
                override.delete();
            } catch (IOException | InterruptedException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        customization.addArguments(List.of("--config", override.getRemote()));
        customization.addCleanupAction(override::delete);
        return customization;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return OmpLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return OmpStatsExtractor.INSTANCE;
    }

    @Override
    public int resolveExitCode(int processExitCode, File rawLogFile) throws IOException {
        if (processExitCode != 0 || rawLogFile == null || !rawLogFile.isFile()) {
            return processExitCode;
        }
        boolean failed = false;
        try (BufferedReader reader =
                Files.newBufferedReader(rawLogFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject json;
                try {
                    json = JSONObject.fromObject(line);
                } catch (RuntimeException ignored) {
                    continue;
                }
                JSONObject result = json.optJSONObject("result");
                if (result != null && result.has("stopReason")) {
                    failed = !"end_turn".equals(result.optString("stopReason"));
                } else if ("message_end".equals(json.optString("type"))) {
                    JSONObject message = json.optJSONObject("message");
                    if (message != null && "assistant".equals(message.optString("role"))) {
                        String reason = message.optString("stopReason");
                        // A completed retry supersedes the earlier failed assistant attempt.
                        failed = "error".equals(reason) || "aborted".equals(reason);
                    }
                } else if (json.optJSONObject("error") != null) {
                    failed = true;
                }
            }
        }
        return failed ? 1 : 0;
    }

    @Extension
    @Symbol("omp")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Oh My Pi (OMP)";
        }
    }
}
