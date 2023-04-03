package xyz.sodiumdev.jbasalt;

import xyz.sodiumdev.jbasalt.token.Token;

public class Parser {
    private Token current;
    private Token previous;

    private boolean hadError;
    private boolean panicMode;

    public boolean hadError() {
        return hadError;
    }

    public Token getCurrent() {
        return current;
    }

    public Token getPrevious() {
        return previous;
    }

    public boolean isPanicMode() {
        return panicMode;
    }

    public void setCurrent(Token current) {
        this.current = current;
    }

    public void setHadError(boolean hadError) {
        this.hadError = hadError;
    }

    public void setPanicMode(boolean panicMode) {
        this.panicMode = panicMode;
    }

    public void setPrevious(Token previous) {
        this.previous = previous;
    }
}
