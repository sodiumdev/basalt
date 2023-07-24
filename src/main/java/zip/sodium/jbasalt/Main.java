package zip.sodium.jbasalt;

import org.apache.commons.io.FilenameUtils;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.InnerClassNode;
import zip.sodium.jbasalt.compiler.Compiler;
import zip.sodium.jbasalt.compiler.EphemeralRunner;

import java.io.*;

public class Main {
    private static void deleteContentsOfFolder(File folder) {
        final File[] files = folder.listFiles();

        if (files == null)
            return;

        for (File f : files) {
            if (f.isDirectory())
                deleteContentsOfFolder(f);

            f.delete();
        }
    }

    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        deleteContentsOfFolder(new File("build/classes/basalt/"));

        File outDir = new File("build/classes/basalt/main/zip/sodium/generated/");
        File inDir = new File("src/main/basalt/zip/sodium/generated/");
        outDir.mkdirs();
        inDir.mkdirs();

        final File[] files = inDir.listFiles();
        if (files == null)
            return;

        final EphemeralRunner runner = new EphemeralRunner();
        Thread.currentThread().setContextClassLoader(runner);

        for (File f : files) {
            final Compiler compiler = new Compiler(f.getName(), runner);

            try (FileInputStream din = new FileInputStream(new File(inDir, f.getName()))) {
                compiler.compileToEphemeralRunner(new String(din.readAllBytes()));
            }

            try (FileOutputStream dout = new FileOutputStream(new File(outDir, FilenameUtils.removeExtension(f.getName()) + ".class"))) {
                dout.write(runner.classes.get("zip.sodium.generated." + FilenameUtils.removeExtension(f.getName())));
            }

            for (InnerClassNode innerClass : compiler.getCurrentClass().innerClasses) {
                try (FileOutputStream dout = new FileOutputStream(new File(outDir, compiler.currentClass + "$" + innerClass.innerName + ".class"))) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            if ("java/lang/Object".equals(type1) ||
                                    "java/lang/Object".equals(type2))
                                return "java/lang/Object";
                            return super.getCommonSuperClass(type1, type2);
                        }
                    };
                    Compiler.classes.get(compiler.currentClass + "$" + innerClass.innerName).accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            // The InstructionAdapter correct some op codes to more optimized alternatives.
                            // TODO: Make compiler emit optimized variants directly.
                            return new InstructionAdapter(super.visitMethod(access, name, descriptor, signature, exceptions));
                        }
                    });

                    dout.write(cw.toByteArray());
                }
            }
        }

        runner.runMain(args);
    }
}