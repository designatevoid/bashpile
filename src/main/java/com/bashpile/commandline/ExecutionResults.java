package com.bashpile.commandline;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Holds STDIN, the Linux exit code and STDOUT */
public record ExecutionResults(@Nonnull String stdin, int exitCode, @Nonnull String stdout) {

    public static final Pattern windowsLineEndings = Pattern.compile("\r\n");

    public ExecutionResults(@Nonnull final String stdin, final int exitCode, @Nonnull final String stdout) {
        // convert windows line ending to Linux line endings
        this.stdin = windowsLineEndings.matcher(stdin).replaceAll("\n");
        this.exitCode = exitCode;
        this.stdout = windowsLineEndings.matcher(stdout).replaceAll("\n");
    }

    public @Nonnull List<String> stdinLines() {
        return Arrays.asList(stdin.split("\n"));
    }

    /**
     * Gets the stdout split by newlines.
     * <br>
     * Ending blank lines may not be preserved correctly due to a Java bug (on OpenJDK v20).
     * As a workaround check stdinLines instead (e.g. verify that the script executed ends with two echo commands).
     *
     * @return A list of lines.  The end elements of the list may not be the empty String.
     */
    public @Nonnull List<String> stdoutLines() {
        return Arrays.asList(stdout.split("\n"));
    }
}
