import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/*
 * https://discuss.gradle.org/t/concatenating-multiple-files-into-one-using-gradle/5056/2
 */
class ConcatFiles extends DefaultTask {

    @InputFiles
    FileCollection sources

    @OutputFile
    File target

    void setTarget(target) {
        this.target = getProject().file(target)
    }

    @TaskAction
    void concat() {
        target.withOutputStream { fout ->
            sources.each { file ->
                file.withInputStream { fin ->
                    fout << fin
                }
            }
        }
    }

}
