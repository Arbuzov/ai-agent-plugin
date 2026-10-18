package io.jenkins.plugins.aiagentjob.geminicli;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeLogFormat;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeStatsExtractor;

import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;

/**
 * Deserialization shim for saved jobs configured with the retired Gemini CLI handler.
 *
 * <p>New jobs should use {@code AntigravityAgentHandler}. This class intentionally has no Jenkins
 * descriptor or Pipeline symbol, and rejects execution so existing jobs must be migrated
 * explicitly.
 */
@Deprecated
public final class GeminiCliAgentHandler extends AiAgentTypeHandler {
    private static final String REMOVED_MESSAGE =
            "Gemini CLI was removed; select Antigravity CLI (agy).";

    @DataBoundConstructor
    public GeminiCliAgentHandler() {}

    @Override
    public String getId() {
        return "GEMINI_CLI";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "";
    }

    @Override
    public void validateExecution(AiAgentConfiguration config) {
        throw new IllegalStateException(REMOVED_MESSAGE);
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        throw new IllegalStateException(REMOVED_MESSAGE);
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return ClaudeCodeLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return ClaudeCodeStatsExtractor.INSTANCE;
    }
}
