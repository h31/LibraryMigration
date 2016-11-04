import org.jetbrains.annotations.Mutable
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
    val libraries: MutableMap<String, Library> = mutableMapOf()

    val java: Library by libraries
    val apache: Library by libraries
    val okhttp: Library by libraries

    @Before
    fun init() {
        libraries.putAll(libraryModels())
        makePictures(libraries)
    }

    @Test
    fun migrateInstagram() {
        val testPatcher = {path: Path ->
            val testFile = path.resolve("src/test/java/InstagramTest.java").toFile()
            val lines = testFile.readLines()
            Assert.assertTrue(lines[20].endsWith("@Test"))
            val newContent = lines.filterIndexed { i, s -> i != 20 }.joinToString("\n")
            testFile.writeText(newContent)
        }
        Assert.assertTrue(migrate(projectPath = Paths.get("examples/instagram-java-scraper"),
                sourceName = "Instagram.java",
                from = okhttp,
                to = apache,
                testPatcher = testPatcher
        ))
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Java.java",
                from = java,
                to = apache
        ))
    }

    @Test
    fun migrateJavaOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Java.java",
                from = java,
                to = okhttp
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Apache.java",
                from = apache,
                to = java
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Apache.java",
                from = apache,
                to = okhttp
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "OkHttp.java",
                from = okhttp,
                to = java,
                runClass = "migration.OkHttp"
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "OkHttp.java",
                from = okhttp,
                to = apache
        ))
    }
}