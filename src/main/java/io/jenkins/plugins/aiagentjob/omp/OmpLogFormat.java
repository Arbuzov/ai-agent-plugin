package io.jenkins.plugins.aiagentjob.omp;

import io.jenkins.plugins.aiagentjob.AcpLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser.ParsedLine;
import io.jenkins.plugins.aiagentjob.pi.PiLogFormat;

import net.sf.json.JSONObject;

import java.util.List;
import java.util.Set;

/** Classifies OMP ACP events and Pi-compatible completed native messages and tools. */
public final class OmpLogFormat implements AiAgentLogFormat {
    public static final OmpLogFormat INSTANCE = new OmpLogFormat();

    private static final Set<String> INTERNAL_EVENTS =
            Set.of(
                    "message_update",
                    "tool_stream_update",
                    "auto_compaction_start",
                    "auto_compaction_end",
                    "retry_fallback_applied",
                    "retry_fallback_succeeded",
                    "model_changed",
                    "config_warnings_changed",
                    "advisor_cost_changed",
                    "advisor_yielded",
                    "ttsr_triggered",
                    "todo_reminder",
                    "todo_auto_clear",
                    "goal_updated");

    private OmpLogFormat() {}

    @Override
    public ParsedLine classify(long lineNumber, JSONObject json) {
        List<ParsedLine> lines = classifyAll(lineNumber, json);
        return lines == null || lines.isEmpty() ? null : lines.get(0);
    }

    @Override
    public List<ParsedLine> classifyAll(long lineNumber, JSONObject json) {
        if ("elicitation/create".equals(json.optString("method"))) return List.of();
        ParsedLine acpLine = AcpLogFormat.INSTANCE.classify(lineNumber, json);
        if (acpLine != null) return List.of(acpLine);
        String type = json.optString("type");
        // OMP removes the message snapshot from updates. Render its authoritative message_end.
        if (INTERNAL_EVENTS.contains(type)) return List.of();
        if ("notice".equals(type)) {
            String category = "error".equals(json.optString("level")) ? "error" : "system";
            return List.of(
                    ParsedLine.message(
                            lineNumber,
                            category,
                            "error".equals(category) ? "Error" : "Notice",
                            json.optString("message"),
                            json.toString(2)));
        }
        return PiLogFormat.INSTANCE.classifyAll(lineNumber, json);
    }
}
