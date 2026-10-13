package io.jenkins.plugins.aiagentjob.omp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.model.TaskListener;

import io.jenkins.plugins.aiagentjob.AiAgentBuilder;
import io.jenkins.plugins.aiagentjob.AiAgentExecutionCustomization;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler.AcpExecutionSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class OmpAgentHandlerTest {
    @TempDir Path temp;

    private final OmpAgentHandler handler = new OmpAgentHandler();

    private AiAgentBuilder config() {
        AiAgentBuilder config = new AiAgentBuilder();
        config.setAgent(handler);
        return config;
    }

    @Test
    void defaultsToEphemeralJsonWithSupportedReadOnlyTools() {
        assertEquals(
                List.of(
                        "omp",
                        "--print",
                        "--mode",
                        "json",
                        "--no-session",
                        "--no-extensions",
                        "--approval-mode",
                        "write",
                        "--tools",
                        "read,grep,glob",
                        "Review the code"),
                handler.buildDefaultCommand(config(), "Review the code"));
    }

    @Test
    void yoloUsesNativeApprovalPolicyAndAllowsTrustedCustomization() {
        AiAgentBuilder config = config();
        config.setYoloMode(true);
        config.setExtraArgs(
                "--tools bash,edit,write -e /opt/trusted-extension.ts --config /opt/omp.yml");
        List<String> command = handler.buildDefaultCommand(config, "Fix tests");
        assertEquals("yolo", command.get(command.indexOf("--approval-mode") + 1));
        assertFalse(command.contains("--tools"));
        assertFalse(command.contains("--approve"));
        assertFalse(command.contains("--no-approve"));
    }

    @Test
    void modelShorthandAndExplicitThinkingPreserveProvider() {
        AiAgentBuilder config = config();
        config.setModel("openai/gpt-5.5:xhigh");
        List<String> command = handler.buildDefaultCommand(config, "Review");
        assertEquals("openai/gpt-5.5", command.get(command.indexOf("--model") + 1));
        assertEquals("xhigh", command.get(command.indexOf("--thinking") + 1));
        config.setReasoningEffort("auto");
        command = handler.buildDefaultCommand(config, "Review");
        assertEquals("auto", command.get(command.indexOf("--thinking") + 1));
        config.setModel("custom/model::high");
        assertEquals(
                "custom/model:high",
                handler.resolveModelSelection(config.getModel(), "").getModel());
        config.setReasoningEffort("invalid");
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.buildDefaultCommand(config, "Review"));
    }

    @Test
    void promptsCannotBecomeOptionsOrFileAttachments() {
        for (String prompt : List.of("--help", "@private.txt", "- Review this")) {
            List<String> command = handler.buildDefaultCommand(config(), prompt);
            assertEquals("\n" + prompt, command.get(command.size() - 1));
        }
    }

    @Test
    void credentialsRequireAnExplicitProviderEnvironmentVariable() {
        AiAgentBuilder config = config();
        config.setApiCredentialsId("provider-key");
        assertThrows(IllegalArgumentException.class, () -> handler.validateExecution(config));
        config.setApiEnvVarName("OPENAI_API_KEY");
        handler.validateExecution(config);
    }

    @Test
    void manualApprovalsSelectAcpModelsAndThinkingWithoutNativeFlags() {
        AiAgentBuilder config = config();
        config.setRequireApprovals(true);
        config.setModel("anthropic/fixture-model:high");
        config.setExtraArgs("--model openai/fixture-model --thinking=off --tools read,bash,write");
        AcpExecutionSpec execution = handler.buildAcpExecution(config);
        assertEquals(
                List.of(
                        "omp",
                        "acp",
                        "--no-session",
                        "--no-extensions",
                        "--tools",
                        "read,bash,write",
                        "--approval-mode",
                        "always-ask"),
                execution.getCommand());
        assertEquals("openai/fixture-model", execution.getModel());
        assertEquals("off", execution.getReasoningEffort());
        assertEquals(List.of("agent"), execution.getFallbackAuthenticationMethods());
        assertTrue(handler.supportsManualApprovals());
    }

    @Test
    void manualExtraArgsCannotBypassApprovalsOrReplaceTheProtocol() {
        AiAgentBuilder config = config();
        config.setRequireApprovals(true);
        for (String args :
                List.of(
                        "--auto-approve",
                        "--auto-approve=true",
                        "--yolo",
                        "--approval-mode yolo",
                        "--approval-mode=write",
                        "--plan-yolo",
                        "-e /tmp/extension.ts",
                        "--extension=/tmp/extension.ts",
                        "--hook /tmp/hook.ts",
                        "--trusted-extension /tmp/trusted.ts",
                        "--plugin-dir=/tmp/plugin",
                        "--config /tmp/approvals.yml",
                        "--mode=json",
                        "--print",
                        "--resume prior-session",
                        "-r prior-session",
                        "--fork prior-session",
                        "--from-claude",
                        "--from-codex",
                        "--acp-terminal-auth",
                        "--export prior-session.jsonl",
                        "--alias omp-shortcut",
                        "--help",
                        "-h",
                        "--version",
                        "-v",
                        "--")) {
            config.setExtraArgs(args);
            assertThrows(
                    IllegalArgumentException.class, () -> handler.buildAcpExecution(config), args);
        }
        config.setExtraArgs("--model");
        assertThrows(IllegalArgumentException.class, () -> handler.buildAcpExecution(config));
        config.setExtraArgs("--thinking=invalid");
        assertThrows(IllegalArgumentException.class, () -> handler.buildAcpExecution(config));
        config.setExtraArgs("");
        config.setCommandOverride("omp acp");
        assertThrows(IllegalArgumentException.class, () -> handler.validateExecution(config));
    }

    @Test
    void nativeReadOnlyModeCannotBeExpandedByExtraArgs() {
        AiAgentBuilder config = config();
        for (String args :
                List.of(
                        "--tools=write",
                        "--yolo",
                        "--auto-approve",
                        "-e /tmp/tool.ts",
                        "--plugin-dir /tmp/plugin")) {
            config.setExtraArgs(args);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> handler.buildDefaultCommand(config, "Review"));
        }
        config.setYoloMode(true);
        config.setExtraArgs("--mode text");
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.buildDefaultCommand(config, "Review"));
    }

    @Test
    void manualApprovalsResetInheritedGrantsInAnExecutionScopedOverride() throws Exception {
        AiAgentBuilder config = config();
        config.setRequireApprovals(true);
        FilePath workspace = new FilePath(temp.resolve("workspace").toFile());
        workspace.mkdirs();
        AiAgentExecutionCustomization customization =
                handler.prepareExecution(config, workspace, TaskListener.NULL);
        assertEquals("--config", customization.getArguments().get(0));
        assertEquals(2, customization.getArguments().size());
        Path override = Path.of(customization.getArguments().get(1));
        assertFalse(override.startsWith(temp.resolve("workspace")));
        assertEquals("tools:\n  approval: null\n", Files.readString(override));
        if (override.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(override));
        }
        customization.cleanup(TaskListener.NULL);
        assertFalse(Files.exists(override));

        assertTrue(
                handler.prepareExecution(config(), workspace, TaskListener.NULL)
                        .getArguments()
                        .isEmpty());
        config.setYoloMode(true);
        assertTrue(
                handler.prepareExecution(config, workspace, TaskListener.NULL)
                        .getArguments()
                        .isEmpty());
        try (var files = Files.list(override.getParent())) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void terminalErrorsFailTheJobAndSuccessfulRetriesRecover() throws Exception {
        Path log = temp.resolve("omp.jsonl");
        String error =
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\"}}\n";
        String success =
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"stop\"}}\n";
        Files.writeString(log, "startup diagnostic\n" + error);
        assertEquals(1, handler.resolveExitCode(0, log.toFile()));
        assertEquals(42, handler.resolveExitCode(42, log.toFile()));
        Files.writeString(log, error + success);
        assertEquals(0, handler.resolveExitCode(0, log.toFile()));
        Files.writeString(log, success + error.replace("error", "aborted"));
        assertEquals(1, handler.resolveExitCode(0, log.toFile()));
        Files.writeString(
                log, "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"refusal\"}}\n");
        assertEquals(1, handler.resolveExitCode(0, log.toFile()));
        Files.writeString(
                log, "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}\n");
        assertEquals(0, handler.resolveExitCode(0, log.toFile()));
        assertEquals(0, handler.resolveExitCode(0, null));
    }
}
