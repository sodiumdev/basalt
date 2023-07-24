package zip.sodium.jbasalt.compiler;

import java.util.function.BiConsumer;

public record ParseRule(BiConsumer<Compiler, Boolean> prefixRule, BiConsumer<Compiler, Boolean> infixRule, Precedence precedence) {
    public static ParseRule NULL = new ParseRule(null, null, Precedence.PREC_NONE);
}
