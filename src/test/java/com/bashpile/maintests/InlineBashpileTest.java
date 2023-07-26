package com.bashpile.maintests;

import com.bashpile.commandline.ExecutionResults;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Order(60)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InlineBashpileTest extends BashpileTest {

    /** Simple one word command */
    @Test @Order(10)
    public void inlineWorks() {
        final ExecutionResults results = runText("""
                fileContents: str = $(cat src/test/resources/testdata.txt)
                print(fileContents)""");
        assertEquals("test\n", results.stdout());
    }

    @Test @Order(20)
    public void shellStringInlineWorks() {
        final ExecutionResults results = runText("""
                #(echo $(cat src/test/resources/testdata.txt))""");
        assertEquals("test\n", results.stdout());
    }

    @Test @Order(30)
    public void nestedInlineWorks() {
        final ExecutionResults results = runText("""
                #(export filename=src/test/resources/testdata.txt)
                contents: str = $(cat $(echo $filename))
                print(contents)""");
        assertEquals("test\n", results.stdout());
    }

    @Test @Order(40)
    public void nestedInlineInShellScriptWorks() {
        final ExecutionResults results = runText("""
                #(export filename=src/test/resources/testdata.txt)
                contents: str = #($(cat $(echo $filename)))
                print(contents)""");
        assertEquals("test\n", results.stdout());
    }

    @Test @Order(41)
    public void nestedInlineInShellScriptWithReassignmentWorks() {
        final ExecutionResults results = runText("""
                #(export filename=src/test/resources/testdata.txt)
                contents: str = "Stub contents"
                contents = #($(cat $(echo $filename)))
                print(contents)""");
        assertEquals("test\n", results.stdout());
    }

    @Test @Order(50)
    public void nestedInlineWithCalcWorks() {
        final ExecutionResults results = runText("""
                result: int = $(expr 2 - $(expr 3 + 4)) + 5
                print(result)""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(51)
    public void nestedInlineWithCalcInPrintWorks() {
        final ExecutionResults results = runText("""
                print($(expr 2 - $(expr 3 + 4)) + 5)""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(60)
    public void nestedInlineWithCalcReassignmentWorks() {
        final ExecutionResults results = runText("""
                result: int = 5
                result = $(expr 2 - $(expr 3 + 4)) + 5
                print(result)""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(70)
    public void nestedInlineWithExpressionStatementWorks() {
        final ExecutionResults results = runText("""
                $(expr 2 - $(expr 3 + 4)) + 5""");
        assertEquals(ExecutionResults.COMMAND_NOT_FOUND, results.exitCode());
        assertTrue(results.stdout().contains("0"));
    }

    @Test @Order(80)
    public void nestedInlineWithCalcInAnonymousBlockWorks() {
        final ExecutionResults results = runText("""
                block:
                    result: int = $(expr 2 - $(expr 3 + 4)) + 5
                    print(result)""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(90)
    public void inlineWithErroredCalcInAnonymousBlockThrows() {
        String bashText = """
                block:
                    result: int = $(expr 3 + 4; exit 1)
                    print(result)""";
        assertEquals(1, runText(bashText).exitCode());
    }

    @Test @Order(100)
    public void nestedInlineWithErroredCalcInAnonymousBlockThrows() {
        String bashText = """
                block:
                    result: int = $(expr 2 - $(expr 3 + 4; exit 1)) + 5
                    print(result)""";
        assertEquals(1, runText(bashText).exitCode());
    }

    @Test @Order(110)
    public void nestedInlineWithCalcInReturnPsudoStatementWorks() {
        final ExecutionResults results = runText("""
                function mathIt:int(last:int):
                    return $(expr 2 - $(expr 3 + 4)) + last
                print(mathIt(5))""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(120)
    public void nestedInlineInFunctionCallWorks() {
        final ExecutionResults results = runText("""
                function mathIt:int(first:int):
                    return first + 5
                print(mathIt($(expr 2 - $(expr 3 + 4))))""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(120)
    public void inlineInFunctionCallWithVariableWorks() {
        final ExecutionResults results = runText("""
                function mathIt:int(first:int):
                    return first + 5
                seven: float = 7
                print(mathIt($(expr 2 - $seven)))""");
        assertEquals("0\n", results.stdout());
    }

    @Test @Order(130)
    public void inlineWithVariableWorks() {
        final ExecutionResults results = runText("""
                seven: float = 7
                print($(expr 2 - $seven))""");
        assertEquals("-5\n", results.stdout());
    }
}
