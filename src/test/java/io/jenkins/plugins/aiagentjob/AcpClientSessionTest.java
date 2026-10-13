package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Proc;

import io.jenkins.plugins.aiagentjob.grokbuild.GrokBuildAgentHandler;

import net.sf.json.JSONObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@WithJenkins
class AcpClientSessionTest {

    @TempDir Path tempDirectory;

    @Test
    void usesApiKeyWhenProcessBootstrapReportsIt(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","method":"ai-agent/auth_environment","params":{"name":"XAI_API_KEY"}}
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of("XAI_API_KEY", "xai.api_key"),
                            List.of("cached_token"),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesCachedIdentityWhenProcessBootstrapDoesNotProvideApiKey(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of("XAI_API_KEY", "xai.api_key"),
                            List.of("cached_token"),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"cached_token\""));
            assertFalse(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesAdvertisedApiKeyWhenConfiguredOutsideProcessEnvironment(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);
        AiAgentBuilder config = new AiAgentBuilder();
        config.setAgent(new GrokBuildAgentHandler());
        AiAgentTypeHandler.AcpExecutionSpec execution = config.getAgent().buildAcpExecution(config);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            execution.getAuthenticationMethods(),
                            execution.getFallbackAuthenticationMethods(),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void resolvesArbitraryConfigIdsWithoutCategoriesAndUsesUpdatedOptions(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"active-model","name":"Model","type":"select","currentValue":"model-1","options":[{"value":"model-1","name":"Model 1"},{"value":"model-2","name":"Model 2"}]},{"id":"initial-depth","name":"Reasoning effort","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"}]}]}}
                {"jsonrpc":"2.0","id":3,"result":{"configOptions":[{"id":"active-model","name":"Model","type":"select","currentValue":"model-2","options":[{"value":"model-1","name":"Model 1"},{"value":"model-2","name":"Model 2"}]},{"id":"thinking-depth","name":"Reasoning effort","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}]}}
                {"jsonrpc":"2.0","id":4,"result":{"configOptions":[]}}
                {"jsonrpc":"2.0","id":5,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "model-2",
                            "high",
                            Map.of(),
                            List.of(),
                            Map.of()));
            assertTrue(
                    proc.stdinText()
                            .contains("\"configId\":\"active-model\",\"value\":\"model-2\""));
            assertTrue(
                    proc.stdinText()
                            .contains("\"configId\":\"thinking-depth\",\"value\":\"high\""));
            assertFalse(proc.stdinText().contains("\"configId\":\"initial-depth\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void rejectsUnsupportedAdvertisedConfigValue(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"active-model","name":"Model","category":"model","type":"select","currentValue":"model-1","options":[{"value":"model-1","name":"Model 1"}]}]}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "missing-model",
                                            "",
                                            Map.of(),
                                            List.of(),
                                            Map.of()));

            assertTrue(error.getMessage().contains("missing-model"));
            assertTrue(error.getMessage().contains("active-model"));
            assertTrue(error.getMessage().contains("model-1"));
        } finally {
            proc.kill();
        }
    }

    @Test
    void rejectsAmbiguousCategorylessConfigValue(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"quality","name":"Quality","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]},{"id":"thinking-depth","name":"Thinking","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}]}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "high",
                                            Map.of(),
                                            List.of(),
                                            Map.of()));

            assertTrue(error.getMessage().contains("high"));
            assertTrue(error.getMessage().contains("quality"));
            assertTrue(error.getMessage().contains("thinking-depth"));
        } finally {
            proc.kill();
        }
    }

    @Test
    void timesOutWhenAuthenticationDoesNotRespond(JenkinsRule jenkins) throws Exception {
        String initialize =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"}]}}
                """;
        FakeProc proc = new FakeProc(initialize, true);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(200));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of("XAI_API_KEY", "xai.api_key"),
                                            List.of(),
                                            Map.of("XAI_API_KEY", "fixture-key")));

            assertTrue(
                    error.getMessage().contains("ACP authenticate timed out"),
                    "Unexpected ACP failure: " + error.getMessage());
            assertFalse(proc.isAlive());
        } finally {
            proc.kill();
        }
    }

    @Test
    void startsProtocolTimeoutAfterProcessReadyMarker(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","method":"ai-agent/process_ready"}
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false, Duration.ofMillis(250));

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(50),
                            true);

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of(),
                            List.of(),
                            Map.of()));
        } finally {
            proc.kill();
        }
    }

    @Test
    void doesNotFallBackToCachedIdentityAfterApiKeyFailure(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"invalid API key"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of("XAI_API_KEY", "xai.api_key"),
                                            List.of("cached_token"),
                                            Map.of("XAI_API_KEY", "invalid-fixture-key")));

            assertTrue(error.getMessage().contains("invalid API key"));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
            assertFalse(proc.stdinText().contains("\"methodId\":\"cached_token\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesAllowAlwaysWhenAllowOnceIsNotOffered(JenkinsRule jenkins) throws Exception {
        assertApprovedPermissionOption(
                """
                [{"optionId":"always","kind":"allow_always"},{"optionId":"reject","kind":"reject_once"}]
                """,
                "always");
    }

    @Test
    void prefersAllowOnceWhenBothAllowOptionsAreOffered(JenkinsRule jenkins) throws Exception {
        assertApprovedPermissionOption(
                """
                [{"optionId":"always","kind":"allow_always"},{"optionId":"once","kind":"allow_once"}]
                """,
                "once");
    }

    @Test
    void approvesOmpToolFormThroughJenkinsAndMasksItsSummary(JenkinsRule jenkins) throws Exception {
        FakeProc proc = new FakeProc(approvalFormResponses("fixture-secret"), false);
        ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();
        File rawLog = tempDirectory.resolve("approval.jsonl").toFile();
        try (AiAgentExecutor.AgentOutputHandler output =
                new AiAgentExecutor.AgentOutputHandler(
                        new ByteArrayOutputStream(),
                        rawLog,
                        liveExecution,
                        List.of("fixture-secret"))) {
            CompletableFuture<Boolean> execution =
                    startExecution(proc, output, liveExecution, Duration.ofSeconds(2));
            ExecutionRegistry.PendingApproval pending = waitForPendingApproval(liveExecution);
            assertEquals("write", pending.getToolName());
            assertEquals("form-1", pending.getToolCallId());
            assertEquals("Allow tool: write\nArgs: ****", pending.getInputSummary());
            assertTrue(liveExecution.approve(pending.getId()));
            assertTrue(execution.get(2, TimeUnit.SECONDS));
            JSONObject initialize =
                    JSONObject.fromObject(proc.stdinText().lines().findFirst().orElseThrow());
            assertTrue(
                    initialize
                            .getJSONObject("params")
                            .getJSONObject("clientCapabilities")
                            .getJSONObject("elicitation")
                            .has("form"));
            assertEquals(
                    "Approve",
                    response(proc, "form-1").getJSONObject("content").getString("value"));
            assertTrue(liveExecution.getPendingApprovals().isEmpty());
            String recorded = Files.readString(rawLog.toPath());
            JSONObject recordedForm =
                    recorded.lines()
                            .map(JSONObject::fromObject)
                            .filter(
                                    line ->
                                            "elicitation/create"
                                                    .equals(line.optString("method", "")))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "Allow tool: write\nArgs: ****",
                    recordedForm.getJSONObject("params").getString("message"));
            assertFalse(recorded.contains("fixture-secret"));
        } finally {
            proc.kill();
        }
    }

    @Test
    void deniesOmpToolFormWithoutContinuingPrompt(JenkinsRule jenkins) throws Exception {
        FakeProc proc = new FakeProc(approvalFormResponses("fixture text"), false);
        ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();
        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            CompletableFuture<Boolean> execution =
                    startExecution(proc, output, liveExecution, Duration.ofSeconds(2));
            ExecutionRegistry.PendingApproval pending = waitForPendingApproval(liveExecution);
            assertTrue(liveExecution.deny(pending.getId(), "denied for fixture"));
            assertFalse(execution.get(2, TimeUnit.SECONDS));
            assertEquals(
                    "Deny", response(proc, "form-1").getJSONObject("content").getString("value"));
            assertTrue(liveExecution.getPendingApprovals().isEmpty());
        } finally {
            proc.kill();
        }
    }

    @Test
    void timesOutOmpToolFormWithoutApprovingIt(JenkinsRule jenkins) throws Exception {
        FakeProc proc = new FakeProc(approvalFormResponses("fixture text"), false);
        ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();
        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            CompletableFuture<Boolean> execution =
                    startExecution(proc, output, liveExecution, Duration.ofMillis(50));
            assertFalse(execution.get(2, TimeUnit.SECONDS));
            assertEquals(
                    "Deny", response(proc, "form-1").getJSONObject("content").getString("value"));
            assertTrue(liveExecution.getPendingApprovals().isEmpty());
        } finally {
            proc.kill();
        }
    }

    @Test
    void cancelsOmpToolFormWithoutApprovingIt(JenkinsRule jenkins) throws Exception {
        FakeProc proc = new FakeProc(approvalFormResponses("fixture text"), false);
        ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();
        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            CompletableFuture<Boolean> execution =
                    startExecution(proc, output, liveExecution, Duration.ofSeconds(2));
            waitForPendingApproval(liveExecution);
            liveExecution.cancelPendingApprovals("build stopped");
            assertFalse(execution.get(2, TimeUnit.SECONDS));
            assertEquals(
                    "Deny", response(proc, "form-1").getJSONObject("content").getString("value"));
            assertTrue(liveExecution.getPendingApprovals().isEmpty());
        } finally {
            proc.kill();
        }
    }

    @Test
    void rejectsUnsupportedElicitationFormsWithoutCreatingApprovals(JenkinsRule jenkins)
            throws Exception {
        JSONObject supported = JSONObject.fromObject(approvalFormRequest("fixture text"));
        JSONObject plan = JSONObject.fromObject(supported.toString());
        plan.getJSONObject("params")
                .getJSONObject("requestedSchema")
                .getJSONObject("properties")
                .getJSONObject("value")
                .put("enum", List.of("Approve and execute", "Refine"));
        JSONObject textInput = JSONObject.fromObject(supported.toString());
        textInput
                .getJSONObject("params")
                .getJSONObject("requestedSchema")
                .getJSONObject("properties")
                .getJSONObject("value")
                .remove("enum");
        JSONObject extraProperty = JSONObject.fromObject(supported.toString());
        extraProperty
                .getJSONObject("params")
                .getJSONObject("requestedSchema")
                .getJSONObject("properties")
                .put("secret", JSONObject.fromObject("{\"type\":\"string\"}"));
        JSONObject otherSession = JSONObject.fromObject(supported.toString());
        otherSession.getJSONObject("params").put("sessionId", "other-session");
        JSONObject genericSelection = JSONObject.fromObject(supported.toString());
        genericSelection.getJSONObject("params").put("message", "Choose an unrelated setting");
        JSONObject url = JSONObject.fromObject(supported.toString());
        url.getJSONObject("params").put("mode", "url");

        for (JSONObject form :
                List.of(plan, textInput, extraProperty, otherSession, genericSelection, url)) {
            FakeProc proc = new FakeProc(protocolResponses(form.toString()), false);
            ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();
            try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
                assertTrue(
                        startExecution(proc, output, liveExecution, Duration.ofSeconds(1))
                                .get(2, TimeUnit.SECONDS));
                JSONObject error = message(proc, "form-1").getJSONObject("error");
                assertEquals(-32602, error.getInt("code"));
                assertTrue(error.getString("message").contains("only tool approval is supported"));
                assertTrue(liveExecution.getPendingApprovals().isEmpty());
                assertFalse(proc.stdinText().contains("\"value\":\"Approve\""));
            } finally {
                proc.kill();
            }
        }
    }

    @Test
    void recordsPromptUsageWithoutArbitraryResponseMetadata(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"_meta":{"token":"fixture-secret"}}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"fixture-control"}]}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn","usage":{"inputTokens":12,"outputTokens":3,"totalTokens":23,"cachedReadTokens":5,"cachedWriteTokens":3,"thoughtTokens":1,"untrusted":"fixture-secret"},"_meta":{"token":"fixture-secret"},"configOptions":[{"id":"fixture-control"}]}}
                """;
        FakeProc proc = new FakeProc(responses, false);
        File rawLog = tempDirectory.resolve("prompt-usage.jsonl").toFile();
        try (AiAgentExecutor.AgentOutputHandler output =
                new AiAgentExecutor.AgentOutputHandler(
                        new ByteArrayOutputStream(),
                        rawLog,
                        new ExecutionRegistry.LiveExecution(),
                        List.of())) {
            assertTrue(
                    startExecution(
                                    proc,
                                    output,
                                    new ExecutionRegistry.LiveExecution(),
                                    Duration.ofSeconds(1))
                            .get(2, TimeUnit.SECONDS));
            String log = Files.readString(rawLog.toPath());
            JSONObject recorded = JSONObject.fromObject(log.trim()).getJSONObject("result");
            assertEquals("end_turn", recorded.getString("stopReason"));
            assertEquals(12, recorded.getJSONObject("usage").getInt("inputTokens"));
            assertEquals(3, recorded.getJSONObject("usage").getInt("outputTokens"));
            assertEquals(23, recorded.getJSONObject("usage").getInt("totalTokens"));
            assertEquals(5, recorded.getJSONObject("usage").getInt("cachedReadTokens"));
            assertEquals(3, recorded.getJSONObject("usage").getInt("cachedWriteTokens"));
            assertEquals(1, recorded.getJSONObject("usage").getInt("thoughtTokens"));
            assertFalse(log.contains("fixture-secret"));
            assertFalse(log.contains("fixture-control"));
        } finally {
            proc.kill();
        }
    }

    private String approvalFormResponses(String summary) {
        return protocolResponses(approvalFormRequest(summary));
    }

    private static String approvalFormRequest(String summary) {
        JSONObject params =
                JSONObject.fromObject(
                        """
                {"mode":"form","sessionId":"session-1","requestedSchema":{"type":"object","properties":{"value":{"type":"string","enum":["Approve","Deny"]}},"required":["value"]}}
                """);
        params.put("message", "Allow tool: write\nArgs: " + summary);
        JSONObject request =
                JSONObject.fromObject(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"form-1\",\"method\":\"elicitation/create\"}");
        request.put("params", params);
        return request.toString();
    }

    private static String protocolResponses(String form) {
        return """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1"}}
                %s
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
                """
                .formatted(form);
    }

    private CompletableFuture<Boolean> startExecution(
            FakeProc proc,
            AiAgentExecutor.AgentOutputHandler output,
            ExecutionRegistry.LiveExecution liveExecution,
            Duration timeout) {
        AcpClientSession session =
                new AcpClientSession(
                        proc,
                        proc.getStdout(),
                        proc.getStdin(),
                        output,
                        liveExecution,
                        timeout,
                        Duration.ofSeconds(1));
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return session.execute(
                                tempDirectory.toString(),
                                "respond done",
                                "",
                                "",
                                Map.of(),
                                List.of(),
                                Map.of());
                    } catch (IOException | InterruptedException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    private static JSONObject response(FakeProc proc, String id) {
        JSONObject message = message(proc, id);
        assertEquals("accept", message.getJSONObject("result").getString("action"));
        return message.getJSONObject("result");
    }

    private static JSONObject message(FakeProc proc, String id) {
        return proc.stdinText()
                .lines()
                .map(JSONObject::fromObject)
                .filter(message -> id.equals(message.optString("id", "")))
                .findFirst()
                .orElseThrow();
    }

    private void assertApprovedPermissionOption(String options, String expectedOptionId)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":"permission-1","method":"session/request_permission","params":{"sessionId":"session-1","toolCall":{"toolCallId":"call-1","title":"run command","kind":"execute","rawInput":{"command":"true"}},"options":%s}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
                """
                        .formatted(options.trim());
        FakeProc proc = new FakeProc(responses, false);
        ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            liveExecution,
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(1));
            CompletableFuture<Boolean> execution =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of(),
                                            List.of(),
                                            Map.of());
                                } catch (IOException | InterruptedException e) {
                                    throw new CompletionException(e);
                                }
                            });

            ExecutionRegistry.PendingApproval pending = waitForPendingApproval(liveExecution);
            assertTrue(liveExecution.approve(pending.getId()));
            assertTrue(execution.get(2, TimeUnit.SECONDS));
            assertTrue(
                    proc.stdinText()
                            .contains(
                                    "\"outcome\":\"selected\",\"optionId\":\""
                                            + expectedOptionId
                                            + "\""));
        } finally {
            proc.kill();
        }
    }

    private static ExecutionRegistry.PendingApproval waitForPendingApproval(
            ExecutionRegistry.LiveExecution liveExecution) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<ExecutionRegistry.PendingApproval> approvals = liveExecution.getPendingApprovals();
            if (!approvals.isEmpty()) {
                return approvals.get(0);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("ACP permission request did not reach Jenkins");
    }

    private AiAgentExecutor.AgentOutputHandler newOutputHandler() throws IOException {
        File rawLog = tempDirectory.resolve("raw-" + System.nanoTime() + ".jsonl").toFile();
        return new AiAgentExecutor.AgentOutputHandler(
                new ByteArrayOutputStream(),
                rawLog,
                new ExecutionRegistry.LiveExecution(),
                List.of());
    }

    private static final class FakeProc extends Proc {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private volatile boolean alive = true;

        FakeProc(String responses, boolean keepOutputOpen) throws IOException {
            this(responses, keepOutputOpen, Duration.ZERO);
        }

        FakeProc(String responses, boolean keepOutputOpen, Duration delay) throws IOException {
            serverOutput = new PipedOutputStream(stdout);
            Thread writer =
                    new Thread(
                            () -> {
                                try {
                                    if (!delay.isZero()) {
                                        Thread.sleep(delay.toMillis());
                                    }
                                    writeResponses(responses, keepOutputOpen);
                                    if (keepOutputOpen) {
                                        stopped.await();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    closeServerOutput();
                                } catch (IOException e) {
                                    closeServerOutput();
                                }
                            },
                            "fake-acp-response-writer");
            writer.setDaemon(true);
            writer.start();
        }

        private void writeResponses(String responses, boolean keepOutputOpen) throws IOException {
            serverOutput.write(responses.getBytes(StandardCharsets.UTF_8));
            serverOutput.flush();
            if (!keepOutputOpen) {
                serverOutput.close();
            }
        }

        private void closeServerOutput() {
            try {
                serverOutput.close();
            } catch (IOException ignored) {
            }
        }

        String stdinText() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void kill() throws IOException {
            alive = false;
            stopped.countDown();
            serverOutput.close();
            stdout.close();
        }

        @Override
        public int join() {
            alive = false;
            stopped.countDown();
            return 0;
        }

        @Override
        public InputStream getStdout() {
            return stdout;
        }

        @Override
        public InputStream getStderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getStdin() {
            return stdin;
        }
    }
}
