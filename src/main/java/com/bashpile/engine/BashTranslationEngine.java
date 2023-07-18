package com.bashpile.engine;

import com.bashpile.Asserts;
import com.bashpile.BashpileParser;
import com.bashpile.engine.strongtypes.FunctionTypeInfo;
import com.bashpile.engine.strongtypes.Type;
import com.bashpile.engine.strongtypes.TypeMetadata;
import com.bashpile.engine.strongtypes.TypeStack;
import com.bashpile.exceptions.TypeError;
import com.bashpile.exceptions.UserError;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bashpile.AntlrUtils.*;
import static com.bashpile.Asserts.*;
import static com.bashpile.engine.LevelCounter.*;
import static com.bashpile.engine.Translation.toStringTranslation;
import static com.bashpile.engine.Translation.toTranslation;
import static com.bashpile.engine.strongtypes.TypeMetadata.INLINE;
import static com.bashpile.engine.strongtypes.TypeMetadata.NORMAL;
import static com.google.common.collect.Iterables.getLast;

/**
 * Translates to Bash5 with four spaces as a tab.
 */
public class BashTranslationEngine implements TranslationEngine {

    // static variables

    public static final String TAB = "    ";

    // static methods

    private static @Nonnull String getLocalText() {
        return getLocalText(false);
    }

    private static @Nonnull String getLocalText(final boolean reassignment) {
        final boolean indented = LevelCounter.in(BLOCK);
        if (indented && !reassignment) {
            return "local ";
        } else if (indented) { // and a reassignment
            return "";
        } else { // not indented
            return "export ";
        }
    }

    private static @Nonnull String getHoisted() {
        return LevelCounter.in(FORWARD_DECL) ? " (hoisted)" : "";
    }

    // instance variables

    private final TypeStack typeStack = new TypeStack();

    private BashpileVisitor visitor;

    /** We need to name the anonymous blocks, anon0, anon1, anon2, etc.  We keep that counter here. */
    private int anonBlockCounter = 0;

    /** Used to ensure variable names are unique */
    private int subshellWorkaroundCounter = 0;

    /** All the functions hoisted so far, so we can ensure we don't emit them twice */
    private final Set<String> foundForwardDeclarations = new HashSet<>();

    // instance methods

    @Override
    public void setVisitor(@Nonnull final BashpileVisitor visitor) {
        this.visitor = visitor;
    }

    // header translations

    /**
     * Set Bash options for scripts.
     * <br>
     * <ul><li>set -e: exit on failed command</li>
     * <li>set -u: exit on undefined variable --
     *  we don't need this for Bashpile generated code but there may be `source`d code.</li>
     * <li>set -o pipefail: exit immediately when a command in a pipeline fails.</li>
     * <li>set -o posix: Posix mode -- we need this so that all subshells inherit the -eu options.</li></ul>
     *
     * @see <a href=https://unix.stackexchange.com/a/23099">Q & A </a>
     * @return The Strict Mode header
     */
    @Override
    public @Nonnull Translation strictModeHeader() {
        String strictMode = """
                set -euo pipefail -o posix
                export IFS=$'\\n\\t'
                """;
        return toStringTranslation("# strict mode header\n%s".formatted(strictMode));
    }

    @Override
    public @Nonnull Translation importsHeaders() {
        // stub
        String text = "";
        return toStringTranslation(text);
    }

    // statement translations

    @Override
    public Translation expressionStatement(@Nonnull final BashpileParser.ExpressionStatementContext ctx) {
        final Translation expr = visitor.visit(ctx.expression());
        final String textBlock = """
                # expression statement, Bashpile line %d
                %s%s
                """.formatted(ctx.start.getLine(), expr.preamble(), expr.body());
        return new Translation(
                textBlock, expr.type(), expr.typeMetadata());
    }

    @Override
    public @Nonnull Translation assignmentStatement(@Nonnull final BashpileParser.AssignmentStatementContext ctx) {
        // add this variable to the type map
        final String variableName = ctx.typedId().Id().getText();
        final Type type = Type.valueOf(ctx.typedId().Type().getText().toUpperCase());
        typeStack.putVariableType(variableName, type, ctx.start.getLine());

        // visit the right hand expression
        final boolean exprExists = ctx.expression() != null;
        final Translation exprTranslation = exprExists ? visitor.visit(ctx.expression()) : Translation.EMPTY_STRING;
        Asserts.assertTypesMatch(type, exprTranslation.type(), ctx.typedId().Id().getText(), ctx.start.getLine());

        // create translation
        final String lineComment = "# assign statement, Bashpile line %d".formatted(ctx.start.getLine());
        final String unnestedText = exprTranslation.preamble();
        final String value = exprExists ? "=" + exprTranslation.body() : "";
        /*
         * Translation will be something like:
         * `# assign statement, Bashpile line 1
         * local variableName=value`
         * or just the comment line and
         * `local variableName`
         */
        return new Translation(
                "%s\n%s%s%s%s\n".formatted(lineComment, unnestedText, getLocalText(), variableName, value),
                Type.NA, NORMAL);
    }

    // TODO handle preambles
    @Override
    public @Nonnull Translation reassignmentStatement(@Nonnull final BashpileParser.ReassignmentStatementContext ctx) {
        // get name and type
        final String variableName = ctx.Id().getText();
        final Type expectedType = typeStack.getVariableType(variableName);
        if (expectedType.isNotFound()) {
            throw new TypeError(variableName + " has not been declared", ctx.start.getLine());
        }

        // get expression and it's type
        final Translation exprTranslation = visitor.visit(ctx.expression());
        final Type actualType = exprTranslation.type();
        Asserts.assertTypesMatch(expectedType, actualType, variableName, ctx.start.getLine());

        // create translation
        final String lineComment = "# reassign statement, Bashpile line %d".formatted(ctx.start.getLine());
        final String value = exprTranslation.body();
        return new Translation(
                "%s\n%s%s=%s\n".formatted(lineComment, getLocalText(true), variableName, value),
                Type.NA, NORMAL);
    }

    @Override
    public @Nonnull Translation printStatement(@Nonnull final BashpileParser.PrintStatementContext ctx) {
        // guard

        final BashpileParser.ArgumentListContext argList = ctx.argumentList();
        if (argList == null) {
            return toStringTranslation("echo\n");
        }

        // body
        try (final LevelCounter ignored = new LevelCounter(PRINT)) {
            final String lineComment = "# print statement, Bashpile line %d".formatted(ctx.start.getLine());
            final String printText = ("%s\n%s\n").formatted(lineComment, argList.expression().stream()
                    .map(visitor::visit)
                    .map(tr -> {
                        if (tr.isNotSubshell()) {
                            return "%secho %s".formatted(tr.preamble(), tr.body());
                        } // else tr is a subshell
                        final Pair<String, String> subshell = subshellWorkaroundTextBlock(tr.body());
                        final String trBodyWorkaround = subshell.getValue();
                        return """
                                %s%secho "${%s}\"""".formatted(tr.preamble(), trBodyWorkaround, subshell.getKey());
                    })
                    .collect(Collectors.joining(" ")));
            return toStringTranslation(printText);
        }
    }

    /**
     * Subshell errored exit codes are ignored in Bash despite all configurations.
     * This workaround explicitly propagates errored exit codes.
     * @param translatedText We run this subshell Bash text and check for errors.
     * @return The variable name assigned to the value of running <code>translatedText</code> and
     * the workaround in three lines, ending with newline.
     */
    private Pair<String, String> subshellWorkaroundTextBlock(@Nonnull final String translatedText) {
        final String subshellReturn = "__bp_subshellReturn%d".formatted(subshellWorkaroundCounter);
        final String exitCodeName = "__bp_exitCode%d".formatted(subshellWorkaroundCounter++);
        // assign subshellReturn, assign exitCodeName, exit with exitCodeName on error (if not equal to 0)
        final String workaroundText = """
                export %s=%s
                %s=$?
                if [ "$%s" -ne 0 ]; then exit "$%s"; fi
                """.formatted(subshellReturn, translatedText, exitCodeName, exitCodeName, exitCodeName);
        return Pair.of(subshellReturn, workaroundText);
    }

    @Override
    public @Nonnull Translation functionForwardDeclarationStatement(
            @Nonnull final BashpileParser.FunctionForwardDeclarationStatementContext ctx) {
        final ParserRuleContext functionDeclCtx = getFunctionDeclCtx(visitor, ctx);
        try (LevelCounter ignored = new LevelCounter(FORWARD_DECL)) {
            final String lineComment =
                    "# function forward declaration, Bashpile line %d".formatted(ctx.start.getLine());
            final String hoistedFunctionText = visitor.visit(functionDeclCtx).body();
            assertTextBlock(hoistedFunctionText);
            final String ret = "%s\n%s".formatted(lineComment, hoistedFunctionText);
            return toStringTranslation(ret);
        } finally {
            foundForwardDeclarations.add(ctx.typedId().Id().getText());
        }
    }

    @Override
    public @Nonnull Translation functionDeclarationStatement(
            @Nonnull final BashpileParser.FunctionDeclarationStatementContext ctx) {
        // avoid translating twice if was part of a forward declaration
        final String functionName = ctx.typedId().Id().getText();
        if (foundForwardDeclarations.contains(functionName)) {
            return Translation.EMPTY_STRING;
        }

        // check for double declaration
        if (typeStack.containsFunction(functionName)) {
            throw new UserError(
                    functionName + " was declared twice (function overloading is not supported)", ctx.start.getLine());
        }

        // regular processing -- no forward declaration

        // register function param types and return type
        final List<Type> typeList = ctx.paramaters().typedId()
                .stream().map(Type::valueOf).collect(Collectors.toList());
        final Type retType = Type.valueOf(ctx.typedId().Type().getText().toUpperCase());
        typeStack.putFunctionTypes(functionName, new FunctionTypeInfo(typeList, retType));

        // create block
        String block;
        try (LevelCounter ignored = new LevelCounter(BLOCK)) {
            typeStack.push();

            // register local variable types
            ctx.paramaters().typedId().forEach(
                    x -> typeStack.putVariableType(
                            x.Id().getText(), Type.valueOf(x.Type().getText().toUpperCase()), ctx.start.getLine()));

            // handles nested blocks
            final AtomicInteger i = new AtomicInteger(1);
            // the empty string or ...
            final String namedParams = ctx.paramaters().typedId().isEmpty() ? "" :
                    // local var1=$1; local var2=$2; etc
                    "%s%s\n".formatted(TAB, ctx.paramaters().typedId().stream()
                                    .map(BashpileParser.TypedIdContext::Id)
                                    .map(visitor::visit)
                                    .map(Translation::body)
                                    .map(str -> "local %s=$%s;".formatted(str, i.getAndIncrement()))
                                    .collect(Collectors.joining(" ")));
            assertTextLine(namedParams);
            final Stream<ParserRuleContext> contextStream =
                    addContexts(ctx.functionBlock().statement(), ctx.functionBlock().returnPsudoStatement());
            final String blockText = visitBlock(visitor, contextStream).body();
            assertTextBlock(blockText);
            final String functionComment = "# function declaration, Bashpile line %d%s"
                    .formatted(ctx.start.getLine(), getHoisted());
            block = "%s\n%s () {\n%s%s}\n"
                    .formatted(functionComment, functionName, namedParams, blockText);
        } finally {
            typeStack.pop();
        }
        assertTextBlock(block);
        return toStringTranslation(block);
    }

    // TODO handle preambles
    @Override
    public @Nonnull Translation anonymousBlockStatement(
            @Nonnull final BashpileParser.AnonymousBlockStatementContext ctx) {
        String block;
        try (LevelCounter ignored = new LevelCounter(BLOCK)) {
            typeStack.push();
            // behind the scenes we need to name the anonymous function
            final String anonymousFunctionName = "anon" + anonBlockCounter++;
            // map of x to x needed for upcasting to parent type
            final Stream<ParserRuleContext> stmtStream = ctx.statement().stream().map(x -> x);
            final String blockBodyTextBlock = visitBlock(visitor, stmtStream).body();
            assertTextBlock(blockBodyTextBlock);
            final String lineComment = "# anonymous block, Bashpile line %d%s"
                    .formatted(ctx.start.getLine(), getHoisted());
            // define function and then call immediately with no arguments
            block = "%s\n%s () {\n%s}; %s\n"
                    .formatted(lineComment, anonymousFunctionName, blockBodyTextBlock, anonymousFunctionName);
        } finally {
            typeStack.pop();
        }
        return toStringTranslation(block);
    }

    @Override
    public @Nonnull Translation returnPsudoStatement(@Nonnull final BashpileParser.ReturnPsudoStatementContext ctx) {
        final boolean exprExists = ctx.expression() != null;

        // check return matches with function declaration
        final BashpileParser.FunctionDeclarationStatementContext enclosingFunction =
                (BashpileParser.FunctionDeclarationStatementContext) ctx.parent.parent;
        final String functionName = enclosingFunction.typedId().Id().getText();
        final FunctionTypeInfo functionTypes = typeStack.getFunctionTypes(functionName);
        final Translation exprTranslation =
                exprExists ? visitor.visit(ctx.expression()) : Translation.EMPTY_TYPE;
        assertTypesMatch(functionTypes.returnType(), exprTranslation.type(), functionName, ctx.start.getLine());

        if (exprExists) {
            // insert echo right at start of last line

            // exprTranslation.body() does not end in newline and may be multiple lines
            final String exprText = "# return statement, Bashpile line %d%s\n%s%s"
                    .formatted(ctx.start.getLine(), getHoisted(), exprTranslation.preamble(), exprTranslation.body());
            final String[] retLines = exprText.split("\n");
            final int lastLineIndex = retLines.length - 1;
            retLines[lastLineIndex] = "echo " + retLines[lastLineIndex];
            final String retText = String.join("\n", retLines) + "\n";
            return toStringTranslation(retText);
        } // else
        return Translation.EMPTY_STRING;
    }

    // expressions

    @Override
    public Translation typecastExpression(BashpileParser.TypecastExpressionContext ctx) {
        final Translation expression = visitor.visit(ctx.expression());
        return expression.toType(Type.valueOf(ctx.Type().getText().toUpperCase()));
    }

    @Override
    public @Nonnull Translation calculationExpression(@Nonnull final BashpileParser.CalculationExpressionContext ctx) {
        // get the child translations
        List<Translation> childTranslations;
        try (LevelCounter ignored = new LevelCounter(CALC)) {
            childTranslations = ctx.children.stream()
                    .map(visitor::visit)
                    .map(childTranslation -> {
                        if (childTranslation.isNotSubshell()) {
                            return childTranslation;
                        } // else unwind subshell by moving logic to translation's preamble
                        final Pair<String, String> varAndLogic = subshellWorkaroundTextBlock(childTranslation.body());
                        // create our translation as ${varName}
                        // TODO use and create fluent API
                        return new Translation(
                                "${%s}".formatted(varAndLogic.getKey()),
                                Type.NUMBER,
                                NORMAL,
                                childTranslation.preamble() + varAndLogic.getValue());
                    })
                    .collect(Collectors.toList());
        }

        // child translations in the format of 'expr operator expr', so we are only interested in the first and last
        final Translation first = childTranslations.get(0);
        final Translation second = getLast(childTranslations);
        // check for nested calc call
        if (LevelCounter.in(CALC) && areNumberExpressions(first, second)) {
            return toTranslation(childTranslations.stream(), Type.NUMBER, NORMAL);
        // types section
        } else if (areStringExpressions(first, second)) {
            final String op = ctx.op.getText();
            Asserts.assertEquals("+", op, "Only addition is allowed on Strings, but got " + op);
            return toTranslation(Stream.of(first, second), Type.STR, NORMAL);
        } else if (areNumberExpressions(first, second)) {
            final String translationsString = childTranslations.stream()
                    .map(Translation::body).collect(Collectors.joining(" "));
            return toTranslation(childTranslations.stream(), Type.NUMBER, INLINE)
                    .body("$(bc <<< \"%s\")".formatted(translationsString));
        // found no matching types -- error section
        } else if (first.type().equals(Type.NOT_FOUND) || second.type().equals(Type.NOT_FOUND)) {
            throw new UserError("`%s` or `%s` are undefined".formatted(
                    first.body(), second.body()), ctx.start.getLine());
        } else {
            // throw type error for all others
            throw new TypeError("Incompatible types in calc: %s and %s".formatted(
                    first.type(), second.type()), ctx.start.getLine());
        }
    }

    private boolean areStringExpressions(@Nonnull final Translation... translations) {
        // if all strings the stream of not-strings will be empty
        return Stream.of(translations).filter(x -> x.type() != Type.STR).findAny().isEmpty();
    }

    private boolean areNumberExpressions(@Nonnull final Translation... translations) {
        // if all numbers the stream of not-numbers will be empty
        return Stream.of(translations).filter(x -> !x.type().isNumeric()).findAny().isEmpty();
    }

    @Override
    public Translation parenthesisExpression(@Nonnull final BashpileParser.ParenthesisExpressionContext ctx) {
        final Translation expr = visitor.visit(ctx.expression());
        // No parens for strings and no parens for numbers not in a calc (e.g. "(((5)))" becomes "5" eventually)
        final String format = expr.type().isNumeric() && LevelCounter.in(CALC) ? "(%s)" : "%s";
        return new Translation(format.formatted(expr.body()), expr.type(), expr.typeMetadata());
    }

    @Override
    public @Nonnull Translation functionCallExpression(
            @Nonnull final BashpileParser.FunctionCallExpressionContext ctx) {
        final String id = ctx.Id().getText();

        // check arg types

        // get functionName and a stream creator
        final String functionName = ctx.Id().getText();
        final Supplier<Stream<Translation>> argListTranslationStream =
                () -> ctx.argumentList().expression().stream().map(visitor::visit);
        // get the expected and actual types
        final FunctionTypeInfo expectedTypes = typeStack.getFunctionTypes(functionName);
        final List<Type> actualTypes = ctx.argumentList() != null
                ? argListTranslationStream.get().map(Translation::type).collect(Collectors.toList())
                : List.of();
        // assert equals
        Asserts.assertTypesMatch(expectedTypes.parameterTypes(), actualTypes, functionName, ctx.start.getLine());

        // get arguments

        final boolean hasArgs = ctx.argumentList() != null;
        // empty list or ' arg1Text arg2Text etc.'
        // TODO handle case where arguments have unnested ext blocks (e.g. nested command substitutions)
        final String args = hasArgs
                ? " " + argListTranslationStream.get().map(Translation::body).collect(Collectors.joining(" "))
                : "";

        // lookup return type of this function
        final Type retType = typeStack.getFunctionTypes(id).returnType();

        // suppress output if we are a top-level statement
        // this covers the case of calling a str function without using the string
        final boolean topLevelStatement = !in(CALC) && !in(PRINT);
        if (topLevelStatement) {
            return toStringTranslation(id + args + " >/dev/null");
        } // else return a command substitution
        final String text = "$(%s%s)".formatted(id, args);
        return new Translation(text, retType, INLINE);
    }

    @Override
    public Translation idExpression(BashpileParser.IdExpressionContext ctx) {
        final String variableName = ctx.Id().getText();
        final Type type = typeStack.getVariableType(variableName);
        return new Translation("${%s}".formatted(ctx.getText()), type, NORMAL);
    }

    // expression helpers

    @Override
    public Translation shellString(@Nonnull final BashpileParser.ShellStringContext ctx) {
        final Stream<Translation> translationStream = ctx.shellStringContents().stream().map(visitor::visit);
        Translation shellStringTranslation =
                toTranslation(translationStream, Type.STR, TypeMetadata.COMMAND).unescapeText();
        if (LevelCounter.inCommandSubstitution()) {
            final Pair<String, String> workaround =
                    subshellWorkaroundTextBlock("$(%s)".formatted(shellStringTranslation.body()));
            shellStringTranslation = new Translation(
                    "${%s}".formatted(workaround.getKey()),
                    shellStringTranslation.type(),
                    shellStringTranslation.typeMetadata(),
                    workaround.getValue() + shellStringTranslation.preamble());
        }
        return shellStringTranslation;
    }

    /**
     * Unnests command substitutions as needed.
     *
     * @see Translation
     * @see #printStatement(BashpileParser.PrintStatementContext)
     * @param ctx the context.
     * @return A translation, possibly with the {@link Translation#preamble()} set.
     */
    @Override
    public Translation inline(BashpileParser.InlineContext ctx) {
        final boolean nested = LevelCounter.inCommandSubstitution();
        try (LevelCounter ignored = new LevelCounter(LevelCounter.INLINE)) {
            final Stream<Translation> children = ctx.children.stream().map(visitor::visit);
            final Translation joined = toTranslation(children, Type.STR, NORMAL).unescapeText();
            if (!nested) {
                return joined;
            } // else nested
            final Pair<String, String> subshell = subshellWorkaroundTextBlock(joined.body());
            final String body = "${%s}".formatted(subshell.getKey());
            // TODO remove typemetadata in favor of using LevelCounter
            // TODO detect type automatically
            return new Translation(body, Type.STR, INLINE, joined.preamble() + subshell.getValue());
        }
    }
}
