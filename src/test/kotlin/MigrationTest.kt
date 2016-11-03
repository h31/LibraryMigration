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
    var java: Library? = null
    var apache: Library? = null
    var okhttp: Library? = null

    @Before
    fun init() {
        val models = libraryModels()
        makePictures(models)
        java = models["java"]
        apache = models["apache"]
        okhttp = models["okhttp"]
    }

    @Test
    fun migrateInstagram() {
        Assert.assertFalse(migrate(projectPath = Paths.get("/home/artyom/Compile/instagram-java-scraper"),
                sourceName = "Instagram.java",
                traceFile = File("/home/artyom/Compile/instagram-java-scraper/log.json"),
                from = okhttp!!,
                to = apache!!
        )) // TODO: Still doesn't migrates properly
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Main.java",
                traceFile = File("HTTP/log.json"),
                from = java!!,
                to = apache!!
        ))
    }

    @Test
    fun migrateJavaOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Main.java",
                traceFile = File("HTTP/log.json"),
                from = java!!,
                to = okhttp!!
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Main.java",
                traceFile = File("HTTP/log.json"),
                from = apache!!,
                to = java!!
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Main.java",
                traceFile = File("HTTP/log.json"),
                from = apache!!,
                to = okhttp!!
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Main.java",
                traceFile = File("HTTP/log.json"),
                from = okhttp!!,
                to = java!!
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(projectPath = Paths.get("HTTP"),
                sourceName = "Main.java",
                traceFile = File("HTTP/log.json"),
                from = okhttp!!,
                to = apache!!
        ))
    }
}