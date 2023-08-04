package com.bashpile.maintests;

import com.bashpile.BashpileMain;
import com.bashpile.StringUtils;
import com.bashpile.exceptions.BashpileUncheckedAssertionException;
import com.bashpile.exceptions.BashpileUncheckedException;
import com.bashpile.exceptions.UserError;
import com.bashpile.shell.BashShell;
import com.bashpile.shell.ExecutionResults;
import com.google.common.collect.Streams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

abstract public class BashpileTest {

    protected static final Pattern END_OF_LINE_COMMENT = Pattern.compile("^[^ #]+#.*$");

    private static final Logger LOG = LogManager.getLogger(BashpileTest.class);

    protected static void assertSuccessfulExitCode(@Nonnull final ExecutionResults executionResults) {
        assertEquals(ExecutionResults.SUCCESS, executionResults.exitCode(),
                "Found failing (non-0) exit code: %s.  Full text results:\n%s".formatted(
                        executionResults.exitCode(), executionResults.stdout()));
    }

    protected static void assertFailedExitCode(@Nonnull final ExecutionResults executionResults) {
        assertNotEquals(ExecutionResults.SUCCESS, executionResults.exitCode(),
                "Found successful exit code (0) when expecting errored exit code.  Full text results:\n%s".formatted(
                        executionResults.stdout()));
    }

    // TODO add this to all tests
    protected static void assertCorrectFormatting(@Nonnull final ExecutionResults executionResults) {
        // track if, else, fi for now
        final AtomicReference<List<Long>> erroredLines = new AtomicReference<>(new ArrayList<>(10));
        final AtomicLong indentLevel = new AtomicLong(0);
        final List<String> ignored = Streams.mapWithIndex(executionResults.stdinLines().stream(), (line, i) -> {
            final int spaces = line.length() - line.stripLeading().length();
            if (spaces % 4 != 0 || StringUtils.isBlank(line)) {
                erroredLines.get().add(i);
                return line;
            }
            final long tabs = spaces / 4;
            final String keyword = line.stripLeading().split(" ", 2)[0];
            switch (keyword) {
                case "if" -> {
                    if (tabs != indentLevel.get()) {
                        erroredLines.get().add(i);
                    }
                    // generated code uses a Bash if all on one line (e.g. starts with if and ends with fi)
                    if (!line.endsWith("fi")) {
                        indentLevel.getAndIncrement();
                    }
                }
                case "else" -> {
                    if (tabs != indentLevel.get() - 1) {
                        erroredLines.get().add(i);
                    }
                }
                case "fi" -> {
                    indentLevel.getAndDecrement();
                    if (tabs != indentLevel.get()) {
                        erroredLines.get().add(i);
                    }
                }
                default -> {
                    if (tabs != indentLevel.get()) {
                        erroredLines.get().add(i);
                    }
                }
            }
            return line;
        }).toList();
        if (!erroredLines.get().isEmpty()) {
            throw new BashpileUncheckedAssertionException("Bad formatting on lines " + erroredLines.get().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ")));
        }
    }

    protected @Nonnull ExecutionResults runText(@Nonnull final String bashText) {
        LOG.debug("Start of:\n{}", bashText);
        BashpileMain bashpile = new BashpileMain(bashText);
        return execute(bashpile);
    }

    protected @Nonnull BashShell runTextAsync(@Nonnull final String bashText) {
        LOG.debug("Starting background threads for:\n{}", bashText);
        BashpileMain bashpile = new BashpileMain(bashText);
        return executeAsync(bashpile);
    }

    protected @Nonnull ExecutionResults runPath(@Nonnull final Path file) {
        final Path filename = !file.isAbsolute() ? Path.of("src/test/resources/scripts/" + file) : file;
        LOG.debug("Start of {}", filename);
        final BashpileMain bashpile = new BashpileMain(filename);
        return execute(bashpile);
    }

    private @Nonnull ExecutionResults execute(@Nonnull final BashpileMain bashpile) {
        LOG.debug("In {}", System.getProperty("user.dir"));
        String bashScript = null;
        try {
            bashScript = bashpile.transpile();
            return BashShell.runAndJoin(bashScript);
        } catch (UserError | AssertionError e) {
            throw e;
        } catch (Throwable e) {
            throw createExecutionException(e, bashScript);
        }
    }

    private @Nonnull BashShell executeAsync(@Nonnull final BashpileMain bashpile) {
        LOG.debug("In {}", System.getProperty("user.dir"));
        String bashScript = null;
        try {
            bashScript = bashpile.transpile();
            return BashShell.runAsync(bashScript);
        } catch (UserError | AssertionError e) {
            throw e;
        } catch (Throwable e) {
            throw createExecutionException(e, bashScript);
        }
    }

    private static BashpileUncheckedException createExecutionException(Throwable e, String bashScript) {
        String msg = bashScript != null ? "\nCouldn't run `%s`".formatted(bashScript) : "\nCouldn't parse input";
        if (e.getMessage() != null) {
            msg += " because of\n`%s`".formatted(e.getMessage());
        }
        if (e.getCause() != null) {
            msg += "\n caused by `%s`".formatted(e.getCause().getMessage());
        }
        return new BashpileUncheckedException(msg, e);
    }
}
