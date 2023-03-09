package com.bashpile;

import org.antlr.v4.runtime.tree.ParseTree;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Antlr4 calls these methods and we create an AST */
public class BashpileVisitor extends BashpileParserBaseVisitor<AstNode> implements Closeable {
    private final Map<String, Integer> memory = new HashMap<>();

    private final PrintStream output;

    public BashpileVisitor(OutputStream os) {
        output = new PrintStream(os);
    }

    // visitors

    @Override
    public AstNode visit(ParseTree tree) {
        AstNode ret = super.visit(tree);
        output.flush();
        return ret;
    }

    @Override
    public AstNode visitAssign(BashpileParser.AssignContext ctx) {
        String id = ctx.ID().getText();
        int value = visit(ctx.expr()).value;
        memory.put(id, value);
        return new AstNode(value);
    }

    @Override
    public AstNode visitPrintExpr(BashpileParser.PrintExprContext ctx) {
        Integer value = visit(ctx.expr()).value;
        print(value.toString());
        return new AstNode(value);
    }

    @Override
    public AstNode visitInt(BashpileParser.IntContext ctx) {
        return new AstNode(Integer.valueOf(ctx.INT().getText()));
    }

    @Override
    public AstNode visitId(BashpileParser.IdContext ctx) {
        String id = ctx.ID().getText();
        if (memory.containsKey(id)) {
            return new AstNode(memory.get(id));
        }
        throw new RuntimeException("ID %s not found".formatted(id));
    }

    @Override
    public AstNode visitMulDiv(BashpileParser.MulDivContext ctx) {
        int left = visit(ctx.expr(0)).value;
        int right = visit(ctx.expr(1)).value;
        if (ctx.op.getType() == BashpileParser.MUL) {
            return new AstNode(left * right);
        } // else divide
        return new AstNode(left / right);
    }

    @Override
    public AstNode visitAddSub(BashpileParser.AddSubContext ctx) {
        int left = visit(ctx.expr(0)).value;
        int right = visit(ctx.expr(1)).value;
        if (ctx.op.getType() == BashpileParser.ADD) {
            return new AstNode(left + right);
        } // else subtract
        return new AstNode(left - right);
    }

    @Override
    public AstNode visitParens(BashpileParser.ParensContext ctx) {
        return visit(ctx.expr());
    }

    // helpers

    protected void print(String line) {
        output.println(line);
        System.out.println(line);
    }

    @Override
    public void close() throws IOException {
        output.close();
    }
}
