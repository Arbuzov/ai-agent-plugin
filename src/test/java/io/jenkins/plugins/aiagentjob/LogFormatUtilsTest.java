package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.sf.json.JSONObject;

import org.junit.jupiter.api.Test;

class LogFormatUtilsTest {
    @Test
    void applyPatchInputPreservesReadablePatchLines() {
        String patch =
                "*** Begin Patch\n*** Add File: fibonacci.js\n+console.log('FIBONACCI_20=6765');\n*** End Patch";
        JSONObject input = new JSONObject();
        input.put("patchText", patch);

        assertEquals(patch, LogFormatUtils.extractToolInput(input, "apply_patch"));
    }

    @Test
    void outputOnlyToolResultHasUsefulProgressiveSummary() {
        AiAgentLogParser.EventView event =
                AiAgentLogParser.ParsedLine.toolResult(
                                1, "bash", "FIBONACCI_20=6765", "{}", "fibo-run")
                        .toEventView();

        assertEquals("bash: FIBONACCI_20=6765", event.getSummary());
    }
}
