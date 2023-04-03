package xyz.sodiumdev.jbasalt.compiler;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.BiConsumer;

public class BuiltInFunctionRegistry {
    public record BuiltInFunction(BiConsumer<Compiler, Boolean> append, String name, Type description) {
        public BuiltInFunction(BiConsumer<Compiler, Boolean> append, String name, String description) {
            this(append, name, Type.getType(description));
        }
    }

    public static List<BuiltInFunction> builtInFunctions = Arrays.asList(
            new BuiltInFunction((compiler, aBoolean) -> {
                compiler.emit(new FieldInsnNode(Opcodes.GETSTATIC,
                                "java/lang/System", "out", "Ljava/io/PrintStream;"),
                        new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "java/io/PrintStream", "println", "()V"));
            }, "println", "()V"),
            new BuiltInFunction((compiler, aBoolean) -> {
                compiler.emit(new FieldInsnNode(Opcodes.GETSTATIC,
                                "java/lang/System", "out", "Ljava/io/PrintStream;"),
                        new InsnNode(Opcodes.SWAP),
                        new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "java/io/PrintStream", "println", "(Ljava/lang/String;)V"));
                compiler.notifyPopStack();
            }, "println", "(Ljava/lang/String;)V"),
            new BuiltInFunction((compiler, aBoolean) -> {
                compiler.emit(new InsnNode(Opcodes.ICONST_0),
                        new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/lang/System", "exit", "(I)V"));
            }, "exit", "()V"),
            new BuiltInFunction((compiler, aBoolean) -> {
                compiler.emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/System", "exit", "(I)V"));
                compiler.notifyPopStack();
            }, "exit", "(I)V"),
            new BuiltInFunction((compiler, aBoolean) -> {
                LabelNode labelNode = new LabelNode();
                compiler.emitIfNe(labelNode);
                compiler.emit(new TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError"),
                        new InsnNode(Opcodes.DUP),
                        new MethodInsnNode(Opcodes.INVOKESPECIAL,
                                "java/lang/AssertionError", "<init>", "()V"),
                        new InsnNode(Opcodes.ATHROW), labelNode);
            }, "assert", "(Z)V"));

    @SuppressWarnings("unchecked")
    private static final HashMap<String, BuiltInFunction>[] computedBuiltInFunctions =
            (HashMap<String, BuiltInFunction>[]) new HashMap[]{new HashMap<>(), new HashMap<>()};

    static {
        for (BuiltInFunction builtInFunction : builtInFunctions) {
            computedBuiltInFunctions[builtInFunction.description.getArgumentTypes().length]
                    .put(builtInFunction.name, builtInFunction);
        }
    }

    public static BuiltInFunction findBuiltInFunction(String name, int args) {
        if (args >= computedBuiltInFunctions.length) return null;
        return computedBuiltInFunctions[args].get(name);
    }
}
