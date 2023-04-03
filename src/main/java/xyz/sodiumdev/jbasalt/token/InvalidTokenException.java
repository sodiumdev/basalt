package xyz.sodiumdev.jbasalt.token;

public class InvalidTokenException extends RuntimeException {
    private final TokenType tokenType;

    public InvalidTokenException() {
        this.tokenType = null;
    }

    public InvalidTokenException(String s) {
        super(s);
        this.tokenType = null;
    }

    public InvalidTokenException(String s, TokenType tokenType) {
        super(s);
        this.tokenType = tokenType;
    }
}
