package io.jenkins.plugins.aiagentjob.omp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class OmpLogFormatTest {
    @TempDir Path temp;

    private Path fixture() throws Exception {
        Path file = temp.resolve("conversation.jsonl");
        try (InputStream input =
                getClass()
                        .getResourceAsStream(
                                "/io/jenkins/plugins/aiagentjob/fixtures/omp-conversation.jsonl")) {
            Files.copy(input, file);
        }
        return file;
    }

    @Test
    void completedMessagesAndToolsRenderWithoutDuplicatingStrippedUpdates() throws Exception {
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(fixture().toFile(), OmpLogFormat.INSTANCE);
        assertEquals(
                List.of("user", "thinking", "assistant", "tool_call", "tool_result", "assistant"),
                events.stream().map(AiAgentLogParser.EventView::getCategory).toList());
        assertEquals("Inspect the build.", events.get(1).getContent());
        assertEquals("I will run the check.", events.get(2).getContent());
        assertTrue(events.get(3).getToolInput().contains("printf"));
        assertEquals("safe-ok\n", events.get(4).getToolOutput());
        assertEquals("bash", events.get(4).getLabel());
        assertEquals("The check passed.", events.get(5).getContent());
    }

    @Test
    void ompIntentMetadataDoesNotDuplicateToolCallOrHideItsResult() throws Exception {
        Path file = temp.resolve("tool-intent-dedup.jsonl");
        try (InputStream input =
                getClass()
                        .getResourceAsStream(
                                "/io/jenkins/plugins/aiagentjob/fixtures/omp-tool-intent-dedup.jsonl")) {
            Files.copy(input, file);
        }

        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(file.toFile(), OmpLogFormat.INSTANCE);
        assertEquals(
                List.of("tool_call", "tool_result"),
                events.stream().map(AiAgentLogParser.EventView::getCategory).toList());
        assertTrue(events.get(0).getToolInput().contains("requirements.txt"));
        assertFalse(events.get(0).getToolInput().contains("Reading project requirements"));
        assertTrue(events.get(0).getRawDetails().contains("Reading project requirements"));
        assertEquals("fixture requirement", events.get(1).getToolOutput());
    }

    @Test
    void usageIncludesOmpReasoningTokensOnceAcrossSnapshots() throws Exception {
        AgentUsageStats stats =
                AgentUsageStats.fromLogFile(fixture().toFile(), OmpStatsExtractor.INSTANCE);
        assertEquals(150, stats.getInputTokens());
        assertEquals(30, stats.getOutputTokens());
        assertEquals(30, stats.getCacheReadTokens());
        assertEquals(10, stats.getCacheWriteTokens());
        assertEquals(8, stats.getReasoningTokens());
        assertEquals(220, stats.getTotalTokens());
        assertEquals(0.012, stats.getCostUsd(), 0.000001);
        assertEquals(2, stats.getNumTurns());
        assertEquals(1, stats.getToolCalls());
        assertEquals("fixture-model", stats.getDetectedModel());
    }

    @Test
    void acpUsageCountsPromptConsumptionAndIgnoresContextOccupancy() throws Exception {
        Path file = temp.resolve("acp.jsonl");
        Files.writeString(
                file,
                """
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"tool_call","toolCallId":"tool-1","rawInput":{"command":"printf fixture"},"title":"bash"}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"tool_call_update","toolCallId":"tool-1","status":"completed"}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"usage_update","used":123456,"size":200000,"cost":{"amount":0.012,"currency":"USD"}}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"usage_update","used":123456,"size":200000,"cost":{"amount":0.012,"currency":"USD"}}}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn","usage":{"inputTokens":150,"outputTokens":30,"cachedReadTokens":30,"cachedWriteTokens":10,"thoughtTokens":8,"totalTokens":220}}}
                """);
        AgentUsageStats stats =
                AgentUsageStats.fromLogFile(file.toFile(), OmpStatsExtractor.INSTANCE);
        assertEquals(150, stats.getInputTokens());
        assertEquals(30, stats.getOutputTokens());
        assertEquals(30, stats.getCacheReadTokens());
        assertEquals(10, stats.getCacheWriteTokens());
        assertEquals(8, stats.getReasoningTokens());
        assertEquals(220, stats.getTotalTokens());
        assertEquals(0.012, stats.getCostUsd(), 0.000001);
        assertEquals(1, stats.getToolCalls());
        assertEquals(0, stats.getNumTurns());
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(file.toFile(), OmpLogFormat.INSTANCE);
        assertEquals(
                List.of("tool_call", "result"),
                events.stream().map(AiAgentLogParser.EventView::getCategory).toList());
    }

    @Test
    void acpConversationChunksAndToolResultsUseTheSharedProtocolFormat() throws Exception {
        Path file = temp.resolve("acp-conversation.jsonl");
        Files.writeString(
                file,
                """
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"Inspect the file."}}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"I will "}}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"read it."}}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"tool_call","toolCallId":"read-1","title":"read","rawInput":{"path":"fixture.txt"}}}}
                {"jsonrpc":"2.0","id":4,"method":"elicitation/create","params":{"mode":"form","sessionId":"fixture","message":"Approve read fixture.txt?","requestedSchema":{"type":"object","properties":{"value":{"type":"string","enum":["Approve","Deny"]}},"required":["value"]}}}
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fixture","update":{"sessionUpdate":"tool_call_update","toolCallId":"read-1","title":"read","status":"completed","content":[{"type":"content","content":{"type":"text","text":"fixture content"}}]}}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
                """);
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(file.toFile(), OmpLogFormat.INSTANCE);
        assertEquals(
                List.of("thinking", "assistant", "tool_call", "tool_result", "result"),
                events.stream().map(AiAgentLogParser.EventView::getCategory).toList());
        assertEquals("Inspect the file.", events.get(0).getContent());
        assertEquals("I will read it.", events.get(1).getContent());
        assertTrue(events.get(2).getToolInput().contains("fixture.txt"));
        assertEquals("fixture content", events.get(3).getToolOutput());
        assertEquals("End_turn", events.get(4).getContent());
    }

    @Test
    void errorsNoticesImagesAndMultilineResultsRemainInspectable() throws Exception {
        Path file = temp.resolve("errors.jsonl");
        Files.writeString(
                file,
                """
                {"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"Inspect"},{"type":"image","mimeType":"image/png","data":"synthetic-image"}]}}
                {"type":"tool_execution_end","toolCallId":"bad","toolName":"bash","result":{"content":[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]},"isError":true}
                {"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"fixture provider unavailable"}}
                {"type":"notice","level":"warning","message":"fixture warning"}
                """);
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(file.toFile(), OmpLogFormat.INSTANCE);
        assertEquals("Inspect\n[Image: image/png]", events.get(0).getContent());
        assertFalse(events.get(0).getContent().contains("synthetic-image"));
        assertEquals("Error: line one\nline two", events.get(1).getToolOutput());
        assertEquals("error", events.get(2).getCategory());
        assertEquals("fixture provider unavailable", events.get(2).getContent());
        assertEquals("fixture warning", events.get(3).getContent());
    }
}
