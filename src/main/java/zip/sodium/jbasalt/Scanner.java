package zip.sodium.jbasalt;

import org.objectweb.asm.Type;
import zip.sodium.jbasalt.compiler.StackTypes;
import zip.sodium.jbasalt.token.Token;
import zip.sodium.jbasalt.token.TokenType;

import java.util.HashMap;
import java.util.Map;

public class Scanner {
    private static final Map<String, TokenType> keywords;
    private boolean debug = false;

    static {
        keywords = new HashMap<>();
        keywords.put("and",    TokenType.TOKEN_AND);
        keywords.put("class",  TokenType.TOKEN_CLASS);
        keywords.put("else",   TokenType.TOKEN_ELSE);
        keywords.put("false",  TokenType.TOKEN_FALSE);
        keywords.put("for",    TokenType.TOKEN_FOR);
        keywords.put("fn",     TokenType.TOKEN_FN);
        keywords.put("if",     TokenType.TOKEN_IF);
        keywords.put("null",   TokenType.TOKEN_NULL);
        keywords.put("or",     TokenType.TOKEN_OR);
        keywords.put("return", TokenType.TOKEN_RETURN);
        keywords.put("true",   TokenType.TOKEN_TRUE);
        keywords.put("let",    TokenType.TOKEN_LET);
        keywords.put("const",  TokenType.TOKEN_CONST);
        keywords.put("while",  TokenType.TOKEN_WHILE);
        keywords.put("import", TokenType.TOKEN_IMPORT);
        keywords.put("public", TokenType.TOKEN_PUBLIC);
        keywords.put("static", TokenType.TOKEN_STATIC);
        keywords.put("final",  TokenType.TOKEN_FINAL);
        keywords.put("private",TokenType.TOKEN_PRIVATE);
        keywords.put("magic",  TokenType.TOKEN_MAGIC);
    }

    public Scanner() {}

    public Scanner(boolean debug) {
        this.debug = debug;
    }

    public String source;

    private int start = 0;
    private int current = 0;
    private int line = 1;

    public Scanner copy() {
        Scanner scanner = new Scanner();
        scanner.current = current;
        scanner.start = start;
        scanner.line = line;
        scanner.source = source;

        return scanner;
    }

    public Token scanToken() {
        skipWhitespace();

        this.start = this.current;

        if (isAtEnd()) 
            return makeToken(TokenType.TOKEN_EOF);

        char c = advance();

        Token result = switch (c) {
            case '(' -> makeToken(TokenType.TOKEN_LEFT_PAREN);
            case ')' -> makeToken(TokenType.TOKEN_RIGHT_PAREN);
            case '{' -> makeToken(TokenType.TOKEN_LEFT_BRACE);
            case '}' -> makeToken(TokenType.TOKEN_RIGHT_BRACE);
            case '[' -> makeToken(TokenType.TOKEN_LEFT_BRACK);
            case ']' -> makeToken(TokenType.TOKEN_RIGHT_BRACK);
            case ',' -> makeToken(TokenType.TOKEN_COMMA);
            case '.' -> makeToken(TokenType.TOKEN_DOT);
            case '-' -> makeToken(match('=') ? TokenType.TOKEN_MINUS_EQUAL : TokenType.TOKEN_MINUS);
            case '+' -> makeToken(match('=') ? TokenType.TOKEN_PLUS_EQUAL : TokenType.TOKEN_PLUS);
            case ';' -> makeToken(TokenType.TOKEN_SEMICOLON);
            case ':' -> makeToken(TokenType.TOKEN_COLON);
            case '*' -> makeToken(match('=') ? TokenType.TOKEN_STAR_EQUAL : TokenType.TOKEN_STAR);
            case '/' -> makeToken(match('=') ? TokenType.TOKEN_SLASH_EQUAL : TokenType.TOKEN_SLASH);
            case '@' -> makeToken(TokenType.TOKEN_AT);
            case '?' -> makeToken(TokenType.TOKEN_QMARK);

            case '!' -> makeToken(match('=') ? TokenType.TOKEN_BANG_EQUAL : TokenType.TOKEN_BANG);
            case '=' -> makeToken(match('=') ? TokenType.TOKEN_EQUAL_EQUAL : TokenType.TOKEN_EQUAL);
            case '<' -> makeToken(match('=') ? TokenType.TOKEN_GREATER_EQUAL : TokenType.TOKEN_GREATER);
            case '>' -> makeToken(match('=') ? TokenType.TOKEN_LESS_EQUAL : TokenType.TOKEN_LESS);

            case '"' -> string();

            default -> {
                if (isDigit(c))
                    yield number();
                else if (isAlpha(c))
                    yield identifier();
                else
                    yield error("Unexpected character.");
            }
        };

        if (debug)
            System.out.printf("Token \"%s\" scanned\n", result.content());
        
        return result;
    }

    private void skipWhitespace() {
        for (;;) {
            char c = peek();
            switch (c) {
                case ' ', '\r', '\t' -> advance();
                case '\n' -> {
                    this.line++;
                    advance();
                }

                case '/' -> {
                    if (peekNext() != '/')
                        return;

                    while (peek() != '\n' && !isAtEnd()) advance();
                }

                default -> {
                    return;
                }
            }
        }
    }

    private Token identifier() {
        while (isAlphaNumeric(peek())) advance();

        TokenType type = keywords.get(source.substring(start, current));
        if (type == null) type = TokenType.TOKEN_IDENTIFIER;

        return makeToken(type);
    }

    private Token number() {
        while (isDigit(peek())) advance();
        Type numberType = StackTypes.INT;

        boolean fractional = (peek() == '.' && isDigit(peekNext()));

        // Look for a fractional part.
        if (fractional) {
            // Consume the "."
            advance();

            while (isDigit(peek())) advance();

            numberType = StackTypes.DOUBLE;
        }

        int offset = 0;
        if (peek() == 'l' && !fractional) {
            numberType = StackTypes.LONG;
            advance();
            offset = 1;
        } else if (peek() == 'f') {
            numberType = StackTypes.FLOAT;
            advance();
            offset = 1;
        } else if (peek() == 'd') {
            numberType = StackTypes.DOUBLE;
            advance();
            offset = 1;
        }

        return new Token.NumberToken(TokenType.TOKEN_NUMBER, source.substring(start, current - offset), line, numberType);
    }

    private Token string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') line++;
            advance();
        }

        if (isAtEnd()) {
            return error("Unterminated string.");
        }

        // The closing quote
        advance();

        return makeToken(TokenType.TOKEN_STRING);
    }

    private boolean match(char expected) {
        if (isAtEnd()) return false;
        if (source.charAt(current) != expected) return false;

        current++;
        return true;
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return source.charAt(current);
    }

    private char peekNext() {
        if (current + 1 >= source.length()) return '\0';
        return source.charAt(current + 1);
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private Token error(String message) {
        return new Token(TokenType.TOKEN_ERROR, message, line);
    }

    private Token makeToken(TokenType type) {
        return new Token(type, source.substring(start, current), line);
    }
}
