package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;

import org.jenkinsci.plugins.structs.SymbolLookup;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@WithJenkins
class GeminiCliRetirementTest {
    private static final String REMOVED_MESSAGE =
            "Gemini CLI was removed; select Antigravity CLI (agy).";

    @Test
    void legacySavedJobLoadsAndFailsBeforeSetupOrCommand(JenkinsRule jenkins) throws Exception {
        Path setupMarker = Files.createTempFile("gemini-setup", ".marker");
        Path commandMarker = Files.createTempFile("gemini-command", ".marker");
        Files.delete(setupMarker);
        Files.delete(commandMarker);

        FreeStyleProject project = jenkins.createFreeStyleProject("legacy-gemini-cli");
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setAgent(new GeminiCliAgentHandler());
        builder.setPrompt("legacy saved prompt");
        builder.setApiCredentialsId("legacy-gemini-credential");
        builder.setSetupScript("touch " + setupMarker);
        builder.setCommandOverride("touch " + commandMarker);
        project.getBuildersList().add(builder);
        project.save();

        String savedConfig = Files.readString(project.getConfigFile().getFile().toPath());
        assertTrue(savedConfig.contains(GeminiCliAgentHandler.class.getName()));

        jenkins.jenkins.reload();
        project = jenkins.jenkins.getItemByFullName("legacy-gemini-cli", FreeStyleProject.class);
        assertNotNull(project, "Saved job should load even though the Gemini descriptor is gone");
        AiAgentBuilder loadedBuilder = (AiAgentBuilder) project.getBuildersList().get(0);
        GeminiCliAgentHandler loadedHandler = (GeminiCliAgentHandler) loadedBuilder.getAgent();
        assertEquals("", loadedBuilder.getEffectiveApiKeyEnvVar());
        IllegalStateException removedError =
                assertThrows(
                        IllegalStateException.class,
                        () -> loadedHandler.buildDefaultCommand(loadedBuilder, "legacy prompt"));
        assertEquals(REMOVED_MESSAGE, removedError.getMessage());

        JenkinsRule.WebClient webClient = jenkins.createWebClient();
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        var configurePage = webClient.goTo(project.getUrl() + "configure");
        assertEquals(200, configurePage.getWebResponse().getStatusCode());
        assertTrue(configurePage.asNormalizedText().contains("Antigravity CLI"));
        assertFalse(configurePage.asNormalizedText().contains("Gemini CLI"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        assertTrue(build.getLog().contains(REMOVED_MESSAGE));
        assertFalse(Files.exists(setupMarker), "Rejected jobs must not run setup scripts");
        assertFalse(Files.exists(commandMarker), "Rejected jobs must not run command overrides");
    }

    @Test
    void geminiIsAbsentFromJenkinsAgentSelectorAndPipelineSymbols(JenkinsRule jenkins) {
        List<Descriptor<AiAgentTypeHandler>> descriptors =
                jenkins.jenkins.getDescriptorList(AiAgentTypeHandler.class);
        assertTrue(
                descriptors.stream().anyMatch(d -> "Antigravity CLI".equals(d.getDisplayName())));
        assertFalse(descriptors.stream().anyMatch(d -> "Gemini CLI".equals(d.getDisplayName())));
        assertNull(SymbolLookup.get().findDescriptor(AiAgentTypeHandler.class, "geminiCli"));
        assertNotNull(SymbolLookup.get().findDescriptor(AiAgentTypeHandler.class, "antigravity"));
    }
}
