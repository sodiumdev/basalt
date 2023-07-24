package zip.sodium.jbasalt.utils;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.Writer;

public class DebugUtils {
    public static String classDataToDebug(byte[] classData) {
        final StringBuilder stringBuilder = new StringBuilder();
        TraceClassVisitor traceClassVisitor = new TraceClassVisitor(new PrintWriter(new Writer() {
            @Override
            public void write(char @NotNull [] cbuf, int off, int len) {
                stringBuilder.append(cbuf, off, len);
            }

            @Override
            public void write(@NotNull String str, int off, int len) {
                stringBuilder.append(str, off, len);
            }

            @Override
            public void write(char @NotNull [] cbuf) {
                stringBuilder.append(cbuf);
            }

            @Override
            public void write(@NotNull String str) {
                stringBuilder.append(str);
            }

            @Override
            public void write(int c) {
                stringBuilder.append(c);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        }));
        new ClassReader(classData).accept(traceClassVisitor, 0);
        return stringBuilder.toString();
    }
}
