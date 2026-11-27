package gentoo.myrelib.yb;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;

import java.io.IOException;

public class YbPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("Apply the myrelib YB plugin to the root project.");
        }
        Directory directory = project.getLayout().getProjectDirectory();
        project.getTasks().register("genYbRuns", GenerateYbRunConfigurations.class, task -> {
            task.setGroup("yb");
            task.setDescription("Create local Android Studio configurations for Sloth Push and Full Push.");
            task.getProjectDirectory().set(directory);
        });

        // Gradle sync configures the project without executing its tasks.
        try {
            GenerateYbRunConfigurations.generate(directory.getAsFile().toPath());
        } catch (IOException error) {
            project.getLogger().warn("Could not generate the YB run configurations: {}", error.getMessage());
        }
    }
}
