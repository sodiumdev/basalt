package zip.sodium.jbasalt;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.tree.InnerClassNode;
import zip.sodium.jbasalt.compiler.Compiler;
import zip.sodium.jbasalt.compiler.EphemeralRunner;

import java.io.*;
import java.util.Arrays;
import java.util.Collection;

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

    private static final File outDir;
    public static final File inDir;

    private static final Collection<File> files;

    static {
        deleteContentsOfFolder(new File("build/classes/basalt/"));

        outDir = new File("build/classes/basalt/main/");
        inDir = new File("src/main/basalt/");

        outDir.mkdirs();
        inDir.mkdirs();

        files = FileUtils.listFiles(
                inDir,
                new RegexFileFilter("^(.*?)\\.bas$"),
                DirectoryFileFilter.DIRECTORY
        );
        System.out.println(files);
    }

    private static void compileFile(EphemeralRunner runner, File f) throws IOException {
        String filePackage = StringUtils.replaceOnce(f.getParentFile().getPath(), inDir.getPath(), "");
        try {
            filePackage = filePackage.substring(1);
        } catch (IndexOutOfBoundsException ignored) {}

        File outDir = new File(Main.outDir, filePackage);
        outDir.mkdirs();

        final Compiler compiler = new Compiler(filePackage.replace("\\", "."), f.getName(), runner);

        try (FileInputStream din = new FileInputStream(f)) {
            compiler.compileToEphemeralRunner(new String(din.readAllBytes()));
        }

        try (FileOutputStream dout = new FileOutputStream(new File(outDir, FilenameUtils.removeExtension(f.getName()) + ".class"))) {
            dout.write(runner.classes.get(filePackage.replace("\\", ".") + "." + FilenameUtils.removeExtension(f.getName())));
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

    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        if (args.length == 0) {
            System.err.println("Please specify the main class in the first argument");

            System.exit(-1);
            return;
        }

        final EphemeralRunner runner = new EphemeralRunner();
        runner.setCompileFunction(Main::compileFile);

        Thread.currentThread().setContextClassLoader(runner);

        for (File f : files)
            compileFile(runner, f);

        runner.run(args[0], Arrays.copyOfRange(args, 1, args.length));
    }
}