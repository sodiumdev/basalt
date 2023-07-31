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
import org.zeroturnaround.zip.ZipUtil;
import zip.sodium.jbasalt.compiler.Compiler;
import zip.sodium.jbasalt.compiler.EphemeralRunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

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

    public static final File outDir;
    public static final File inDir;

    public static final File jarDir;

    private static final Collection<File> files;

    static {
        deleteContentsOfFolder(new File("build/classes/basalt/"));
        deleteContentsOfFolder(new File("build/libs/"));

        outDir = new File("build/classes/basalt/main/");
        inDir = new File("src/main/basalt/");

        jarDir = new File("build/libs/Basalt.jar");

        outDir.mkdirs();
        inDir.mkdirs();

        try {
            jarDir.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        files = FileUtils.listFiles(
                inDir,
                new RegexFileFilter("^(.*?)\\.bas$"),
                DirectoryFileFilter.DIRECTORY
        );
    }

    private static void compileFile(EphemeralRunner runner, File f) throws IOException {
        String filePackage = StringUtils.replaceOnce(f.getParentFile().getPath(), inDir.getPath(), "");
        try {
            filePackage = filePackage.substring(1);
        } catch (IndexOutOfBoundsException ignored) {}

        final Compiler compiler = new Compiler(filePackage.replace("\\", "."), f.getName(), runner);

        try (FileInputStream din = new FileInputStream(f)) {
            compiler.compileToEphemeralRunner(new String(din.readAllBytes()));
        }

        final File out = new File(outDir, filePackage);
        out.mkdirs();

        try (FileOutputStream dout = new FileOutputStream(new File(out, FilenameUtils.removeExtension(f.getName()) + ".class"))) {
            dout.write(runner.classes.get(filePackage.replace("\\", ".") + "." + FilenameUtils.removeExtension(f.getName())));
        }

        for (InnerClassNode innerClass : compiler.getCurrentClass().innerClasses) {
            File file = new File(outDir, innerClass.name + ".class");
            file.getParentFile().mkdirs();
            file.createNewFile();

            try (FileOutputStream dout = new FileOutputStream(file)) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        if ("java/lang/Object".equals(type1) ||
                                "java/lang/Object".equals(type2))
                            return "java/lang/Object";
                        return super.getCommonSuperClass(type1, type2);
                    }
                };
                Compiler.classes.get(innerClass.name).accept(new ClassVisitor(Opcodes.ASM9, cw) {
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

    private static void addResourcesToJar(File dir) {
        for (File f : Objects.requireNonNull(dir.listFiles())) {
            if (f.isDirectory())
                addResourcesToJar(dir);
            else ZipUtil.addEntry(jarDir, StringUtils.replaceOnce(f.getPath(), "build\\resources\\main\\", ""), f);
        }
    }

    private static void jarFile() {
        ZipUtil.pack(outDir, jarDir);
        final File resources = new File("build/resources/main/");
        resources.mkdirs();

        addResourcesToJar(resources);
        ZipUtil.addEntry(jarDir, "META-INF/MANIFEST.MF", new File("build/tmp/jar/MANIFEST.MF"));
    }

    public static void main(String[] args) throws IOException, ReflectiveOperationException, URISyntaxException {
        final EphemeralRunner runner = new EphemeralRunner(Thread.currentThread().getContextClassLoader());
        runner.setCompileFunction(Main::compileFile);

        Thread.currentThread().setContextClassLoader(runner);

        for (File f : files)
            compileFile(runner, f);

        if (args.length >= 1)
            runner.run(args[0], Arrays.copyOfRange(args, 1, args.length));
    }
}