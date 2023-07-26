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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class EphemeralRunner extends ClassLoader {
    public final Map<String, byte[]> classes = new LinkedHashMap<>();
    private final Map<String, Class<?>> emulatedClassInstances = new HashMap<>();

    private Compiler compiler;
    private CompileFunction compileFunction;

    public EphemeralRunner() {
        super(EphemeralRunner.class.getClassLoader());
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!classes.containsKey(name)) {
            try {
                compileFunction.apply(this, new File(Main.inDir, name));
            } catch (IOException ignored) {}

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

    public void setCompiler(Compiler compiler) {
        this.compiler = compiler;
    }

    public void setCompileFunction(CompileFunction compileFunction) {
        this.compileFunction = compileFunction;
    }
}
