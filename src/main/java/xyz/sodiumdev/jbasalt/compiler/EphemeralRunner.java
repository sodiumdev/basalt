package xyz.sodiumdev.jbasalt.compiler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class EphemeralRunner extends ClassLoader {
    public final byte[] classData;
    public final String className;
    private final String emulatedPath;
    private Class<?> emulatedClassInstance;

    EphemeralRunner(byte[] classData, String className) {
        this.classData = classData;
        this.className = className.replace('/', '.');
        this.emulatedPath = className.replace('.', '/') + ".class";
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (className.equals(name)) {
            if (emulatedClassInstance == null) {
                return emulatedClassInstance =
                        defineClass(className, classData, 0, classData.length, null);
            }
            return emulatedClassInstance;
        }

        throw new ClassNotFoundException(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (emulatedPath.equals(name)) {
            return new ByteArrayInputStream(this.classData);
        }
        return super.getResourceAsStream(name);
    }

    public void runMain(String... args) throws ReflectiveOperationException {
        this.loadClass(className)
                .getDeclaredMethod("main", String[].class)
                .invoke(null, (Object) args);
    }
}
