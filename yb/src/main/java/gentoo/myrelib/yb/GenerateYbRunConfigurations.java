package gentoo.myrelib.yb;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

public abstract class GenerateYbRunConfigurations extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @TaskAction
    public void generate() throws IOException {
        generate(getProjectDirectory().get().getAsFile().toPath());
    }

    static void generate(Path project) throws IOException {
        Path launcher = project.resolve(".gradle/myrelib/yb.jar");
        installLauncher(launcher);

        Path configurations = project.resolve(".idea/runConfigurations");
        Files.createDirectories(configurations);
        writeConfiguration(configurations.resolve("YbSlothPush.xml"), "Sloth Push", "sloth");
        writeConfiguration(configurations.resolve("YbFullPush.xml"), "Full Push", "full");
    }

    private static void installLauncher(Path launcher) throws IOException {
        byte[] contents;
        try (InputStream source = Objects.requireNonNull(
                GenerateYbRunConfigurations.class.getResourceAsStream("/yb-launcher.jar"))) {
            contents = source.readAllBytes();
        }
        if (Files.isRegularFile(launcher) && Arrays.equals(contents, Files.readAllBytes(launcher))) return;

        Files.createDirectories(launcher.getParent());
        Path temporary = Files.createTempFile(launcher.getParent(), "yb-", ".jar");
        try {
            Files.write(temporary, contents);
            try {
                Files.move(temporary, launcher, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, launcher, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeConfiguration(Path path, String name, String mode) throws IOException {
        String xml = """
                <component name="ProjectRunConfigurationManager">
                  <configuration default="false" name="%s" type="JarApplication">
                    <option name="JAR_PATH" value="$PROJECT_DIR$/.gradle/myrelib/yb.jar" />
                    <option name="PROGRAM_PARAMETERS" value="push %s" />
                    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
                    <method v="2" />
                  </configuration>
                </component>
                """.formatted(name, mode);
        try {
            Files.writeString(path, xml, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // Keep local edits on subsequent syncs.
        }
    }
}
