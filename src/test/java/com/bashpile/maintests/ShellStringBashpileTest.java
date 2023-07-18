package com.bashpile.maintests;

import com.bashpile.commandline.ExecutionResults;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO verify comment line is emitted
@Order(50)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ShellStringBashpileTest extends BashpileTest {

    /** Simple one word command */
    @Test @Order(10)
    public void runLsWorks() {
        final ExecutionResults results = runText("#(ls)");
        assertTrue(results.stdout().contains("pom.xml\n"));
    }

    /** Command with arguments */
    @Test @Order(20)
    public void runEchoWorks() {
        final ExecutionResults results = runText("#(echo hello command object)");
        assertEquals("hello command object\n", results.stdout());
    }

    @Test @Order(30)
    public void runInvalidCommandHadBadExitCode() {
        final ExecutionResults results = runText("#(invalid_command_example_for_testing)");
        assertTrue(results.exitCode() != ExecutionResults.SUCCESS);
    }

    @Test @Order(40)
    public void runEchoParenthesisWorks() {
        final ExecutionResults results = runPath(Path.of("runEchoParenthesis.bashpile"));
        assertEquals(ExecutionResults.SUCCESS, results.exitCode());
        assertEquals("()\n", results.stdout());
    }

    @Test @Order(50)
    public void nestedShellStringsWork() {
        final ExecutionResults results = runText("#(cat #(src/test/resources/testdata.txt))");
        assertEquals(ExecutionResults.SUCCESS, results.exitCode());
        assertEquals("test\n", results.stdout());
    }

    @Test @Order(60)
    public void shellStringsWithHashWork() {
        final ExecutionResults results = runText("#(echo '#')");
        assertEquals(ExecutionResults.SUCCESS, results.exitCode());
        assertEquals("#\n", results.stdout());
    }
}