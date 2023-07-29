package zip.sodium.jbasalt.compiler;

import zip.sodium.jbasalt.Main;
import zip.sodium.jbasalt.utils.DebugUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class EphemeralRunner extends ClassLoader {
    public final Map<String, byte[]> classes = new LinkedHashMap<>();
    private final Map<String, Class<?>> emulatedClassInstances = new HashMap<>();

    private CompileFunction compileFunction;

    public EphemeralRunner(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!classes.containsKey(name)) {
            return super.findClass(name);
        }

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

    public void run(String className, String... args) throws InvocationTargetException {
        try {
            this.loadClass(className)
                    .getDeclaredMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (NoSuchMethodException | ClassNotFoundException |
                 IllegalAccessException | Error e) {
            throw new InvocationTargetException(e, "Invalid class format \n" +
                    DebugUtils.classDataToDebug(classes.get(className)));
        }
    }

    public void setCompiler(Compiler compiler) {
    }

    public void setCompileFunction(CompileFunction compileFunction) {
        this.compileFunction = compileFunction;
    }
}
