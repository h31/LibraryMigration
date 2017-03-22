import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import mu.KotlinLogging
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
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
    makePictures(HttpModels.withName())

//    migrate(projectDir = Paths.get("examples/instagram-java-scraper"),
//            from = models["okhttp"]!!,
//            to = models["java"]!!
//    )
    migrate(projectDir = Paths.get("/home/artyom/Compile/acme4j/acme4j-client"),
            from = HttpModels.java,
            to = HttpModels.okhttp
    )
}

fun migrate(projectDir: Path,
            traceFile: Path = projectDir.resolve("log.json"),
            answersFile: Path = projectDir.resolve("answers.json"),
            from: Library,
            to: Library,
            testPatcher: (Path) -> Unit = {},
            testClassName: String? = null): Boolean {
    val importsForMigration = diffLibraryClasses(from, to)
    val pending = findJavaFilesForMigration(projectDir, importsForMigration)
    if (pending.none()) {
        error("Nothing to migrate")
    }

    val invocations = parseInvocations(traceFile.toFile())

    val testDir = projectDir.resolveSibling("${projectDir.fileName}_test_${from.name}_${to.name}")
    prepareTestDir(projectDir, testDir)

    for ((source, cu) in pending) {
        logger.info("Migrating $source")
        val codeElements = CodeElements();
        CodeElementsVisitor().visit(cu, codeElements);

        migrateFile(from, to, codeElements, source, invocations)
        addImports(cu, to)

        val migratedCode = cu.toString()
        println(migratedCode);

        val relativePath = projectDir.relativize(source.toPath())
        Files.write(testDir.resolve(relativePath), migratedCode.toByteArray())
    }
    testPatcher(testDir)
    return checkMigrationCorrectness(testDir, testClassName)
}

typealias GroupedInvocation = Map<String, Map<String, List<RouteExtractor.Invocation>>>

private fun parseInvocations(traceFile: File): GroupedInvocation {
    val invocations = ObjectMapper().registerKotlinModule().readValue<List<RouteExtractor.Invocation>>(traceFile)
    val grouped = invocations.groupBy { it.filename }.mapValues { it.value.groupBy { it.callerName } } // TODO: groupingBy
    return grouped
}

private fun prepareTestDir(projectDir: Path, testDir: Path) {
    testDir.toFile().deleteRecursively()

    Files.walk(projectDir).forEach { path ->
        Files.copy(path, testDir.resolve(projectDir.relativize(path)))
    }
}

private fun addImports(cu: CompilationUnit, library: Library) {
    cu.imports.addAll((library.allTypes())
            .filter { type -> type.contains('.') }
            .filterNot { type -> type.contains('$') }
            .map { type -> ImportDeclaration(Name(type), false, false) })
}

private fun parseImports(imports: List<ImportDeclaration>) = imports.map { x -> x.name.toString() }

private fun diffLibraryClasses(library1: Library, library2: Library) = library1.allTypes() - library2.allTypes()

private fun findJavaFilesForMigration(root: Path, importsForMigration: List<String>) = root.toFile().walk().filter { file -> file.extension == "java" }.mapNotNull { javaSource ->
    val cu = parseFile(javaSource)
    val fileImports = parseImports(cu.imports)
    if (fileImports.intersect(importsForMigration).isNotEmpty()) {
        Pair(javaSource, cu)
    } else {
        null
    }
}

fun makePictures(libraries: Map<String, Library>) = libraries.forEach {
    library ->
    graphvizRender(toDOT(library.value), library.key)
}

fun migrateFile(library1: Library,
                library2: Library,
                codeElements: CodeElements,
                file: File,
                invocations: GroupedInvocation) {
    for (methodDecl in codeElements.methodDecls) {
        val methodLocalCodeElements = methodDecl.getCodeElements()

        val migration = Migration(
                library1 = library1,
                library2 = library2,
                codeElements = methodLocalCodeElements,
                functionName = methodDecl.name(),
                sourceFile = file,
                invocations = invocations)

        migration.doMigration()
        migration.migrateClassMembers(codeElements)
        migration.migrateFunctionArguments(methodDecl)
        migration.migrateReturnValue(methodDecl)
    }
//    fixEntityTypes(codeElements, library1, library2)
}

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
        arg.nodes.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: ClassOrInterfaceDeclaration, arg: CodeElements) {
        arg.classes.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: ConstructorDeclaration, arg: CodeElements) {
        arg.methodDecls.add(MethodOrConstructorDeclaration(n));
        arg.nodes.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: ObjectCreationExpr, arg: CodeElements) {
        arg.objectCreation.add(n);
        arg.nodes.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: VariableDeclarationExpr, arg: CodeElements) {
        arg.variableDeclarations.add(n);
        super.visit(n, arg)
    }

    override fun visit(n: BlockStmt, arg: CodeElements) {
        arg.blockStmts.add(n);
        super.visit(n, arg)
    }
}

data class CodeElements(val classes: MutableList<ClassOrInterfaceDeclaration> = mutableListOf(),
                        val methodDecls: MutableList<MethodOrConstructorDeclaration> = mutableListOf(),
                        val methodCalls: MutableList<MethodCallExpr> = mutableListOf(),
                        val objectCreation: MutableList<ObjectCreationExpr> = mutableListOf(),
                        val variableDeclarations: MutableList<VariableDeclarationExpr> = mutableListOf(),
                        val blockStmts: MutableList<BlockStmt> = mutableListOf(),
                        val nodes: MutableList<Node> = mutableListOf())

data class MethodDiff(val methodName: String,
                      val newInSrc: List<IndexedValue<Parameter>>,
                      val newInDst: List<IndexedValue<Parameter>>) {
    fun methodChanged() = newInSrc.isNotEmpty() || newInDst.isNotEmpty()
}

class MethodOrConstructorDeclaration(val node: CallableDeclaration<out Node>) { // TODO: Node?
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

fun checkMigrationCorrectness(testDir: Path, testClassName: String?): Boolean {
    logger.info("Running migrated code")

    val connector = GradleConnector.newConnector()
    val connection = connector.forProjectDirectory(testDir.toFile()).connect()

    val (buildResult, buildOutput) = runGradleTask(connection, "assemble")
    if (buildResult == false) {
        logger.error("Compilation failed!")
        logger.error(buildOutput)
        return false
    }

    val runTests = true
    val (testResult, testOutput) = when {
        runTests && testClassName != null -> runGradleTest(connection, testClassName)
        runTests && testClassName == null -> runGradleTask(connection, "test")
        else -> Pair(true, "")
    }
    connection.close()
    if (testResult) {
        logger.info("Migration OK")
        return true
    } else {
        logger.error("Migrated code doesn't work properly")
        logger.error("Migrated:")
        logger.error(testOutput)
        return false
    }
}

private fun runGradleTask(connection: ProjectConnection, taskName: String): Pair<Boolean, String> {
    val buildOutputStream = ByteArrayOutputStream()
    val buildLauncher = connection.newBuild().forTasks(taskName).withArguments("-i").setStandardError(buildOutputStream)
    try {
        buildLauncher.run()
        return Pair(true, "")
    } catch (ex: Exception) {
        return Pair(false, ex.toString() + "\n" + buildOutputStream.toString(Charset.defaultCharset().toString()))
    }
}

private fun runGradleTest(connection: ProjectConnection, testClassName: String): Pair<Boolean, String> {
    val buildOutputStream = ByteArrayOutputStream()
    val buildLauncher = connection.newTestLauncher().withJvmTestClasses(testClassName).withArguments("-i").setStandardError(buildOutputStream)
    try {
        buildLauncher.run()
        return Pair(true, "")
    } catch (ex: Exception) {
        return Pair(false, ex.toString() + "\n" + buildOutputStream.toString(Charset.defaultCharset().toString()))
    }
}