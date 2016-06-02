import com.github.javaparser.ASTHelper
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NamedNode
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import org.jgrapht.DirectedGraph
import org.jgrapht.Graph
import org.jgrapht.ext.DOTExporter
import org.jgrapht.ext.IntegerNameProvider
import org.jgrapht.ext.VertexNameProvider
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.DirectedSubgraph
import org.jgrapht.graph.SimpleDirectedGraph
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

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

//    val classDiff = diffClasses(srcElements.classes.first { it -> it.name == "src" },
//            dstElements.classes.first { it -> it.name == "dst" })
//    println(classDiff)
//
//    for (method in classDiff.methodsChanged) {
//        val missingDstParams = method.value.newInDst
//        for (param in missingDstParams) {
//            assert(param.value.annotations.size > 0);
//            val tag = param.value.annotations.first().name.name
//            for (constructor in srcElements.constructors) {
//                val srcParam = findAnnotatedParam(constructor, tag)
//                if (srcParam != null) {
//                    val new = codeElements.objectCreation
//                            .filter { it -> it.type.name == constructor.name }
//                            .filter { it -> it.args != null }
//                            .filter { it -> it.args.size > srcParam.index }
//                    val arg = new.first().args.get(srcParam.index)
//                    codeElements.methodCalls
//                            .filter { it -> it.name == method.key.name }
//                            .forEach { it -> it.args.add(srcParam.index, arg) }
//                    break
//                }
//            }
//        }
//    }
//
//    val srcClasses = srcElements.classes.filter { it -> it.name != "src" }
//    val dstClasses = dstElements.classes.filter { it -> it.name != "dst" }
//    val classPairs = findTagPairs(srcClasses, dstClasses)
//    for (entry in classPairs) {
//        val diff = diffClasses(entry.value as ClassOrInterfaceDeclaration,
//                entry.key as ClassOrInterfaceDeclaration) // TODO: src, dst order
//        for (method in diff.constructorsChanged) {
//            for (param in method.value.newInSrc) {
//                codeElements.objectCreation
//                        .filter { it -> it.type.name == method.key.name }
//                        .filter { it -> it.args != null }
//                        .filter { it -> it.args.size > param.index }
//                        .forEach { it -> it.args.removeAt(param.index) }
//            }
//        }
//    }
//    println(classPairs)
//
//    //    println(srcElements.constructors)
//    println(cu);
//    return;
//    println(srcElements.methodDecls)
//    println(dstElements.methodDecls)
//
//    val pairs = findTagPairs(srcElements.methodDecls, dstElements.methodDecls)
//
//    for (pair in pairs.entries.withIndex()) {
//        println("Pair " + pair.index)
//        val diff = diffMethods(pair.value.key as MethodDeclaration, pair.value.value as MethodDeclaration)
//        println(diff)
//    }
//
//    println("Hello World!");
//    // visit and print the methods names
//    val acceptedNames = listOf("get", "before", "after", "post", "put", "delete");
//
//    val methods = codeElements.methodCalls.filter {
//        p ->
//        acceptedNames.contains(p.name)
//    }
//
//    for (node in methods) {
//        val objectCreation = node.args.first()
//        if (objectCreation !is ObjectCreationExpr) {
//            continue
//        }
//        val path = if (objectCreation.args?.isEmpty() == false) {
//            objectCreation.args.first()
//        } else {
//            null
//        };
//        val method = objectCreation.anonymousClassBody.first({ p -> p is MethodDeclaration });
//        if (method !is MethodDeclaration) {
//            continue
//        }
//        val params = method.parameters;
//        val body = method.body;
//
//        node.args.clear();
//        if (path != null) {
//            node.args.add(path);
//        }
//        val lambda = wrapInLambda(params, body)
//        node.args.add(lambda);
//        if (objectCreation.type.name == "JsonTransformer") {
//            val obj = ObjectCreationExpr();
//            obj.type = objectCreation.type;
//            node.args.add(obj)
//        }
//    }

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

//fun diffStates(old: State, new: State) {
//    val srcList = src.parameters.withIndex().toMutableList()
//    srcList.removeAll { it -> dst.parameters.contains(it.value) }
//
//    val dstList = dst.parameters.withIndex().toMutableList()
//    dstList.removeAll { it -> src.parameters.contains(it.value) }
//
//    return MethodDiff(methodName = src.name,
//            newInSrc = srcList,
//            newInDst = dstList)
//
//}

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

fun checkMethodArgType(method: MethodCallExpr, i: Int, arg: Class<Expression>): Boolean {
    if (method.args == null) {
        return false
    }
    if (method.args.size < i) {
        return false
    }
    if (arg.isInstance(method.args[i]) == false) {
        return false
    }
    return true
}

fun getIthArg() {

}

fun wrapInLambda(parameters: List<Parameter>, body: Statement): LambdaExpr {
    val lambda = LambdaExpr()
    lambda.isParametersEnclosed = true
    lambda.parameters = parameters
    lambda.body = body
    return lambda
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

fun diffMethods(src: MethodDeclaration, dst: MethodDeclaration): MethodDiff {
    val srcList = src.parameters.withIndex().toMutableList()
    srcList.removeAll { it -> dst.parameters.contains(it.value) }

    val dstList = dst.parameters.withIndex().toMutableList()
    dstList.removeAll { it -> src.parameters.contains(it.value) }

    return MethodDiff(methodName = src.name,
            newInSrc = srcList,
            newInDst = dstList)
}

fun diffConstructors(src: ConstructorDeclaration, dst: ConstructorDeclaration): MethodDiff {
    val srcList = src.parameters.withIndex().toMutableList()
    srcList.removeAll { it -> dst.contains(it.value) }

    val dstList = dst.parameters.withIndex().toMutableList()
    dstList.removeAll { it -> src.contains(it.value) }

    return MethodDiff(methodName = src.name,
            newInSrc = srcList,
            newInDst = dstList)
}

data class MethodDiff(val methodName: String,
                      val newInSrc: List<IndexedValue<Parameter>>,
                      val newInDst: List<IndexedValue<Parameter>>) {
    fun methodChanged() = newInSrc.isNotEmpty() || newInDst.isNotEmpty()
}

data class ClassDiff(val name: String,
                     val methodsChanged: Map<MethodDeclaration, MethodDiff>)

//fun diffClasses(src: ClassOrInterfaceDeclaration, dst: ClassOrInterfaceDeclaration): ClassDiff {
//    val srcMethods: List<MethodOrConstructor> = src.members.flatMap {
//        it ->
//        if (isMethodOrConstructor(it)) listOf(MethodOrConstructor(it)) else listOf()
//    }
//    val dstMethods: List<MethodOrConstructor> = dst.members.flatMap {
//        it ->
//        if (isMethodOrConstructor(it)) listOf(MethodOrConstructor(it)) else listOf()
//    }
//    val changedMethods = mutableMapOf<MethodOrConstructor, MethodDiff>()
//    for (method in srcMethods) {
//        val changed = dstMethods
//                .filter { it -> (it.name == method.name) && diffMethods(method, it).methodChanged() }
//                .map { it -> Pair(it, diffMethods(method, it)) }
//        changedMethods.putAll(changed)
//    }
//
////    val srcConstructors: List<ConstructorDeclaration> = src.members.flatMap {
////        it ->
////        if (it is ConstructorDeclaration) listOf(it) else listOf()
////    }
////    val dstConstructors: List<ConstructorDeclaration> = dst.members.flatMap {
////        it ->
////        if (it is ConstructorDeclaration) listOf(it) else listOf()
////    }
////    val changedConstructors = mutableMapOf<ConstructorDeclaration, MethodDiff>()
////    for (constructor in dstConstructors) {
////        val changed = srcConstructors
////                .filter { it -> (it.name == constructor.name) && diffConstructors(constructor, it).methodChanged() }
////                .map { it -> Pair(it, diffConstructors(constructor, it)) }
////        changedConstructors.putAll(changed)
////    }
//
//    return ClassDiff(src.name, changedMethods)
//}

//fun diffClasses(src: ClassOrInterfaceDeclaration, dst: ClassOrInterfaceDeclaration): Boolean {
//    return src.isInterface == dst.isInterface && src.name == dst.name;
//}

fun findTagPairs(src: List<AnnotableNode>, dst: List<AnnotableNode>): Map<AnnotableNode, AnnotableNode> {
    val pairs = mutableMapOf<AnnotableNode, AnnotableNode>()
    for (srcMethod in src) {
        for (srcTag in srcMethod.annotations) {
            val sameTag = dst.filter { it -> it.annotations.first().name == srcTag.name }
            if (sameTag.isNotEmpty()) {
                pairs.put(srcMethod, sameTag.first())
            }
        }
    }

    return pairs;
}

//fun findClassPairs(src: List<ClassOrInterfaceDeclaration>, dst: List<ClassOrInterfaceDeclaration>):
//        Map<ClassOrInterfaceDeclaration, ClassOrInterfaceDeclaration> {
//    val pairs = mutableMapOf<ClassOrInterfaceDeclaration, ClassOrInterfaceDeclaration>()
//    for (srcClass in src) {
//        val sameName = dst.firstOrNull { it -> it.name == srcClass.name }
//        if (sameName != null) {
//            pairs.put(srcClass, sameName)
//        }
//    }
//
//    return pairs;
//}

fun findAnnotationsPairs(src: List<MethodDeclaration>, dst: List<MethodDeclaration>): Set<Pair<MethodDeclaration, MethodDeclaration>> {
    val pairs = mutableSetOf<Pair<MethodDeclaration, MethodDeclaration>>()
    for (srcMethod in src) {
        val srcName = srcMethod.name
        val sameName = dst.filter { it -> it.name == srcName }
        if (sameName.isNotEmpty()) {
            pairs.add(Pair(srcMethod, sameName.first()))
        }
    }

    return pairs;
}

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

fun findAnnotatedParam(method: ConstructorDeclaration, tag: String): IndexedValue<Parameter>? {
    for (param in method.parameters.withIndex()) {
        if (param.value.annotations.any { ann -> ann.name.name == tag }) {
            return param
        }
    }
    return null
}

class MethodOrConstructor(var node: BodyDeclaration) {
    fun get() = node
}

fun isMethodOrConstructor(node: BodyDeclaration) = node is MethodDeclaration || node is ConstructorDeclaration


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