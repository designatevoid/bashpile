parser grammar BashpileParser;
options { tokenVocab = BashpileLexer; }

prog: stat+;

stat: expr NL       # printExpr
    | ID EQ expr NL # assign
    | NL            # blank
    ;

expr: expr (MUL|DIV|ADD|SUB) expr # Calc
    | INT                         # int
    | ID                          # id
    | OPAREN expr CPAREN          # parens
    ;