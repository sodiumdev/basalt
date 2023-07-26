package zip.sodium.jbasalt.compiler;

import java.io.File;
import java.io.IOException;

public interface CompileFunction {
    void apply(EphemeralRunner runner, File f) throws IOException;
}
