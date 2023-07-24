package zip.sodium.jbasalt.token;

import org.objectweb.asm.Type;

public class Token {
    private final TokenType type;
    private final String content;
    private final int line;

    public Token(TokenType type, String content, int line) {
        this.type = type;
        this.content = content;
        this.line = line;
    }

    public static class NumberToken extends Token {
        private final Type numberType;

        public NumberToken(TokenType type, String content, int line, Type numberType) {
            super(type, content, line);

            this.numberType = numberType;
        }

        public Type numberType() {
            return numberType;
        }
    }

    public int line() {
        return line;
    }

    public String content() {
        return content;
    }

    public TokenType type() {
        return type;
    }
}
