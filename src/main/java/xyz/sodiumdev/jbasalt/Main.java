package xyz.sodiumdev.jbasalt;

import xyz.sodiumdev.jbasalt.compiler.Compiler;
import xyz.sodiumdev.jbasalt.compiler.EphemeralRunner;

import java.io.*;

public class Main {
    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        EphemeralRunner ephemeralRunner =
                new Compiler().compileToEphemeralRunner("""
                        fun xabc(b: double) {
                         let x: double = b + 1
                         let y: double = x + x
                         return x + y;
                        }
                        
                        fun yabc(a: double) {
                         let x: double = a / 360
                         let y: double = x * x
                         
                         return y;
                        }
                        
                        println(xabc(1))
                        println(yabc(1))""");

        File outDir = new File("out/xyz/sodiumdev/asm");
        outDir.mkdirs();
        try (FileOutputStream dout = new FileOutputStream(new File(outDir,"Generated.class"))) {
            dout.write(ephemeralRunner.classData);
        }

        System.out.println("\nRunning code...\n");

        ephemeralRunner.runMain();

        System.out.println("\nFinished\n");
    }
}