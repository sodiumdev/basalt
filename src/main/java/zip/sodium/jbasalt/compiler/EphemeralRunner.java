package zip.sodium.jbasalt.compiler;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.InstructionAdapter;
import zip.sodium.jbasalt.utils.DebugUtils;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class EphemeralRunner extends ClassLoader {
    public final Map<String, byte[]> classes = new LinkedHashMap<>();
    private final Map<String, Class<?>> emulatedClassInstances = new HashMap<>();

    public EphemeralRunner() {
        super(EphemeralRunner.class.getClassLoader());
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!classes.containsKey(name))
            return super.findClass(name);

        final byte[] classData = classes.get(name);

        if (emulatedClassInstances.get(name) == null)
            emulatedClassInstances.put(name, defineClass(name, classData, 0, classData.length, null));
        return emulatedClassInstances.get(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (classes.containsKey(name.replace("/", ".")))
            return new ByteArrayInputStream(classes.get(name.replace("/", ".")));

        return super.getResourceAsStream(name);
    }

    public void runMain(String... args) throws InvocationTargetException {
        try {
            this.loadClass("zip.sodium.generated.Main")
                    .getDeclaredMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (NoSuchMethodException | ClassNotFoundException |
                 IllegalAccessException | Error e) {
            throw new InvocationTargetException(e, "Invalid class format \n" +
                    DebugUtils.classDataToDebug(classes.get("zip.sodium.generated.Main")));
        }
    }
}
