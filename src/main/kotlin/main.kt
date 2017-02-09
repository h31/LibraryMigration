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
import com.github.javaparser.ast.stmt.ExpressionStmt
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
            to = HttpModels.apache
    )
}

fun migrate(projectDir: Path,
            traceFile: Path = projectDir.resolve("log.json"),
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
//        println(migratedCode);

        val relativePath = projectDir.relativize(source.toPath())
        Files.write(testDir.resolve(relativePath), migratedCode.toByteArray())
    }
    testPatcher(testDir)
    return checkMigrationCorrectness(testDir, testClassName)
}

private fun parseInvocations(traceFile: File) = ObjectMapper().registerKotlinModule().readValue<List<RouteExtractor.Invocation>>(traceFile)

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
            .map { type -> ImportDeclaration(NameExpr(type), false, false) })
}

private fun migrateClassMembers(library1: Library, library2: Library,
                                codeElements: CodeElements) {
    for (classDecl in codeElements.classes) {
        val fields = classDecl.members.filterIsInstance<FieldDeclaration>()
        for (field in fields) {
            if (field.variables.size > 1) {
                continue
            }
            val fieldType = field.type.toString()
            field.type = getNewType(fieldType, library1, library2) ?: field.type
        }
    }
}

private fun migrateFunctionArguments(library1: Library, library2: Library,
                                     methodDecl: MethodOrConstructorDeclaration) {
    val node = methodDecl.get()
    if (node is ConstructorDeclaration) {
        val args = node.parameters
        for (arg in args) {
            val argType = arg.type.toString()
            arg.type = getNewType(argType, library1, library2) ?: arg.type
        }
    }
}

private fun migrateReturnValue(library1: Library, library2: Library, methodDecl: MethodOrConstructorDeclaration) {
    val node = methodDecl.get()
    if (node is MethodDeclaration) {
        node.type = getNewType(node.type.toString(), library1, library2) ?: node.type
    }
}

private fun getNewType(oldType: String, library1: Library, library2: Library): ClassOrInterfaceType? {
    val machine = library1.machineTypes.filterValues { type -> library1.simpleType(type) == oldType }.keys.firstOrNull()
    if (machine != null) {
        val realMachine = if (machine.name == "HttpConnection") library1.stateMachines.first { it.name == "Connection" } else machine
        if (library2.machineTypes.contains(realMachine) == false) return null // TODO()
        val newType = library2.machineTypes[realMachine]
        return ClassOrInterfaceType(newType)
    } else {
        return null
    }
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
                invocations: List<RouteExtractor.Invocation>) {
    for (methodDecl in codeElements.methodDecls) {
        val methodLocalCodeElements = methodDecl.getCodeElements()

        val migration = Migration(
                library1 = library1,
                library2 = library2,
                codeElements = methodLocalCodeElements,
                functionName = methodDecl.name(),
                sourceFile = file,
                invocations = invocations)

        try {
            migration.doMigration()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
//        migrateClassMembers(library1, library2, codeElements)
//        migrateFunctionArguments(library1, library2, methodDecl)
//        migrateReturnValue(library1, library2, methodDecl)
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

private fun setAsChild(parent: Node, expr: Expression) {
    if (parent is MethodCallExpr) {
        parent.scope = expr
    } else if (parent is ExpressionStmt) {
        parent.expression = expr
    } else if (parent is VariableDeclarator) {
        parent.init = expr
    } else {
        error("TODO")
    }
}

var listNameCounter = 0;

private fun fixEntityTypes(codeElements: CodeElements, graph1: Library, graph2: Library) {
    for (type in graph1.machineSimpleTypes) {
        val newType = graph2.machineSimpleTypes[type.key]
        if (newType != null) {
            val declarations = codeElements.variableDeclarations.filter { it -> it.type.toString() == type.value }
            for (decl in declarations) {
                decl.type = ClassOrInterfaceType(newType)
            }
            val objectCreations = codeElements.objectCreation.filter { it -> it.type.toString() == type.value }
            for (obj in objectCreations) {
                obj.type = ClassOrInterfaceType(newType)
            }
        }
    }
}

private fun checkNodePosition(node: Node, beginLine: Int, endLine: Int) = node.begin.line >= beginLine && node.end.line <= endLine

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

class MethodOrConstructorDeclaration(val node: BodyDeclaration) {
    fun get() = node
    fun getCodeElements(): CodeElements {
        val methodLocalCodeElements = CodeElements()
        if (node is MethodDeclaration) {
            CodeElementsVisitor().visit(node, methodLocalCodeElements);
        } else if (node is ConstructorDeclaration) {
            CodeElementsVisitor().visit(node, methodLocalCodeElements);
        }
        return methodLocalCodeElements
    }

    fun name() = if (node is MethodDeclaration) node.name else "<init>"
    fun arguments() = when (node) {
        is MethodDeclaration -> node.parameters
        is ConstructorDeclaration -> node.parameters
        else -> throw IllegalArgumentException()
    }
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