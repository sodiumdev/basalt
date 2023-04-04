package xyz.sodiumdev.jbasalt.test;

import org.junit.jupiter.api.Test;
import xyz.sodiumdev.jbasalt.compiler.Compiler;

import java.lang.reflect.InvocationTargetException;

public class CoreFeaturesTest {
    @Test
    public void assertFails() throws Throwable {
        try {
            new Compiler().compileAndRun("assert(false);");
            throw new AssertionError("assertion is true?");
        } catch (InvocationTargetException t) {
            if (!(t.getCause() instanceof AssertionError)) {
                throw t;
            }
        }
    }
    @Test
    public void basicMathTestNoReturn() throws Throwable {
        new Compiler().compileAndRun("""
""");
    }
}
