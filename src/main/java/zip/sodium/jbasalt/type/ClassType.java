package zip.sodium.jbasalt.type;

import org.objectweb.asm.Type;

public class ClassType extends Type {
    public ClassType(int sort, String valueBuffer, int valueBegin, int valueEnd) {
        super(sort, valueBuffer, valueBegin, valueEnd);
    }

    public static ClassType of(Type type) {
        return new ClassType(type.sort, type.valueBuffer, type.valueBegin, type.valueEnd);
    }

    public static ClassType of(Class<?> clazz) {
        return of(Type.getType(clazz));
    }
}
