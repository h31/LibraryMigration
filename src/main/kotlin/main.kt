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
        if ((methodDecl.name() == "java") == false) continue
        val methodLocalCodeElements = methodDecl.getCodeElements()

        val migration = Migration(
                library1 = java,
                library2 = apache,
                codeElements = methodLocalCodeElements,
                functionName = methodDecl.name(),
                traceFile = File("HTTP/log.json"))

        migration.doMigration()
        fixEntityTypes(codeElements, java, apache, methodDecl.get().beginLine, methodDecl.get().endLine)
    }
}

fun findActionsInCode(srcLibrary: Library, codeElements: CodeElements) {
    val edges = srcLibrary.stateMachines.flatMap { machine -> machine.edges }
    for (edge in edges) {
        val calls = codeElements.methodCalls
    }
}

private fun findJavaCode(path: Path) = path.toFile().walk().single { file -> file.endsWith(".java") }

private fun prettyPrinter(string: String): String {
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

data class InsertionPoint(val scope: Expression?, val parent: Node)

private fun getArgs() {
//    val callAction = unpackCallAction(step)
//    val args = if (callAction.param != null && callAction.param.machine.entity == action.param?.machine?.entity) {
//        methodCall.args
//    } else {
//        listOf()
//    }
}

data class CallExpressionParams(val scope: Expression?, val args: List<Expression>)
//class NeedDependencyException(val machine: StateMachine) : Exception()

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

//private fun makeArray(action: MakeArrayAction): List<Statement> {
//    val getSize = action.getSize
//    val getItem = action.getItem
//    val scope = if (point.scope is MethodCallExpr) point.scope.scope else point.scope
//    val getSizeNode = MethodCallExpr(scope.clone() as Expression, getSize.methodName, listOf())
//    val getItemNode = MethodCallExpr(scope.clone() as Expression, getItem.methodName, listOf(NameExpr("i")))
//
//    val listName = "tmp" + (listNameCounter++)
//    val list = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType("List"), listName)
//    list.vars.first().init = ObjectCreationExpr(null, ClassOrInterfaceType("ArrayList"), listOf(getSizeNode.clone() as Expression))
//    val listNode = NameExpr(listName)
//
//    val init = ASTHelper.createVariableDeclarationExpr(ASTHelper.INT_TYPE, "i")
//    init.vars.first().init = IntegerLiteralExpr("0")
//    val compare = BinaryExpr(NameExpr("i"), getSizeNode, BinaryExpr.Operator.less)
//    val update = UnaryExpr(NameExpr("i"), UnaryExpr.Operator.posIncrement)
//
//    val listFill = MethodCallExpr(listNode, "add", listOf(NameExpr("i"), getItemNode))
//    val body = BlockStmt(listOf(ExpressionStmt(listFill)))
//    val forLoop = ForStmt(listOf(init), compare, listOf(update), body)
//
//    return listOf(forLoop, ExpressionStmt(list))
////    val pos = 0 // blockStmt.stmts.indexOf(node)
////    blockStmt.stmts.add(pos, forLoop)
////    blockStmt.stmts.add(pos, ExpressionStmt(list))
//
////    val parent = point.scope.parentNode
////    parent.childrenNodes.remove(point.scope)
////    if (parent is MethodCallExpr) {
////        parent.scope = listNode
////    }
//}

private fun fixEntityTypes(codeElements: CodeElements, graph1: Library, graph2: Library, beginLine: Int, endLine: Int) {
    for (type in graph1.machineTypes) {
        val declarations = codeElements.variableDeclarations.filter { it -> it.type.toString() == type.value && checkNodePosition(it, beginLine, endLine) }
        for (decl in declarations) {
            decl.type = ClassOrInterfaceType(graph2.machineTypes.get(type.key))
        }
        val objectCreations = codeElements.objectCreation.filter { it -> it.type.toString() == type.value  && checkNodePosition(it, beginLine, endLine) }
        for (obj in objectCreations) {
            obj.type = ClassOrInterfaceType(graph2.machineTypes.get(type.key))
        }
    }
}

private fun checkNodePosition(node: Node, beginLine: Int, endLine: Int) = node.beginLine >= beginLine && node.endLine <= endLine

//fun doMigration(srcLib: Library, dstLib: Library) {
//    assert(srcLib.stateMachines.size == dstLib.stateMachines.size)
//
//    for (machines in srcLib.stateMachines.zip(dstLib.stateMachines)) {
//        val machine1 = machines.first
//        val machine2 = machines.second
//        val diff = diffStateMachines(machine1, machine2)
//        val (newInSrc, newInDst) = diff;
//        for (state in newInDst) {
//            println(state)
//            val param = state.params.first()
//            println(param)
//            val userStates = findEntityUsers(srcLib, param.entity)
//            val grouped = userStates.groupBy { it -> it.findInLibrary(srcLib).entity }.map { it -> it.value.first() }
//            for (userState in grouped) {
//                val userMachine = userState.findInLibrary(srcLib);
//                println(userState)
//                val type = userMachine.entity.type;
//                if (userState.action == Action.CONSTRUCTOR) {
//                    if (state.action == Action.STATIC_CALL) {
//                        val calls = codeElements.methodCalls
//                                .filter { it -> it.scope == null }
//                                .filter { it -> it.name == state.methodName }
//                        for (call in calls) {
//                            val creationNodes = codeElements.objectCreation
//                                    .filter { it -> it.type.name == type }
//                                    .filter { it -> isChildOf(call, it) }
//                            if (creationNodes.isNotEmpty()) {
//                                val node = creationNodes.first()
//                                if (node.args != null) {
//                                    val paramValue = node.args.get(param.pos)
//                                    call.args.add(param.pos, paramValue)
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        }
//        for (state in newInSrc) {
//            println(state)
//            val param = state.params.first()
//            println(param)
//            if (state.action == Action.CONSTRUCTOR) {
//                val creationNodes = codeElements.objectCreation.filter { it -> it.type.name == machine1.entity.type }
//                for (node in creationNodes) {
//                    if (node.args != null && node.args.size > param.pos) {
//                        node.args.removeAt(param.pos)
//                    }
//                }
//            }
//        }
//    }
//}

fun diffStateMachines(src: StateMachine, dst: StateMachine): Pair<List<State>, List<State>> {
    val srcList = src.states.toMutableList()
    srcList.removeAll { srcIt -> dst.states.any { dstIt -> srcIt.name == dstIt.name } }

    val dstList = dst.states.toMutableList()
    dstList.removeAll { dstIt -> src.states.any { srcIt -> srcIt.name == dstIt.name } }

    return Pair(srcList, dstList)
}

fun isChildOf(parent: Node, child: Node): Boolean {
    var node = child.parentNode;
    while (node != null) {
        if (node === parent) {
            return true;
        }
        node = node.parentNode
    }
    return false;
}

//fun findEntityUsers(library: Library, entity: Entity): List<Edge> {
//    val users = mutableListOf<Edge>();
//    for (machine in library.stateMachines) {
//        for (edge in machine.edges) {
//            for (param in edge.params) {
//                if (param.entity == entity) {
//                    users.add(edge)
//                }
//            }
//        }
//    }
//    return users;
//}

fun readLib(file: File): CompilationUnit {
    val fis = FileInputStream(file)
    return JavaParser.parse(fis);
}

data class SplittedImport(val className: String,
                          val packageName: String)

fun parseImports(imports: List<ImportDeclaration>) = imports.map { x ->
    val name = x.name
    val packageName = if (name is QualifiedNameExpr) name.qualifier.toString() else ""
    SplittedImport(name.name, packageName)
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
    testDir.toFile().walk(FileWalkDirection.BOTTOM_UP).forEach { file -> file.delete() }

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

//fun makeSpark1(entities: Map<String, Entity>): Library {
//    val init = makeInitState()
//    val constructed = makeConstructedState()
//    val registerPathGet = Edge(src = init,
//            dst = constructed,
//            action = Action.CONSTRUCTOR,
//            methodName = "Route",
//            params = listOf(Param(entities["path"]!!, 0)))
//    val registerPathFilter = registerPathGet.copy(methodName = "Filter")
//
//    val getHandler = StateMachine(entity = entities["route"]!!,
//            states = listOf(init, constructed),
//            edges = listOf(registerPathGet))
//
//    val filter = getHandler.copy(entity = entities["filter"]!!,
//            edges = listOf(registerPathFilter))
//
//    val postHandler = getHandler.copy()
//    val putHandler = getHandler.copy()
//    val deleteHandler = getHandler.copy()
//
//    val handlerState = makeInitState()
//
//    val get = Edge(src = handlerState,
//            dst = handlerState,
//            action = Action.STATIC_CALL,
//            methodName = "get",
//            params = listOf(Param(entities["route"]!!, 0)))
//
//    val before = Edge(src = handlerState,
//            dst = handlerState,
//            action = Action.STATIC_CALL,
//            methodName = "before",
//            params = listOf(Param(entities["filter"]!!, 0)))
//
//    val after = before.copy(methodName = "after")
//    val post = get.copy(methodName = "post")
//    val put = get.copy(methodName = "put")
//    val delete = get.copy(methodName = "delete")
//    val webServer = StateMachine(entity = entities["server"]!!,
//            states = listOf(handlerState),
//            edges = listOf(get, before, after, post, put, delete))
//
//    return Library(listOf(webServer, getHandler, filter, postHandler, putHandler, deleteHandler))
//}

//fun makeSpark2(entities: Map<String, Entity>): Library {
//    val handler = StateMachine(entities["route"]!!, listOf())
//
//    val filter = StateMachine(entities["filter"]!!, listOf())
//    val afterHandler = filter.copy()
//
//    val postHandler = handler.copy()
//    val putHandler = handler.copy()
//    val deleteHandler = handler.copy()
////    val getTypeHandler = StateMachine(entities["transformer"]!!, listOf())
//
//    val registerPathGet = State(name = "registerPathGet",
//            before = listOf(),
//            action = Action.STATIC_CALL,
//            methodName = "get",
//            params = listOf(Param(entities["path"]!!, 0)))
//
//    val registerPathFilter = registerPathGet.copy(name = "registerPathFilter", methodName = "before")
//    val registerPathAfter = registerPathFilter.copy(methodName = "after")
//    val registerPathPost = registerPathGet.copy(methodName = "post")
//    val registerPathPut = registerPathGet.copy(methodName = "put")
//    val registerPathDelete = registerPathGet.copy(methodName = "delete")
//
////    val registerPathGetType = registerPathGet.copy(name = "registerPathGetType",
////            methodName = "get", params = listOf(Param(entities["path"]!!, 0)))
////    val registerPathType = registerPathGet.copy(name = "registerPathType",
////            methodName = "get", params = listOf(Param(entities["acceptType"]!!, 1)))
//
//    val get = State(name = "get",
//            before = listOf(registerPathGet),
//            action = Action.STATIC_CALL,
//            methodName = "get",
//            params = listOf(Param(entities["route"]!!, 1)))
//
//    val before = State(name = "before",
//            before = listOf(registerPathFilter),
//            action = Action.STATIC_CALL,
//            methodName = "before",
//            params = listOf(Param(entities["filter"]!!, 1)))
//
//    val after = before.copy(name = "after",
//            before = listOf(registerPathAfter),
//            methodName = "after")
//
//    val post = get.copy(name = "post",
//            before = listOf(registerPathPost),
//            methodName = "post")
//
//    val put = get.copy(name = "put",
//            before = listOf(registerPathPut),
//            methodName = "put")
//
//    val delete = get.copy(name = "delete",
//            before = listOf(registerPathDelete),
//            methodName = "delete")
//
////    val getType = get.copy(name = "get",
////            before = listOf(registerPathType),
////            methodName = "get",
////            params = listOf(Param(entities["route"]!!, 2)))
//
//    val webServer = StateMachine(entity = entities["server"]!!,
//            states = listOf(registerPathGet, get, registerPathFilter, before, registerPathAfter, after, registerPathPost,
//                    post, registerPathPut, put, registerPathDelete, delete))
//
//    return Library(listOf(webServer, handler, filter, afterHandler, postHandler, putHandler, deleteHandler))
//}