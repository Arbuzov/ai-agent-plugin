package io.jenkins.plugins.aiagentjob.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.AiAgentLogParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

class ClaudeCodeLogFormatTest {

    @Test
    void suppressesThinkingTokenTelemetryAndKeepsConversationStatusAndErrorsVisible(
            @TempDir Path tempDir) throws IOException {
        List<String> lines =
                List.of(
                        "{\"type\":\"system\",\"subtype\":\"thinking_tokens\","
                                + "\"session_id\":\"sample-session\",\"uuid\":\"usage-1\","
                                + "\"estimated_tokens\":116,\"estimated_tokens_delta\":1}",
                        "{\"type\":\"system\",\"subtype\":\"init\","
                                + "\"model\":\"claude-sonnet-4\","
                                + "\"session_id\":\"sample-session\"}",
                        "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\","
                                + "\"role\":\"assistant\",\"content\":["
                                + "{\"type\":\"thinking\","
                                + "\"thinking\":\"I will compute the sequence.\"},"
                                + "{\"type\":\"text\",\"text\":\"The answer is 6765.\"}]}}",
                        "{\"type\":\"system\",\"subtype\":\"status\","
                                + "\"message\":\"Quota warning: 90% of the weekly limit used.\"}",
                        "{\"type\":\"error\",\"error\":\"API_ERROR\","
                                + "\"message\":\"Rate limit exceeded.\"}");
        Path log = tempDir.resolve("claude.jsonl");
        Files.writeString(log, String.join("\n", lines) + "\n");

        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(log.toFile(), ClaudeCodeLogFormat.INSTANCE);

        assertEquals(
                List.of("system", "thinking", "assistant", "system", "error"),
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList()));
        assertTrue(events.get(0).getContent().contains("claude-sonnet-4"));
        assertEquals("I will compute the sequence.", events.get(1).getContent());
        assertEquals("The answer is 6765.", events.get(2).getContent());
        assertTrue(events.get(3).getContent().contains("Quota warning"));
        assertEquals("Rate limit exceeded.", events.get(4).getContent());
        assertFalse(
                events.stream().anyMatch(event -> event.getLabel().contains("thinking_tokens")));
    }
}
