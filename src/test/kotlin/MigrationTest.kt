import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URL;

/**
 * Created by artyom on 03.11.16.
 */
class MigrationTest {
    var libraries: Map<String, Library> = mapOf()

    val java: Library by libraries
    val apache: Library by libraries
    val okhttp: Library by libraries

    @Before
    fun init() {
        libraries = libraryModels()
        makePictures(libraries)
    }

    fun downloadInstagram() {
        val url = "https://github.com/raiym/instagram-java-scraper/archive/0b3d2c3ec79b2a32019eaf09dd99f82d4734277e.zip"
        val stream = URL(url).openStream()
        val projectsDir = File("projects/")
        projectsDir.mkdir()
        ZipUtil.unpack(stream, projectsDir)
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
        Assert.assertTrue(migrate(projectPath = Paths.get("/home/artyom/Compile/instagram-java-scraper"),
                sourceName = "Instagram.java",
                traceFile = File("/home/artyom/Compile/instagram-java-scraper/log.json"),
                from = okhttp,
                to = apache,
                testPatcher = testPatcher
        ))
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Java.java",
                traceFile = File("HTTP/log.json"),
                from = java,
                to = apache
        ))
    }

    @Test
    fun migrateJavaOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Java.java",
                traceFile = File("HTTP/log.json"),
                from = java,
                to = okhttp
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Apache.java",
                traceFile = File("HTTP/log.json"),
                from = apache,
                to = java
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Apache.java",
                traceFile = File("HTTP/log.json"),
                from = apache,
                to = okhttp
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "OkHttp.java",
                traceFile = File("HTTP/log.json"),
                from = okhttp,
                to = java,
                runClass = "migration.OkHttp"
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "OkHttp.java",
                traceFile = File("HTTP/log.json"),
                from = okhttp,
                to = apache
        ))
    }
}