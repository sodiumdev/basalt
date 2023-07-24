package zip.sodium.jbasalt.test;

import org.junit.jupiter.api.Test;
import zip.sodium.jbasalt.compiler.Compiler;
import zip.sodium.jbasalt.compiler.EphemeralRunner;

import java.lang.reflect.InvocationTargetException;

public class CoreFeaturesTest {
    @Test
    public void assertFails() throws Throwable {
        try {
            Compiler.compileAndRun("assert(false)");
            throw new AssertionError("assertion is true?");
        } catch (InvocationTargetException t) {
            if (!(t.getCause() instanceof AssertionError)) {
                throw t;
            }
        }
    }

    @Test
    public void basicMathTest() throws Throwable {
        Compiler.compileAndRun("""
                         let a: int = 1
                         let x: int = a + 1
                         let y: int = x + x
                         assert(y == 4)
                         """);
    }

    @Test
    public void assignVariable() throws Throwable {
        Compiler.compileAndRun("""
                let a: java.lang.String = ""
                assert(a != null)
                """);
    }

    @Test
    public void makeAnotherMethod() throws Throwable {
        Compiler.compileAndRun("""
                fn xabc(a: double): void {
                  println(a)
                }
                fn main(args: java.lang.String[]): void {
                  xabc(12d)
                }
                """);
    }

    @Test
    public void importAndTestArrays() throws Throwable {
        Compiler.compileAndRun("""
                import java.lang.String
                
                class Main {
                    fn main(args: String[]): void {
                        let array: long[][] = [long[]: [long: 1l, 2l, 3l]]
                    
                        println(array)
                    }
                }
                """);
    }
}
