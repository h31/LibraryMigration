import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by artyom on 03.11.16.
 */
class MigrationTest {
    val java: Library = HttpModels.java
    val apache: Library = HttpModels.apache
    val okhttp: Library = HttpModels.okhttp

    val examples = Paths.get("examples")

    val stripAspects = {path: Path ->
        val testFile = path.resolve("build.gradle").toFile()
        val lines = testFile.readLines()
        val newContent = lines.filterNot { s -> s.contains("aspectj") }.joinToString("\n")
        testFile.writeText(newContent)
    }

    @Before
    fun init() {
        makePictures(HttpModels.withName())
    }

    @Test
    fun migrateInstagramOkHttp() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("instagram-java-scraper"),
                from = okhttp,
                to = apache,
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateInstagramJava() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("instagram-java-scraper"),
                from = okhttp,
                to = java,
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = java,
                to = apache,
                testClassName = "migration.Java",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateJavaApacheTwice() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = java,
                to = apache
        ))
        println("And again...")
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP_test_java_apache"),
                from = apache,
                to = java
        ))
        println("And again...")
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP_test_java_apache_test_apache_java"),
                from = java,
                to = apache
        ))
    }

    @Test
    fun migrateJavaOkhttp() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = java,
                to = okhttp,
                testClassName = "migration.Java",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = apache,
                to = java,
                testClassName = "migration.Apache",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = apache,
                to = okhttp,
                testClassName = "migration.Apache",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = okhttp,
                to = java,
                testClassName = "migration.OkHttp",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = okhttp,
                to = apache,
                testPatcher = stripAspects
        ))
    }
}