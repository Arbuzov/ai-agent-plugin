package io.jenkins.plugins.aiagentjob.antigravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.AiAgentLogParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class AntigravityLogFormatTest {
    @TempDir Path temp;

    @Test
    void completedToolWithoutOutputKeepsCallInputAndLabelWhileHidingPendingUpdate()
            throws Exception {
        Path log = temp.resolve("conversation.jsonl");
        Files.writeString(
                log,
                """
                {"event":"step_update","step_update":{"conversation_id":"fixture","step_index":4,"state":"ACTIVE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/tmp/fibonacci.js"}}}}
                {"event":"step_update","step_update":{"conversation_id":"fixture","step_index":4,"state":"RUNNING","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/tmp/fibonacci.js"}}}}
                {"event":"step_update","step_update":{"conversation_id":"fixture","step_index":4,"state":"DONE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/tmp/fibonacci.js"}}}}
                """);

        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(log.toFile(), AntigravityLogFormat.INSTANCE);

        assertEquals(
                List.of("tool_call", "tool_result"),
                events.stream().map(AiAgentLogParser.EventView::getCategory).toList());
        assertEquals("write_to_file", events.get(0).getLabel());
        assertEquals("write_to_file", events.get(1).getLabel());
        assertTrue(events.get(0).getToolInput().contains("/tmp/fibonacci.js"));
        assertTrue(events.get(1).getToolInput().contains("/tmp/fibonacci.js"));
        assertEquals("Completed", events.get(1).getToolOutput());
    }

    @Test
    void completedToolFailureRemainsVisible() throws Exception {
        Path log = temp.resolve("failed-tool.jsonl");
        Files.writeString(
                log,
                """
                {"event":"step_update","step_update":{"conversation_id":"fixture","step_index":5,"state":"ERROR","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/tmp/fibonacci.js"},"error":{"message":"write denied"}}}}
                """);

        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(log.toFile(), AntigravityLogFormat.INSTANCE);

        assertEquals(1, events.size());
        assertEquals("tool_result", events.get(0).getCategory());
        assertEquals("write denied", events.get(0).getToolOutput());
    }
}
