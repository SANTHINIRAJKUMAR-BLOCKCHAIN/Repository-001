// Due to Capsule being in the default package, which cannot be imported, this caplet
// must also be in the default package. When using Kotlin there are a whole host of exceptions
// trying to construct this from Capsule, so it is written in Java.

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CordaCaplet extends Capsule {

    protected CordaCaplet(Capsule pred) {
        super(pred);
        checkExistingVersion();
    }

    /**
     * Aborts starting the node if an existing deployment with a different version is detected in the current directory
     */
    private void checkExistingVersion() {
        String currentVersion = super.attribute(ATTR_APP_VERSION);
        Path versionFile = Paths.get("version");
        try {
            if (Files.exists(versionFile)) {
                String existingVersion = Files.readAllLines(versionFile).get(0);
                if (!existingVersion.equals(currentVersion)) {
                    System.err.println("ERROR: Unable to start the node, version change detected - current: " +
                            currentVersion + ", existing: " + existingVersion + ". Node upgrades are not yet supported.");
                    System.exit(1);
                }
            } else {
                Files.write(versionFile, currentVersion.getBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Overriding the Caplet classpath generation via the intended interface in Capsule.
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T attribute(Map.Entry<String, T> attr) {
        // Equality is used here because Capsule never instantiates these attributes but instead reuses the ones
        // defined as public static final fields on the Capsule class, therefore referential equality is safe.
        if (ATTR_APP_CLASS_PATH == attr) {
            T cp = super.attribute(attr);
            List<Path> classpath = augmentClasspath((List<Path>) cp, "plugins");
            return (T) augmentClasspath(classpath, "dependencies");
        }
        return super.attribute(attr);
    }

    // TODO: Make directory configurable via the capsule manifest.
    // TODO: Add working directory variable to capsules string replacement variables.
    private List<Path> augmentClasspath(List<Path> classpath, String dirName) {
        File dir = new File(dirName);
        if (!dir.exists()) {
            dir.mkdir();
        }

        File[] files = dir.listFiles();
        for (File file : files) {
            if (file.isFile() && isJAR(file)) {
                classpath.add(file.toPath().toAbsolutePath());
            }
        }
        return classpath;
    }

    private Boolean isJAR(File file) {
        return file.getName().toLowerCase().endsWith(".jar");
    }
}
