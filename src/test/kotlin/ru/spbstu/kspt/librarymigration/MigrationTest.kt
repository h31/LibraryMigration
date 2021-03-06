package ru.spbstu.kspt.librarymigration

import mu.KotlinLogging
import org.eclipse.jetty.server.Server
import org.gradle.tooling.GradleConnector
import org.junit.Assert
import org.junit.Test
import org.zeroturnaround.zip.ZipUtil
import ru.spbstu.kspt.librarymigration.models.HttpModels
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by artyom on 03.11.16.
 */
class MigrationTest {
    companion object {
        val mock = InstagramMock()
        val server = Server(8010)
        private val logger = KotlinLogging.logger {}

        init {
            unzipInstagramScraper()
            makeHTTPTraces()
            makeInstagramTraces()

            mock.read()
            server.handler = mock;
            server.start()
        }

        fun unzipInstagramScraper() {
            val projectName = "instagram-java-scraper"
            val branch = "emulator"

            val outputDir = File("examples/$projectName")
            if (outputDir.resolve("build.gradle").exists()) return

            val url = "https://github.com/h31/$projectName/archive/$branch.zip"
            val urlStream = URL(url).openStream()
            ZipUtil.unpack(urlStream, outputDir, { name -> name.removePrefix("$projectName-$branch") })
            urlStream.close()
        }

        fun makeHTTPTraces() {
            val projectDir = File("examples/HTTP")
            if (projectDir.resolve("log.json").exists()) return

            logger.info("Making an HTTP example project execution trace")
            val connection = GradleConnector.newConnector().forProjectDirectory(projectDir).connect()
            connection.newBuild().forTasks("clean", "test").run()
        }

        fun makeInstagramTraces() {
            val src = File("examples/log.json.instagram")
            val dst = File("examples/instagram-java-scraper/log.json")
            if (!dst.exists()) {
                logger.info("Making an instagram-java-scraper project execution trace")
                src.copyTo(dst)
            }
        }
    }
    val java: Library = HttpModels.java
    val apache: Library = HttpModels.apache
    val okhttp: Library = HttpModels.okhttp

    val examples = Paths.get("examples")

    val instagram = GradleProject(examples.resolve("instagram-java-scraper"))
    val http = GradleProject(examples.resolve("HTTP"))

    val stripAspects = {path: Path ->
        val testFile = path.resolve("build.gradle").toFile()
        val lines = testFile.readLines()
        val newContent = lines.filterNot { s -> s.contains("aspectj") }.joinToString("\n")
        testFile.writeText(newContent)
    }

    val instagramDisableTest = {path: Path ->
        val testFile = path.resolve("src/test/java/InstagramTest.java").toFile()
        val lines = testFile.readLines()
        val removed = lines.mapIndexedNotNull { index, s -> if (s.contains("testGetMediaBy")) index - 1 else null }
        // Assert.assertTrue(lines[20].endsWith("@Test"))
        val newContent = lines.filterIndexed { i, _ -> i !in removed }.joinToString("\n")
        testFile.writeText(newContent)
    }

    @Test
    fun migrateInstagramApache() {
        Assert.assertTrue(migrate(instagram,
                from = okhttp,
                to = apache,
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateInstagramJava() {
        Assert.assertTrue(migrate(instagram,
                from = okhttp,
                to = java,
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateJavaApache() {
        Assert.assertTrue(migrate(http,
                from = java,
                to = apache,
                testClassName = "migration.Java",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateJavaApacheTwice() {
        Assert.assertTrue(migrate(http,
                from = java,
                to = apache
        ))
        println("And again...")
        Assert.assertTrue(migrate(project = GradleProject(examples.resolve("migrated/HTTP_migrated_java_apache")),
                from = apache,
                to = java
        ))
        println("And again...")
        Assert.assertTrue(migrate(project = GradleProject(examples.resolve("migrated/HTTP_migrated_java_apache_migrated_apache_java")),
                from = java,
                to = apache
        ))
    }

    @Test
    fun migrateJavaOkhttp() {
        Assert.assertTrue(migrate(http,
                from = java,
                to = okhttp,
                testClassName = "migration.Java",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateApacheJava() {
        Assert.assertTrue(migrate(http,
                from = apache,
                to = java,
                testClassName = "migration.Apache",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateApacheOkhttp() {
        Assert.assertTrue(migrate(http,
                from = apache,
                to = okhttp,
                testClassName = "migration.Apache",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateOkhttpJava() {
        Assert.assertTrue(migrate(http,
                from = okhttp,
                to = java,
                testClassName = "migration.OkHttp",
                testPatcher = stripAspects
        ))
    }

    @Test
    fun migrateOkhttpApache() {
        Assert.assertTrue(migrate(http,
                from = okhttp,
                to = apache,
                testPatcher = stripAspects
        ))
    }
}