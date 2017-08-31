package ru.spbstu.kspt.librarymigration

import org.junit.Assert
import org.junit.Test
import ru.spbstu.kspt.librarymigration.models.Logging
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Created by artyom on 12.07.17.
 */
class LoggingTest {
    val log4j = Logging.makeLog4j()
    val slf4j = Logging.makeSLF4J()
    val log4j20 = Logging.makeLog4j20()

    @Test
    fun testKalah() {
        migrate(project = GradleProject(Paths.get("/home/artyom/Compile/schwan_kalah")),
                from = log4j,
                to = slf4j
        )
        migrate(project = GradleProject(Paths.get("/home/artyom/Compile/migrated/schwan_kalah_migrated_Log4j_SLF4J")),
                from = log4j20,
                to = slf4j)
        migrate(project = GradleProject(Paths.get("/home/artyom/Compile/migrated/schwan_kalah_migrated_Log4j_SLF4J_migrated_Log4j20_SLF4J")),
                from = slf4j,
                to = log4j)
        migrate(project = GradleProject(Paths.get("/home/artyom/Compile/migrated/schwan_kalah_migrated_Log4j_SLF4J_migrated_Log4j20_SLF4J")),
                from = slf4j,
                to = log4j)
    }

    @Test
    fun testLoggingExample() {
        migrate(project = GradleProject(Paths.get("examples/Logging")),
                from = log4j,
                to = slf4j)
        val srcFileContent = Files.readAllBytes(Paths.get("examples/migrated/Logging_migrated_Log4j_SLF4J/log4j.log"))
        val dstFileContent = Files.readAllBytes(Paths.get("examples/migrated/Logging_migrated_Log4j_SLF4J/log4j.log"))
        Assert.assertArrayEquals(srcFileContent, dstFileContent)
    }
}