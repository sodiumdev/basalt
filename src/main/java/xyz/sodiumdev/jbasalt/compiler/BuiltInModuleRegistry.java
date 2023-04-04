package xyz.sodiumdev.jbasalt.compiler;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class BuiltInModuleRegistry {
    public static class BuiltInModule {
        public record BuiltInField(String name, Consumer<Compiler> append) {}

        @SuppressWarnings("unchecked")
        private final Map<String, BuiltInFunctionRegistry.BuiltInFunction>[] computedBuiltInFunctions =
                (HashMap<String, BuiltInFunctionRegistry.BuiltInFunction>[]) new HashMap[]{new HashMap<>(), new HashMap<>()};
        private final Map<String, BuiltInField> computedFields = new HashMap<>();

        private final String name;
        private final BuiltInFunctionRegistry.BuiltInFunction[] functions;
        private final BuiltInField[] fields;

        public BuiltInModule(String name, BuiltInFunctionRegistry.BuiltInFunction[] functions, BuiltInField[] fields) {
            this.name = name;
            this.functions = functions;
            this.fields = fields;

            for (BuiltInFunctionRegistry.BuiltInFunction builtInFunction : functions) {
                computedBuiltInFunctions[builtInFunction.description().getArgumentTypes().length]
                        .put(builtInFunction.name(), builtInFunction);
            }

            for (BuiltInField builtInField : fields) {
                computedFields.put(builtInField.name, builtInField);
            }
        }

        public BuiltInField[] fields() {
            return fields;
        }

        public BuiltInFunctionRegistry.BuiltInFunction[] functions() {
            return functions;
        }

        public String name() {
            return name;
        }

        public BuiltInFunctionRegistry.BuiltInFunction findBuiltInFunction(String name, int args) {
            if (args >= computedBuiltInFunctions.length) return null;
            return computedBuiltInFunctions[args].get(name);
        }

        public BuiltInField findField(String name) {
            return computedFields.get(name);
        }
    }

    public static List<BuiltInModule> builtInModules = List.of(
            new BuiltInModule("math", new BuiltInFunctionRegistry.BuiltInFunction[] {
                    new BuiltInFunctionRegistry.BuiltInFunction((compiler, aBoolean) -> {
                        compiler.emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/lang/Math", "sqrt", "(D)D"));
                        compiler.notifyReplaceLastStack(StackTypes.DOUBLE);
                    }, "sqrt", "(D)D"),
                    new BuiltInFunctionRegistry.BuiltInFunction((compiler, aBoolean) -> {
                        compiler.emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/lang/Math", "sin", "(D)D"));
                        compiler.notifyReplaceLastStack(StackTypes.DOUBLE);
                    }, "sin", "(D)D"),
                    new BuiltInFunctionRegistry.BuiltInFunction((compiler, aBoolean) -> {
                        compiler.emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/lang/Math", "cos", "(D)D"));
                        compiler.notifyReplaceLastStack(StackTypes.DOUBLE);
                    }, "cos", "(D)D"),
                    new BuiltInFunctionRegistry.BuiltInFunction((compiler, aBoolean) -> {
                        compiler.emit(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "java/lang/Math", "tan", "(D)D"));
                        compiler.notifyReplaceLastStack(StackTypes.DOUBLE);
                    }, "tan", "(D)D"),
            }, new BuiltInModule.BuiltInField[] {
                    new BuiltInModule.BuiltInField("PI", (compiler) -> {
                        compiler.emitConstant(Math.PI);
                        compiler.notifyReplaceLastStack(StackTypes.DOUBLE);
                    }),
                    new BuiltInModule.BuiltInField("E", (compiler) -> {
                        compiler.emitConstant(Math.E);
                        compiler.notifyReplaceLastStack(StackTypes.DOUBLE);
                    })
            })
    );

    private static final HashMap<String, BuiltInModule> computedBuiltInModules = new HashMap<>();

    static {
        for (BuiltInModule builtInModule : builtInModules) {
            computedBuiltInModules.put(builtInModule.name, builtInModule);
        }
    }

    public static BuiltInModule findBuiltInModule(String name) {
        return computedBuiltInModules.get(name);
    }
}
