package gentoo.myrelib.yb;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class YbLauncher {
    public static void main(String[] args) throws IOException, InterruptedException {
        Path script = Files.createTempFile("yb-", ".py");
        int exitCode;
        try (InputStream source = Objects.requireNonNull(YbLauncher.class.getResourceAsStream("/yb.py"))) {
            Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            Process process = launch(script, args);
            exitCode = process.waitFor();
        } finally {
            Files.deleteIfExists(script);
        }
        System.exit(exitCode);
    }

    private static Process launch(Path script, String[] args) throws IOException {
        if (File.separatorChar == '\\') {
            try {
                return launch(List.of("py", "-3"), script, args);
            } catch (IOException missingLauncher) {
                return launch(List.of("python"), script, args);
            }
        }
        return launch(List.of("python3"), script, args);
    }

    private static Process launch(List<String> interpreter, Path script, String[] args) throws IOException {
        List<String> command = new ArrayList<>(interpreter);
        command.add(script.toString());
        command.addAll(Arrays.asList(args));
        ProcessBuilder process = new ProcessBuilder(command).inheritIO();
        process.environment().put("YB_PROJECT_ROOT", System.getProperty("user.dir"));
        return process.start();
    }
}
