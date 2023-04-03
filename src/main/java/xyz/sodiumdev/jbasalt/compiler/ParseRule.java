package xyz.sodiumdev.jbasalt.compiler;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public record ParseRule(BiConsumer<Compiler, Boolean> prefixRule, BiConsumer<Compiler, Boolean> infixRule, Precedence precedence) {
    public static ParseRule NULL = new ParseRule(null, null, Precedence.PREC_NONE);
}
