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
    migrateHTTP(projectPath = Paths.get("HTTP/"))
}

fun migrateHTTP(projectPath: Path) {
    val source = findJavaCode(projectPath)

    val cu = readLib(source)

    val codeElements = CodeElements();
    CodeElementsVisitor().visit(cu, codeElements);

    val java = makeJava()
    val apache = makeApache()

    graphvizRender(toDOT(java), java.name)
    graphvizRender(toDOT(apache), apache.name)

    javaToApache(java, apache, codeElements)

    val migratedCode = cu.toString()
    println(migratedCode);
    checkMigrationCorrectness(source.toPath(), projectPath, migratedCode)
}

fun javaToApache(java: Library, apache: Library, codeElements: CodeElements) {
    for (methodDecl in codeElements.methodDecls) {
        val methodLocalCodeElements = methodDecl.getCodeElements()

        val migration = Migration(
                library1 = apache,
                library2 = java,
                codeElements = methodLocalCodeElements,
                functionName = methodDecl.name(),
                traceFile = File("HTTP/log.json"))

        migration.doMigration()
    }
    fixEntityTypes(codeElements, apache, java)
}

private fun findJavaCode(path: Path) = path.toFile().walk().single { file -> file.extension == "java" }

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
    for (type in graph1.machineTypes) {
        val newType = graph2.machineTypes[type.key]
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

fun readLib(file: File): CompilationUnit {
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
}

data class ClassDiff(val name: String,
                     val methodsChanged: Map<MethodDeclaration, MethodDiff>)

fun checkMigrationCorrectness(migratedFile: Path, projectDir: Path, migratedCode: String) {
    val rt = Runtime.getRuntime();
    val command = "./gradlew -q run"
    val testDir = projectDir.resolveSibling(projectDir.fileName.toString() + "_test")
    val relativePath = projectDir.relativize(migratedFile)
    testDir.toFile().deleteRecursively()

    println("Running original code")

    val process1 = rt.exec(command, null, projectDir.toFile())
    val originalOutput = process1.inputStream.readBytes().toString(Charset.defaultCharset())

    Files.walk(projectDir).forEach { path ->
            Files.copy(path, testDir.resolve(projectDir.relativize(path)))
    }

    Files.write(testDir.resolve(relativePath), migratedCode.toByteArray())

    println("Running migrated code")

    val build = rt.exec("./gradlew --console rich build", null, testDir.toFile())
    build.waitFor()
    if (build.exitValue() != 0) {
        println("Compilation failed!")
        println(build.inputStream.readBytes().toString(Charset.defaultCharset()))
        return
    }

    val process2 = rt.exec(command, null, testDir.toFile())
    val migratedOutput = process2.inputStream.readBytes().toString(Charset.defaultCharset())

    if (originalOutput == migratedOutput) {
        println("Migration OK")
    } else {
        println("Migrated code doesn't work properly")
        println("Migrated:")
        println(migratedOutput)
        println("Original:")
        println(originalOutput)
    }
}