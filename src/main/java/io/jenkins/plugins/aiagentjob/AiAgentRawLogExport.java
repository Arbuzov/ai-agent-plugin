package io.jenkins.plugins.aiagentjob;

import edu.umd.cs.findbugs.annotations.CheckForNull;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Copies the raw agent log captured on the controller to a workspace-relative file on the build
 * node, so external systems can collect agent conversations with ordinary workspace tooling.
 *
 * <p>The copy is made after the agent process exits. The agent therefore cannot read or rewrite the
 * exported file while it runs, and the exported content is identical to the build-page raw log,
 * including credential masking.
 */
final class AiAgentRawLogExport {
    private static final Pattern SEPARATORS = Pattern.compile("[/\\\\]");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private AiAgentRawLogExport() {}

    /**
     * Returns the validation problem for a configured path, or {@code null} when the path is empty
     * or acceptable. The check is lexical; symlinks are resolved on the build node during export.
     */
    @CheckForNull
    static String validationError(@CheckForNull String path) {
        String trimmed = Util.fixNull(path).trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("/")
                || trimmed.startsWith("\\")
                || WINDOWS_DRIVE.matcher(trimmed).matches()) {
            return "Raw log path must be relative to the workspace: " + trimmed;
        }
        if (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            return "Raw log path must name a file, not a directory: " + trimmed;
        }
        String[] segments = SEPARATORS.split(trimmed);
        for (String segment : segments) {
            if ("..".equals(segment)) {
                return "Raw log path must stay inside the workspace and cannot contain '..': "
                        + trimmed;
            }
        }
        String fileName = segments[segments.length - 1];
        if (fileName.isEmpty() || ".".equals(fileName)) {
            return "Raw log path must name a file, not a directory: " + trimmed;
        }
        return null;
    }

    /**
     * Validates a configured path before the agent starts.
     *
     * @return the trimmed path, or {@code null} when export is disabled
     */
    @CheckForNull
    static String resolve(@CheckForNull String path) throws AbortException {
        String error = validationError(path);
        if (error != null) {
            throw new AbortException("[ai-agent] " + error);
        }
        return Util.fixEmptyAndTrim(path);
    }

    /**
     * Copies {@code source} to {@code relativePath} inside {@code workspace}, replacing any
     * existing file. A symlink at the target is replaced by a regular file; a parent directory that
     * resolves outside the workspace is rejected.
     */
    static void export(File source, FilePath workspace, String relativePath, TaskListener listener)
            throws IOException, InterruptedException {
        if (!source.isFile()) {
            listener.getLogger()
                    .println(
                            "[ai-agent] No raw agent log was captured; nothing to save to "
                                    + relativePath);
            return;
        }

        String parentPath = parentOf(relativePath);
        if (!parentPath.isEmpty() && !workspace.isDescendant(parentPath)) {
            throw new AbortException(
                    "[ai-agent] Raw log path parent resolves outside the workspace (check for"
                            + " symlinks): "
                            + relativePath);
        }

        FilePath target = workspace.child(relativePath);
        FilePath parent = target.getParent();
        if (parent != null) {
            parent.mkdirs();
        }
        if (target.readLink() != null) {
            listener.getLogger()
                    .println(
                            "[ai-agent] Replacing symlink at raw log path with a regular file: "
                                    + relativePath);
            target.delete();
        } else if (target.isDirectory()) {
            throw new AbortException(
                    "[ai-agent] Raw log path points to an existing directory: " + relativePath);
        }

        target.copyFrom(new FilePath(source));
        listener.getLogger()
                .println("[ai-agent] Raw agent log saved to workspace path: " + relativePath);
    }

    private static String parentOf(String relativePath) {
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return slash <= 0 ? "" : relativePath.substring(0, slash);
    }
}
