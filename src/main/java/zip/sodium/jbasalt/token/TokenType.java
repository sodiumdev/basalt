package zip.sodium.jbasalt.token;

import org.objectweb.asm.Opcodes;
import zip.sodium.jbasalt.compiler.Compiler;

public enum TokenType {
    // Single-character tokens.
    TOKEN_LEFT_PAREN, TOKEN_RIGHT_PAREN,
    TOKEN_LEFT_BRACE, TOKEN_RIGHT_BRACE,
    TOKEN_LEFT_BRACK, TOKEN_RIGHT_BRACK,
    TOKEN_COMMA, TOKEN_DOT, TOKEN_MINUS, TOKEN_PLUS, TOKEN_COLON,
    TOKEN_SEMICOLON, TOKEN_SLASH, TOKEN_STAR, TOKEN_AT, TOKEN_QMARK,
    // One or two character tokens.
    TOKEN_BANG, TOKEN_BANG_EQUAL,
    TOKEN_PLUS_EQUAL, TOKEN_MINUS_EQUAL, TOKEN_STAR_EQUAL, TOKEN_SLASH_EQUAL,
    TOKEN_EQUAL, TOKEN_EQUAL_EQUAL,
    TOKEN_GREATER, TOKEN_GREATER_EQUAL,
    TOKEN_LESS, TOKEN_LESS_EQUAL,
    // Literals.
    TOKEN_IDENTIFIER, TOKEN_STRING, TOKEN_NUMBER,
    // Keywords.
    TOKEN_AND, TOKEN_CLASS, TOKEN_ELSE, TOKEN_FALSE,
    TOKEN_FOR, TOKEN_FN, TOKEN_IF, TOKEN_NULL, TOKEN_OR,
    TOKEN_RETURN,
    TOKEN_TRUE, TOKEN_LET, TOKEN_CONST, TOKEN_WHILE,
    TOKEN_IMPORT,

    TOKEN_PUBLIC(Opcodes.ACC_PUBLIC), TOKEN_STATIC(Opcodes.ACC_STATIC),
    TOKEN_FINAL(Opcodes.ACC_FINAL), TOKEN_PRIVATE(Opcodes.ACC_PRIVATE),
    TOKEN_MAGIC,

    TOKEN_ERROR, TOKEN_EOF;

    public final int modifier;

    TokenType() {
        modifier = 0;
    }

    TokenType(int i) {
        modifier = i;
    }

    public InvalidTokenException makeInvalidTokenException(Compiler compiler, String text) {
        return new InvalidTokenException(
                text.replace("%s", this.name()) +
                ": " + compiler.typeStack, this);
    }
}
