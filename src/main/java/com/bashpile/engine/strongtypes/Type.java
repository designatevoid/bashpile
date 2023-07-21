package com.bashpile.engine.strongtypes;

import com.bashpile.BashpileParser;
import com.bashpile.exceptions.TypeError;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;

public enum Type {
    /** Not Found */
    NOT_FOUND,
    /** Not applicable -- as in for statements */
    NA,
    /** Type could not be determined, matches any */
    UNKNOWN,
    /** Instead of NIL or null we have the empty String or an empty object */
    EMPTY,
    BOOL,
    INT,
    FLOAT,
    /** For when INT or FLOAT cannot be determined. */
    NUMBER,
    STR,
    ARRAY,
    MAP,
    /** A Bash reference */
    REF;

    public static @Nonnull Type valueOf(@Nonnull BashpileParser.TypedIdContext ctx) {
        final boolean hasTypeInfo = ctx.Type() != null && StringUtils.isNotBlank(ctx.Type().getText());
        if (hasTypeInfo) {
            return valueOf(ctx.Type().getText().toUpperCase());
        }
        throw new TypeError("No type info for " + ctx.Id(), ctx.start.getLine());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static Type parseNumberString(String text) {
        Type type;
        try {
            Integer.parseInt(text);
            type = Type.INT;
        } catch (NumberFormatException ignored) {
            Float.parseFloat(text);
            type = Type.FLOAT;
        }
        return type;
    }

    public boolean isNotFound() {
        return this.equals(NOT_FOUND);
    }

    public boolean isStr() {
        return this.equals(STR);
    }

    // TODO take unknown out of there
    public boolean isNumeric() {
        return this.equals(UNKNOWN) || this.equals(NUMBER) || this.equals(INT) || this.equals(FLOAT);
    }

    public boolean coercesTo(@Nonnull final Type other) {
        // the types match if they are equal
        return this.equals(other)
                // unknown coerces to everything
                || this.equals(Type.UNKNOWN) || other.equals(Type.UNKNOWN)
                // an INT coerces to a FLOAT
                || (this.equals(Type.INT) && other.equals(Type.FLOAT))
                // a NUMBER coerces to an INT or a FLOAT
                || (this.equals(Type.NUMBER) && other.isNumeric())
                // an INT or a FLOAT coerces to a NUMBER
                || (this.isNumeric() && other.equals(Type.NUMBER));
    }
}
