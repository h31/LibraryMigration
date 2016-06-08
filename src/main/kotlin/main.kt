import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import org.jgrapht.ext.DOTExporter
import org.jgrapht.ext.VertexNameProvider
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.DirectedSubgraph
import org.jgrapht.graph.SimpleDirectedGraph
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by aleksyuk on 4/7/16.
 */

fun main(args: Array<String>) {
    val source = Paths.get("../spark1/src/main/java/Main.java");
    val destination = Paths.get("../spark2/src/main/java/Main.java");

//    val lib_src = Paths.get("src.java")
//    val lib_dst = Paths.get("dst.java")

    val cu = readLib(source)

//    val srcLib = readLib(lib_src)
//    val dstLib = readLib(lib_dst)

    val codeElements = CodeElements();
    CodeElementsVisitor().visit(cu, codeElements);

    parseImports(cu.imports)

//    val srcElements = CodeElements();
//    CodeElementsVisitor().visit(srcLib, srcElements);
//
//    val dstElements = CodeElements();
//    CodeElementsVisitor().visit(dstLib, dstElements);

    val path = Entity("path", "String")
    val route = Entity("route", "Route")
    val filter = Entity("filter", "Filter")
    val server = Entity("server", "static")
    val acceptType = Entity("acceptType", "String")
    val transformer = Entity("transformer", "JsonTransformer")
    val entityMap = listOf(path, route, filter, server, acceptType, transformer).map { it -> it.name to it }.toMap()

    val spark1 = makeSpark1(entityMap)
    val spark2 = makeSpark2(entityMap)

    makeGraph(spark1, Paths.get("graph1.dot"))
    makeGraph(spark2, Paths.get("graph2.dot"))

    for (machineNum in 0..spark1.stateMachines.size - 1) {
        val machine1 = spark1.stateMachines[machineNum]
        val machine2 = spark2.stateMachines[machineNum]
        val diff = diffStateMachines(machine1, machine2)
        val (newInSrc, newInDst) = diff;
        for (state in newInDst) {
            println(state)
            val param = state.params.first()
            println(param)
            val userStates = findEntityUsers(spark1, param.entity)
            val grouped = userStates.groupBy { it -> it.findInLibrary(spark1).entity }.map { it -> it.value.first() }
            for (userState in grouped) {
                val userMachine = userState.findInLibrary(spark1);
                println(userState)
                val type = userMachine.entity.type;
                if (userState.action == Action.CONSTRUCTOR) {
                    if (state.action == Action.STATIC_CALL) {
                        val calls = codeElements.methodCalls
                                .filter { it -> it.scope == null }
                                .filter { it -> it.name == state.methodName }
                        for (call in calls) {
                            val creationNodes = codeElements.objectCreation
                                    .filter { it -> it.type.name == type }
                                    .filter { it -> isChildOf(call, it) }
                            if (creationNodes.isNotEmpty()) {
                                val node = creationNodes.first()
                                if (node.args != null) {
                                    val paramValue = node.args.get(param.pos)
                                    call.args.add(param.pos, paramValue)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (state in newInSrc) {
            println(state)
            val param = state.params.first()
            println(param)
            if (state.action == Action.CONSTRUCTOR) {
                val creationNodes = codeElements.objectCreation.filter { it -> it.type.name == machine1.entity.type }
                for (node in creationNodes) {
                    if (node.args != null && node.args.size > param.pos) {
                        node.args.removeAt(param.pos)
                    }
                }
            }
        }
    }

    println(cu);
    Files.write(destination, cu.toString().toByteArray());
}

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

fun findEntityUsers(library: Library, entity: Entity): List<State> {
    val users = mutableListOf<State>();
    for (machine in library.stateMachines) {
        for (state in machine.states) {
            for (param in state.params) {
                if (param.entity == entity) {
                    users.add(state)
                }
            }
        }
    }
    return users;
}

fun readLib(path: Path): CompilationUnit {
    val fis = FileInputStream(path.toFile())
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
        arg.methodDecls.add(MethodOrConstructor(n));
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
        arg.methodDecls.add(MethodOrConstructor(n));
        super.visit(n, arg)
    }

    override fun visit(n: ObjectCreationExpr, arg: CodeElements) {
        arg.objectCreation.add(n);
        super.visit(n, arg)
    }
}

data class CodeElements(val classes: MutableList<ClassOrInterfaceDeclaration> = mutableListOf(),
                        val methodDecls: MutableList<MethodOrConstructor> = mutableListOf(),
                        val methodCalls: MutableList<MethodCallExpr> = mutableListOf(),
                        val objectCreation: MutableList<ObjectCreationExpr> = mutableListOf())

data class MethodDiff(val methodName: String,
                      val newInSrc: List<IndexedValue<Parameter>>,
                      val newInDst: List<IndexedValue<Parameter>>) {
    fun methodChanged() = newInSrc.isNotEmpty() || newInDst.isNotEmpty()
}

data class ClassDiff(val name: String,
                     val methodsChanged: Map<MethodDeclaration, MethodDiff>)

fun makeLabel(v: State): String {
    "%s(%s: %s)".format(v.methodName, v.params.first().entity.name, v.params.first().entity.type)
    val arg = v.params.first()
    val previousArgs = "..., ".repeat(arg.pos)
    val args = "(%s%s: %s)".format(previousArgs, arg.entity.name, arg.entity.type)
    when (v.action) {
        Action.CONSTRUCTOR -> return "new " + v.methodName + args
        Action.METHOD_CALL -> return v.methodName + args
        Action.STATIC_CALL -> return v.methodName + args
    }
}

fun makeGraph(library: Library, filePath: Path) {
    val graph = SimpleDirectedGraph<State, DefaultEdge>(DefaultEdge::class.java)
    val exporter = DOTExporter<State, DefaultEdge>(VertexNameProvider { v -> v.name + "_" + v.methodName},
            VertexNameProvider { v -> makeLabel(v)}, null)

    for (fsm in library.stateMachines) {
        for (state in fsm.states) {
            graph.addVertex(state)
            for (dependency in state.before) {
                graph.addVertex(dependency)
                graph.addEdge(dependency, state)
            }
        }
        val subgraph = DirectedSubgraph(graph, fsm.states.toSet(), null)

//        exporter.export(FileWriter(filePath.toFile(), true), subgraph)
    }
//    val edge = graph.edgeSet().first()
//    val vertex = graph.vertexSet().first()
    exporter.export(FileWriter(filePath.toFile(), false), graph)
}

class MethodOrConstructor(var node: BodyDeclaration) {
    fun get() = node
}

enum class Action {
    CONSTRUCTOR, METHOD_CALL, STATIC_CALL
}

data class Entity(val name: String,
                  val type: String)

data class Library(val stateMachines: List<StateMachine>)

data class StateMachine(val entity: Entity,
                        val states: List<State>)

data class State(val name: String,
                 val before: List<State>,
                 val action: Action,
                 val methodName: String,
                 val params: List<Param>) {
    fun findInLibrary(library: Library): StateMachine {
        for (machine in library.stateMachines) {
            for (state in machine.states) {
                if (state == this) {
                    return machine;
                }
            }
        }
        throw Exception()
    }
}

data class Param(val entity: Entity,
                 val pos: Int)

fun makeSpark1(entities: Map<String, Entity>): Library {
    val registerPathGet = State("registerPathGet", listOf(),
            Action.CONSTRUCTOR, "Route", listOf(Param(entities["path"]!!, 0)))
    val registerPathFilter = registerPathGet.copy(name = "registerPathFilter", methodName = "Filter")

//    val registerPathGetType = registerPathGet.copy()
//    val registerPathType = registerPathGet.copy(params =  listOf(Param(entities["acceptType"]!!, 1)))

    val getHandler = StateMachine(entity = entities["route"]!!,
            states = listOf(registerPathGet))

    val filter = StateMachine(entity = entities["filter"]!!,
            states = listOf(registerPathFilter))

    val postHandler = getHandler.copy()
    val putHandler = getHandler.copy()
    val deleteHandler = getHandler.copy()

//    val getTypeHandler = StateMachine(entity = entities["transformer"]!!,
//            states = listOf(registerPathGetType, registerPathType))

    val get = State(name = "get",
            before = listOf(registerPathGet),
            action = Action.STATIC_CALL,
            methodName = "get",
            params = listOf(Param(entities["route"]!!, 0)))

    val before = State(name = "before",
            before = listOf(registerPathFilter),
            action = Action.STATIC_CALL,
            methodName = "before",
            params = listOf(Param(entities["filter"]!!, 0)))

    val after = before.copy(name = "after", methodName = "after")
    val post = get.copy(name = "post", methodName = "post")
    val put = get.copy(name = "put", methodName = "put")
    val delete = get.copy(name = "delete", methodName = "delete")
    val webServer = StateMachine(entity = entities["server"]!!,
            states = listOf(get, before, after, post, put, delete))

    return Library(listOf(webServer, getHandler, filter, postHandler, putHandler, deleteHandler))
}

fun makeSpark2(entities: Map<String, Entity>): Library {
    val handler = StateMachine(entities["route"]!!, listOf())

    val filter = StateMachine(entities["filter"]!!, listOf())
    val afterHandler = filter.copy()

    val postHandler = handler.copy()
    val putHandler = handler.copy()
    val deleteHandler = handler.copy()
//    val getTypeHandler = StateMachine(entities["transformer"]!!, listOf())

    val registerPathGet = State(name = "registerPathGet",
            before = listOf(),
            action = Action.STATIC_CALL,
            methodName = "get",
            params = listOf(Param(entities["path"]!!, 0)))

    val registerPathFilter = registerPathGet.copy(name = "registerPathFilter", methodName = "before")
    val registerPathAfter = registerPathFilter.copy(methodName = "after")
    val registerPathPost = registerPathGet.copy(methodName = "post")
    val registerPathPut = registerPathGet.copy(methodName = "put")
    val registerPathDelete = registerPathGet.copy(methodName = "delete")

//    val registerPathGetType = registerPathGet.copy(name = "registerPathGetType",
//            methodName = "get", params = listOf(Param(entities["path"]!!, 0)))
//    val registerPathType = registerPathGet.copy(name = "registerPathType",
//            methodName = "get", params = listOf(Param(entities["acceptType"]!!, 1)))

    val get = State(name = "get",
            before = listOf(registerPathGet),
            action = Action.STATIC_CALL,
            methodName = "get",
            params = listOf(Param(entities["route"]!!, 1)))

    val before = State(name = "before",
            before = listOf(registerPathFilter),
            action = Action.STATIC_CALL,
            methodName = "before",
            params = listOf(Param(entities["filter"]!!, 1)))

    val after = before.copy(name = "after",
            before = listOf(registerPathAfter),
            methodName = "after")

    val post = get.copy(name = "post",
            before = listOf(registerPathPost),
            methodName = "post")

    val put = get.copy(name = "put",
            before = listOf(registerPathPut),
            methodName = "put")

    val delete = get.copy(name = "delete",
            before = listOf(registerPathDelete),
            methodName = "delete")

//    val getType = get.copy(name = "get",
//            before = listOf(registerPathType),
//            methodName = "get",
//            params = listOf(Param(entities["route"]!!, 2)))

    val webServer = StateMachine(entity = entities["server"]!!,
            states = listOf(registerPathGet, get, registerPathFilter, before, registerPathAfter, after, registerPathPost,
                    post, registerPathPut, put, registerPathDelete, delete))

    return Library(listOf(webServer, handler, filter, afterHandler, postHandler, putHandler, deleteHandler))
}