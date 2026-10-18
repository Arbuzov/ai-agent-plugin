package io.jenkins.plugins.aiagentjob.claudecode;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;

import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Stats extractor for Claude Code / legacy Gemini CLI JSONL output. These logs use the shared
 * "result" event structure with cost, duration, and usage blocks, plus "assistant" message usage —
 * all of which are handled by the shared extractor in {@link AgentUsageStats}.
 *
 * <p>This extractor handles Claude-specific "result" events and "assistant" message usage blocks.
 */
public final class ClaudeCodeStatsExtractor implements AiAgentStatsExtractor {

    public static final ClaudeCodeStatsExtractor INSTANCE = new ClaudeCodeStatsExtractor();

    private ClaudeCodeStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);

        if ("result".equals(type)) {
            stats.extractResultStats(json);
            return true;
        }

        if ("assistant".equals(type)) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONObject usage = message.optJSONObject("usage");
                if (usage != null) {
                    stats.accumulateUsage(usage);
                    return true;
                }
            }
        }

        return false;
    }
}
