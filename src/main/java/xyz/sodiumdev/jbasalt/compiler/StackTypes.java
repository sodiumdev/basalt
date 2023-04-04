package xyz.sodiumdev.jbasalt.compiler;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

public final class StackTypes {
    private StackTypes() {
        throw new AssertionError("Cannot instantiate StackTypes!");
    }

    public static final Type VOID = Type.VOID_TYPE;
    public static final Type BOOLEAN = Type.BOOLEAN_TYPE;
    public static final Type CHAR = Type.CHAR_TYPE;
    public static final Type BYTE = Type.BYTE_TYPE;
    public static final Type SHORT = Type.SHORT_TYPE;
    public static final Type INT = Type.INT_TYPE;
    public static final Type FLOAT = Type.FLOAT_TYPE;
    public static final Type LONG = Type.LONG_TYPE;
    public static final Type DOUBLE = Type.DOUBLE_TYPE;
    public static final Type OBJECT_TYPE = InstructionAdapter.OBJECT_TYPE;
    public static final Type STRING_TYPE = Type.getType(String.class);
    public static final Type CLASS_TYPE = Type.getType(Class.class);
    public static final Type INTEGER_TYPE = Type.getType(Integer.class);
    public static final Type DOUBLE_TYPE = Type.getType(Double.class);
    public static final Type FLOAT_TYPE = Type.getType(Float.class);
    public static final Type LONG_TYPE = Type.getType(Long.class);

    public static Type getTypeFromClassName(final String className) {
        return switch (className) {
            case "void" -> VOID;
            case "boolean" -> BOOLEAN;
            case "char" -> CHAR;
            case "byte" -> BYTE;
            case "short" -> SHORT;
            case "int" -> INT;
            case "float" -> FLOAT;
            case "long" -> LONG;
            case "double" -> DOUBLE;
            case "java/lang/Object", "java.lang.Object" -> OBJECT_TYPE;
            case "java/lang/String", "java.lang.String" -> STRING_TYPE;
            case "java/lang/Class", "java.lang.Class" -> CLASS_TYPE;
            case "java/lang/Integer", "java.lang.Integer" -> INTEGER_TYPE;
            case "java/lang/Float", "java.lang.Float" -> FLOAT_TYPE;
            case "java/lang/Long", "java.lang.Long" -> LONG_TYPE;
            case "java/lang/Double", "java.lang.Double" -> DOUBLE_TYPE;
            default -> Type.getObjectType(className);
        };
    }

    public static Type getTypeFromLdcInstance(final Object instance) {
        if (instance instanceof Integer) {
            return INT;
        }
        if (instance instanceof Float) {
            return FLOAT;
        }
        if (instance instanceof Long) {
            return LONG;
        }
        if (instance instanceof Double) {
            return DOUBLE_TYPE;
        }
        if (instance instanceof String) {
            return STRING_TYPE;
        }
        if (instance instanceof Type) {
            return CLASS_TYPE;
        }
        throw new IllegalArgumentException("Invalid Ldc type: " + instance.getClass().getName());
    }

    public static boolean isTypeStackInt(Type type) {
        return type == BOOLEAN || type == CHAR || type == BYTE || type == SHORT || type == INT;
    }

    public static boolean isTypeStackDouble(Type type) {
        return type == DOUBLE;
    }

    public static boolean isTypeStackFloat(Type type) {
        return type == FLOAT;
    }

    public static boolean isTypeStackLong(Type type) {
        return type == LONG;
    }

    public static boolean isTypeStackNumber(Type type) {
        return isTypeStackInt(type) || isTypeStackFloat(type) || isTypeStackLong(type) || isTypeStackDouble(type);
    }

    public static boolean isTypeStackObject(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT, Type.ARRAY -> true;
            default -> false;
        };
    }

    public static boolean isTypeStackPureObject(Type type) {
        return type.getSort() == Type.OBJECT;
    }

    public static boolean isTypeStackString(Type type) {
        return STRING_TYPE.equals(type);
    }

    public static boolean isTypeStackIntegerType(Type type) {
        return INTEGER_TYPE.equals(type);
    }

    public static boolean isTypeStackBoolean(Type type) {
        return BOOLEAN.equals(type);
    }
}
