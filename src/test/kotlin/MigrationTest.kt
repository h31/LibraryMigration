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
    fun migrateInstagramOkHttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("examples/instagram-java-scraper"),
                from = okhttp,
                to = apache
        ))
    }

    @Test
    fun migrateInstagramJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("examples/instagram-java-scraper"),
                from = okhttp,
                to = java
        ))
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                from = java,
                to = apache
        ))
    }

    @Test
    fun migrateJavaOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                from = java,
                to = okhttp
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                from = apache,
                to = java
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                from = apache,
                to = okhttp
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                from = okhttp,
                to = java,
                runClass = "migration.OkHttp"
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                from = okhttp,
                to = apache
        ))
    }
}