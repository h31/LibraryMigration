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
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by aleksyuk on 4/7/16.
 */

fun main(args: Array<String>) {
    val models = libraryModels()
    makePictures(models)

    migrate(projectPath = Paths.get("examples/instagram-java-scraper"),
            from = models["okhttp"]!!,
            to = models["java"]!!
    )
//    migrate(projectPath = Paths.get("HTTP"),
//            from = models["apache"]!!,
//            to = models["okhttp"]!!
//    )
}

fun migrate(projectPath: Path,
            traceFile: Path = projectPath.resolve("log.json"),
            from: Library,
            to: Library,
            runClass: String? = null,
            testPatcher: (Path) -> Unit = {}): Boolean {
    val importsForMigration = diffLibraryClasses(from, to)
    val pending = findJavaFilesForMigration(projectPath, importsForMigration)
    if (pending.none()) {
        error("Nothing to migrate")
    }
    for ((source, cu) in pending) {
        val codeElements = CodeElements();
        CodeElementsVisitor().visit(cu, codeElements);

        migrateFile(from, to, codeElements, traceFile.toFile())
        addImports(cu, to)

        val migratedCode = cu.toString()
        println(migratedCode);
        val result = checkMigrationCorrectness(source.toPath(), projectPath, migratedCode, runClass, testPatcher)
        if (result == false) {
            return false
        }
    }
    return true
}

private fun addImports(cu: CompilationUnit, library: Library) {
    cu.imports.addAll((library.machineTypes.values + library.additionalTypes)
            .filter { type -> type.contains('.') }
            .filterNot { type -> type.contains('$') }
            .map { type -> ImportDeclaration(NameExpr(type), false, false) })
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

fun libraryModels() = listOf(makeJava(), makeApache(), makeOkHttp()).associateBy(Library::name)

fun makePictures(libraries: Map<String, Library>) = libraries.forEach {
    library ->
    graphvizRender(toDOT(library.value), library.key)
}

fun migrateFile(library1: Library,
                library2: Library,
                codeElements: CodeElements,
                traceFile: File) {
    for (methodDecl in codeElements.methodDecls) {
        val methodLocalCodeElements = methodDecl.getCodeElements()

        val migration = Migration(
                library1 = library1,
                library2 = library2,
                codeElements = methodLocalCodeElements,
                functionName = methodDecl.name(),
                traceFile = traceFile)

        migration.doMigration()
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

private fun checkNodePosition(node: Node, beginLine: Int, endLine: Int) = node.beginLine >= beginLine && node.endLine <= endLine

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

    fun name() = if (node is MethodDeclaration) node.name else "constructor"
    fun arguments() = when (node) {
        is MethodDeclaration -> node.parameters
        is ConstructorDeclaration -> node.parameters
        else -> throw IllegalArgumentException()
    }
}

data class ClassDiff(val name: String,
                     val methodsChanged: Map<MethodDeclaration, MethodDiff>)

fun checkMigrationCorrectness(migratedFile: Path, projectDir: Path,
                              migratedCode: String, runClass: String? = null,
                              testPatcher: (Path) -> Unit = {}): Boolean {
    val testDir = projectDir.resolveSibling(projectDir.fileName.toString() + "_test")
    val relativePath = projectDir.relativize(migratedFile)
    testDir.toFile().deleteRecursively()

    Files.walk(projectDir).forEach { path ->
        Files.copy(path, testDir.resolve(projectDir.relativize(path)))
    }

    Files.write(testDir.resolve(relativePath), migratedCode.toByteArray())
    testPatcher(testDir)

    println("Running migrated code")

    val connector = GradleConnector.newConnector()
    val connection = connector.forProjectDirectory(testDir.toFile()).connect()
    val buildOutputStream = ByteArrayOutputStream()

    if (runGradleTask(connection, "build", buildOutputStream) == false) {
        println("Compilation failed!")
        println(buildOutputStream.toString(Charset.defaultCharset().toString()))
        return false
    }

    val testOutputStream = ByteArrayOutputStream()
    if (runGradleTask(connection, "test", testOutputStream)) {
        println("Migration OK")
        return true
    } else {
        println("Migrated code doesn't work properly")
        println("Migrated:")
        println(buildOutputStream.toString(Charset.defaultCharset().toString()))
        return false
    }
}

private fun runGradleTask(connection: ProjectConnection, taskName: String, output: OutputStream): Boolean {
    val buildLauncher = connection.newBuild().forTasks(taskName).setStandardOutput(output)
    try {
        buildLauncher.run()
        return true
    } catch (ex: Exception) {
        return false
    }
}