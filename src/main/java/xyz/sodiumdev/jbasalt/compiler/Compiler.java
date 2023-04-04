package xyz.sodiumdev.jbasalt.compiler;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.*;
import xyz.sodiumdev.jbasalt.Parser;
import xyz.sodiumdev.jbasalt.Scanner;
import xyz.sodiumdev.jbasalt.token.Token;
import xyz.sodiumdev.jbasalt.token.TokenType;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiConsumer;

public class Compiler {
    private final CompilerType type;
    public final Parser parser;
    public final Scanner scanner;

    private static final Set<BuiltInModuleRegistry.BuiltInModule> importedModules = new HashSet<>();

    public static Map<String, ClassNode> classes = new HashMap<>();

    public String currentClass;
    public int currentMethod;

    public static final Map<TokenType, ParseRule> rules = new HashMap<>();

    public Map<String, Local> locals = new HashMap<>();
    public DelayedInstruction delayedInstruction;
    public int maxLocals = 0;

    public record Local(Type type, int index) {}
    public record Pair<K, V>(K k, V v) {}

    /**
     * Current stack.
     * - {@link #clearStack()}
     * - {@link #peekLastStack()}
     * - {@link #notifyPushStack(Type)}
     * - {@link #notifyPopStack()}
     */
    public LinkedList<Type> currentStack = new LinkedList<>();

    static {
        rules.put(TokenType.TOKEN_RIGHT_PAREN, ParseRule.NULL);
        rules.put(TokenType.TOKEN_LEFT_BRACE, ParseRule.NULL);
        rules.put(TokenType.TOKEN_RIGHT_BRACE, ParseRule.NULL);
        rules.put(TokenType.TOKEN_COMMA, ParseRule.NULL);
        rules.put(TokenType.TOKEN_FUN, ParseRule.NULL);
        rules.put(TokenType.TOKEN_SEMICOLON, ParseRule.NULL);
        rules.put(TokenType.TOKEN_LET, ParseRule.NULL);
        rules.put(TokenType.TOKEN_ERROR, ParseRule.NULL);
        rules.put(TokenType.TOKEN_EOF, ParseRule.NULL);
        rules.put(TokenType.TOKEN_RETURN, ParseRule.NULL);
        rules.put(TokenType.TOKEN_CLASS, ParseRule.NULL);
        rules.put(TokenType.TOKEN_WHILE, ParseRule.NULL);

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
        rules.put(TokenType.TOKEN_LESS, new ParseRule(null, Compiler::binary, Precedence.PREC_COMPARISON));
        rules.put(TokenType.TOKEN_LESS_EQUAL, new ParseRule(null, Compiler::binary, Precedence.PREC_COMPARISON));

        rules.put(TokenType.TOKEN_IDENTIFIER, new ParseRule(Compiler::variable, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_NUMBER, new ParseRule(Compiler::number, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_STRING, new ParseRule(Compiler::string, null, Precedence.PREC_NONE));

        rules.put(TokenType.TOKEN_AND, new ParseRule(null, Compiler::and, Precedence.PREC_AND));
        rules.put(TokenType.TOKEN_OR, new ParseRule(null, Compiler::or, Precedence.PREC_OR));

        rules.put(TokenType.TOKEN_FALSE, new ParseRule(Compiler::literal, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_TRUE, new ParseRule(Compiler::literal, null, Precedence.PREC_NONE));
        rules.put(TokenType.TOKEN_NIL, new ParseRule(Compiler::literal, null, Precedence.PREC_NONE));

        rules.put(TokenType.IMPORT, new ParseRule(Compiler::import_, null, Precedence.PREC_NONE));
    }

    private enum CompilerType {
        MAIN, FUN, NESTED
    }

    public Compiler() {
        type = CompilerType.MAIN;
        parser = new Parser();
        scanner = new Scanner();

        ClassNode mainClass = new ClassNode();

        mainClass.version = Opcodes.V1_8;
        mainClass.access = Opcodes.ACC_PUBLIC;
        mainClass.signature = "Lxyz/sodiumdev/asm/Generated;";
        mainClass.name = "xyz/sodiumdev/asm/Generated";
        mainClass.superName = "java/lang/Object";

        MethodNode mainMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);

        currentClass = "Generated";
        currentMethod = 0;
        classes.put(currentClass, mainClass);

        addMethodToCurrentClass(mainMethod);
    }

    @SuppressWarnings("CopyConstructorMissesField")
    private Compiler(Compiler parent) {
        type = CompilerType.FUN;
        parser = parent.parser;
        scanner = parent.scanner;

        currentClass = parent.currentClass;
        currentMethod = parent.currentMethod + 1;
    }

    private Compiler(Compiler parent, Void ignored) {
        type = CompilerType.NESTED;
        parser = parent.parser;
        scanner = parent.scanner;

        currentClass = parent.currentClass;

        locals.putAll(parent.locals);
        maxLocals = parent.maxLocals;
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
        return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, null, null, null, null);
    }

    public MethodNode getCurrentMethod(boolean create) {
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

        System.err.printf("[line %d] Error", token.line());

        if (token.type() == TokenType.TOKEN_EOF) {
            System.err.print(" at end");
        } else if (token.type() != TokenType.TOKEN_ERROR) {
            System.err.printf(" at \"%s\"", token.content());
        }

        System.err.printf(" -> %s\n", message);
        parser.setHadError(true);
    }

    public void error(String message) {
        errorAt(parser.getPrevious(), message);
    }

    public void errorAtCurrent(String message) {
        errorAt(parser.getCurrent(), message);
    }

    public void advance() {
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

    public boolean check(TokenType type) {
        return parser.getCurrent().type() == type;
    }

    public boolean match(TokenType type) {
        if (!check(type))
            return false;

        advance();
        return true;
    }

    public void emit(AbstractInsnNode... nodes) {
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

    public void emitLoop() {
        // TODO: DEAL WITH THIS SHIT LATER
    }

    public void expression() {
        parsePrecedence(Precedence.PREC_ASSIGNMENT);
    }

    public void expressionStatement() {
        expression();
        optionalConsume(TokenType.TOKEN_SEMICOLON);
        clearStack();
    }

    public void parsePrecedence(Precedence precedence) {
        advance();
        BiConsumer<Compiler, Boolean> prefixRule = getRule(parser.getPrevious().type()).prefixRule();
        if (prefixRule == null) {
            error("Expect expression");
            return;
        }

        boolean canAssign = precedence.ordinal() <= Precedence.PREC_ASSIGNMENT.ordinal();
        prefixRule.accept(this, canAssign);

        TokenType currentTokenType;
        while ((currentTokenType = parser.getCurrent().type()) != TokenType.TOKEN_RIGHT_BRACE &&
                precedence.ordinal() <= getRule(currentTokenType).precedence().ordinal()) {
            advance();
            BiConsumer<Compiler, Boolean> infixRule = getRule(parser.getPrevious().type()).infixRule();

            infixRule.accept(this, canAssign);
        }

        if (canAssign && match(TokenType.TOKEN_EQUAL))
            error("Invalid assignment target");
    }

    public ParseRule getRule(TokenType type) {
        return rules.get(type);
    }

    public void binary(boolean canAssign) {
        TokenType op = parser.getPrevious().type();
        ParseRule rule = getRule(op);
        parsePrecedence(Precedence.values()[rule.precedence().ordinal() + 1]);

        Type previousLastStack = peekPreviousLastStack();
        Type tmpLastStack;
        switch (op) { // We allow doing addition of integer with objects for initial version.
            case TOKEN_BANG_EQUAL, TOKEN_EQUAL_EQUAL,
                    TOKEN_PLUS, TOKEN_MINUS, TOKEN_STAR, TOKEN_SLASH,
                    // Special tokens that need to be inverted for code consistency
                    TOKEN_GREATER, TOKEN_GREATER_EQUAL, TOKEN_LESS, TOKEN_LESS_EQUAL -> {
                if ((StackTypes.OBJECT_TYPE.equals(previousLastStack) &&
                        StackTypes.INT.equals(tmpLastStack = peekLastStack()))) {
                    previousLastStack = tmpLastStack;
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
        if (previousLastStack == null || StackTypes.isTypeStackNumber(previousLastStack)) {
            if (previousLastStack == null) {
                error("Unable to compute last stack");
            }
            switch (op) {
                case TOKEN_BANG_EQUAL -> emitDelayed(DelayedInstruction.INT_NOT_EQUAL);
                case TOKEN_EQUAL_EQUAL -> emitDelayed(DelayedInstruction.INT_EQUAL);
                case TOKEN_GREATER -> emitDelayed(DelayedInstruction.INT_GREATER);
                case TOKEN_GREATER_EQUAL -> emitDelayed(DelayedInstruction.INT_GREATER_EQUAL);
                case TOKEN_LESS -> emitDelayed(DelayedInstruction.INT_LESS);
                case TOKEN_LESS_EQUAL -> emitDelayed(DelayedInstruction.INT_LESS_EQUAL);

                case TOKEN_PLUS -> {
                    convertLastStackToDouble();
                    emit(new InsnNode(Opcodes.DADD));
                }
                case TOKEN_MINUS -> {
                    convertLastStackToDouble();
                    emit(new InsnNode(Opcodes.DSUB));
                }
                case TOKEN_STAR -> {
                    convertLastStackToDouble();
                    emit(new InsnNode(Opcodes.DMUL));
                }
                case TOKEN_SLASH -> {
                    convertLastStackToDouble();
                    emit(new InsnNode(Opcodes.DDIV));
                }
                default -> throw op.makeInvalidTokenException(this, "Cannot use %s token with numbers!");
            }
            if (delayedInstruction == null)
                notifyPopStack();
        } else if (StackTypes.isTypeStackObject(previousLastStack)) {
            switch (op) {
                case TOKEN_BANG_EQUAL -> emitDelayed(DelayedInstruction.OBJECT_NOT_EQUAL);
                case TOKEN_EQUAL_EQUAL -> emitDelayed(DelayedInstruction.OBJECT_EQUAL);
                case TOKEN_PLUS -> {
                    if (StackTypes.isTypeStackNumber(previousLastStack)) {
                        emit(new InsnNode(Opcodes.DADD));

                        return;
                    }

                    if (!StackTypes.isTypeStackString(previousLastStack))
                        throw op.makeInvalidTokenException(this, "Cannot use %s token with non-string objects!");

                    convertLastStackToString();
                    emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                            "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
                }
                default -> throw op.makeInvalidTokenException(this, "Cannot use %s token with objects!");
            }
            if (delayedInstruction == null)
                notifyPopStack();
        }
    }

    public void literal(boolean canAssign) {
        switch (parser.getPrevious().type()) {
            case TOKEN_FALSE -> emitBoolean(false);
            case TOKEN_TRUE -> emitBoolean(true);
            case TOKEN_NIL -> emitNull();
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

    public void grouping(boolean canAssign) {
        expression();
        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after expression");
    }

    public Pair<Integer, List<Type>> argumentList() {
        List<Type> types = new ArrayList<>();
        if (!check(TokenType.TOKEN_RIGHT_PAREN)) {
            do {
                expression();
                types.add(peekLastStack());
            } while (match(TokenType.TOKEN_COMMA));
        }

        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after arguments");

        return new Pair<>(types.size(), types);
    }

    public void call(boolean canAssign) {
        // emit(new MethodInsnNode(Opcodes.INVOKESTATIC, getCurrentClass().name, "xabc", Type.getMethodDescriptor(StackTypes.OBJECT_TYPE, argumentList().v.toArray(Type[]::new))));
    }

    public void dot(boolean canAssign) {
        consume(TokenType.TOKEN_IDENTIFIER, "Expect property name after \".\"");
        String afterDot = parser.getPrevious().content();

        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            expression();
            emit(new FieldInsnNode(Opcodes.PUTFIELD, "", afterDot, peekLastStack().getDescriptor()));
        } else {
            emit(new FieldInsnNode(Opcodes.GETFIELD, "", afterDot, peekLastStack().getDescriptor()));
        }
    }

    public void variable(boolean canAssign) {
        final String identifier = parser.getPrevious().content();

        Local local = locals.get(identifier);

        if (canAssign && match(TokenType.TOKEN_EQUAL)) {
            expression();

            if (local == null) {
                errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                return;
            }

            emit(new VarInsnNode(Opcodes.ASTORE, local.index));
            notifyPopStack(); // POP Stack
        } else {
            if (match(TokenType.TOKEN_RIGHT_BRACE) &&
                    identifier.equals(getCurrentMethod().name)) {
                // TODO: Fix the bug of why the code reach here
                // The return is needed to avoid crashes...
                return;
            }

            if (!match(TokenType.TOKEN_LEFT_PAREN)) {
                if (importedModules.stream().anyMatch(x -> identifier.equals(x.name()))) {
                    if (!match(TokenType.TOKEN_DOT)) return;

                    BuiltInModuleRegistry.BuiltInModule module = importedModules.stream().filter(x -> identifier.equals(x.name())).findFirst().get();
                    consume(TokenType.TOKEN_IDENTIFIER, "Expect identifier after \".\"");

                    final String method = parser.getPrevious().content();

                    if (!match(TokenType.TOKEN_LEFT_PAREN)) {
                        BuiltInModuleRegistry.BuiltInModule.BuiltInField field = module.findField(method);
                        if (field == null) {
                            errorAtCurrent("Field \"" + identifier + "\" does not exist");
                            return;
                        }

                        field.append().accept(this);

                        return;
                    }

                    final int arity = argumentList().k;
                    if (arity < currentStack.size()) {
                        error("Calling method with " + arity + " argument, " +
                                "but stack only has " + currentStack.size());
                    }
                    BuiltInFunctionRegistry.BuiltInFunction builtInFunction = module.findBuiltInFunction(method, arity);

                    if (builtInFunction != null) {
                        Type[] types = builtInFunction.description().getArgumentTypes();
                        if (types.length != 0)
                            convertLastStackForType(types[types.length - 1]);

                        builtInFunction.append().accept(this, canAssign);

                        return;
                    }

                    if (local == null)
                        errorAtCurrent("Method \"" + identifier + "\" does not exist");
                }
                if (getCurrentClass().methods.stream().noneMatch(m -> identifier.equals(m.name))) {
                    if (local == null) {
                        errorAtCurrent("Variable \"" + identifier + "\" does not exist");
                        return;
                    }

                    emit(new VarInsnNode(local.type.getOpcode(Opcodes.ILOAD), local.index));
                    notifyPushStack(local.type); // PUSH Stack
                    return;
                }

                return;
            }

            final int arity = argumentList().k;
            if (arity < currentStack.size()) {
                error("Calling method with " + arity + " argument, " +
                        "but stack only has " + currentStack.size());
            }
            for (MethodNode m : getCurrentClass().methods) {
                if (!Modifier.isStatic(m.access))
                    continue;
                if (!identifier.equals(m.name))
                    continue;

                Type[] types = Type.getArgumentTypes(m.desc);
                if (arity != types.length) continue;
                if (types.length != 0)
                    convertLastStackForType(types[types.length - 1]);

                for (Type ignored : types)
                    notifyPopStack();

                if ("main".equals(m.name))
                    emit(new MethodInsnNode(Opcodes.INVOKESTATIC, getCurrentClass().name, "basalt$main", m.desc));
                else
                    emit(new MethodInsnNode(Opcodes.INVOKESTATIC, getCurrentClass().name, m.name, m.desc));

                notifyPushStack(Type.getReturnType(m.desc));

                return;
            }

            BuiltInFunctionRegistry.BuiltInFunction builtInFunction =
                    BuiltInFunctionRegistry.findBuiltInFunction(identifier, arity);

            if (builtInFunction != null) {
                Type[] types = builtInFunction.description().getArgumentTypes();
                if (types.length != 0)
                    convertLastStackForType(types[types.length - 1]);

                builtInFunction.append().accept(this, canAssign);

                return;
            }

            if (local == null)
                errorAtCurrent("Method \"" + identifier + "\" does not exist");
        }
    }

    public void number(boolean canAssign) {
        emitConstant(Double.parseDouble(parser.getPrevious().content()));
    }

    public void string(boolean canAssign) {
        String content = parser.getPrevious().content();
        emitConstant(content.substring(1, content.length() - 1));
    }

    public void and(boolean canAssign) {

    }

    public void import_(boolean canAssign) {
        consume(TokenType.TOKEN_IDENTIFIER, "Expect module after \"import\"");
        final String type = parser.getPrevious().content();

        System.out.println("Importing \"" + type + "\"");
        BuiltInModuleRegistry.BuiltInModule module = BuiltInModuleRegistry.findBuiltInModule(type);

        importedModules.add(module);
    }

    public void or(boolean canAssign) {
        parsePrecedence(Precedence.PREC_OR);
    }

    public void emitNullReturn() {
        emitNull();
        emit(new InsnNode(Opcodes.ARETURN));
    }

    private void emitReturn() {
        emit(new InsnNode(Opcodes.RETURN));
    }

    public Type peekPreviousLastStack() {
        int size = currentStack.size();
        return size < 2 ? null : currentStack.get(size - 2);
    }

    public Type peekLastStack() {
        return currentStack.isEmpty() ? null : currentStack.getLast();
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
        } else if (StackTypes.OBJECT_TYPE.equals(type)) {
            convertLastStackToObject();
        } else if (StackTypes.INTEGER_TYPE.equals(type)) {
            convertLastStackToInteger();
        }
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
        if (StackTypes.isTypeStackIntegerType(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D"));
            notifyReplaceLastStack(StackTypes.DOUBLE);
            return;
        }
        if (StackTypes.isTypeStackPureObject(lastStack)) {
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "double"));
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
            emit(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
            emit(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I"));
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
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
            notifyReplaceLastStack(StackTypes.INTEGER_TYPE);
            return;
        }
        if (StackTypes.isTypeStackDouble(lastStack)) {
            emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
            notifyReplaceLastStack(StackTypes.DOUBLE_TYPE);
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
        currentStack.add(type);
    }

    public Type notifyPopStack() {
        try {
            return currentStack.removeLast();
        } catch (NoSuchElementException e) {
            error("Lost track of stack?");
            e.printStackTrace();
        }

        return null;
    }

    public String parseIdentifier(String error) {
        consume(TokenType.TOKEN_IDENTIFIER, error);

        return parser.getPrevious().content();
    }

    public String parseType(String error) {
        StringBuilder builder = new StringBuilder();

        do {
            consume(TokenType.TOKEN_IDENTIFIER, error);

            builder.append(parser.getPrevious().content());

            if (check(TokenType.TOKEN_DOT))
                builder.append(".");
        } while (match(TokenType.TOKEN_DOT));

        return builder.toString();
    }

    public void block() {
        while (!check(TokenType.TOKEN_RIGHT_BRACE) && !check(TokenType.TOKEN_EOF)) {
            declaration();
        }

        consume(TokenType.TOKEN_RIGHT_BRACE, "Expect \"}\" after block");
    }

    public void funDeclaration() {
        String name = parseIdentifier("Expect function name");

        if (name.equals("main"))
            name = "basalt$main";

        consume(TokenType.TOKEN_LEFT_PAREN, "Expect \"(\" after function name");

        int arity = 0;

        final Compiler compiler = new Compiler(this);
        compiler.currentMethod = getCurrentClass().methods.size();

        final List<Type> locals = new ArrayList<>();
        if (!check(TokenType.TOKEN_RIGHT_PAREN)) {
            do {
                String arg = parseIdentifier("Expect parameter name");
                String typeName = consumeType("Expect type after parameter name");
                Type type = StackTypes.getTypeFromClassName(typeName);

                locals.add(type);

                // ADDED STATIC TYPING -sodium
                compiler.locals.put(arg, new Local(type, arity));
                arity++;
            } while (match(TokenType.TOKEN_COMMA));
        }
        compiler.maxLocals = arity;

        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \")\" after parameters");

        String type = consumeType("Expect return type after \":\"");

        final MethodNode methodNode = compiler.getCurrentMethod(true);

        methodNode.name = name;
        methodNode.desc = Type.getMethodDescriptor(StackTypes.getTypeFromClassName(type), locals.toArray(Type[]::new));

        addMethodToCurrentClass(methodNode);

        consume(TokenType.TOKEN_LEFT_BRACE, "Expect \"{\" before function body");

        compiler.block();

        compiler.emitReturn();

        // name/desc was defined here before
    }

    public void addMethodToCurrentClass(MethodNode methodNode) {
        getCurrentClass().methods.removeIf(x -> Objects.equals(methodNode.name, x.name) && Objects.equals(methodNode.desc, x.desc));

        getCurrentClass().methods.add(methodNode);
    }

    public void optionalConsume(TokenType type) {
        match(type);
    }

    public void varDeclaration() {
        clearStack(); // When declaring a var, stack must be empty for now.

        String name = parseIdentifier("Expect variable name.");

        String typeName = consumeType("Expect type after parameter name");

        if (match(TokenType.TOKEN_EQUAL)) {
            expression();
        } else emitNull();

        if (currentStack.isEmpty())
            error("Variable expression doesn't output anything");

        Type lastStack = peekLastStack();
        if (currentStack.size() != 1)
            error("Variable expression has multiple outputs: " + currentStack);

        Type type = StackTypes.getTypeFromClassName(typeName);

        Local local = new Local(lastStack == null ?
                type : lastStack, maxLocals);
        maxLocals += local.type.getSize();
        emit(new VarInsnNode(local.type.getOpcode(Opcodes.ISTORE), local.index));
        notifyPopStack();

        locals.put(name, local);

        optionalConsume(TokenType.TOKEN_SEMICOLON);
    }

    public void returnStatement() {
        if (type == CompilerType.MAIN)
            error("Can't return from top-level code");

        if (match(TokenType.TOKEN_SEMICOLON))
            emitNullReturn();
        else {
            expression();

            Type returnType = Type.getReturnType(getCurrentMethod().desc);

            if (StackTypes.isTypeStackInt(returnType))
                emit(new InsnNode(Opcodes.IRETURN));
            if (StackTypes.isTypeStackFloat(returnType))
                emit(new InsnNode(Opcodes.FRETURN));
            if (StackTypes.isTypeStackLong(returnType))
                emit(new InsnNode(Opcodes.LRETURN));
            if (StackTypes.isTypeStackDouble(returnType))
                emit(new InsnNode(Opcodes.DRETURN));
            if (StackTypes.isTypeStackObject(returnType))
                emit(new InsnNode(Opcodes.ARETURN));

            consume(TokenType.TOKEN_SEMICOLON, "Expect \";\" after \"return\"");
        }
    }

    public void statement() {
        if (match(TokenType.TOKEN_RETURN))
            returnStatement();
        else if (match(TokenType.TOKEN_LEFT_BRACE))
            block();
        else if (match(TokenType.TOKEN_WHILE))
            whileStatement();
        else {
            expressionStatement();
        }
    }

    public void classDeclaration() {
        consume(TokenType.TOKEN_IDENTIFIER, "Expect class name");
        String className = parser.getPrevious().content();

        ClassNode classNode = new ClassNode();

        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.signature = "Lxyz/sodiumdev/asm/%s;".formatted(className);
        classNode.name = "xyz/sodiumdev/asm/%s".formatted(className);
        classNode.superName = "java/lang/Object";

        if (match(TokenType.TOKEN_COLON)) {
            consume(TokenType.TOKEN_IDENTIFIER, "Expect superclass name");

            String superClassName = parser.getPrevious().content();

            if (className.equals(superClassName)) {
                error("A class can't inherit from itself");
            }

            classNode.superName = "xyz/sodiumdev/asm/%s".formatted(superClassName);
        }

        classes.put(className, classNode);
    }

    public void declaration() {
        if (match(TokenType.TOKEN_CLASS)) {
            classDeclaration();
        } else if (match(TokenType.TOKEN_FUN)) {
            funDeclaration();
        } else if (match(TokenType.TOKEN_LET)) {
            varDeclaration();
        } else if (match(TokenType.TOKEN_RIGHT_BRACE)) {
            // TODO This should soft-lock the program cause we don't consume the bracket
            error("Closing file too soon");
        } else {
            statement();
        }

        if (parser.isPanicMode()) synchronize();

        clearStack();
    }

    public void whileStatement() {
        consume(TokenType.TOKEN_RIGHT_PAREN, "Expect \"(\" after \"while\"");

        expression();
        Type type = peekLastStack();
        if (!StackTypes.isTypeStackBoolean(type))
            return;

        consume(TokenType.TOKEN_LEFT_PAREN, "Expect \")\" after condition");

        consume(TokenType.TOKEN_LEFT_BRACE, "Expect \"{\" before function body");

        LabelNode labelNode = new LabelNode();

        emitIfEq(labelNode);
        block();
        emit(labelNode);
    }

    public void synchronize() {
        parser.setPanicMode(false);

        while (parser.getCurrent().type() != TokenType.TOKEN_EOF) {
            if (parser.getPrevious().type() == TokenType.TOKEN_SEMICOLON) return;
            switch (parser.getCurrent().type()) {
                case TOKEN_CLASS, TOKEN_FUN, TOKEN_LET, TOKEN_FOR, TOKEN_IF, TOKEN_WHILE, TOKEN_PRINT, TOKEN_RETURN -> {
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

        if (type == CompilerType.MAIN) {
            emitReturn();

            System.out.println(getCurrentClass().methods.stream().map(x -> x.name + x.desc).toList());
        }

        clearStack();
    }

    public byte[] compileToByteArray(String source) {
        compile(source);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
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

    public EphemeralRunner compileToEphemeralRunner(String source) {
        return new EphemeralRunner(this.compileToByteArray(source), getCurrentClass().name);
    }

    public void compileAndRun(String source, String... arguments) throws ReflectiveOperationException {
        compileToEphemeralRunner(source).runMain(arguments);
    }
}
