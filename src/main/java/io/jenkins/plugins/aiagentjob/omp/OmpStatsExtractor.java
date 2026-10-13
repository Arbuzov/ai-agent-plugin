package io.jenkins.plugins.aiagentjob.omp;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.pi.PiStatsExtractor;

import net.sf.json.JSONObject;

/** Extracts OMP native message usage and ACP prompt-result usage without counting snapshots. */
public final class OmpStatsExtractor implements AiAgentStatsExtractor {
    public static final OmpStatsExtractor INSTANCE = new OmpStatsExtractor();

    private OmpStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        if ("session/update".equals(json.optString("method"))) {
            JSONObject params = json.optJSONObject("params");
            JSONObject update = params == null ? null : params.optJSONObject("update");
            if (update == null) return true;
            String type = update.optString("sessionUpdate");
            if ("tool_call".equals(type) || "tool_call_update".equals(type)) {
                stats.recordToolCall(update.optString("toolCallId"));
            } else if ("usage_update".equals(type)) {
                // used/size describe the context window, not billable token consumption.
                JSONObject cost = update.optJSONObject("cost");
                if (cost != null && "USD".equals(cost.optString("currency"))) {
                    double amount = cost.optDouble("amount", 0);
                    if (Double.isFinite(amount) && amount > 0) stats.addCostUsd(amount);
                }
            }
            return true;
        }
        JSONObject result = json.optJSONObject("result");
        if (result != null && result.has("stopReason")) {
            JSONObject usage = result.optJSONObject("usage");
            if (usage != null) {
                stats.incrementInputTokens(usage.optLong("inputTokens", 0));
                stats.incrementOutputTokens(usage.optLong("outputTokens", 0));
                stats.incrementCacheReadTokens(usage.optLong("cachedReadTokens", 0));
                stats.incrementCacheWriteTokens(usage.optLong("cachedWriteTokens", 0));
                stats.incrementReasoningTokens(usage.optLong("thoughtTokens", 0));
                stats.incrementTotalTokens(usage.optLong("totalTokens", 0));
            }
            return true;
        }
        boolean handled = PiStatsExtractor.INSTANCE.extract(json, stats);
        if ("message_end".equals(json.optString("type"))) {
            JSONObject message = json.optJSONObject("message");
            JSONObject usage = message == null ? null : message.optJSONObject("usage");
            if (usage != null
                    && ("assistant".equals(message.optString("role"))
                            || "toolResult".equals(message.optString("role")))
                    && !usage.has("reasoning")) {
                stats.incrementReasoningTokens(usage.optLong("reasoningTokens", 0));
            }
        }
        return handled;
    }
}
