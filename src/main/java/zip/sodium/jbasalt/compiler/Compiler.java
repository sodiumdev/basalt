 package zip.sodium.jbasalt.compiler;

import basalt.lang.Inline;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.*;
import zip.sodium.jbasalt.Parser;
import zip.sodium.jbasalt.Scanner;
import zip.sodium.jbasalt.token.Token;
import zip.sodium.jbasalt.token.TokenType;
import zip.sodium.jbasalt.type.ClassType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;


public class Compiler {
    private static final String MAGIC_PREFIX = "magic^";
    private static final AnnotationNode INLINE_ANNOTATION = new AnnotationNode("Lbasalt/lang/Inline;");

    private CompilerType type;
    public Parser parser;
    public Scanner scanner;

    public static final String PRIMITIVE_DESCRIPTORS = "VZCBSIFJD";
    public static final List<Class<?>> PRIMITIVE_TYPES =
            List.of(void.class, boolean.class, char.class,
                    byte.class, short.class, int.class,
                    float.class, long.class, double.class);

    private final Map<String, String> classNameReplacements = new HashMap<>();
    private final Map<String, String> methodNameReplacements = new HashMap<>();

    private static final Set<AnnotationNode> annotationsForNextElement = new HashSet<>();
    private static final Set<TokenType> modifiersForNextElement = new HashSet<>();

    public static Map<String, ClassNode> classes = new HashMap<>();

    public String currentClass;
    public String parentClass;

    public int currentMethod;

    public static final Map<TokenType, ParseRule> rules = new HashMap<>();

    public Map<String, Local> locals = new LinkedHashMap<>();
    public DelayedInstruction delayedInstruction;
    public int maxLocals = 0;

    private final EphemeralRunner runner;

    public final String fileName;
    public final String filePackage;

    public record Local(Type type, int index, String signature, LabelNode start) { }
    public record Pair<K, V>(K k, V v) {}

    public record InlineMethod(String owner, String name, String methodDescriptor) {}
    public record InlineField(String owner, String name, Type type) {}

    private final List<InlineMethod> inlineMethods = new ArrayList<>();
    private final List<InlineField> inlineFields = new ArrayList<>();

    /**
     * - {@link #callStack}
     */
    public record MethodCall(int opcode, String owner, String name) {}

    /**
     * - {@link #clearStack()}
     * - {@link #peekLastStack()}
     * - {@link #notifyPushStack(Type)}
     * - {@link #notifyPopStack()}
     */
    public final Stack<Type> typeStack = new Stack<>();

    /**
     * - {@link #notifyPushCallStack(MethodCall)}
     * - {@link #notifyPopCallStack()}
     *
     * - {@link MethodCall}
     */
    public final Stack<MethodCall> callStack = new Stack<>();

    static {
        rules.put(TokenType.TOKEN_RIGHT_PAREN, ParseRule.NULL);
        rules.put(TokenType.TOKEN_LEFT_BRACE, ParseRule.NULL);
        rules.put(TokenType.TOKEN_RIGHT_BRACE, ParseRule.NULL);
        rules.put(TokenType.TOKEN_LEFT_BRACK, new ParseRule(Compiler::array, Compiler::subscript, Precedence.PREC_CALL));
        rules.put(TokenType.TOKEN_RIGHT_BRACK, ParseRule.NULL);
        rules.put(TokenType.TOKEN_COMMA, ParseRule.NULL);
        rules.put(TokenType.TOKEN_FN, ParseRule.NULL);
        rules.put(TokenType.TOKEN_SEMICOLON, ParseRule.NULL);
        rules.put(TokenType.TOKEN_COLON, new ParseRule(null, Compiler::specialDot, Precedence.PREC_CALL));
        rules.put(TokenType.TOKEN_LET, ParseRule.NULL);
        rules.put(TokenType.TOKEN_CONST, ParseRule.NULL);
        rules.put(TokenType.TOKEN_ERROR, ParseRule.NULL);
        rules.put(TokenType.TOKEN_EOF, ParseRule.NULL);
        rules.put(TokenType.TOKEN_RETURN, ParseRule.NULL);
        rules.put(TokenType.TOKEN_CLASS, ParseRule.NULL);
        rules.put(TokenType.TOKEN_WHILE, ParseRule.NULL);
        rules.put(TokenType.TOKEN_IF, ParseRule.NULL);
        rules.put(TokenType.TOKEN_ELSE, ParseRule.NULL);

        rules.put(TokenType.TOKEN_STATIC, ParseRule.NULL);
        rules.put(TokenType.TOKEN_PRIVATE, ParseRule.NULL);
        rules.put(TokenType.TOKEN_FINAL, ParseRule.NULL);
        rules.put(TokenType.TOKEN_MAGIC, ParseRule.NULL);
        rules.put(TokenType.TOKEN_INLINE, ParseRule.NULL);

        rules.put(TokenType.TOKEN_QDOT, new ParseRule(null, Compiler::qDot, Precedence.PREC_CALL));

        rules.put(TokenType.TOKEN_QMARK, new ParseRule(null, Compiler::ternary, Precedence.PREC_CALL));

        rules.put(TokenType.TOKEN_LEFT_PAREN, new ParseRule(Compiler::grouping, Compiler::call, Precedence.PREC_CALL));
        rules.put(TokenType.TOKEN_DOT, new ParseRule(null, Compiler::dot, Precedence.PREC_CALL));
        rules.put(TokenType.TOKEN_MINUS, new ParseRule(Compiler::unary, Compiler::binary, Precedence.PREC_TERM));
        rules.put(TokenType.TOKEN_PLUS, new ParseRule(null, Compiler::binary, Precedence.PREC_TERM));
        rules.put(TokenType.TOKEN_SLASH, new ParseRule(null, Compiler::binary, Precedence.PREC_FACTOR));
        rules.put(TokenType.TOKEN_STAR, new ParseRule(null, Compiler::binary, Precedence.PREC_FACTOR));

        rules.put(TokenType.TOKEN_BANG, new ParseRule(Compiler::unary, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_BANG_EQUAL, new ParseRule(null, Compiler::binary, Precedence.PREC_EQUALITY));
        rules.put(TokenType.TOKEN_EQUAL_EQUAL, new ParseRule(null, Compiler::binary, Precedence.PREC_EQUALITY));
        rules.put(TokenType.TOKEN_GREATER, new ParseRule(null, Compiler::binary, Precedence.PREC_COMPARISON));
        rules.put(TokenType.TOKEN_GREATER_EQUAL, new ParseRule(null, Compiler::binary, Precedence.PREC_COMPARISON));
        rules.put(TokenType.TOKEN_LESS, new ParseRule(Compiler::cast, Compiler::binary, Precedence.PREC_COMPARISON));
        rules.put(TokenType.TOKEN_LESS_EQUAL, new ParseRule(null, Compiler::binary, Precedence.PREC_COMPARISON));

        rules.put(TokenType.TOKEN_IDENTIFIER, new ParseRule(Compiler::variable, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_NUMBER, new ParseRule(Compiler::number, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_STRING, new ParseRule(Compiler::string, null, Precedence.PREC_NONE));

        rules.put(TokenType.TOKEN_AND, new ParseRule(null, Compiler::and, Precedence.PREC_AND));
        rules.put(TokenType.TOKEN_OR, new ParseRule(null, Compiler::or, Precedence.PREC_OR));

        rules.put(TokenType.TOKEN_FALSE, new ParseRule(Compiler::literal, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_TRUE, new ParseRule(Compiler::literal, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_NULL, new ParseRule(Compiler::literal, null, Precedence.PREC_NONE));

        rules.put(TokenType.TOKEN_IMPORT, new ParseRule(Compiler::import_, null, Precedence.PREC_NONE));

        rules.put(TokenType.TOKEN_AT, new ParseRule(Compiler::at, null, Precedence.PREC_NONE));
    }

    private enum CompilerType {
        TOP,
        CLASS, METHOD,
        NESTED_CLASS, NESTED_METHOD,
    }

    public Compiler(String filePackage, String fileName, EphemeralRunner runner) {
        type = CompilerType.TOP;
        parser = new Parser();
        scanner = new Scanner();

        this.runner = runner;
        this.fileName = fileName;
        this.filePackage = filePackage;

        runner.setCompiler(this);
    }

    private Compiler(CompilerType type, Compiler parent) {
        this.type = type;
        parser = parent.parser;
        scanner = parent.scanner;

        if (type != CompilerType.NESTED_CLASS)
            currentClass = parent.currentClass;
        else parentClass = parent.currentClass;

        runner = parent.runner;
        fileName = parent.fileName;
        filePackage = parent.filePackage;

        classNameReplacements.putAll(parent.classNameReplacements);
        methodNameReplacements.putAll(parent.classNameReplacements);
        inlineMethods.addAll(parent.inlineMethods);
        inlineFields.addAll(parent.inlineFields);
    }

    @Nullable
    public MethodNode getCurrentMethod() {
        try {
            ClassNode classNode = getCurrentClass();

            if (classNode == null) return null;

            return classNode.methods.get(currentMethod);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private MethodNode generateMethod() {
        return new MethodNode(0, null, null, null, null);
    }

    public MethodNode getCurrentMethod(boolean create) {
        if (currentMethod == -1)
            return generateMethod();

        ClassNode classNode = getCurrentClass();

        if (classNode == null)
            return create ? generateMethod() : null;

        try {
            return classNode.methods.get(currentMethod);
        } catch (IndexOutOfBoundsException e) {
            if (!create)
                return null;
        }

        return generateMethod();
    }

    public ClassNode getCurrentClass() {
        return classes.get(currentClass);
    }

    public void errorAt(Token token, String message) {
        if (parser.isPanicMode()) return;

        parser.setPanicMode(true);

        System.err.printf("[file %s] [line %d] Error", fileName, token.line());

        if (token.type() == TokenType.TOKEN_EOF) {
            System.err.print(" at end");
        } else if (token.type() != TokenType.TOKEN_ERROR) {
            System.err.printf(" at \"%s\"", token.content());
        }

        System.err.printf(" -> %s\n", message);
        parser.setHadError(true);
    }

    public void error(String message) {
        errorAt(parser.getBeforePrevious(), message);
    }

    public void errorAtCurrent(String message) {
        errorAt(parser.getCurrent(), message);
    }

    public void advance() {
        parser.setBeforePrevious(parser.getPrevious());
        parser.setPrevious(parser.getCurrent());

        for (;;) {
            parser.setCurrent(scanner.scanToken());

            if (parser.getCurrent().type() != TokenType.TOKEN_ERROR) break;

            errorAtCurrent(parser.getCurrent().content());
        }
    }


    public void consume(TokenType type, String message) {
        if (parser.getCurrent().type() == type) {
            advance();
            return;
        }

        errorAtCurrent(message);
    }

    public String consumeType(String error) {
        consume(TokenType.TOKEN_COLON, error);

        return parseType("Expected type name after \":\".");
    }

    public Pair<String, String> consumeGenericType(String error) {
        consume(TokenType.TOKEN_COLON, error);

        return new Pair<>(parseType("Expected type name after \":\"."), parseGenericType());
    }

    public boolean check(TokenType type) {
        return parser.getCurrent().type() == type;
    }

    public boolean match(TokenType type) {
        if (!check(type))
            return false;

        advance();
        return true;
    }

    public boolean match(TokenType... type) {
        return Arrays.stream(type).anyMatch(this::match);
    }

    public void emit(AbstractInsnNode... nodes) {
        emitDelayedConstant();
        for (AbstractInsnNode node : nodes)
            getCurrentMethod(true).instructions.add(node);
    }

    public void emit(InsnList nodes) {
        emitDelayedConstant();
        for (AbstractInsnNode node : nodes)
            getCurrentMethod(true).instructions.add(node);
    }

    public void emitBoolean(boolean value) {
        emit(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        notifyPushStack(StackTypes.BOOLEAN);
    }

    public void emitConstant(Object object) {
        emit(new LdcInsnNode(object));
        notifyPushStack(StackTypes.getTypeFromLdcInstance(object));
    }

    public void emitNull() {
        emit(new InsnNode(Opcodes.ACONST_NULL));
        notifyPushStack(StackTypes.OBJECT_TYPE);
    }

    public void emitSwap() {
        Type previousLastStack = peekPreviousLastStack();
        Type lastStack = peekLastStack();
        if (previousLastStack == null) {
            error("Stack too thin to emit swap opcode");
            return;
        }
        if (previousLastStack.getSize() == lastStack.getSize()) {
            switch (lastStack.getSize()) {
                case 1 -> emit(new InsnNode(Opcodes.SWAP));
                case 2 -> emit(new InsnNode(Opcodes.DUP2_X2),
                        new InsnNode(Opcodes.POP2));
            }
        } else {
            switch (lastStack.getSize()) {
                case 1 -> emit(new InsnNode(Opcodes.DUP_X2),
                        new InsnNode(Opcodes.POP));
                case 2 -> emit(new InsnNode(Opcodes.DUP2_X1),
                        new InsnNode(Opcodes.POP2));
            }
        }
        notifyPopStack();
        notifyPopStack();
        notifyPushStack(lastStack);
        notifyPushStack(previousLastStack);
    }

    public void emitDelayed(DelayedInstruction delayedInstruction) {
        emitDelayedConstant();
        this.delayedInstruction = delayedInstruction;
        delayedInstruction.applyStackChanges(this);
    }

    public void emitDelayedConstant() {
        DelayedInstruction delayedInstruction = this.delayedInstruction;
        this.delayedInstruction = null;
        if (delayedInstruction != null)
            delayedInstruction.emitConstant(this);
    }

    public void emitIfEq(LabelNode labelNode) {
        DelayedInstruction delayedInstruction = this.delayedInstruction;
        this.delayedInstruction = null;
        if (delayedInstruction != null) {
            delayedInstruction.invert().emitJump(this, labelNode);
        } else {
            emit(new JumpInsnNode(Opcodes.IFEQ, labelNode));
        }
        notifyPopStack();
    }

    public void emitIfNe(LabelNode labelNode) {
        DelayedInstruction delayedInstruction = this.delayedInstruction;
        this.delayedInstruction = null;
        if (delayedInstruction != null) {
            delayedInstruction.emitJump(this, labelNode);
        } else {
            emit(new JumpInsnNode(Opcodes.IFNE, labelNode));
        }
        notifyPopStack();
    }

    public void expression() {
        parsePrecedence(Precedence.PREC_ASSIGNMENT);
    }

    public void expressionStatement(boolean semicolon) {
        expression();
        if (semicolon)
            consume(TokenType.TOKEN_SEMICOLON, "Expect \";\"");
        else match(TokenType.TOKEN_SEMICOLON);
        clearStack();
    }

    public void parsePrecedence(Precedence precedence) {
        advance();
        BiConsumer<Compiler, Boolean> prefixRule = getRule(parser.getPrevious().type()).prefixRule();
        if (prefixRule == null) {
            error("Expected expression, but got \"" + parser.getPrevious().content() + "\" instead");
            return;
        }

        boolean canAssign = precedence.ordinal() <= Precedence.PREC_ASSIGNMENT.ordinal();
        prefixRule.accept(this, canAssign);

        while (precedence.ordinal() <= getRule(parser.getCurrent().type()).precedence().ordinal()) {
            advance();
            getRule(parser.getPrevious().type()).infixRule().accept(this, canAssign);
        }

        if (canAssign && match(TokenType.TOKEN_EQUAL))
            error("Invalid assignment target");
    }

    public ParseRule getRule(TokenType type) {
        return rules.get(type);
    }

    public void cast(boolean canAssign) {
        final Type type = Type.getType(parseType("Expected type name"));

        consume(TokenType.TOKEN_GREATER, "Expected \">\" after type name");

        expression();

        final String typeName;
        if (PRIMITIVE_DESCRIPTORS.contains(type.getDescriptor())) {
            convertLastStackForType(type);

            return;
        } else typeName = type.getInternalName();

        emit(new TypeInsnNode(Opcodes.CHECKCAST, typeName));
        notifyReplaceLastStack(type);
    }

    public void binary(boolean canAssign) {
        TokenType op = parser.getPrevious().type();
        ParseRule rule = getRule(op);
        parsePrecedence(Precedence.values()[rule.precedence().ordinal() + 1]);

        Type previousLastStack = peekPreviousLastStack();
        Type lastStack = peekLastStack();
        switch (op) { // We allow doing addition of integer with objects for initial version.
            case TOKEN_BANG_EQUAL, TOKEN_EQUAL_EQUAL,
                    TOKEN_PLUS, TOKEN_MINUS, TOKEN_STAR, TOKEN_SLASH,
                    // Special tokens that need to be inverted for code consistency
                    TOKEN_GREATER, TOKEN_GREATER_EQUAL, TOKEN_LESS, TOKEN_LESS_EQUAL -> {
                if ((StackTypes.OBJECT_TYPE.equals(previousLastStack) &&
                        StackTypes.INT.equals(lastStack))) {
                    previousLastStack = lastStack;
                    lastStack = peekPreviousLastStack();

                    emitSwap();
                    op = switch (op) {
                        default -> op;
                        // Make code consistent after we swapped the arguments
                        case TOKEN_GREATER_EQUAL -> TokenType.TOKEN_LESS_EQUAL;
                        case TOKEN_LESS_EQUAL -> TokenType.TOKEN_GREATER_EQUAL;
                        case TOKEN_GREATER -> TokenType.TOKEN_LESS;
                        case TOKEN_LESS -> TokenType.TOKEN_GREATER;
                    };
                }
            }
        }

        if (previousLastStack == null)
            error("Unable to compute last stack");

        switch (op) {
            case TOKEN_BANG_EQUAL -> {
                if (StackTypes.isTypeStackDouble(previousLastStack))
                    emitDelayed(DelayedInstruction.D_NOT_EQUAL);
                else if (StackTypes.isTypeStackFloat(previousLastStack))
                    emitDelayed(DelayedInstruction.F_NOT_EQUAL);
                else if (StackTypes.isTypeStackInt(previousLastStack) || StackTypes.isTypeStackLong(previousLastStack))
                    emitDelayed(DelayedInstruction.NUM_NOT_EQUAL);
                else {
                    try {
                        callObject("eq", previousLastStack, typeToClass(lastStack));

                    } catch (ClassNotFoundException | NoSuchMethodException e) {
                        emitDelayed(DelayedInstruction.OBJECT_NOT_EQUAL);
                    }
                }
            }
            case TOKEN_EQUAL_EQUAL -> {
                if (StackTypes.isTypeStackDouble(previousLastStack))
                    emitDelayed(DelayedInstruction.D_EQUAL);
                else if (StackTypes.isTypeStackFloat(previousLastStack))
                    emitDelayed(DelayedInstruction.F_EQUAL);
                else if (StackTypes.isTypeStackInt(previousLastStack) || StackTypes.isTypeStackLong(previousLastStack))
                    emitDelayed(DelayedInstruction.NUM_EQUAL);
                else {
                    try {
                        callObject("eq", previousLastStack, typeToClass(lastStack));
                    } catch (ClassNotFoundException | NoSuchMethodException e) {
                        emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z"));
                        notifyReplaceLastStack(Type.BOOLEAN_TYPE);
                    }
                }
            }
            case TOKEN_GREATER -> {
                if (StackTypes.isTypeStackDouble(previousLastStack))
                    emitDelayed(DelayedInstruction.D_GREATER);
                else if (StackTypes.isTypeStackFloat(previousLastStack))
                    emitDelayed(DelayedInstruction.F_GREATER);
                else if (StackTypes.isTypeStackInt(previousLastStack) || StackTypes.isTypeStackLong(previousLastStack))
                    emitDelayed(DelayedInstruction.NUM_GREATER);
                else if (StackTypes.isTypeStackPureObject(previousLastStack))
                    try {
                        callObject("gt", previousLastStack, typeToClass(lastStack));
                    } catch (ClassNotFoundException | NoSuchMethodException e) {
                        errorAtCurrent("Can't apply operator GREATER to " + previousLastStack.getInternalName());
                    }
            }
            case TOKEN_GREATER_EQUAL -> {
                if (StackTypes.isTypeStackDouble(previousLastStack))
                    emitDelayed(DelayedInstruction.D_GREATER_EQUAL);
                else if (StackTypes.isTypeStackFloat(previousLastStack))
                    emitDelayed(DelayedInstruction.F_GREATER_EQUAL);
                else if (StackTypes.isTypeStackInt(previousLastStack) || StackTypes.isTypeStackLong(previousLastStack))
                    emitDelayed(DelayedInstruction.NUM_GREATER_EQUAL);
                else if (StackTypes.isTypeStackPureObject(previousLastStack))
                    try {
                        callObject("ge", previousLastStack, typeToClass(lastStack));
                    } catch (ClassNotFoundException | NoSuchMethodException e) {
                        errorAtCurrent("Can't apply operator GREATER_EQUAL to " + previousLastStack.getInternalName());
                    }
            }
            case TOKEN_LESS -> {
                if (StackTypes.isTypeStackDouble(previousLastStack))
                    emitDelayed(DelayedInstruction.D_LESS);
                else if (StackTypes.isTypeStackFloat(previousLastStack))
                    emitDelayed(DelayedInstruction.F_LESS);
                else if (StackTypes.isTypeStackInt(previousLastStack) || StackTypes.isTypeStackLong(previousLastStack))
                    emitDelayed(DelayedInstruction.NUM_LESS);
                else if (StackTypes.isTypeStackPureObject(previousLastStack))
                    try {
                        callObject("lt", previousLastStack, typeToClass(lastStack));
                    } catch (ClassNotFoundException | NoSuchMethodException e) {
                        errorAtCurrent("Can't apply operator LESS to " + previousLastStack.getInternalName());
                    }
            }
            case TOKEN_LESS_EQUAL -> {
                if (StackTypes.isTypeStackDouble(previousLastStack))
                    emitDelayed(DelayedInstruction.D_LESS_EQUAL);
                else if (StackTypes.isTypeStackFloat(previousLastStack))
                    emitDelayed(DelayedInstruction.F_LESS_EQUAL);
                else if (StackTypes.isTypeStackInt(previousLastStack) || StackTypes.isTypeStackLong(previousLastStack))
                    emitDelayed(DelayedInstruction.NUM_LESS_EQUAL);
                else if (StackTypes.isTypeStackPureObject(previousLastStack))
                    try {
                        callObject("le", previousLastStack, typeToClass(lastStack));
                    } catch (ClassNotFoundException | NoSuchMethodException e) {
                        errorAtCurrent("Can't apply operator LESS_EQUAL to " + previousLastStack.getInternalName());
                    }
            }

            case TOKEN_PLUS -> {
                if (StackTypes.isTypeStackString(previousLastStack))
                    emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
                else if (!StackTypes.isTypeStackPureObject(previousLastStack))
                    emit(new InsnNode(previousLastStack.getOpcode(Opcodes.IADD)));
                else try {
                    callObject("add", previousLastStack, typeToClass(lastStack));
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                        errorAtCurrent("Can't apply operator ADD to " + previousLastStack.getInternalName());
                }
            }
            case TOKEN_MINUS -> {
                if (!StackTypes.isTypeStackPureObject(previousLastStack))
                    emit(new InsnNode(previousLastStack.getOpcode(Opcodes.ISUB)));
                else try {
                    callObject("subtract", previousLastStack, typeToClass(lastStack));
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                    errorAtCurrent("Can't apply operator SUBTRACT to " + previousLastStack.getInternalName());
                }
            }
            case TOKEN_STAR -> {
                if (!StackTypes.isTypeStackPureObject(previousLastStack))
                    emit(new InsnNode(previousLastStack.getOpcode(Opcodes.IMUL)));
                else try {
                    callObject("multiply", previousLastStack, typeToClass(lastStack));
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                    errorAtCurrent("Can't apply operator MULTIPLY to " + previousLastStack.getInternalName());
                }
            }
            case TOKEN_SLASH ->  {
                if (!StackTypes.isTypeStackPureObject(previousLastStack))
                    emit(new InsnNode(previousLastStack.getOpcode(Opcodes.IDIV)));
                else try {
                    callObject("divide", previousLastStack, typeToClass(lastStack));
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                    errorAtCurrent("Can't apply operator DIVIDE to " + previousLastStack.getInternalName());
                }
            }
            default -> throw op.makeInvalidTokenException(this, "Cannot use %s token with numbers!");
        }
    }

    public void literal(boolean canAssign) {
        switch (parser.getPrevious().type()) {
            case TOKEN_FALSE -> emitBoolean(false);
            case TOKEN_TRUE -> emitBoolean(true);
            case TOKEN_NULL -> emitNull();
        }
    }

    public void unary(boolean canAssign) {
        TokenType op = parser.getPrevious().type();

        parsePrecedence(Precedence.PREC_UNARY);

        switch (op) {
            case TOKEN_BANG -> emit(new InsnNode(Opcodes.IFNE));
            case TOKEN_MINUS -> emit(new InsnNode(Opcodes.INEG));
        }
    }


    public void array(boolean canAssign) {
        final String arrayType = parseType("Expected array type after \"[\"");
        consume(TokenType.TOKEN_COLON, "Expected \":\" after array type");

        Type internalType;
        try {
            internalType = Type.getType(arrayType);
        } catch (IllegalArgumentException ignored) {
            internalType = StackTypes.getTypeFromClassName(arrayType);
        }

        final AtomicInteger arraySize = new AtomicInteger(0);
        final Type finalInternalType = internalType;
        Pair<AbstractInsnNode[], Object> abstractInsnNodes = captureInstructions(compiler -> {
            int index = 0;
            if (!compiler.check(TokenType.TOKEN_RIGHT_BRACK)) {
                do {
                    compiler.emitConstant(index);
                    compiler.expression();

                    compiler.emit(new InsnNode(finalInternalType.getOpcode(Opcodes.IASTORE)));
                    compiler.emit(new InsnNode(Opcodes.DUP));

                    index++;
                } while (compiler.match(TokenType.TOKEN_COMMA));

                compiler.emit(new InsnNode(Opcodes.POP));
            }

            arraySize.set(index);

            return null;
        });

        emitConstant(arraySize.get());

        int arrayType1;
        switch (internalType.getSort()) {
            case Type.BOOLEAN -> arrayType1 = Opcodes.T_BOOLEAN;
            case Type.CHAR -> arrayType1 = Opcodes.T_CHAR;
            case Type.BYTE -> arrayType1 = Opcodes.T_BYTE;
            case Type.SHORT -> arrayType1 = Opcodes.T_SHORT;
            case Type.INT -> arrayType1 = Opcodes.T_INT;
            case Type.FLOAT -> arrayType1 = Opcodes.T_FLOAT;
            case Type.LONG -> arrayType1 = Opcodes.T_LONG;
            case Type.DOUBLE -> arrayType1 = Opcodes.T_DOUBLE;
            default -> {
                emit(new TypeInsnNode(Opcodes.ANEWARRAY, internalType.getInternalName()),
                        new InsnNode(Opcodes.DUP));

                notifyReplaceLastStack(StackTypes.arrayOf(internalType));
                emit(abstractInsnNodes.k);

                consume(TokenType.TOKEN_RIGHT_BRACK, "Expect \"]\" after expression");

                return;
            }
        }

        emit(new IntInsnNode(Opcodes.NEWARRAY, arrayType1),
                new InsnNode(Opcodes.DUP));

        notifyReplaceLastStack(StackTypes.arrayOf(arrayType1));
        emit(abstractInsnNodes.k);

        consume(TokenType.TOKEN_RIGHT_BRACK, "Expect \"]\" after expression");
    }

    public void grouping(boolean canAssign) {
        expression();
        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after expression");
    }

    public List<Type> argumentList() {
        List<Type> types = new ArrayList<>();
        if (!check(TokenType.TOKEN_RIGHT_PAREN)) {
            do {
                expression();
                types.add(peekLastStack());
            } while (match(TokenType.TOKEN_COMMA));
        }

        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after arguments");

        return types;
    }

    public Pair<Integer, Map<String, Object>> keyedArgumentList() {
        final Map<String, Object> types = new LinkedHashMap<>();
        if (!check(TokenType.TOKEN_RIGHT_PAREN)) {
            do {
                consume(TokenType.TOKEN_IDENTIFIER, "Expected key!");
                final String key = parser.getPrevious().content();

                consume(TokenType.TOKEN_EQUAL, "Expected \"=\" after \"" + key + "\"!");

                int oldCurrentMethod = currentMethod;
                currentMethod = -1;

                expression();

                currentMethod = oldCurrentMethod;

                Object value = null;

                if (peekLastStack().equals(StackTypes.STRING_TYPE)) {
                    final String content = parser.getPrevious().content();
                    value = content.substring(1, content.length() - 1);
                } else if (StackTypes.isTypeStackNumber(peekLastStack())) {
                    Token.NumberToken numberToken = (Token.NumberToken) parser.getPrevious();

                    if (numberToken.numberType().equals(StackTypes.INT)) {
                        value = Integer.parseInt(parser.getPrevious().content());
                    } else if (numberToken.numberType().equals(StackTypes.FLOAT)) {
                        value = Float.parseFloat(parser.getPrevious().content());
                    } else if (numberToken.numberType().equals(StackTypes.DOUBLE)) {
                        value = Double.parseDouble(parser.getPrevious().content());
                    } else if (numberToken.numberType().equals(StackTypes.LONG)) {
                        value = Long.parseLong(parser.getPrevious().content());
                    }
                } else value = peekLastStack();

                notifyPopStack();

                types.put(key, value);
            } while (match(TokenType.TOKEN_COMMA));
        }

        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after arguments");

        return new Pair<>(types.size(), types);
    }

    public void subscriptArray(boolean canAssign) {
        final Type internalType = Type.getType(peekPreviousLastStack().getDescriptor().substring(1));

        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            expression();

            emit(new InsnNode(internalType.getOpcode(Opcodes.IASTORE)));
            notifyPopStack();
            notifyPopStack();
            notifyPopStack();
        } else if (canAssign && match(TokenType.TOKEN_PLUS_EQUAL)) {
            emit(new InsnNode(Opcodes.DUP2), new InsnNode(internalType.getOpcode(Opcodes.IALOAD)));
            notifyReplaceLastStack(internalType);

            expression();

            emit(new InsnNode(internalType.getOpcode(Opcodes.IADD)));

            emit(new InsnNode(internalType.getOpcode(Opcodes.IASTORE)));
            notifyPopStack();
            notifyPopStack();
            notifyPopStack();
        } else if (canAssign && match(TokenType.TOKEN_MINUS_EQUAL)) {
            emit(new InsnNode(Opcodes.DUP2), new InsnNode(internalType.getOpcode(Opcodes.IALOAD)));
            notifyReplaceLastStack(internalType);

            expression();

            emit(new InsnNode(internalType.getOpcode(Opcodes.INEG)), new InsnNode(internalType.getOpcode(Opcodes.IADD)));

            emit(new InsnNode(internalType.getOpcode(Opcodes.IASTORE)));
            notifyPopStack();
            notifyPopStack();
            notifyPopStack();
        } else if (canAssign && match(TokenType.TOKEN_STAR_EQUAL)) {
            emit(new InsnNode(Opcodes.DUP2), new InsnNode(internalType.getOpcode(Opcodes.IALOAD)));
            notifyReplaceLastStack(internalType);

            expression();

            emit(new InsnNode(internalType.getOpcode(Opcodes.IMUL)));

            emit(new InsnNode(internalType.getOpcode(Opcodes.IASTORE)));
            notifyPopStack();
            notifyPopStack();
            notifyPopStack();
        } else if (canAssign && match(TokenType.TOKEN_SLASH_EQUAL)) {
            emit(new InsnNode(Opcodes.DUP2), new InsnNode(internalType.getOpcode(Opcodes.IALOAD)));
            notifyReplaceLastStack(internalType);

            expression();

            emit(new InsnNode(internalType.getOpcode(Opcodes.IDIV)));

            emit(new InsnNode(internalType.getOpcode(Opcodes.IASTORE)));
            notifyPopStack();
            notifyPopStack();
            notifyPopStack();
        } else {
            emit(new InsnNode(internalType.getOpcode(Opcodes.IALOAD)));
            notifyPopStack();
            notifyReplaceLastStack(internalType);
        }
    }

    public void subscriptMap(boolean canAssign) {
        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            expression();

            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                    new InsnNode(Opcodes.POP));

            notifyPopStack();
            notifyPopStack();
            notifyPopStack();
        } else {
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"));

            notifyPopStack();
            notifyReplaceLastStack(StackTypes.OBJECT_TYPE);
        }
    }

    public void subscriptObject(boolean canAssign, Type objectType) throws ClassNotFoundException, NoSuchMethodException {
        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            final Method method = Class.forName(objectType.getInternalName().replace("/", "."), true, runner).getMethod(MAGIC_PREFIX + "SUBSCRIPT_ASSIGN", Object.class, Object.class);

            final Type returnType = Type.getType(method.getReturnType());
            convertLastStackToObject();

            expression();
            convertLastStackToObject();

            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    objectType.getInternalName(), MAGIC_PREFIX + "SUBSCRIPT_ASSIGN", Type.getMethodDescriptor(method)));

            notifyPopStack();
            notifyReplaceLastStack(returnType);

        } else {
            final Method method = Class.forName(objectType.getInternalName().replace("/", "."), true, runner).getMethod(MAGIC_PREFIX + "SUBSCRIPT", Object.class);

            final Type returnType = Type.getType(method.getReturnType());
            convertLastStackToObject();

            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    objectType.getInternalName(), MAGIC_PREFIX + "SUBSCRIPT", Type.getMethodDescriptor(method)));

            notifyPopStack();
            notifyReplaceLastStack(returnType);
        }
    }

    public void subscript(boolean canAssign) {
        boolean isArray = peekLastStack().getSort() == Type.ARRAY;
        boolean isMap;

        Type lastStack = peekLastStack();

        try {
            isMap = Map.class.isAssignableFrom(Class.forName(peekLastStack().getInternalName().replace("/", "."), true, runner));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        expression();
        consume(TokenType.TOKEN_RIGHT_BRACK, "Expect \"]\" after subscript");

        if (isArray)
            subscriptArray(canAssign);
        else if (isMap)
            subscriptMap(canAssign);
        else if (!PRIMITIVE_DESCRIPTORS.contains(lastStack.getDescriptor()))
            try {
                subscriptObject(canAssign, lastStack);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
    }

    public Class<?> typeToClass(Type type) {
        try {
            if (PRIMITIVE_DESCRIPTORS.contains(type.getDescriptor()))
                return PRIMITIVE_TYPES.get(type.getSort());

            return Class.forName(type.getInternalName().replace("/", "."), true, runner);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public Class<?>[] typeToClass(Type[] types) {
        return Arrays.stream(types).map(this::typeToClass).toArray(Class[]::new);
    }

    public List<Class<?>> typeToClass(List<Type> types) {
        return types.stream().map(this::typeToClass).collect(Collectors.toList());
    }

    public void callMethod(@NotNull MethodCall call) throws ClassNotFoundException {
        int opcode = call.opcode;
        if (Modifier.isStatic(Objects.requireNonNull(getCurrentMethod()).access))
            opcode = opcode == -1 ? Opcodes.INVOKESTATIC : opcode;
        else emit(new VarInsnNode(Opcodes.ALOAD, 0));

        final List<Type> args = argumentList();

        String descriptor = null;

        if (call.owner.equals(getCurrentClass().name))
            for (MethodNode method : getCurrentClass().methods) {
                if (opcode == Opcodes.INVOKESTATIC && !Modifier.isStatic(method.access))
                    continue;
                if (!method.name.equals(call.name) && !methodNameReplacements.containsValue(call.name))
                    continue;

                final Type[] methodArgs = Type.getArgumentTypes(method.desc);
                if (args.size() != methodArgs.length)
                    continue;

                for (int i = 0; i < args.size(); i++) {
                    convertLastStackForType(methodArgs[i]);
                    notifyPopStack();
                }

                descriptor = method.desc;
                if (opcode == -1) {
                    if (Modifier.isStatic(method.access))
                        opcode = Opcodes.INVOKESTATIC;
                    else opcode = Opcodes.INVOKEVIRTUAL;
                }

                break;
            }
        else for (Method method : Class.forName(call.owner.replace("/", "."), true, runner).getDeclaredMethods()) {
            if (!method.getName().equals(call.name) && !methodNameReplacements.containsValue(call.name))
                continue;
            if (args.size() != method.getParameterCount())
                continue;

            for (int i = 0; i < args.size(); i++) {
                convertLastStackForType(Type.getType(method.getParameterTypes()[i]));
                notifyPopStack();
            }

            descriptor = Type.getMethodDescriptor(method);
            if (opcode == -1)
                if (Modifier.isStatic(method.getModifiers()))
                    opcode = Opcodes.INVOKESTATIC;
                else opcode = Opcodes.INVOKEVIRTUAL;

            break;
        }

        if (opcode == -1 || descriptor == null) {
            error("Couldn't find any methods called \"" + call.name + "\"!");

            return;
        }

        final Type returnType = Type.getReturnType(descriptor);

        emit(new MethodInsnNode(opcode, call.owner, call.name, descriptor));

        notifyPushStack(returnType);
    }

    public void callInlineMethod(String identifier) {
        final List<Type> args = argumentList();

        final List<InlineMethod> clone = new ArrayList<>(inlineMethods);
        Collections.reverse(clone);

        InlineMethod method = clone.stream().filter(x ->
                x.name.equals(identifier)
                        && Type.getArgumentTypes(x.methodDescriptor).length == args.size()
        ).findFirst().orElseThrow();

        for (int i = 0; i < args.size(); i++) {
            convertLastStackForType(Type.getArgumentTypes(method.methodDescriptor)[i]);
            notifyPopStack();
        }

        final Type returnType = Type.getReturnType(method.methodDescriptor);

        emit(new MethodInsnNode(Opcodes.INVOKESTATIC, method.owner, method.name, method.methodDescriptor));

        notifyPushStack(returnType);
    }

    public void call(boolean canAssign) {
        if (callStack.size() > 0) {
            try {
                callMethod(notifyPopCallStack());
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }

            if (callStack.size() > 0)
                error("Call stack is not empty! \"" + callStack.size() + "\" elements long!");

            return;
        }

        final Type lastStack = peekLastStack();
        if (PRIMITIVE_DESCRIPTORS.contains(lastStack.getDescriptor())) {
            error("Last stack is a primitive and not an object!");

            return;
        }

        final List<Type> args = argumentList();

        try {
            callObject("CALL", lastStack, typeToClass(args).toArray(Class[]::new));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public void callObject(String magicMethodName, Type objectType, Class<?>... args) throws ClassNotFoundException, NoSuchMethodException {
        final Method method = Class.forName(objectType.getInternalName().replace("/", "."), true, runner).getMethod(MAGIC_PREFIX + magicMethodName, args);

        final Type returnType = Type.getType(method.getReturnType());

        emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                objectType.getInternalName(), MAGIC_PREFIX + magicMethodName, Type.getMethodDescriptor(method)));

        notifyPopStack();
        notifyReplaceLastStack(returnType);
    }

    public void qDot(boolean canAssign) {
        consume(TokenType.TOKEN_IDENTIFIER, "Expect property name after \".\"");
        final String afterDot = parser.getPrevious().content();

        LabelNode l2 = new LabelNode();
        LabelNode l3 = new LabelNode();

        emit(new InsnNode(Opcodes.DUP));
        emit(new JumpInsnNode(Opcodes.IFNULL, l2));

        if (!match(TokenType.TOKEN_LEFT_PAREN)) {
            if (peekLastStack() instanceof ClassType type)
                emit(new FieldInsnNode(Opcodes.GETSTATIC, type.getInternalName(), afterDot, notifyPopStack().getDescriptor()));
            else try {
                final Type fieldType = findFieldType(peekLastStack(), afterDot);

                emit(new FieldInsnNode(Opcodes.GETFIELD, notifyPopStack().getInternalName(), afterDot, fieldType.getDescriptor()));
                notifyPushStack(fieldType);
                convertLastStackToObject();
            } catch (ClassNotFoundException | NoSuchFieldException e) {
                error("Couldn't find field!");
            }

            emit(new JumpInsnNode(Opcodes.GOTO, l3));
            emit(l2, new InsnNode(Opcodes.POP),
                    new InsnNode(Opcodes.ACONST_NULL));
            emit(l3);

            return;
        }

        if (peekLastStack() instanceof ClassType)
            notifyPushCallStack(new MethodCall(Opcodes.INVOKESTATIC, notifyPopStack().getInternalName(), afterDot));
        else notifyPushCallStack(new MethodCall(Opcodes.INVOKEVIRTUAL, notifyPopStack().getInternalName(), afterDot));

        call(false);
        convertLastStackToObject();

        emit(new JumpInsnNode(Opcodes.GOTO, l3));
        emit(l2, new InsnNode(Opcodes.POP),
                new InsnNode(Opcodes.ACONST_NULL));
        emit(l3);
    }

    public void dot(boolean canAssign) {
        consume(TokenType.TOKEN_IDENTIFIER, "Expect property name after \".\"");
        final String afterDot = parser.getPrevious().content();

        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            expression();
            if (peekPreviousLastStack() instanceof ClassType)
                emit(new FieldInsnNode(Opcodes.PUTSTATIC, peekPreviousLastStack().getInternalName(), afterDot, peekLastStack().getDescriptor()));
            else emit(new FieldInsnNode(Opcodes.PUTFIELD, peekPreviousLastStack().getInternalName(), afterDot, peekLastStack().getDescriptor()));

            notifyPopStack();
            notifyPopStack();
        } else {
            if (!check(TokenType.TOKEN_LEFT_PAREN)) {
                if (peekLastStack() instanceof ClassType type)
                    emit(new FieldInsnNode(Opcodes.GETSTATIC, type.getInternalName(), afterDot, notifyPopStack().getDescriptor()));
                else try {
                    final Type fieldType = findFieldType(peekLastStack(), afterDot);

                    emit(new FieldInsnNode(Opcodes.GETFIELD, notifyPopStack().getInternalName(), afterDot, fieldType.getDescriptor()));
                    notifyPushStack(fieldType);
                } catch (ClassNotFoundException | NoSuchFieldException e) {
                    error("Couldn't find field!");
                }

                return;
            }

            if (peekLastStack() instanceof ClassType)
                notifyPushCallStack(new MethodCall(Opcodes.INVOKESTATIC, notifyPopStack().getInternalName(), afterDot));
            else notifyPushCallStack(new MethodCall(Opcodes.INVOKEVIRTUAL, notifyPopStack().getInternalName(), afterDot));
        }
    }

    public Type findFieldType(Type parentType, String name) throws ClassNotFoundException, NoSuchFieldException {
        if (parentType.getInternalName().equals(getCurrentClass().name))
            return Type.getType(getCurrentClass().fields.stream().filter(x -> Objects.equals(x.name, name)).findAny().orElseThrow(NoSuchFieldException::new).desc);

        final Field field = Class.forName(parentType.getInternalName().replace("/", "."), true, runner).getField(name);

        return Type.getType(field.getType());
    }

    public void variable(boolean canAssign) {
        final String identifier = parser.getPrevious().content();

        Local local;
        if (type == CompilerType.METHOD || type == CompilerType.NESTED_METHOD)
            local = locals.get(identifier);
        else local = null;

        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            expression();

            final List<InlineField> clone = new ArrayList<>(inlineFields);
            Collections.reverse(clone);

            Optional<InlineField> oInlineField = clone.stream().filter(x -> x.name.equals(identifier)).findFirst();
            if (oInlineField.isPresent()) {
                final InlineField inlineField = oInlineField.get();
                convertLastStackForType(inlineField.type);

                emit(new FieldInsnNode(Opcodes.PUTSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));

                notifyPopStack();

                return;
            }

            if (local == null || (type != CompilerType.METHOD && type != CompilerType.NESTED_METHOD)) {
                errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                return;
            }

            emit(new VarInsnNode(notifyPopStack().getOpcode(Opcodes.ISTORE), local.index));
        } else if (canAssign && match(TokenType.TOKEN_PLUS_EQUAL)) {
            final AbstractInsnNode[] nodes = captureInstructions(compiler -> {
                compiler.expression();

                return null;
            }).k;

            final List<InlineField> clone = new ArrayList<>(inlineFields);
            Collections.reverse(clone);

            Optional<InlineField> oInlineField = clone.stream().filter(x -> x.name.equals(identifier)).findFirst();
            if (oInlineField.isPresent()) {
                final InlineField inlineField = oInlineField.get();
                emit(nodes);
                convertLastStackForType(inlineField.type);

                emit(new FieldInsnNode(Opcodes.GETSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));
                emit(new InsnNode(inlineField.type.getOpcode(Opcodes.IADD)));

                emit(new FieldInsnNode(Opcodes.PUTSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));

                notifyPopStack();

                return;
            }

            if (local == null || (type != CompilerType.METHOD && type != CompilerType.NESTED_METHOD)) {
                errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                return;
            }

            if (local.type.equals(StackTypes.INT)) {
                emit(new IincInsnNode(local.index, Integer.parseInt(parser.getPrevious().content())));
                notifyPopStack();

                return;
            }

            convertLastStackForType(local.type);

            notifyPopStack();

            emit(new VarInsnNode(local.type.getOpcode(Opcodes.ILOAD), local.index));
            emit(nodes);
            emit(new InsnNode(local.type.getOpcode(Opcodes.IADD)));

            emit(new VarInsnNode(local.type.getOpcode(Opcodes.ISTORE), local.index));
        } else if (canAssign && match(TokenType.TOKEN_MINUS_EQUAL)) {
            final AbstractInsnNode[] nodes = captureInstructions(compiler -> {
                compiler.expression();
                compiler.convertLastStackForType(local.type);

                return null;
            }).k;

            final List<InlineField> clone = new ArrayList<>(inlineFields);
            Collections.reverse(clone);

            Optional<InlineField> oInlineField = clone.stream().filter(x -> x.name.equals(identifier)).findFirst();
            if (oInlineField.isPresent()) {
                final InlineField inlineField = oInlineField.get();
                emit(nodes);
                convertLastStackForType(inlineField.type);
                emit(new InsnNode(inlineField.type.getOpcode(Opcodes.INEG)));

                emit(new FieldInsnNode(Opcodes.GETSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));
                emit(new InsnNode(inlineField.type.getOpcode(Opcodes.IADD)));

                emit(new FieldInsnNode(Opcodes.PUTSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));

                notifyPopStack();

                return;
            }

            if (local == null || (type != CompilerType.METHOD && type != CompilerType.NESTED_METHOD)) {
                errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                return;
            }

            if (local.type.equals(StackTypes.INT)) {
                emit(new IincInsnNode(local.index, -Integer.parseInt(parser.getPrevious().content())));
                notifyPopStack();

                return;
            }

            convertLastStackForType(local.type);

            notifyPopStack();

            emit(new VarInsnNode(local.type.getOpcode(Opcodes.ILOAD), local.index));
            emit(nodes);
            emit(new InsnNode(local.type.getOpcode(Opcodes.INEG)));
            emit(new InsnNode(local.type.getOpcode(Opcodes.IADD)));

            emit(new VarInsnNode(local.type.getOpcode(Opcodes.ISTORE), local.index));
        } else if (canAssign && match(TokenType.TOKEN_STAR_EQUAL)) {
            expression();

            final List<InlineField> clone = new ArrayList<>(inlineFields);
            Collections.reverse(clone);

            Optional<InlineField> oInlineField = clone.stream().filter(x -> x.name.equals(identifier)).findFirst();
            if (oInlineField.isPresent()) {
                final InlineField inlineField = oInlineField.get();
                convertLastStackForType(inlineField.type);

                emit(new FieldInsnNode(Opcodes.GETSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));
                emit(new InsnNode(inlineField.type.getOpcode(Opcodes.IMUL)));

                emit(new FieldInsnNode(Opcodes.PUTSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));

                notifyPopStack();

                return;
            }

            if (local == null || (type != CompilerType.METHOD && type != CompilerType.NESTED_METHOD)) {
                errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                return;
            }

            emit(new VarInsnNode(peekLastStack().getOpcode(Opcodes.ILOAD), local.index));
            emit(new InsnNode(peekLastStack().getOpcode(Opcodes.IMUL)));

            emit(new VarInsnNode(notifyPopStack().getOpcode(Opcodes.ISTORE), local.index));
        } else if (canAssign && match(TokenType.TOKEN_SLASH_EQUAL)) {
            expression();


            final List<InlineField> clone = new ArrayList<>(inlineFields);
            Collections.reverse(clone);

            Optional<InlineField> oInlineField = clone.stream().filter(x -> x.name.equals(identifier)).findFirst();
            if (oInlineField.isPresent()) {
                final InlineField inlineField = oInlineField.get();
                convertLastStackForType(inlineField.type);

                emit(new FieldInsnNode(Opcodes.GETSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));
                emit(new InsnNode(inlineField.type.getOpcode(Opcodes.IDIV)));

                emit(new FieldInsnNode(Opcodes.PUTSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));

                notifyPopStack();

                return;
            }

            if (local == null || (type != CompilerType.METHOD && type != CompilerType.NESTED_METHOD)) {
                errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                return;
            }

            emit(new VarInsnNode(peekLastStack().getOpcode(Opcodes.ILOAD), local.index));
            emit(new InsnNode(peekLastStack().getOpcode(Opcodes.IDIV)));

            emit(new VarInsnNode(notifyPopStack().getOpcode(Opcodes.ISTORE), local.index));
        } else {
            if (check(TokenType.TOKEN_LEFT_PAREN) && local == null) {
                if (inlineMethods.stream().noneMatch(x -> x.name.equals(identifier))) {
                    notifyPushCallStack(new MethodCall(
                            -1,
                            getCurrentClass().name,
                            Objects.requireNonNullElse(methodNameReplacements.get(identifier), identifier)
                    ));

                    return;
                }

                match(TokenType.TOKEN_LEFT_PAREN);
                callInlineMethod(identifier);

                return;
            }

            if (classNameReplacements.containsKey(identifier)) {
                final String rep = classNameReplacements.get(identifier).replace("/", ".");

                final Class<?> repClass;
                try {
                    repClass = Class.forName(rep, true, runner);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    return;
                }

                notifyPushStack(ClassType.of(repClass));
            } else if (getCurrentClass().methods.stream().noneMatch(m -> identifier.equals(m.name))) {
                final List<InlineField> clone = new ArrayList<>(inlineFields);
                Collections.reverse(clone);

                Optional<InlineField> oInlineField = clone.stream().filter(x -> x.name.equals(identifier)).findFirst();
                if (oInlineField.isPresent()) {
                    final InlineField inlineField = oInlineField.get();
                    emit(new FieldInsnNode(Opcodes.GETSTATIC, inlineField.owner, inlineField.name, inlineField.type.getInternalName()));
                    notifyPushStack(inlineField.type);
                    return;
                }

                boolean isField = getCurrentClass().fields.stream()
                        .anyMatch(x -> Objects.equals(x.name, identifier)) && !locals.containsKey(identifier);

                boolean isFieldStatic = isField && getCurrentClass().fields.stream()
                        .anyMatch(x -> (x.access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC && Objects.equals(x.name, identifier));

                if (local == null && type != CompilerType.CLASS && type != CompilerType.NESTED_CLASS && !isField) {
                    errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                    return;
                }

                if (isField && (type == CompilerType.CLASS || type == CompilerType.NESTED_CLASS)) {
                    final String desc = getCurrentClass().fields.stream()
                            .filter(x -> Objects.equals(x.name, identifier))
                            .findAny().get().desc;

                    if (isFieldStatic)
                        emit(new FieldInsnNode(Opcodes.GETSTATIC, getCurrentClass().name, identifier, desc));
                    else emit(new FieldInsnNode(Opcodes.GETFIELD, getCurrentClass().name, identifier, desc));
                    notifyPushStack(Type.getType(desc));
                } else {
                    emit(new VarInsnNode(local.type.getOpcode(Opcodes.ILOAD), local.index));
                    notifyPushStack(local.type);
                }
            }
        }
    }

    public void number(boolean canAssign) {
        Token.NumberToken numberToken = (Token.NumberToken) parser.getPrevious();

        if (numberToken.numberType().equals(StackTypes.INT)) {
            emitConstant(Integer.parseInt(parser.getPrevious().content()));
        } else if (numberToken.numberType().equals(StackTypes.FLOAT)) {
            emitConstant(Float.parseFloat(parser.getPrevious().content()));
        } else if (numberToken.numberType().equals(StackTypes.DOUBLE)) {
            emitConstant(Double.parseDouble(parser.getPrevious().content()));
        } else if (numberToken.numberType().equals(StackTypes.LONG)) {
            emitConstant(Long.parseLong(parser.getPrevious().content()));
        }
    }

    public void string(boolean canAssign) {
        String content = parser.getPrevious().content();
        emitConstant(content.substring(1, content.length() - 1));
    }

    public void and(boolean canAssign) {
        LabelNode label1 = new LabelNode();
        LabelNode label2 = new LabelNode();
        emitIfEq(label1);

        parsePrecedence(Precedence.PREC_AND);
        emitIfEq(label1);
        emit(new InsnNode(Opcodes.ICONST_1));
        emit(new JumpInsnNode(Opcodes.GOTO, label2));

        emit(label1);
        emit(new InsnNode(Opcodes.ICONST_0));

        emit(label2);

        notifyPushStack(StackTypes.BOOLEAN);
    }

    public void import_(boolean canAssign) {
        if (type != CompilerType.TOP) {
            errorAtCurrent("You can only import in top-level code!");
            return;
        }

        final String type = parseImportType("Expect module after \"import\"");

        final Class<?> clazz;

        try {
            clazz = Class.forName(type, true, runner);
        } catch (ClassNotFoundException e) {
            error("\"" + type + "\" is not a valid class!");
            return;
        }

        for (Method method : clazz.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()))
                continue;
            if (method.getAnnotation(Inline.class) == null)
                continue;
            inlineMethods.add(new InlineMethod(type.replace(".", "/"), method.getName(), Type.getMethodDescriptor(method)));
        }

        for (Field field : clazz.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()))
                continue;
            if (field.getAnnotation(Inline.class) == null)
                continue;
            inlineFields.add(new InlineField(type.replace(".", "/"), field.getName(), Type.getType(field.getType())));
        }

        classNameReplacements.put(clazz.getSimpleName(), type.replace(".", "/"));
    }

    public void ternary(boolean canAssign) {
        if (!peekLastStack().equals(StackTypes.BOOLEAN)) {
            error("Last stack isn't a boolean!");
            return;
        }

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        emitIfEq(start);

        parsePrecedence(Precedence.PREC_PRIMARY);

        emit(new JumpInsnNode(Opcodes.GOTO, end));

        consume(TokenType.TOKEN_COLON, "colon");

        emit(start);

        expression();

        emit(end);

        notifyPopStack();
    }

    public void at(boolean canAssign) {
        final Set<AnnotationNode> annotations = new HashSet<>();

        do {
            final String type = parseType("Expected annotation after \"@\"!");
            final boolean requiresArguments = match(TokenType.TOKEN_LEFT_PAREN);

            try {
                Class.forName(type.replace("/", ".").substring(1, type.length() - 1), true, runner);
            } catch (ClassNotFoundException e) {
                errorAtCurrent("Invalid annotation!");

                continue;
            }

            if (!requiresArguments) {
                annotations.add(new AnnotationNode(type));

                continue;
            }

            Pair<Integer, Map<String, Object>> arguments = keyedArgumentList();
            final AnnotationNode node = new AnnotationNode(type);
            for (Map.Entry<String, Object> entry : arguments.v.entrySet())
                node.visit(entry.getKey(), entry.getValue());

            annotations.add(node);
        } while (match(TokenType.TOKEN_AT));

        annotationsForNextElement.addAll(annotations);
    }

    public void modifier() {
        final Set<TokenType> modifiers = new HashSet<>();

        while (match(TokenType.TOKEN_INLINE, TokenType.TOKEN_PRIVATE, TokenType.TOKEN_STATIC,
                TokenType.TOKEN_FINAL, TokenType.TOKEN_MAGIC)) {
            modifiers.add(parser.getPrevious().type());
        }

        switch (parser.getCurrent().type()) {
            case TOKEN_FN, TOKEN_LET -> modifiersForNextElement.addAll(modifiers);
            case TOKEN_CLASS -> {
                if (modifiers.contains(TokenType.TOKEN_MAGIC)) {
                    error("A class can not be magic!");

                    return;
                }
                if (modifiers.contains(TokenType.TOKEN_INLINE)) {
                    error("A class can not be inline!");

                    return;
                }

                modifiersForNextElement.addAll(modifiers);
            }

            default -> {
                if (modifiers.isEmpty()) return;

                errorAtCurrent("Element does not support modifiers!");
            }
        }
    }

    public void specialDot(boolean canAssign) {
        final Type lastStack = peekLastStack();

        final String internalName = peekLastStack().getInternalName();

        consume(TokenType.TOKEN_IDENTIFIER, "Expected identifier after \":\"!");
        final String content = parser.getPrevious().content();

        consume(TokenType.TOKEN_LEFT_PAREN, "Expected \"(\" after \"" + content + "\"!");
        final Pair<AbstractInsnNode[], Object> insns = captureInstructions(Compiler::argumentList);

        @SuppressWarnings("unchecked")
        final List<Type> args = (List<Type>) insns.v;

        switch (content) {
            case "new" -> {
                if (!(lastStack instanceof ClassType)) {
                    errorAtCurrent("Last stack is not a class!");
                    return;
                }
                emit(new TypeInsnNode(Opcodes.NEW, internalName), new InsnNode(Opcodes.DUP));
                emit(insns.k);
                for (Type ignored : args)
                    notifyPopStack();

                emit(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                        internalName, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, args.toArray(Type[]::new))));
                notifyReplaceLastStack(Type.getType(lastStack.getDescriptor()));
            }
            case "subscript" -> {
                final Method method;
                try {
                    method = Class.forName(lastStack.getInternalName().replace("/", "."), true, runner).getMethod(MAGIC_PREFIX + "SUBSCRIPT", Object.class);
                } catch (NoSuchMethodException | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                final Type returnType = Type.getType(method.getReturnType());
                convertLastStackToObject();

                emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        lastStack.getInternalName(), MAGIC_PREFIX + "SUBSCRIPT", Type.getMethodDescriptor(method)));

                notifyPopStack();
                notifyReplaceLastStack(returnType);
            }
            case "call" -> {
                final Method method;
                try {
                    method = Class.forName(lastStack.getInternalName().replace("/", "."), true, runner).getMethod(MAGIC_PREFIX + "SUBSCRIPT", Object.class);
                } catch (NoSuchMethodException | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                final Type returnType = Type.getType(method.getReturnType());
                convertLastStackToObject();

                emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        lastStack.getInternalName(), MAGIC_PREFIX + "CALL", Type.getMethodDescriptor(method)));

                notifyPopStack();
                notifyReplaceLastStack(returnType);
            }
        }

        // specialMethods.get(content).accept(argumentList());
    }

    public void or(boolean canAssign) {
        LabelNode label3 = new LabelNode();
        LabelNode label4 = new LabelNode();
        LabelNode label5 = new LabelNode();
        emitIfNe(label3);

        parsePrecedence(Precedence.PREC_OR);
        emitIfEq(label4);

        emit(label3);
        emit(new InsnNode(Opcodes.ICONST_1));
        emit(new JumpInsnNode(Opcodes.GOTO, label5));

        emit(label4);
        emit(new InsnNode(Opcodes.ICONST_0));

        emit(label5);

        notifyPushStack(StackTypes.BOOLEAN);
    }

    public void emitNullReturn() {
        emitNull();
        emit(new InsnNode(Opcodes.ARETURN));
    }

    private void emitVoidReturn() {
        emit(new InsnNode(Opcodes.RETURN));
    }

    public Type peekPreviousLastStack() {
        int size = typeStack.size();
        return size < 2 ? null : typeStack.get(size - 2);
    }

    public Type peekLastStack() {
        return typeStack.isEmpty() ? null : typeStack.peek();
    }

    public Type requireLastStack() {
        return Objects.requireNonNull(peekLastStack(), "last stack reference is null");
    }

    private void clearStack() {
        emitDelayedConstant();
        Type lastStack;
        while ((lastStack = peekLastStack()) != null) {
            switch (lastStack.getSize()) {
                case 1 -> emit(new InsnNode(Opcodes.POP));
                case 2 -> emit(new InsnNode(Opcodes.POP2));
            }
            notifyPopStack();
        }
    }

    public void convertLastStackForType(Type type) {
        if (StackTypes.isTypeStackString(type)) {
            convertLastStackToString();
        } else if (StackTypes.isTypeStackObject(type)) {
            convertLastStackToObject();
        } else if (StackTypes.INT.equals(type)) {
            convertLastStackToInteger();
        } else if (StackTypes.isTypeStackDouble(type)) {
            convertLastStackToDouble();
        } else if (StackTypes.isTypeStackFloat(type)) {
            convertLastStackToFloat();
        }  else if (StackTypes.isTypeStackBoolean(type)) {
            convertLastStackToBoolean();
        } else if (StackTypes.isTypeStackLong(type)) {
            convertLastStackToLong();
        }
    }

    public void convertLastStackToFloat() {
        Type lastStack = requireLastStack();
        if (StackTypes.isTypeStackFloat(lastStack))
            return;
        if (StackTypes.isTypeStackDouble(lastStack)) {
            emit(new InsnNode(Opcodes.D2F));
            notifyReplaceLastStack(StackTypes.FLOAT);
            return;
        }
        if (StackTypes.isTypeStackLong(lastStack)) {
            emit(new InsnNode(Opcodes.L2F));
            notifyReplaceLastStack(StackTypes.FLOAT);
            return;
        }
        if (StackTypes.isTypeStackInt(lastStack)) {
            emit(new InsnNode(Opcodes.I2F));
            notifyReplaceLastStack(StackTypes.FLOAT);
            return;
        }
        if (StackTypes.isTypeStackString(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Float", "parseFloat", "(Ljava/lang/String;)F"));
            notifyReplaceLastStack(StackTypes.FLOAT);
            return;
        }
        if (StackTypes.isTypeStackObject(lastStack)) {
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F"));
            notifyReplaceLastStack(StackTypes.FLOAT);
            return;
        }
        error("Cannot convert " + lastStack + " into a double");
    }

    public void convertLastStackToLong() {
        Type lastStack = requireLastStack();
        if (StackTypes.isTypeStackLong(lastStack))
            return;
        if (StackTypes.isTypeStackFloat(lastStack)) {
            emit(new InsnNode(Opcodes.F2L));
            notifyReplaceLastStack(StackTypes.LONG);
            return;
        }
        if (StackTypes.isTypeStackDouble(lastStack)) {
            emit(new InsnNode(Opcodes.D2L));
            notifyReplaceLastStack(StackTypes.LONG);
            return;
        }
        if (StackTypes.isTypeStackInt(lastStack)) {
            emit(new InsnNode(Opcodes.I2L));
            notifyReplaceLastStack(StackTypes.LONG);
            return;
        }
        if (StackTypes.isTypeStackString(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Long", "parseLong", "(Ljava/lang/String;)J"));
            notifyReplaceLastStack(StackTypes.LONG);
            return;
        }
        if (StackTypes.isTypeStackObject(lastStack)) {
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J"));
            notifyReplaceLastStack(StackTypes.LONG);
            return;
        }
        error("Cannot convert " + lastStack + " into a long");
    }

    public void convertLastStackToBoolean() {
        Type lastStack = requireLastStack();
        if (StackTypes.BOOLEAN.equals(lastStack))
            return;
        if (StackTypes.isTypeStackString(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z"));
            notifyReplaceLastStack(StackTypes.BOOLEAN);
            return;
        }
        if (StackTypes.isTypeStackObject(lastStack)) {
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z"));
            notifyReplaceLastStack(StackTypes.BOOLEAN);
            return;
        }
        if (StackTypes.INT.equals(lastStack)) {
            notifyReplaceLastStack(StackTypes.BOOLEAN);
            return;
        }
        error("Cannot convert " + lastStack + " into a boolean");
    }

    public void convertLastStackToDouble() {
        Type lastStack = requireLastStack();
        if (StackTypes.isTypeStackDouble(lastStack))
            return;
        if (StackTypes.isTypeStackFloat(lastStack)) {
            emit(new InsnNode(Opcodes.F2D));
            notifyReplaceLastStack(StackTypes.DOUBLE);
            return;
        }
        if (StackTypes.isTypeStackLong(lastStack)) {
            emit(new InsnNode(Opcodes.L2D));
            notifyReplaceLastStack(StackTypes.DOUBLE);
            return;
        }
        if (StackTypes.isTypeStackInt(lastStack)) {
            emit(new InsnNode(Opcodes.I2D));
            notifyReplaceLastStack(StackTypes.DOUBLE);
            return;
        }
        if (StackTypes.isTypeStackString(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Double", "parseDouble", "(Ljava/lang/String;)D"));
            notifyReplaceLastStack(StackTypes.DOUBLE);
            return;
        }
        if (StackTypes.isTypeStackObject(lastStack)) {
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D"));
            notifyReplaceLastStack(StackTypes.DOUBLE);
            return;
        }
        error("Cannot convert " + lastStack + " into a double");
    }

    public void convertLastStackToInteger() {
        Type lastStack = requireLastStack();
        if (StackTypes.isTypeStackInt(lastStack))
            return;
        if (StackTypes.isTypeStackFloat(lastStack)) {
            emit(new InsnNode(Opcodes.F2I));
            notifyReplaceLastStack(StackTypes.INT);
            return;
        }
        if (StackTypes.isTypeStackLong(lastStack)) {
            emit(new InsnNode(Opcodes.L2I));
            notifyReplaceLastStack(StackTypes.INT);
            return;
        }
        if (StackTypes.isTypeStackDouble(lastStack)) {
            emit(new InsnNode(Opcodes.D2I));
            notifyReplaceLastStack(StackTypes.INT);
            return;
        }
        if (StackTypes.isTypeStackBoolean(lastStack)) {
            emitConstant(false);
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "compare", "(ZZ)I"));
            notifyReplaceLastStack(StackTypes.INT);
        }
        if (StackTypes.isTypeStackString(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I"));
            notifyReplaceLastStack(StackTypes.INT);
            return;
        }
        if (StackTypes.isTypeStackIntegerType(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I"));
            notifyReplaceLastStack(StackTypes.INT);
            return;
        }
        if (StackTypes.isTypeStackPureObject(lastStack)) {
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I"));
            notifyReplaceLastStack(StackTypes.INT);
            return;
        }
        error("Cannot convert " + lastStack + " into an integer");
    }

    public void convertLastStackToObject() {
        Type lastStack = peekLastStack();
        if (lastStack == null) {
            emitNull();
            return;
        }
        if (StackTypes.isTypeStackObject(lastStack)) return;
        if (StackTypes.isTypeStackInt(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
            notifyReplaceLastStack(StackTypes.INTEGER_TYPE);
            return;
        }
        if (StackTypes.isTypeStackDouble(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
            notifyReplaceLastStack(StackTypes.DOUBLE_TYPE);
            return;
        }
        if (StackTypes.isTypeStackLong(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
            notifyReplaceLastStack(StackTypes.LONG_TYPE);
            return;
        }
        error("Cannot convert " + lastStack + " into an object");
    }

    public void convertLastStackToString() {
        Type lastStack = peekLastStack();
        if (lastStack == null) {
            emitConstant("null");
            return;
        }
        if (StackTypes.isTypeStackString(lastStack))
            return;

        if (StackTypes.isTypeStackArray(lastStack))
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "basalt/lang/STDLib",
                    "arrayToString", "(Ljava/lang/Object;)Ljava/lang/String;"));
        else if (StackTypes.isTypeStackPureObject(lastStack))
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "basalt/lang/STDLib",
                    "toString", "(Ljava/lang/Object;)Ljava/lang/String;"));
        else
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String",
                    "valueOf", "(" + lastStack.getDescriptor() + ")Ljava/lang/String;"));
        notifyReplaceLastStack(StackTypes.STRING_TYPE);
    }

    public void notifyReplaceLastStack(Type type) {
        notifyPopStack();
        notifyPushStack(type);
    }

    public void notifyPushStack(Type type) {
        if (type == StackTypes.VOID) return;
        typeStack.add(type);
    }

    public Type notifyPopStack() {
        try {
            return typeStack.pop();
        } catch (EmptyStackException e) {
            error("Lost track of stack?");
            e.printStackTrace();
        }

        return null;
    }

    public void notifyPushCallStack(MethodCall call) {
        callStack.add(call);
    }

    public MethodCall notifyPopCallStack() {
        try {
            return callStack.pop();
        } catch (NoSuchElementException e) {
            error("Lost track of call stack?");
            e.printStackTrace();
        }

        return null;
    }

    public String parseIdentifier(String error) {
        consume(TokenType.TOKEN_IDENTIFIER, error);

        return parser.getPrevious().content();
    }

    public String parseImportType(String error) {
        StringBuilder builder = new StringBuilder();
        boolean isWrapper = false;

        do {
            consume(TokenType.TOKEN_IDENTIFIER, error);

            builder.append(parser.getPrevious().content());

            if (check(TokenType.TOKEN_DOT)) {
                isWrapper = true;

                builder.append(".");
            }
        } while (match(TokenType.TOKEN_DOT));

        while (match(TokenType.TOKEN_LEFT_BRACK)) {
            consume(TokenType.TOKEN_RIGHT_BRACK, "Expect \"]\" after \"[\"");

            if (!isWrapper)
                builder = new StringBuilder(StackTypes.getTypeFromClassName(builder.toString()).getInternalName());

            builder.insert(0, "[");
        }

        if (!match(TokenType.TOKEN_LEFT_BRACK) && !isWrapper)
            return StackTypes.getTypeFromClassName(builder.toString()).getInternalName();

        return builder.toString();
    }

    public String parseGenericType() {
        StringBuilder builder = new StringBuilder();

        if (match(TokenType.TOKEN_LESS)) {
            builder.append("<");

            List<String> types = new ArrayList<>();
            if (!check(TokenType.TOKEN_GREATER)) {
                do {
                    types.add(parseType("Invalid type!"));
                } while (match(TokenType.TOKEN_COMMA));
            }

            builder.append(String.join("", types)).append(">");

            consume(TokenType.TOKEN_GREATER, "Expected \">\" after generics!");
        }

        return builder.toString();
    }

    public String parseType(String error) {
        StringBuilder builder = new StringBuilder();
        boolean isWrapper = false;

        do {
            consume(TokenType.TOKEN_IDENTIFIER, error);

            builder.append(parser.getPrevious().content());

            if (check(TokenType.TOKEN_DOT)) {
                if (!isWrapper)
                    builder.insert(0, "L");

                isWrapper = true;

                builder.append("/");
            }
        } while (match(TokenType.TOKEN_DOT));

        final String clone = builder.toString();

        if (classNameReplacements.containsKey(clone)) {
            builder = new StringBuilder("L" + classNameReplacements.get(clone));

            isWrapper = true;
        }

        while (match(TokenType.TOKEN_LEFT_BRACK)) {
            consume(TokenType.TOKEN_RIGHT_BRACK, "Expect \"]\" after \"[\"");

            if (!isWrapper)
                builder = new StringBuilder(StackTypes.getTypeFromClassName(builder.toString()).getInternalName());

            builder.insert(0, "[");
        }

        if (isWrapper)
            builder.append(";");

        if (!match(TokenType.TOKEN_LEFT_BRACK) && !isWrapper)
            return StackTypes.getTypeFromClassName(builder.toString()).getInternalName();

        return builder.toString();
    }

    public void block() {
        while (!check(TokenType.TOKEN_RIGHT_BRACE) && !check(TokenType.TOKEN_EOF)) {
            declaration();
        }

        consume(TokenType.TOKEN_RIGHT_BRACE, "Expect \"}\" after block");
    }

    public void declarationInMethod(String fnName) {
        modifier();

        if (match(TokenType.TOKEN_FN)) {
            nestedFnDeclaration(fnName);
        } else if (match(TokenType.TOKEN_LET)) {
            varDeclaration(false);
        } else if (match(TokenType.TOKEN_AT))
            at(false);
        else statement();
    }

    public void fnDeclaration() {
        if (type != CompilerType.CLASS && type != CompilerType.NESTED_CLASS) {
            errorAtCurrent("Methods can only be inside of classes!");
            return;
        }

        final String name;
        if (modifiersForNextElement.contains(TokenType.TOKEN_MAGIC))
            name = MAGIC_PREFIX + parseIdentifier("Expected name of magic method!");
        else {
            if (!match(TokenType.TOKEN_IDENTIFIER))
                name = "<init>";
            else name = parser.getPrevious().content();
        }

        if (modifiersForNextElement.contains(TokenType.TOKEN_INLINE)) {
            annotationsForNextElement.add(INLINE_ANNOTATION);
            modifiersForNextElement.add(TokenType.TOKEN_STATIC);
        }

        boolean constructor = name.equals("<init>");
        consume(TokenType.TOKEN_LEFT_PAREN, "Expected \"(\" after function name!");

        final Compiler compiler = new Compiler(CompilerType.METHOD, this);
        compiler.currentMethod = compiler.getCurrentClass().methods.size();

        final LabelNode start = new LabelNode();
        compiler.emit(start);

        boolean isMethodStatic = !modifiersForNextElement.isEmpty() && modifiersForNextElement.contains(TokenType.TOKEN_STATIC);
        if (!isMethodStatic) {
            final Type thisType = Type.getType("L" + getCurrentClass().name + ";");
            compiler.locals.put("this", new Local(thisType, 0, null, start));

            compiler.maxLocals += thisType.getSize();
        }

        final List<Type> parameters = new ArrayList<>();
        if (!check(TokenType.TOKEN_RIGHT_PAREN)) {
            do {
                String arg = parseIdentifier("Expected parameter name");
                String typeName = consumeType("Expected type after parameter name");
                Type type = Type.getType(typeName);

                parameters.add(type);

                compiler.locals.put(arg, new Local(type, compiler.maxLocals, null, start));
                compiler.maxLocals += type.getSize();
            } while (match(TokenType.TOKEN_COMMA));
        }

        consume(TokenType.TOKEN_RIGHT_PAREN, "Expected \")\" after parameters!");

        Type type = StackTypes.VOID;
        if (!constructor && check(TokenType.TOKEN_COLON))
            type = Type.getType(consumeType("Expected return type after \":\"!"));

        MethodNode methodNode = compiler.getCurrentMethod(true);

        methodNode.name = name;
        methodNode.access = modifiersForNextElement.stream().map(x -> x.modifier).reduce((x, y) -> x | y).orElse(0);
        if (!Modifier.isPrivate(methodNode.access))
            methodNode.access |= Opcodes.ACC_PUBLIC;
        methodNode.desc = Type.getMethodDescriptor(type, parameters.toArray(Type[]::new));
        if (constructor) {
            if (methodNode.desc.equals("()V")) {
                methodNode = getCurrentClass().methods.get(0);
                methodNode.access = modifiersForNextElement.stream().map(x -> x.modifier).reduce((x, y) -> x | y).orElse(0);
                if (!Modifier.isPrivate(methodNode.access))
                    methodNode.access |= Opcodes.ACC_PUBLIC;
            } else {
                getCurrentClass().methods.set(0, methodNode);
            }

            compiler.currentMethod = 0;
        }

        modifiersForNextElement.clear();

        methodNode.visibleAnnotations = new ArrayList<>(annotationsForNextElement);
        annotationsForNextElement.clear();

        if (!(constructor && methodNode.desc.equals("()V")))
            addMethodToCurrentClass(methodNode);

        if (constructor && !methodNode.desc.equals("()V"))
            compiler.emit(new VarInsnNode(Opcodes.ALOAD, 0),
                    new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V"));

        if (match(TokenType.TOKEN_LEFT_BRACE)) {
            while (!check(TokenType.TOKEN_RIGHT_BRACE) && !check(TokenType.TOKEN_EOF)) {
                compiler.declarationInMethod(name);
            }

            consume(TokenType.TOKEN_RIGHT_BRACE, "Expected \"}\" after method body");
        } else if (!match(TokenType.TOKEN_SEMICOLON))
            compiler.declarationInMethod(name);

        final LabelNode end = new LabelNode();
        compiler.emit(end);

        if (type.equals(StackTypes.VOID) && !constructor)
            compiler.emitVoidReturn();

        for (Map.Entry<String, Local> entry : compiler.locals.entrySet()) {
            final Local local = entry.getValue();

            methodNode.localVariables.add(new LocalVariableNode(entry.getKey(), local.type.getDescriptor(), local.signature, local.start, end, local.index));
        }
    }

    public void nestedFnDeclaration(String parentName) {
        final String name = parseIdentifier("Expect function name");
        final String jvmName = parentName + "#" + name;

        consume(TokenType.TOKEN_LEFT_PAREN, "Expect \"(\" after function name");

        final Compiler compiler = new Compiler(CompilerType.NESTED_METHOD, this);
        compiler.currentMethod = getCurrentClass().methods.size();

        final LabelNode start = new LabelNode();
        compiler.emit(start);

        if (modifiersForNextElement.contains(TokenType.TOKEN_MAGIC)) {
            error("Nested methods can not be magic!");

            return;
        }

        boolean isMethodStatic = !modifiersForNextElement.isEmpty() && modifiersForNextElement.contains(TokenType.TOKEN_STATIC);
        if (!isMethodStatic) {
            final Type thisType = Type.getType("L" + getCurrentClass().name + ";");
            compiler.locals.put("this", new Local(thisType, 0, null, start));

            compiler.maxLocals += thisType.getSize();
        }

        final List<Type> locals = new ArrayList<>();
        if (!check(TokenType.TOKEN_RIGHT_PAREN)) {
            do {
                String arg = parseIdentifier("Expect parameter name");
                String typeName = consumeType("Expect type after parameter name");
                Type type = Type.getType(typeName);

                locals.add(type);

                // ADDED STATIC TYPING -sodium
                compiler.locals.put(arg, new Local(type, compiler.maxLocals, null, start));
                compiler.maxLocals += type.getSize();
            } while (match(TokenType.TOKEN_COMMA));
        }

        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after parameters");

        final Type type = Type.getType(consumeType("Expect return type after \":\""));

        final MethodNode methodNode = compiler.getCurrentMethod(true);

        methodNode.name = jvmName;
        methodNode.access = modifiersForNextElement.stream().map(x -> x.modifier).reduce((x, y) -> x | y).orElse(0);
        if (!Modifier.isPrivate(methodNode.access))
            methodNode.access |= Opcodes.ACC_PUBLIC;
        methodNode.desc = Type.getMethodDescriptor(type, locals.toArray(Type[]::new));

        methodNode.visibleAnnotations = new ArrayList<>(annotationsForNextElement);
        annotationsForNextElement.clear();

        addMethodToCurrentClass(methodNode);

        if (match(TokenType.TOKEN_LEFT_BRACE)) {
            while (!check(TokenType.TOKEN_RIGHT_BRACE) && !check(TokenType.TOKEN_EOF)) {
                compiler.declarationInMethod(jvmName);
            }

            consume(TokenType.TOKEN_RIGHT_BRACE, "Expected \"}\" after method body");
        } else if (!match(TokenType.TOKEN_SEMICOLON))
            compiler.declarationInMethod(name);

        final LabelNode end = new LabelNode();
        compiler.emit(end);

        if (type.equals(StackTypes.VOID))
            compiler.emitVoidReturn();

        for (Map.Entry<String, Local> entry : compiler.locals.entrySet()) {
            final Local local = entry.getValue();
            if (local.start == null)
                continue;

            methodNode.localVariables.add(new LocalVariableNode(entry.getKey(), local.type.getDescriptor(), local.signature, local.start, end, local.index));
        }

        methodNameReplacements.put(name, jvmName);
    }

    public void addMethodToCurrentClass(MethodNode methodNode) {
        getCurrentClass().methods.add(methodNode);
    }

    public void varDeclaration(boolean semicolon) {
        clearStack();

        final LabelNode start = new LabelNode();
        if (type == CompilerType.METHOD || type == CompilerType.NESTED_METHOD)
            emit(start);

        boolean isFieldStatic = !modifiersForNextElement.isEmpty() && (modifiersForNextElement.contains(TokenType.TOKEN_STATIC) || modifiersForNextElement.contains(TokenType.TOKEN_INLINE));

        int oldCurrentMethod = currentMethod;
        if (type == CompilerType.CLASS || type == CompilerType.NESTED_CLASS) {
            currentMethod = isFieldStatic ? 1 : 0;

            if (!isFieldStatic)
                emitToInit(new VarInsnNode(Opcodes.ALOAD, 0));
        }

        String name = parseIdentifier("Expect variable name.");

        boolean inference = !match(TokenType.TOKEN_COLON);

        Pair<String, String> typeName = null;
        Type type = null;
        if (!inference) {
            typeName = new Pair<>(parseType("Expected type name after \":\"."), parseGenericType());
            type = Type.getType(typeName.k);
        } else {
            if (!check(TokenType.TOKEN_EQUAL)) {
                error("Expected \"=\", can not infer type");

                return;
            }
        }

        boolean setValue = match(TokenType.TOKEN_EQUAL);
        if (setValue) {
            expression();

            if (!inference)
                convertLastStackForType(type);
            else {
                type = peekLastStack();
                typeName = new Pair<>(type.getDescriptor(), "");
            }

            if (typeStack.isEmpty())
                error("Variable expression doesn't output anything");
            if (typeStack.size() != 1)
                error("Variable expression has multiple outputs: " + typeStack);
        }

        if (this.type == CompilerType.CLASS || this.type == CompilerType.NESTED_CLASS) {
            if (modifiersForNextElement.contains(TokenType.TOKEN_INLINE)) {
                annotationsForNextElement.add(INLINE_ANNOTATION);
                modifiersForNextElement.add(TokenType.TOKEN_STATIC);
            }
            FieldNode fieldNode = new FieldNode(modifiersForNextElement.stream().map(x -> x.modifier).reduce((x, y) -> x | y).orElse(0), name, type.getDescriptor(), null, null);
            if (!modifiersForNextElement.contains(TokenType.TOKEN_PRIVATE))
                fieldNode.access |= Opcodes.ACC_PUBLIC;
            fieldNode.visibleAnnotations = new ArrayList<>(annotationsForNextElement);
            annotationsForNextElement.clear();

            getCurrentClass().fields.add(fieldNode);
            if (setValue) {
                if (isFieldStatic)
                    emit(new FieldInsnNode(Opcodes.PUTSTATIC, getCurrentClass().name, name, type.getDescriptor()));
                else
                    emit(new FieldInsnNode(Opcodes.PUTFIELD, getCurrentClass().name, name, type.getDescriptor()));

                if (!modifiersForNextElement.contains(TokenType.TOKEN_INLINE)) {
                    notifyPopStack();
                    notifyPopStack();
                }
            }

            modifiersForNextElement.clear();
        } else if (this.type == CompilerType.METHOD || this.type == CompilerType.NESTED_METHOD) {
            if (!modifiersForNextElement.isEmpty()) {
                errorAtCurrent("A variable can not have modifiers!");

                return;
            }

            Local local = new Local(type, maxLocals, typeName.v.isEmpty() ? null : (typeName.k.replace(";", "") + typeName.v + ";"), start);
            maxLocals += type.getSize();
            if (setValue)
                emit(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), local.index));

            locals.put(name, local);
        }

        if (setValue)
            notifyPopStack();

        currentMethod = oldCurrentMethod;

        if (semicolon) consume(TokenType.TOKEN_SEMICOLON, "Expect \";\" after variable declaration");
        else match(TokenType.TOKEN_SEMICOLON);
    }

    private void emitToInit(AbstractInsnNode... nodes) {
        emitDelayedConstant();
        for (AbstractInsnNode node : nodes)
            getCurrentClass().methods.get(0).instructions.add(node);
    }

    private void emitToClinit(AbstractInsnNode... nodes) {
        emitDelayedConstant();
        for (AbstractInsnNode node : nodes)
            getCurrentClass().methods.get(1).instructions.add(node);
    }

    public void returnStatement() {
        if (type != CompilerType.METHOD && type != CompilerType.NESTED_METHOD) {
            error("Can't return from code that are not in methods!");
            return;
        }

        if (match(TokenType.TOKEN_SEMICOLON)) {
            emitVoidReturn();
            return;
        }

        expression();

        final Type returnType = Type.getReturnType(getCurrentMethod(true).desc);
        if (!returnType.equals(StackTypes.STRING_TYPE))
            convertLastStackForType(returnType);

        emit(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

        consume(TokenType.TOKEN_SEMICOLON, "Expected \";\" after return statement");
    }

    public void statement() {
        if (match(TokenType.TOKEN_RETURN))
            returnStatement();
        else if (match(TokenType.TOKEN_LEFT_BRACE))
            block();
        else if (match(TokenType.TOKEN_WHILE))
            whileStatement();
        else if (match(TokenType.TOKEN_IF))
            ifStatement();
        else if (match(TokenType.TOKEN_FOR))
            forStatement();
        else
            expressionStatement(false);
    }

    private Pair<AbstractInsnNode[], Object> captureInstructions(Function<Compiler, Object> consumer) {
        final Compiler compiler = new Compiler(CompilerType.METHOD, this);
        compiler.currentMethod = getCurrentClass().methods.size();
        compiler.addMethodToCurrentClass(compiler.getCurrentMethod(true));

        compiler.locals = locals;

        final Object result = consumer.apply(compiler);

        AbstractInsnNode[] insnNodes = compiler.getCurrentMethod(true).instructions.toArray();

        compiler.getCurrentClass().methods.remove(compiler.getCurrentMethod(true));

        typeStack.addAll(compiler.typeStack);

        return new Pair<>(insnNodes, result);
    }

    private void captureInstructions(Consumer<Compiler> consumer, boolean addToStack) {
        final Compiler compiler = new Compiler(CompilerType.METHOD, this);
        compiler.getCurrentClass().methods.add(compiler.getCurrentMethod(true));
        compiler.type = type;
        compiler.currentMethod = 0;
        compiler.locals = locals;

        consumer.accept(compiler);

        compiler.getCurrentClass().methods.remove(compiler.getCurrentMethod(true));

        if (addToStack)
            typeStack.addAll(compiler.typeStack);
    }

    public void foreachStatement() {
        varDeclaration(false);
    }

    public void forStatement() {
        if (match(TokenType.TOKEN_LESS)) {
            foreachStatement();

            return;
        }

        if (match(TokenType.TOKEN_LET))
            varDeclaration(true);
        else consume(TokenType.TOKEN_SEMICOLON, "Expected \";\"");

        LabelNode l0 = new LabelNode();
        LabelNode l1 = new LabelNode();
        emit(l0);

        expression();
        DelayedInstruction delayedInstruction = this.delayedInstruction;
        System.out.println(delayedInstruction);
        this.delayedInstruction = null;
        if (delayedInstruction != null)
            delayedInstruction.emitJump(this, l1);

        consume(TokenType.TOKEN_SEMICOLON, "Expect \";\"");
        if (!StackTypes.isTypeStackBoolean(notifyPopStack())) {
            error("Last stack is not a boolean!");

            return;
        }

        AbstractInsnNode[] nodes = captureInstructions(compiler -> {
            compiler.expression();
            return null;
        }).k;
        clearStack();

        if (match(TokenType.TOKEN_LEFT_BRACE))
            block();
        else declaration();

        emit(nodes);
        emit(new JumpInsnNode(Opcodes.GOTO, l0));
        emit(l1);
    }

    public void ifStatement() {
        expression();
        if (!StackTypes.isTypeStackBoolean(peekLastStack())) {
            error("Last stack is not a boolean!");

            return;
        }

        LabelNode labelNode = new LabelNode();
        LabelNode end = new LabelNode();
        emitIfEq(labelNode);

        if (match(TokenType.TOKEN_LEFT_BRACE))
            block();
        else declaration();

        emit(new JumpInsnNode(Opcodes.GOTO, end));
        emit(labelNode);

        if (match(TokenType.TOKEN_ELSE))
            if (match(TokenType.TOKEN_LEFT_BRACE))
                block();
            else declaration();

        emit(end);
    }

    public void classDeclaration() {
        consume(TokenType.TOKEN_IDENTIFIER, "Expect class name");
        final String className = parser.getPrevious().content();

        type = CompilerType.CLASS;

        ClassNode classNode = new ClassNode();

        boolean definedSuperclass = match(TokenType.TOKEN_COLON);
        if (definedSuperclass)
            classNode.superName = parseType("Expect superclass name");

        consume(TokenType.TOKEN_LEFT_BRACE, "Expected \"{\" before class body");

        classNode.version = Opcodes.V1_8;
        classNode.access = modifiersForNextElement.stream().map(x -> x.modifier).reduce((x, y) -> x | y).orElse(0);
        if (!Modifier.isPrivate(classNode.access))
            classNode.access |= Opcodes.ACC_PUBLIC;
        classNode.name = (filePackage.replace(".", "/") + "/%s").formatted(className);
        if (!definedSuperclass)
            classNode.superName = "java/lang/Object";

        if (modifiersForNextElement.contains(TokenType.TOKEN_MAGIC)) {
            error("Classes can not be magic!");

            return;
        }

        modifiersForNextElement.clear();

        classNode.visibleAnnotations = new ArrayList<>(annotationsForNextElement);
        annotationsForNextElement.clear();

        classes.put(className, classNode);

        currentClass = className;
        currentMethod = 1;

        addMethodToCurrentClass(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));
        emitToInit(new VarInsnNode(Opcodes.ALOAD, 0),
                new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));

        addMethodToCurrentClass(new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null));

        while (!check(TokenType.TOKEN_RIGHT_BRACE) && !check(TokenType.TOKEN_EOF)) {
            modifier();

            if (match(TokenType.TOKEN_FN)) {
                fnDeclaration();
            } else if (match(TokenType.TOKEN_LET)) {
                varDeclaration(false);
            } else if (match(TokenType.TOKEN_CLASS)) {
                nestedClassDeclaration();
            } else if (match(TokenType.TOKEN_AT))
                at(false);
        }

        consume(TokenType.TOKEN_RIGHT_BRACE, "Expected \"}\" after class body");
    }

    public void nestedClassDeclaration() {
        final Compiler compiler = new Compiler(CompilerType.NESTED_CLASS, this);

        compiler.consume(TokenType.TOKEN_IDENTIFIER, "Expect class name");
        final String className = compiler.parser.getPrevious().content();

        final String innerClassName = (filePackage.replace(".", "/") + "/%s").formatted(currentClass + "$" + className);

        ClassNode classNode = new ClassNode();

        if (compiler.match(TokenType.TOKEN_COLON))
            classNode.superName = parseType("Expect superclass name");

        compiler.consume(TokenType.TOKEN_LEFT_BRACE, "Expected \"{\" before class body");

        classNode.version = Opcodes.V1_8;
        classNode.access = modifiersForNextElement.stream().map(x -> x.modifier).reduce((x, y) -> x | y).orElse(0);
        if (!Modifier.isPrivate(classNode.access))
            classNode.access |= Opcodes.ACC_PUBLIC;
        classNode.name = (filePackage.replace(".", "/") + "/%s").formatted(className);
        classNode.superName = "java/lang/Object";

        if (modifiersForNextElement.contains(TokenType.TOKEN_MAGIC)) {
            error("Classes can not be magic!");

            return;
        }

        modifiersForNextElement.clear();

        classNode.visibleAnnotations = new ArrayList<>(annotationsForNextElement);
        annotationsForNextElement.clear();

        if (compiler.match(TokenType.TOKEN_COLON))
            classNode.superName = parseType("Expect superclass name");

        getCurrentClass().visitInnerClass(innerClassName, getCurrentClass().name, className, classNode.access);
        classNode.visitOuterClass(currentClass, null, null);

        classes.put(currentClass + "$" + className, classNode);

        compiler.currentClass = currentClass + "$" + className;
        compiler.currentMethod = 1;

        compiler.addMethodToCurrentClass(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));
        compiler.emitToInit(new VarInsnNode(Opcodes.ALOAD, 0),
                new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V"));

        compiler.addMethodToCurrentClass(new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null));

        getCurrentClass().visitNestMember(innerClassName);

        while (!compiler.check(TokenType.TOKEN_RIGHT_BRACE) && !compiler.check(TokenType.TOKEN_EOF)) {
            modifier();

            if (match(TokenType.TOKEN_FN)) {
                compiler.fnDeclaration();
            } else if (match(TokenType.TOKEN_LET)) {
                compiler.varDeclaration(false);
            } else if (match(TokenType.TOKEN_CLASS)) {
                compiler.nestedClassDeclaration();
            } else if (match(TokenType.TOKEN_AT))
                at(false);
        }

        compiler.consume(TokenType.TOKEN_RIGHT_BRACE, "Expected \"}\" after class body");
    }

    public void declaration() {
        modifier();

        if (match(TokenType.TOKEN_CLASS)) {
            classDeclaration();
        } else if (match(TokenType.TOKEN_FN)) {
            fnDeclaration();
        } else if (match(TokenType.TOKEN_LET)) {
            varDeclaration(false);
        } else if (match(TokenType.TOKEN_RIGHT_BRACE)) {
            // TODO: This should soft-lock the program cause we don't consume the bracket
            error("Closing file too soon");
        } else
            statement();

        if (parser.isPanicMode()) synchronize();

        clearStack();
    }

    public void whileStatement() {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();

        emit(start);

        expression();
        if (!StackTypes.isTypeStackBoolean(peekLastStack()))
            return;

        emitIfEq(end);
        if (match(TokenType.TOKEN_LEFT_BRACE))
            block();
        else declaration();
        emit(new JumpInsnNode(Opcodes.GOTO, start));

        emit(end);
    }

    public void synchronize() {
        parser.setPanicMode(false);

        while (parser.getCurrent().type() != TokenType.TOKEN_EOF) {
            if (parser.getPrevious().type() == TokenType.TOKEN_SEMICOLON) return;
            switch (parser.getCurrent().type()) {
                case TOKEN_CLASS, TOKEN_FN, TOKEN_LET, TOKEN_FOR, TOKEN_IF, TOKEN_WHILE, TOKEN_RETURN -> {
                    return;
                }

                default -> {}
            }

            advance();
        }
    }

    public void compile(String source) {
        scanner.source = source;

        parser.setHadError(false);
        parser.setPanicMode(false);

        advance();

        while (!match(TokenType.TOKEN_EOF)) {
            declaration();
        }

        for (MethodNode node : getCurrentClass().methods)
            if (Objects.equals(node.name, "<init>") ||
                    Objects.equals(node.name, "<clinit>"))
                node.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    public byte[] compileToByteArray(String source) {
        compile(source);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                if ("java/lang/Object".equals(type1) ||
                        "java/lang/Object".equals(type2))
                    return "java/lang/Object";
                return super.getCommonSuperClass(type1, type2);
            }
        };
        getCurrentClass().accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                // The InstructionAdapter correct some op codes to more optimized alternatives.
                // TODO: Make compiler emit optimized variants directly.
                return new InstructionAdapter(super.visitMethod(access, name, descriptor, signature, exceptions));
            }
        });

        return cw.toByteArray();
    }

    public void compileToEphemeralRunner(String source) {
        final byte[] bytes = compileToByteArray(source);

        runner.classes.put(getCurrentClass().name.replace("/", "."), bytes);
    }

    public static void compileAndRun(String source, String... arguments) throws InvocationTargetException {
        EphemeralRunner runner = new EphemeralRunner(Thread.currentThread().getContextClassLoader());

        new Compiler("zip.sodium.generated", "Main", runner).compileToEphemeralRunner(source);

        runner.run("zip.sodium.generated.Main", arguments);
    }
}
