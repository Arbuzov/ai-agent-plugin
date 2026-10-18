package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jenkins.plugins.aiagentjob.antigravity.AntigravityAgentHandler;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.codex.CodexAgentHandler;
import io.jenkins.plugins.aiagentjob.cursor.CursorAgentHandler;
import io.jenkins.plugins.aiagentjob.grokbuild.GrokBuildAgentHandler;
import io.jenkins.plugins.aiagentjob.omp.OmpAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.pi.PiAgentHandler;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Portable parser checks for sanitized transcripts from real Jenkins Fibonacci runs. */
class FibonacciConversationTest {

    private static Stream<Arguments> harnesses() {
        return Stream.of(
                arguments(
                        "Claude Code",
                        "claude-code-fibonacci.jsonl",
                        new ClaudeCodeAgentHandler(),
                        "Read",
                        "Bash",
                        3,
                        3),
                arguments(
                        "Codex CLI",
                        "codex-cli-fibonacci.jsonl",
                        new CodexAgentHandler(),
                        "bash",
                        "file_change",
                        3,
                        3),
                arguments(
                        "Cursor Agent",
                        "cursor-agent-fibonacci.jsonl",
                        new CursorAgentHandler(),
                        "read",
                        "edit",
                        3,
                        3),
                arguments(
                        "OpenCode",
                        "opencode-fibonacci.jsonl",
                        new OpenCodeAgentHandler(),
                        "read",
                        "apply_patch",
                        0,
                        3),
                arguments("Pi", "pi-fibonacci.jsonl", new PiAgentHandler(), "read", "write", 3, 3),
                arguments(
                        "Oh My Pi",
                        "omp-fibonacci.jsonl",
                        new OmpAgentHandler(),
                        "read",
                        "write",
                        3,
                        3),
                arguments(
                        "Grok Build",
                        "grok-build-fibonacci.jsonl",
                        new GrokBuildAgentHandler(),
                        "read_file",
                        "search_replace",
                        3,
                        3),
                arguments(
                        "Antigravity",
                        "antigravity-cli-fibonacci.jsonl",
                        new AntigravityAgentHandler(),
                        "view_file",
                        "write_to_file",
                        4,
                        4));
    }

    @ParameterizedTest(name = "{0} renders its Fibonacci tool lifecycle")
    @MethodSource("harnesses")
    void rendersReadWriteRunAndAnswer(
            String harness,
            String fixture,
            AiAgentTypeHandler handler,
            String readTool,
            String writeTool,
            int expectedCalls,
            int expectedResults)
            throws Exception {
        List<AiAgentLogParser.EventView> events = parse(fixture, handler);
        List<AiAgentLogParser.EventView> calls =
                events.stream().filter(e -> "tool_call".equals(e.getCategory())).toList();
        List<AiAgentLogParser.EventView> results =
                events.stream().filter(e -> "tool_result".equals(e.getCategory())).toList();
        List<AiAgentLogParser.EventView> tools =
                events.stream().filter(FibonacciConversationTest::isToolCard).toList();

        assertEquals(expectedCalls, calls.size(), harness + " tool-call cards");
        assertEquals(expectedResults, results.size(), harness + " tool-result cards");
        assertTrue(
                tools.stream()
                        .filter(e -> hasLabel(e, readTool))
                        .anyMatch(
                                e ->
                                        contains(e.getToolInput(), "requirements.txt")
                                                || contains(e.getToolOutput(), "F(20)")),
                harness + " should show a meaningful requirements-file read");
        assertTrue(
                tools.stream()
                        .filter(e -> hasLabel(e, writeTool))
                        .anyMatch(
                                e ->
                                        contains(e.getToolInput(), "fibonacci.js")
                                                || contains(e.getToolOutput(), "fibonacci.js")),
                harness + " should show a write to fibonacci.js");
        assertTrue(
                tools.stream().anyMatch(e -> contains(e.getToolInput(), "node fibonacci.js")),
                harness + " should show the executed program command");
        assertTrue(
                results.stream().anyMatch(e -> e.getToolOutput().contains("FIBONACCI_20=6765")),
                harness + " should show the program's actual output");
        assertTrue(
                events.stream()
                        .filter(e -> "assistant".equals(e.getCategory()))
                        .anyMatch(
                                e ->
                                        e.getContent().contains("20th Fibonacci number")
                                                && e.getContent().contains("6765")),
                harness + " should render the final answer");

        if ("Oh My Pi".equals(harness)) {
            assertFalse(
                    calls.stream()
                            .anyMatch(e -> e.getToolInput().matches("(?s).*\\\"i\\\"\\s*:.*")),
                    "OMP intent metadata should not appear as a tool argument");
        }
        if ("Claude Code".equals(harness)) {
            assertFalse(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            "system".equals(e.getCategory())
                                                    && e.getLabel()
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("thinking_tokens")),
                    "Claude thinking-token telemetry should not render as conversation content");
        }
        if ("Antigravity".equals(harness)) {
            assertTrue(
                    results.stream()
                            .filter(e -> hasLabel(e, "write_to_file"))
                            .anyMatch(e -> "Completed".equals(e.getToolOutput())),
                    "A completed Antigravity write with no tool output should render as Completed");
        }
    }

    private List<AiAgentLogParser.EventView> parse(String fixture, AiAgentTypeHandler handler)
            throws Exception {
        File rawLog = Files.createTempFile("ai-agent-fibonacci-", ".jsonl").toFile();
        rawLog.deleteOnExit();
        try (InputStream input = getClass().getResourceAsStream("fixtures/" + fixture)) {
            assertNotNull(input, "Missing fixture " + fixture);
            Files.copy(input, rawLog.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return AiAgentLogParser.parse(rawLog, handler.getLogFormat());
    }

    private static boolean isToolCard(AiAgentLogParser.EventView event) {
        return "tool_call".equals(event.getCategory()) || "tool_result".equals(event.getCategory());
    }

    private static boolean hasLabel(AiAgentLogParser.EventView event, String expected) {
        return event.getLabel()
                .toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
    }

    private static boolean contains(String value, String expected) {
        return value != null && value.contains(expected);
    }
}
