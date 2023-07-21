package com.bashpile.maintests;

import com.bashpile.commandline.ExecutionResults;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static com.bashpile.Asserts.assertExecutionSuccess;
import static com.bashpile.ListUtils.getLast;
import static org.junit.jupiter.api.Assertions.*;

@Order(20)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExpressionBashpileTest extends BashpileTest {

    @Test
    @Order(10)
    public void printCalcWorks() {
        var ret = runText("print(1 + 1)");
        assertNotNull(ret);
        assertEquals("2\n", ret.stdout());
    }

    @Test
    @Order(20)
    public void multilineCalcWorks() {
        List<String> ret = runText("""
                print(1 + 1)
                print(1-1)""").stdoutLines();
        assertNotNull(ret);
        int expected = 2;
        assertEquals(expected, ret.size(), "Expected %d lines but got %d".formatted(expected, ret.size()));
        assertEquals("2", ret.get(0));
        assertEquals("0", ret.get(1));
    }

    @Test
    @Order(30)
    public void stringConcatWorks() {
        var runResult = runText("""
                print("hello" + " " + "world")""");
        assertExecutionSuccess(runResult);
        List<String> outLines = runResult.stdoutLines();
        assertEquals("hello world", getLast(outLines));
    }

    @Test
    @Order(40)
    public void stringBadOperatorThrows() {
        assertThrows(AssertionError.class, () -> runText("""
                print("hello " * "world")"""));
    }

    @Test
    @Order(50)
    public void parenStringWorks() {
        List<String> ret = runText("""
                print((("hello" + " world") + (", you" + " good?")))""").stdoutLines();
        assertEquals(1, ret.size(), "Unexpected number of lines");
        assertEquals("hello world, you good?", ret.get(0));
    }

    @Test
    @Order(80)
    public void intExpressionsWork() {
        String bashpile = """
                print((3 + 5) * 3)
                print(32000 + 32000)
                print(64 + 64)""";
        List<String> executionResults = runText(bashpile).stdoutLines();
        List<String> expected = List.of("24", "64000", "128");
        assertEquals(3, executionResults.size());
        assertEquals(expected, executionResults);
    }

    @Test
    @Order(90)
    public void parenIntExpressionsWork() {
        List<String> ret = runText("print(((1 + 2) * (3 + 4)))").stdoutLines();
        assertEquals(1, ret.size(), "Unexpected number of lines");
        assertEquals("21", ret.get(0));
    }

    @Test
    @Order(100)
    public void floatExpressionsWork() {
        List<String> executionResults = runText("""
                print((38. + 4) * .5)
                print(7.7 - 0.7)""").stdoutLines();
        List<String> expected = List.of("21.0", "7.0");
        assertEquals(2, executionResults.size());
        assertEquals(expected, executionResults);
    }

    // TODO make only some expressions take types
    @Test
    @Order(110)
    public void numberAndStringExpressionsWork() {
        ExecutionResults executionResults = runText("""
                i: int
                j: float
                i = 4
                j = .5
                print((38. + i) * j)
                print(7.7 - "0.7":float)""");
        // confirm string is unquoted on typecast
        assertFalse(executionResults.stdin().contains("\"0.7\""));
        // header has 1 export, definitions of i and j have one export each.  Reassigns should not have exports
        assertEquals(3, executionResults.stdinLines().stream().filter(x -> x.contains("export")).count());
        List<String> stdoutLines = executionResults.stdoutLines();
        List<String> expected = List.of("21.0", "7.0");
        assertEquals(2, stdoutLines.size());
        assertEquals(expected, stdoutLines);
    }
}
