import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.javaparser.ASTHelper
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import org.jgrapht.alg.DijkstraShortestPath
import com.fasterxml.jackson.module.kotlin.*
import org.jgrapht.graph.DirectedPseudograph
import java.io.File

/**
 * Created by artyom on 22.08.16.
 */

data class PendingExpression(val expression: Expression,
                             val edge: Edge,
                             val provides: String? = null,
                             val depends: List<String> = listOf())

class Migration(val library1: Library,
                val library2: Library,
                val codeElements: CodeElements,
//                val graph1: DirectedPseudograph<State, Edge> = toJGrapht(library1),
//                val graph2: DirectedPseudograph<State, Edge> = toJGrapht(library2),
                val functionName: String,
                val traceFile: File) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    val context: MutableMap<StateMachine, State> = mutableMapOf()
    // val pendingStmts = mutableListOf<Statement>()
    var nameGeneratorCounter = 0

    fun doMigration() {
        val (path, nodeMap) = extractRouteFromJSON(traceFile)
//        checkRoute(path)

//        val edges = library1.stateMachines.flatMap { it -> it.edges }
        println("Function: $functionName")
        context += library2.stateMachines.flatMap { machine -> machine.states }.filter(State::isInit).map { state -> state.machine to state }
        for (edge in path) {
            println("Processing " + edge.label() + "... ")
            if (edge.src == edge.dst) {
                println("  Makes a loop, skipping")
                continue
            }
            if (edge.dst.machine in library2.stateMachines == false) {
                println("No such machine, skipped")
                continue
            }
            when (edge) {
                is AutoEdge -> println("  Has an auto action, skipping") // TODO: Should be error
                is CallEdge -> {
                    println("  Has a call action")
                    extractDependenciesFromMethod(edge, nodeMap[edge] as MethodCallExpr)
                    val route = findRoute(context.values.toSet(), edge.dst)
                    migrateMethodCall(edge, route, nodeMap[edge] as MethodCallExpr)
                }
                is ConstructorEdge -> {
                    println("  Has a constructor action")
                    extractDependenciesFromConstructor(edge, nodeMap[edge] as ObjectCreationExpr)
                    val route = findRoute(context.values.toSet(), edge.dst)
                    migrateConstructorCall(edge, route, nodeMap[edge] as ObjectCreationExpr)
                }
                is LinkedEdge -> {
                    println("  Has a linked action")
                    val dependency = edge.edge
                    if (dependency is CallEdge) {
                        extractDependenciesFromMethod(dependency, nodeMap[dependency] as MethodCallExpr)
                        val route = findRoute(context.values.toSet(), edge.dst)
                        migrateMethodCall(dependency, route, nodeMap[dependency] as MethodCallExpr)
                    }
                }
            }
        }
    }

    private fun printRoute(route: List<Edge>) {
//        println("Route from %s to %s: ".format(src.stateAndMachineName(),
//                dst.stateAndMachineName()))
        for (state in route.withIndex()) {
            println("%d: %s".format(state.index, state.value.label()))
        }
    }

    private fun checkRoute(route: List<Edge>) {
        route.filterNot { edge -> edge is UsageEdge }.fold(listOf<Edge>(), { visited, edge ->
            if (visited.isEmpty() || visited.any { prevEdge -> edge.src == prevEdge.dst }) {
                visited + edge
            } else {
                error("Unexpected edge! Visited: ${printRoute(visited)}, edge: ${edge.label()}")
            }
        })
    }

    private fun extractRoutes() {
        val usedEdges: MutableList<Edge> = mutableListOf()
        val edges = library1.stateMachines.flatMap { machine -> machine.edges }
        for (methodCall in codeElements.methodCalls) {
            val callEdge = edges.firstOrNull { edge -> edge is CallEdge && edge.methodName == methodCall.name } as CallEdge?
            if (callEdge != null) {
                usedEdges += callEdge
                if (methodCall.parentNode is ExpressionStmt == false) {
                    val linkedEdge = callEdge.linkedEdge
                    if (linkedEdge != null) {
                        usedEdges += linkedEdge
                        usedEdges += linkedEdge.getSubsequentAutoEdges()
                    } else {
                        error("Missing linked node")
                    }
                }
                usedEdges += callEdge.usageEdges
            }
        }
        for (objectCreation in codeElements.objectCreation) {
            val constructorEdges = edges.firstOrNull { edge -> edge is ConstructorEdge && edge.machine.type() == objectCreation.type.name } as ConstructorEdge?
            if (constructorEdges != null) {
                usedEdges += constructorEdges
                usedEdges += constructorEdges.usageEdges
            }
        }
        if (usedEdges.isNotEmpty()) {
            println("--- Used edges:")
            for (edge in usedEdges) {
                println(edge.label())
            }
            println("---")
            graphvizRender(toDOT(library1, usedEdges), "extracted_" + functionName)
        }
    }

    @JsonIgnoreProperties("node")
    data class Invocation(
            val name: String,
            val filename: String,
            val line: Int = 0,
            val type: String,
            val callerName: String,
            val kind: String,
            val args: List<String>,
            val id: String,
            val place: String) {
        fun simpleType() = type.substringAfterLast('.')
    }

    private fun extractRouteFromJSON(file: File): Pair<List<Edge>, Map<Edge, Node>> {
        val invocations = ObjectMapper().registerKotlinModule().readValue<List<Invocation>>(file)
        val localInvocations = invocations.filter { inv -> inv.callerName == functionName }

        val usedEdges: MutableList<Edge> = mutableListOf()
        val edges = library1.stateMachines.flatMap { machine -> machine.edges }
        val nodeMap = mutableMapOf<Edge, Node>()
        for (invocation in localInvocations) {
            if (invocation.kind == "method-call") {
                val callEdge = edges.firstOrNull { edge ->
                    edge is CallEdge &&
                            edge.methodName == invocation.name &&
                            invocation.simpleType() == edge.machine.type()
                } as CallEdge?
                if (callEdge == null) {
                    println("Cannot find edge for $invocation")
                    continue
                }
                usedEdges += callEdge
                val methodCall = codeElements.methodCalls.first { call -> call.name == invocation.name && call.beginLine == invocation.line }
                nodeMap[callEdge] = methodCall
                if (methodCall.parentNode is ExpressionStmt == false) {
                    val linkedEdge = callEdge.linkedEdge
                    if (linkedEdge != null) {
                        usedEdges += linkedEdge
                        usedEdges += linkedEdge.getSubsequentAutoEdges()
                    } else {
                        error("Missing linked node")
                    }
                }
                usedEdges += callEdge.usageEdges
            } else if (invocation.kind == "constructor-call") {
                val constructorEdge = edges.firstOrNull { edge -> edge is ConstructorEdge && invocation.simpleType() == edge.machine.type() } as ConstructorEdge?
                if (constructorEdge == null) {
                    println("Cannot find edge for $invocation")
                    continue
                }
                val constructorCall = codeElements.objectCreation.firstOrNull { objectCreation -> (objectCreation.type.toString() == invocation.simpleType()) && (objectCreation.beginLine == invocation.line) }
                if (constructorCall == null) {
                    error("Cannot find node for $invocation")
                }
                nodeMap[constructorEdge] = constructorCall
                usedEdges += constructorEdge
                usedEdges += constructorEdge.usageEdges
            }
        }
        if (usedEdges.isNotEmpty()) {
            println("--- Used edges:")
            for (edge in usedEdges) {
                println(edge.label())
            }
            println("---")
            graphvizRender(toDOT(library1, usedEdges), "extracted_" + functionName)
        }
        return Pair(usedEdges, nodeMap)
    }

    private fun migrateMethodCall(edge: CallEdge, route: List<Edge>, usage: MethodCallExpr) {
            val oldVarName = getVariableNameFromExpression(usage)
            // makeDependenciesFromEdge(edge)
            val pendingStmts = applySteps(route, oldVarName)
            replaceMethodCall(usage, pendingStmts, oldVarName)
    }

    private fun migrateConstructorCall(edge: ConstructorEdge, route: List<Edge>, usage: ObjectCreationExpr) {
        val oldVarName = getVariableNameFromExpression(usage)
        // makeDependenciesFromEdge(edge)
        val pendingStmts = applySteps(route, oldVarName)
        println("Pending !!! $pendingStmts")
//        replaceMethodCall(usage, pendingStmts, oldVarName)
    }

    private fun applySteps(steps: List<Edge>, oldVarName: String?): List<PendingExpression> {
        val pendingExpr = mutableListOf<PendingExpression>()
        for (step in steps) {
            println("    Step: " + step.label())

            val newExpressions: List<PendingExpression> = when (step) {
                is CallEdge -> makeCallStatement(step)
                is ConstructorEdge -> makeConstructorEdge(step)
                is AutoEdge -> listOf()
                is LinkedEdge -> makeLinkedEdge(step, oldVarName)
                is MakeArrayEdge -> TODO() // makeArray(step.action)
                is TemplateEdge -> TODO()
                is UsageEdge -> makeUsageEdge(step)
                else -> error("Unknown action!")
            }
            println("Received expressions: " + newExpressions.toString())
            for (expr in newExpressions) {
                addPendingExpression(expr)
            }
            pendingExpr.addAll(newExpressions)
        }
        return pendingExpr
    }

    private fun makeLinkedEdge(step: LinkedEdge, oldVarName: String?): List<PendingExpression> {
        val name = oldVarName ?: generateVariableName(step)
        val expr = when {
            step.edge is CallEdge -> makeCallExpression(step.edge)
            step.edge is TemplateEdge -> makeTemplateExpression(step.edge)
            else -> error("Unknown type")
        }

//        dependencies[step.dst.machine] = NameExpr(name)
//        context[step.dst.machine] = step.dst
        return listOf(PendingExpression(edge = step, expression = expr, provides = name))
    }

    private fun makeConstructorEdge(step: ConstructorEdge): List<PendingExpression> {
//        val type = checkNotNull(library2.machineTypes[step.dst.machine])
        val name = generateVariableName(step)
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(step.dst.machine.type()), listOf())

        return listOf(PendingExpression(edge = step, expression = expr, provides = name))
    }

    private fun generateVariableName(step: Edge) = "newEntity_${step.dst.machine.name}_${nameGeneratorCounter++}"

    private fun addPendingExpression(pendingExpression: PendingExpression) {
        if (pendingExpression.provides != null) {
            dependencies[pendingExpression.edge.dst.machine] = NameExpr(pendingExpression.provides)
        }
        context[pendingExpression.edge.dst.machine] = pendingExpression.edge.dst
    }

    private fun makeUsageEdge(step: UsageEdge): List<PendingExpression> {
        if (step.edge is ExpressionEdge && step.edge.isStatic) {
            return emptyList()
        }
        val dependencyStep = library2.stateMachines.flatMap { machine -> machine.edges }
                .first { edge: Edge -> (edge.src != edge.dst) && (edge is UsageEdge == false) && (edge.dst == step.dst) }
        val initExpr: Expression = when (dependencyStep) {
            is LinkedEdge -> makeExpression(dependencyStep.edge)
            is TemplateEdge -> makeTemplateExpression(dependencyStep)
            is ConstructorEdge -> makeConstructorExpression(dependencyStep)
            is UsageEdge -> makeExpression(dependencyStep.edge)
            else -> TODO()
        }
//        val callStatement = makeCallStatement(step.edge as CallEdge)
        return listOf(PendingExpression(edge = step, expression = initExpr, provides = generateVariableName(step)))
    }

    private fun replaceMethodCall(methodCall: MethodCallExpr, pendingStmts: List<PendingExpression>, oldVarName: String?) {
        val (statement, blockStmt) = getBlockStmt(methodCall)
        if (oldVarName != null) {
            replaceCodeUsage(blockStmt, oldVarName, pendingStmts)
        }
        val statements = blockStmt.stmts
        val pos = statements.indexOf(statement)
        statements.addAll(pos, pendingStmts.map { pending -> makeNewStatement(pending) })
        val parent = methodCall.parentNode
        val newExpr = NameExpr(getNewVarName(pendingStmts))
        when (parent) {
            is VariableDeclarator -> {
                statements.remove(statement)
                statement.parentNode = null
            }
            is AssignExpr -> parent.value = newExpr
            is BinaryExpr -> parent.left = newExpr
            is ObjectCreationExpr -> {
                val argsPos = parent.args.indexOf(methodCall)
                parent.args.set(argsPos, newExpr)
                newExpr.parentNode = parent
            }
            is MethodCallExpr -> {} // TODO: Is it correct?
            else -> error("Don't know how to insert into " + parent.toString())
        }
        for (stmt in pendingStmts) {
            stmt.expression.parentNode = blockStmt
        }
    }

    private fun getVariableNameFromExpression(methodCall: Node): String? {
        val parent = methodCall.parentNode
        return if (parent is VariableDeclarator) parent.id.name else null
    }

    private fun replaceCodeUsage(blockStmt: BlockStmt, oldVarName: String, pendingStmts: List<PendingExpression>) {
        val newVarName = getNewVarName(pendingStmts)
        if (oldVarName != newVarName) {
            replaceName(blockStmt, oldVarName, newVarName)
        }
    }

    private fun getNewVarName(pendingStmts: List<PendingExpression>): String = pendingStmts.map { stmt -> stmt.provides }.lastOrNull() ?: error("New statement should provide a variable")

    private fun replaceName(node: Node, src: String, dst: String) {
        if (node is NameExpr && node.name == src) {
            node.name = dst
        }
        for (child in node.childrenNodes) {
            replaceName(child, src, dst)
        }
    }

    private fun unpackCallEdge(edge: Edge): CallEdge? {
        if (edge is LinkedEdge) {
            return edge.edge as CallEdge // TODO
        } else if (edge is UsageEdge) {
            return edge.edge as CallEdge
        } else if (edge is CallEdge) {
            return edge
        } else {
            return null
        }
    }

    private fun makeCallExpressionParams(edge: CallEdge): CallExpressionParams {
        val scope = if (edge.isStatic) {
            NameExpr(edge.machine.type())
        } else {
            checkNotNull(dependencies[edge.machine])
        }

        val args = edge.param.map { param -> checkNotNull(dependencies[param.machine]) }

        return CallExpressionParams(scope, args)
    }

    private fun makeNewStatement(pendingExpression: PendingExpression): Statement {
        if (pendingExpression.provides != null) {
            return makeNewVariable(
                    type = checkNotNull(library2.machineTypes[pendingExpression.edge.dst.machine]),
                    name = pendingExpression.provides,
                    initExpr = pendingExpression.expression
            )
        } else {
            return ExpressionStmt(pendingExpression.expression)
        }
    }

    private fun makeNewVariable(type: String, name: String, initExpr: Expression?): Statement {
        val newVariable = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType(type), name)
        if (initExpr != null) {
            newVariable.vars.first().init = initExpr
        }
        return ExpressionStmt(newVariable)
    }

//    private fun makeMissingDependency(machine: StateMachine, requiredState: State): Pair<String, Statement> {
//        val newVariableStatement = makeNewVariable(type, name, initExpr)
//        return Pair(name, newVariableStatement)
//    }

    private fun makeCallStatement(step: CallEdge) = listOf(PendingExpression(edge = step, expression = makeCallExpression(step)))

    private fun makeCallExpression(step: CallEdge): Expression {
        val callParams = makeCallExpressionParams(step)
        val expr = MethodCallExpr(callParams.scope, step.methodName, callParams.args)
        return expr
    }

    private fun makeTemplateExpression(step: TemplateEdge): Expression {
        val stringParams = step.params.mapValues { state -> checkNotNull(dependencies[state.value.machine].toString()) }
        return templateIntoAST(fillPlaceholders(step.template, stringParams))
    }

    private fun makeExpression(step: ExpressionEdge) = when (step) {
        is CallEdge -> makeCallExpression(step)
        is TemplateEdge -> makeTemplateExpression(step)
        else -> TODO()
    }

    private fun makeConstructorExpression(step: ConstructorEdge): Expression {
        if (step.param.isNotEmpty()) TODO()
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(step.dst.machine.type()), listOf())
        return expr
    }

    private fun getBlockStmt(initialNode: Node): Pair<Statement, BlockStmt> {
        var node: Node = initialNode
        while (node.parentNode is BlockStmt == false) {
            node = node.parentNode
        }
        return Pair(node as Statement, node.parentNode as BlockStmt)
    }

    private fun findRoute(src: Set<State>, dst: State): List<Edge> {
        println("  Searching route from %s to %s".format(src.joinToString(transform = State::stateAndMachineName), dst.stateAndMachineName()))
        val edges = library2.stateMachines.flatMap(StateMachine::edges).toSet()
        val pathFinder = PathFinder(edges, src)
        val path2 = pathFinder.findPath(dst)
        if (false) {
            graphvizRender(toDOT(library1, path2), "path2")
            error("Paths are not equal")
        }
        return path2
    }

    private fun getUsages(methodName: String) =
            codeElements.methodCalls.filter { methodCall -> methodCall.name == methodName }

    private fun extractDependenciesFromMethod(edge: CallEdge, methodCall: MethodCallExpr) {
        val args = edge.param.mapIndexed { i, param -> param.state to methodCall.args[i] }.toMap()
        val scope = (edge.src to methodCall.scope)
        val deps = args + scope
        fillContext(deps)
    }

    private fun extractDependenciesFromConstructor(edge: ConstructorEdge, constructorCall: ObjectCreationExpr) {
        val args = edge.param.mapIndexed { i, param -> param.state to constructorCall.args[i] }.toMap()
//        val scope = (edge.src to methodCall.scope)
        val deps = args // TODO: scope
        fillContext(deps)
    }

    private fun fillContext(deps: Map<State, Expression?>) {
        for ((dep, expr) in deps) {
            println("Machine ${dep.machine.label()} is now in state ${dep.label()}")
            context[dep.machine] = dep

            if (expr != null) {
                println("Machine ${dep.machine.label()} can be accessed by expr \"$expr\"")
                dependencies[dep.machine] = expr
            }
        }
    }

//    private fun makeDependenciesFromEdge(edge: Edge) {
//        if (dependencies.containsKey(edge.machine) == false) {
//            makeMissingDependency(edge.machine, edge.src)
//        }
//    }
}