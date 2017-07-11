package ru.spbstu.kspt.librarymigration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import mu.KotlinLogging
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.eclipse.EclipseProject
import org.slf4j.MDC
import ru.spbstu.kspt.librarymigration.models.Logging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by aleksyuk on 4/7/16.
 */

fun main(args: Array<String>) {
    makePictures(HttpModels.all())

//    migrate(projectDir = Paths.get("examples/instagram-java-scraper"),
//            from = models["okhttp"]!!,
//            to = models["java"]!!
//    )
//    val project = MavenProject(projectDir = Paths.get("/home/artyom/Compile/acme4j"),
//            moduleDir = Paths.get("/home/artyom/Compile/acme4j/acme4j-client"))
    val project = MavenProject(projectDir = Paths.get("/home/artyom/Compile/signpost"),
            moduleDir = Paths.get("/home/artyom/Compile/signpost/signpost-core"))
    migrate(project = GradleProject(Paths.get("/home/artyom/Compile/schwan_kalah")),
            from = Logging.makeLog4j(),
            to = Logging.makeSLF4J(),
            testPatcher = {path: Path ->
                val testFile = path.resolve("build.gradle").toFile()
                val lines = testFile.readLines()
                val newContent = lines.filterNot { s -> s.contains("aspectj") }.joinToString("\n")
                testFile.writeText(newContent)
            }
    )
    migrate(project = GradleProject(Paths.get("/home/artyom/Compile/migrated/schwan_kalah_migrated_Log4j_SLF4J")),
            from = Logging.makeLog4j20(),
            to = Logging.makeSLF4J())
//    migrate(project = project,
//            from = HttpModels.java,
//            to = HttpModels.okhttp
//    )
}

fun migrate(project: Project,
            from: Library,
            to: Library,
            testPatcher: (Path) -> Unit = {},
            testClassName: String? = null): Boolean {
    val manager = MigrationManager(project = project,
            from = from,
            to = to)

    val importsForMigration = diffLibraryClasses(from, to)
    val pending = manager.findJavaFilesForMigration(project.projectDir, importsForMigration)
    if (pending.none()) {
        error("Nothing to migrate")
    }

    val invocations = parseInvocations(project.traceFile.toFile(), from)

    for ((source, cu) in pending) {
        logger.info("Migrating $source")
        val codeElements = CodeElements();
        CodeElementsVisitor().visit(cu, codeElements);

        manager.migrateFile(codeElements, source, invocations)
        modifyImports(cu, from, to)

        val migratedCode = cu.toString()
        println(migratedCode);

        val relativePath = project.projectDir.relativize(source.toPath())
        Files.write(manager.destDir.resolve(relativePath), migratedCode.toByteArray())
    }
    testPatcher(manager.destDir)
    return manager.checkMigrationCorrectness(testClassName)
}

typealias GroupedInvocation = Map<String, Map<String, List<RouteExtractor.Invocation>>>

private fun parseInvocations(traceFile: File, from: Library): GroupedInvocation {
    val invocations = ObjectMapper().registerKotlinModule().readValue<List<RouteExtractor.Invocation>>(traceFile)
    val libraryTypes = from.allTypes()
    val filtered = invocations.filter { libraryTypes.contains(it.type) }
    val grouped = filtered.groupBy { it.filename }.mapValues { it.value.groupBy { it.callerName } } // TODO: groupingBy
    return grouped
}

private fun modifyImports(cu: CompilationUnit, from: Library, to: Library) {
    val commonClasses = listOf("java.io.InputStream", "java.io.OutputStream", "java.io.BufferedReader",
            "java.io.InputStreamReader", "java.util.stream.Collectors")

    cu.imports.filter { it.name.toString() in from.allTypes() && it.name.toString() !in commonClasses }
            .forEach { it.parentNode.get().remove(it) }

    cu.imports.addAll((to.allTypes())
            .filter { type -> type.contains('.') }
            .filterNot { type -> type.contains('$') }
            .map { type -> ImportDeclaration(Name(type), false, false) })
}

private fun parseImports(imports: List<ImportDeclaration>) = imports.map { x -> x.name.toString() }

private fun diffLibraryClasses(library1: Library, library2: Library) = library1.allTypes() - library2.allTypes()

fun makePictures(libraries: List<Library>) = libraries.forEach { library -> DOTVisualization().makePicture(library) }

private fun findJavaFile(path: Path, name: String) = path.toFile().walk().single { file -> file.name == name }

fun prettyPrinter(string: String): String {
    var intend = 0
    val buffer = StringBuilder()
    for (char in string) {
        buffer.append(char)
        if (char == '(') {
            buffer.append('\n')
            intend++
            buffer.append(" ".repeat(intend * 2))
        } else if (char == ')') {
            intend--
        } else if (char == ',') {
            buffer.append('\n')
            buffer.append(" ".repeat(intend * 2 - 1))
        }
    }
    return buffer.toString()
}

data class CallExpressionParams(val scope: Expression?, val args: List<Expression>)

fun parseFile(file: File): CompilationUnit {
    val fis = FileInputStream(file)
    return JavaParser.parse(fis);
}

private class CodeElementsVisitor : VoidVisitorAdapter<CodeElements>() {
    override fun visit(n: MethodDeclaration, arg: CodeElements) {
        arg.methodDecls.add(MethodOrConstructorDeclaration(n));
        super.visit(n, arg)
    }

    override fun visit(n: MethodCallExpr, arg: CodeElements) {
        arg.methodCalls.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: ClassOrInterfaceDeclaration, arg: CodeElements) {
        arg.classes.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: ConstructorDeclaration, arg: CodeElements) {
        arg.methodDecls.add(MethodOrConstructorDeclaration(n));
        super.visit(n, arg)
    }

    override fun visit(n: ObjectCreationExpr, arg: CodeElements) {
        arg.objectCreation.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: VariableDeclarationExpr, arg: CodeElements) {
        arg.variableDeclarations.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: FieldDeclaration, arg: CodeElements) {
        arg.fieldDeclaration.add(n);
        super.visit(n, arg)
    }
}

data class CodeElements(val classes: MutableList<ClassOrInterfaceDeclaration> = mutableListOf(),
                        val methodDecls: MutableList<MethodOrConstructorDeclaration> = mutableListOf(),
                        val methodCalls: MutableList<MethodCallExpr> = mutableListOf(),
                        val objectCreation: MutableList<ObjectCreationExpr> = mutableListOf(),
                        val variableDeclarations: MutableList<VariableDeclarationExpr> = mutableListOf(),
                        val fieldDeclaration: MutableList<FieldDeclaration> = mutableListOf())

data class MethodDiff(val methodName: String,
                      val newInSrc: List<IndexedValue<Parameter>>,
                      val newInDst: List<IndexedValue<Parameter>>) {
    fun methodChanged() = newInSrc.isNotEmpty() || newInDst.isNotEmpty()
}

class MethodOrConstructorDeclaration(val node: CallableDeclaration<*>) {
    fun get() = node
    fun getCodeElements(): CodeElements {
        val methodLocalCodeElements = CodeElements()
        if (node is MethodDeclaration) { // TODO: Unify
            CodeElementsVisitor().visit(node, methodLocalCodeElements);
        } else if (node is ConstructorDeclaration) {
            CodeElementsVisitor().visit(node, methodLocalCodeElements);
        }
        return methodLocalCodeElements
    }

    fun name() = if (node is MethodDeclaration) node.name.identifier else "<init>"
    fun arguments() = node.parameters
}

private val logger = KotlinLogging.logger {}

data class ClassDiff(val name: String,
                     val methodsChanged: Map<MethodDeclaration, MethodDiff>)

interface Project {
    val projectDir: Path
    val moduleDir: Path
    val traceFile: Path
    val answersFile: Path

    fun checkMigrationCorrectness(testDir: Path, testClassName: String?): Boolean
}

class GradleProject(override val projectDir: Path) : Project {
    override val moduleDir: Path = projectDir
    override val traceFile: Path = projectDir.resolve("log.json")
    override val answersFile: Path = projectDir.resolve("answers.json")

    val connector = GradleConnector.newConnector()

    private fun runGradleTask(connection: ProjectConnection, taskName: String): Pair<Boolean, String> {
        val buildOutputStream = ByteArrayOutputStream()
        val buildLauncher = connection.newBuild().forTasks(taskName).withArguments("-i")
                .setStandardError(buildOutputStream).setStandardOutput(buildOutputStream)
        try {
            buildLauncher.run()
            return Pair(true, "")
        } catch (ex: Exception) {
            return Pair(false, ex.toString() + "\n" + buildOutputStream.toString(Charset.defaultCharset().toString()))
        }
    }

    private fun runGradleTest(connection: ProjectConnection, testClassName: String): Pair<Boolean, String> {
        val buildOutputStream = ByteArrayOutputStream()
        val buildLauncher = connection.newTestLauncher().withJvmTestClasses(testClassName).withArguments("-i")
                .setStandardError(buildOutputStream).setStandardOutput(buildOutputStream)
        try {
            buildLauncher.run()
            return Pair(true, "")
        } catch (ex: Exception) {
            return Pair(false, ex.toString() + "\n" + buildOutputStream.toString(Charset.defaultCharset().toString()))
        }
    }

    override fun checkMigrationCorrectness(testDir: Path, testClassName: String?): Boolean {
        logger.info("Running migrated code")

        val testProjectConnection = connector.forProjectDirectory(testDir.toFile()).connect()

        val (buildResult, buildOutput) = runGradleTask(testProjectConnection, "assemble")
        if (buildResult == false) {
            logger.error("Compilation failed!")
            logger.error(buildOutput)
            throw Exception("Compilation failed: " + buildOutput)
        }

        val runTests = true
        val (testResult, testOutput) = when {
            runTests && testClassName != null -> runGradleTest(testProjectConnection, testClassName)
            runTests && testClassName == null -> runGradleTask(testProjectConnection, "test")
            else -> Pair(true, "")
        }
        testProjectConnection.close()
        if (testResult) {
            logger.info("Migration OK")
            return true
        } else {
            logger.error("Migrated code doesn't work properly")
            logger.error("Migrated:")
            logger.error(testOutput)
            throw Exception("Migrated code doesn't work properly: " + testOutput)
        }
    }

    fun getTypeSolver(): TypeSolver {
        val combinedTypeSolver = CombinedTypeSolver()
        combinedTypeSolver.add(ReflectionTypeSolver())

        val connection = connector.forProjectDirectory(projectDir.toFile()).connect()
        val model = connection.model(EclipseProject::class.java).get()
        for (dependency in model.classpath.all) {
            println(dependency.file)
            combinedTypeSolver.add(JarTypeSolver.getJarTypeSolver(dependency.file.absolutePath))
        }
        for (source in model.sourceDirectories.all) {
            println(source.directory)
            combinedTypeSolver.add(JavaParserTypeSolver(source.directory))
        }
//        val model2 = connection.model(org.gradle.tooling.model.DomainObjectSet<ExternalDependency>::class.java).get()
//        val model3 = connection.model(org.gradle.tooling.model.Dependency::class.java).get()

//        combinedTypeSolver.add(JavaParserTypeSolver(File("src/test/resources/javaparser_src/proper_source")))
//        combinedTypeSolver.add(JavaParserTypeSolver(File("src/test/resources/javaparser_src/generated")))

        return combinedTypeSolver
    }

    val javaParserFacade: JavaParserFacade by lazy {
        JavaParserFacade.get(getTypeSolver())
    }
}

class MavenProject(override val projectDir: Path,
                   override val moduleDir: Path) : Project {
    override val traceFile: Path = moduleDir.resolve("log.json")
    override val answersFile: Path = projectDir.resolve("answers.json")

    override fun checkMigrationCorrectness(testDir: Path, testClassName: String?): Boolean = true
}

class MigrationManager(val from: Library,
                       val to: Library,
                       val project: Project) {
    private val copy = project.projectDir.parent.fileName != Paths.get("migrated")
    private val migratedDir = if (copy) {
        project.projectDir.resolveSibling("migrated")
    } else {
        project.projectDir.parent
    }
    val destDir = migratedDir.resolve("${project.projectDir.fileName}_migrated_${from.name}_${to.name}")

    init {
        if (!Files.isDirectory(migratedDir)) Files.createDirectory(migratedDir)

        destDir.toFile().deleteRecursively()

        Files.walk(project.projectDir).forEach { path ->
            Files.copy(path, destDir.resolve(project.projectDir.relativize(path)))
        }
    }


    fun findJavaFilesForMigration(root: Path, importsForMigration: List<String>) = root.toFile().walk().filter { file -> file.extension == "java" }.mapNotNull { javaSource ->
        val cu = parseFile(javaSource)
        val fileImports = parseImports(cu.imports)
        if (fileImports.intersect(importsForMigration).isNotEmpty()) {
            Pair(javaSource, cu)
        } else {
            null
        }
    }

    fun migrateFile(codeElements: CodeElements,
                    file: File,
                    invocations: GroupedInvocation) {
        MDC.put("filename", file.name)
        for (methodDecl in codeElements.methodDecls) {
            MDC.put("method", methodDecl.name())
            val methodLocalCodeElements = methodDecl.getCodeElements()

            val migration = Migration(
                    library1 = from,
                    library2 = to,
                    codeElements = methodLocalCodeElements,
                    functionName = methodDecl.name(),
                    sourceFile = file,
                    invocations = invocations,
                    project = project)

            migration.doMigration()
            migration.migrateFunctionArguments(methodDecl)
            migration.migrateReturnValue(methodDecl)
        }

        for (field in codeElements.fieldDeclaration) {
            if (field.variables.size != 1) {
                TODO()
            }
            val variable = field.variables.first()
            MDC.put("method", variable.name.asString())
            val localCodeElements = CodeElements(
                    methodCalls = variable.getChildNodesByType(MethodCallExpr::class.java),
                    objectCreation = variable.getChildNodesByType(ObjectCreationExpr::class.java),
                    methodDecls = codeElements.methodDecls)
            val migration = Migration(from, to, localCodeElements, "<init>", file, invocations, project)
            migration.migrateClassField(field, variable)
        }
        MDC.remove("filename")
        MDC.remove("method")
//    fixEntityTypes(codeElements, library1, library2)
    }

    fun checkMigrationCorrectness(testClassName: String?): Boolean = project.checkMigrationCorrectness(destDir, testClassName)
}