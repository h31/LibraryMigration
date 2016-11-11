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

    val examples = Paths.get("examples")

    @Before
    fun init() {
        libraries.putAll(libraryModels())
        makePictures(libraries)
    }

    @Test
    fun migrateInstagramOkHttp() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("instagram-java-scraper"),
                from = okhttp,
                to = apache
        ))
    }

    @Test
    fun migrateInstagramJava() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("instagram-java-scraper"),
                from = okhttp,
                to = java
        ))
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = java,
                to = apache
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
                to = okhttp
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = apache,
                to = java
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = apache,
                to = okhttp
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = okhttp,
                to = java
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(projectDir = examples.resolve("HTTP"),
                from = okhttp,
                to = apache
        ))
    }
}