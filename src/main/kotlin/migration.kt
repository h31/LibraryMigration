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
import com.github.javaparser.ast.stmt.ReturnStmt
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
                val file: File,
                val traceFile: File) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    val context: MutableMap<StateMachine, State> = mutableMapOf()
    // val pendingStmts = mutableListOf<Statement>()
    var nameGeneratorCounter = 0

    fun doMigration() {
        println("Function: $functionName")
        val path = extractRouteFromJSON(traceFile)
//        checkRoute(path)

//        val edges = library1.stateMachines.flatMap { it -> it.edges }
        context += library2.stateMachines.flatMap { machine -> machine.states }.filter(State::isInit).map { state -> state.machine to state }
        for (usage in path) {
            val edge = usage.edge
            println("Processing " + edge.label() + "... ")
            if (edge.src == edge.dst) {
                println("  Makes a loop, skipping")
                continue
            }
            if (edge.dst.machine in library2.stateMachines == false) {
                println("No such machine, skipped")
                when (edge) {
                    is CallEdge -> extractDependenciesFromMethod(edge, usage.node as MethodCallExpr)
                    is ConstructorEdge -> extractDependenciesFromConstructor(edge, usage.node as ObjectCreationExpr)
                }
                continue
            }
            when (edge) {
                is AutoEdge -> println("  Has an auto action, skipping") // TODO: Should be error
                is CallEdge -> {
                    println("  Has a call action")
                    extractDependenciesFromMethod(edge, usage.node as MethodCallExpr)
                    val route = findRoute(context.values.toSet(), edge.dst)
                    migrateMethodCall(edge, route, usage.node)
                }
                is ConstructorEdge -> {
                    println("  Has a constructor action")
                    extractDependenciesFromConstructor(edge, usage.node as ObjectCreationExpr)
                    val route = findRoute(context.values.toSet(), edge.dst)
                    migrateConstructorCall(edge, route, usage.node)
                }
                is LinkedEdge -> {
                    println("  Has a linked action")
                    val dependency = edge.edge
                    if (dependency is CallEdge) {
                        extractDependenciesFromMethod(dependency, usage.node as MethodCallExpr)
                        val route = findRoute(context.values.toSet(), edge.dst)
                        migrateMethodCall(dependency, route, usage.node)
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

    private fun extractRoutesFromCode() {
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
        fun simpleType() = type.substringAfterLast('.').replace('$', '.')
    }

    data class LocatedEdge(
            val edge: Edge,
            val node: Node,
            val nodeLine: Int = node.endLine
    )

    private fun extractRouteFromJSON(file: File): List<LocatedEdge> {
        val invocations = ObjectMapper().registerKotlinModule().readValue<List<Invocation>>(file)
        val localInvocations = invocations.filter { inv -> inv.callerName == functionName && inv.filename == this.file.name }

        val usedEdges: MutableList<LocatedEdge> = mutableListOf()
        val edges = library1.stateMachines.flatMap { machine -> machine.edges }
        for (invocation in localInvocations) {
            if (invocation.kind == "method-call") {
                val callEdge = edges.filterIsInstance<CallEdge>().firstOrNull { edge ->
                            edge.methodName == invocation.name &&
                            invocation.simpleType() == edge.machine.type()
                }
                if (callEdge == null) {
//                    println("Cannot find edge for $invocation")
                    continue
                }
                val methodCall = codeElements.methodCalls.first { call -> call.name == invocation.name && call.endLine == invocation.line }
                usedEdges += LocatedEdge(callEdge, methodCall)
                if (methodCall.parentNode is ExpressionStmt == false) {
                    val linkedEdge = callEdge.linkedEdge
                    if (linkedEdge != null) {
                        usedEdges += LocatedEdge(linkedEdge, methodCall)
//                        usedEdges += associateEdges(linkedEdge.getSubsequentAutoEdges(), methodCall)
                    } else {
                        println("Missing linked node")
                    }
                }
//                usedEdges += associateEdges(callEdge.usageEdges, methodCall)
            } else if (invocation.kind == "constructor-call") {
                val constructorEdge = edges.filterIsInstance<ConstructorEdge>().firstOrNull { edge -> invocation.simpleType() == edge.machine.type() }
                if (constructorEdge == null) {
//                    println("Cannot find edge for $invocation")
                    continue
                }
                val constructorCall = codeElements.objectCreation.firstOrNull { objectCreation -> (objectCreation.type.toString() == invocation.simpleType()) && (objectCreation.beginLine == invocation.line) }
                if (constructorCall == null) {
                    error("Cannot find node for $invocation")
                }
                usedEdges += LocatedEdge(constructorEdge, constructorCall)
//                usedEdges += associateEdges(constructorEdge.usageEdges, constructorCall)
            }
        }
        if (usedEdges.isNotEmpty()) {
            println("--- Used edges:")
            for (edge in usedEdges) {
                println(edge.edge.label())
            }
            println("---")
            graphvizRender(toDOT(library1, usedEdges.map { usage -> usage.edge }), "extracted_" + functionName)
        }
        return usedEdges.distinct()
    }

    private fun associateEdges(edges: Collection<Edge>, node: Node) = edges.map { edge -> edge to node }

    private fun migrateMethodCall(edge: CallEdge, route: List<Edge>, usage: MethodCallExpr) {
            val oldVarName = getVariableNameFromExpression(usage)
            // makeDependenciesFromEdge(edge)
            val pendingExpressions = applySteps(route, oldVarName)
        if (pendingExpressions.isNotEmpty()) {
            replaceMethodCall(usage, pendingExpressions, oldVarName)
        } else {
            if (dependencies.containsKey(edge.linkedEdge?.dst?.machine)) {
                replaceNode(newExpr = dependencies[edge.linkedEdge!!.dst.machine]!!, oldExpr = usage)
            } else {
                val (statement, blockStmt) = getBlockStmt(usage) ?: return
                blockStmt.stmts.remove(statement)
            }
//            val (statement, blockStmt) = getBlockStmt(usage)
//            if (statement != usage.parentNode) TODO()
//            blockStmt.stmts.remove(statement)
        }
    }

    private fun migrateConstructorCall(edge: ConstructorEdge, route: List<Edge>, usage: ObjectCreationExpr) {
        val oldVarName = getVariableNameFromExpression(usage)
        // makeDependenciesFromEdge(edge)
        val pendingStmts = applySteps(route, oldVarName)
        println("Pending !!! $pendingStmts")
        replaceMethodCall(usage, pendingStmts, oldVarName)
    }

    private fun applySteps(steps: List<Edge>, oldVarName: String?): List<PendingExpression> {
        val pendingExpr = mutableListOf<PendingExpression>()
        for (step in steps) {
            println("    Step: " + step.label())
            val name = if ((step == steps.last()) && (oldVarName != null)) oldVarName else generateVariableName(step)
            val newExpressions: List<PendingExpression> = makeStep(step, name)
            println("Received expressions: " + newExpressions.toString())
            for (expr in newExpressions) {
                addToContext(expr)
            }
            pendingExpr.addAll(newExpressions)
        }
        return pendingExpr
    }

    private fun makeStep(step: Edge, name: String) = when (step) {
        is CallEdge, is LinkedEdge, is TemplateEdge -> makeSimpleEdge(step, name)
        is UsageEdge -> makeUsageEdge(step, name)
        is ConstructorEdge -> makeConstructorEdge(step, name)
        is AutoEdge -> listOf()
        is MakeArrayEdge -> TODO() // makeArray(step.action)
        else -> TODO("Unknown action!")
    }

    private fun makeSimpleEdge(step: Edge, name: String): List<PendingExpression> {
        return listOf(PendingExpression(edge = step, expression = makeExpression(step), provides = name))
    }

//    private fun makeConstructorEdge(step: ConstructorEdge, oldVarName: String?): List<PendingExpression> {
//        val name = makeVariableName(step, oldVarName)
//        val expr = makeConstructorExpression(step)
//
//        return listOf(PendingExpression(edge = step, expression = expr, provides = name))
//    }

//    private fun makeVariableName(step: Edge, oldVarName: String?) = oldVarName ?: generateVariableName(step)

    private fun generateVariableName(step: Edge) = "migration_${step.dst.machine.name}_${nameGeneratorCounter++}"

    private fun addToContext(pendingExpression: PendingExpression) {
        if (pendingExpression.provides != null) {
            dependencies[pendingExpression.edge.dst.machine] = NameExpr(pendingExpression.provides)
        }
        context[pendingExpression.edge.dst.machine] = pendingExpression.edge.dst
    }

    private fun makeUsageEdge(step: UsageEdge, oldVarName: String?): List<PendingExpression> {
        if (step.edge is CallEdge && step.edge.isStatic) {
            return emptyList()
        }

        val dependencyStep = getDependencyStep(step)
        val route = findRoute(context.values.toSet(), dependencyStep.src)
        println("Usage route: $route")
        val steps = applySteps(route, null)

        val initExpr: Expression = makeExpression(dependencyStep)
//        val callStatement = makeCallStatement(step.edge as CallEdge)
        return steps + listOf(PendingExpression(edge = step, expression = initExpr, provides = oldVarName ?: generateVariableName(step)))
    }

    private fun makeConstructorEdge(step: ConstructorEdge, name: String): List<PendingExpression> {
        val missingDeps = step.param.filterNot { param -> context.values.contains(param.state) }
        val steps = mutableListOf<PendingExpression>()
        for (dependency in missingDeps) {
            val route = findRoute(context.values.toSet(), dependency.state)
            println("Usage route: $route")
            val newSteps = applySteps(route, null)
            steps += newSteps
        }
        val expr = makeConstructorExpression(step)
        return steps + listOf(PendingExpression(edge = step, expression = expr, provides = name))
    }

    private fun getDependencyStep(step: Edge) = library2.stateMachines.flatMap { machine -> machine.edges }
            .first { edge: Edge -> (edge.src != edge.dst) && (edge is UsageEdge == false) && (edge.dst == step.dst) }

    private fun makeExpression(step: Edge): Expression = when (step) {
        is LinkedEdge -> makeExpression(step.edge)
        is TemplateEdge -> makeTemplateExpression(step)
        is ConstructorEdge -> makeConstructorExpression(step)
        is UsageEdge -> makeExpression(step.edge)
        is CallEdge -> makeCallExpression(step)
        else -> TODO()
    }

    private fun replaceMethodCall(oldExpr: Node, pendingExpressions: List<PendingExpression>, oldVarName: String?) {
        val (statement, blockStmt) = getBlockStmt(oldExpr) ?: return
//        if (oldVarName != null) {
//            replaceCodeUsage(blockStmt, oldVarName, pendingExpressions)
//        }
        val statements = blockStmt.stmts
        val pos = statements.indexOf(statement)
        val pendingStatements = pendingExpressions.map { pending -> makeNewStatement(pending) }
        statements.addAll(pos, pendingStatements)
        val newExpr = NameExpr(getNewVarName(pendingExpressions))
        replaceNode(newExpr, oldExpr)
        for (stmt in pendingExpressions) {
            stmt.expression.parentNode = blockStmt
        }
    }

    private fun replaceNode(newExpr: Expression, oldExpr: Node) {
        val (statement, blockStmt) = getBlockStmt(oldExpr) ?: return
        val statements = blockStmt.stmts
        val parent = oldExpr.parentNode
        when (parent) {
            is VariableDeclarator -> {
                statements.remove(statement)
                statement.parentNode = null
            }
            is AssignExpr -> parent.value = newExpr
            is BinaryExpr -> parent.left = newExpr
            is ObjectCreationExpr -> {
                val argsPos = parent.args.indexOf(oldExpr)
                parent.args.set(argsPos, newExpr)
                newExpr.parentNode = parent
            }
            is MethodCallExpr -> {
                val argsPos = parent.args.indexOf(oldExpr)
                if (argsPos >= 0) {
                    parent.args.set(argsPos, newExpr)
                } else {
                    parent.scope = newExpr
                }
                newExpr.parentNode = parent
            }
            is ReturnStmt -> parent.expr = newExpr
            is CastExpr -> parent.expr = newExpr
            else -> error("Don't know how to insert into " + parent.toString())
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
                    type = checkNotNull(library2.machineSimpleTypes[pendingExpression.edge.dst.machine]),
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

    private fun makeCallExpression(step: CallEdge): Expression {
        val callParams = makeCallExpressionParams(step)
        val expr = MethodCallExpr(callParams.scope, step.methodName, callParams.args)
        return expr
    }

    private fun makeTemplateExpression(step: TemplateEdge): Expression {
        val stringParams = step.params.mapValues { state -> checkNotNull(dependencies[state.value.machine].toString()) }
        return templateIntoAST(fillPlaceholders(step.template, stringParams))
    }

//    private fun makeExpressionOld(step: ExpressionEdge) = when (step) {
//        is CallEdge -> makeCallExpression(step)
//        is TemplateEdge -> makeTemplateExpression(step)
//        else -> TODO()
//    }

    private fun makeConstructorExpression(step: ConstructorEdge): Expression {
        val params = step.param.map { param -> checkNotNull(dependencies[param.machine]) }
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(step.dst.machine.type()), params)
        return expr
    }

    private fun getBlockStmt(initialNode: Node): Pair<Statement, BlockStmt>? {
        var node: Node = initialNode
        while (node.parentNode is BlockStmt == false) {
            if (node.parentNode == null) {
                return null
            }
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
        addDependenciesToContext(deps)
    }

    private fun extractDependenciesFromConstructor(edge: ConstructorEdge, constructorCall: ObjectCreationExpr) {
        val args = edge.param.mapIndexed { i, param -> param.state to constructorCall.args[i] }.toMap()
//        val scope = (edge.src to methodCall.scope)
        val deps = args // TODO: scope
        addDependenciesToContext(deps)
    }

    private fun addDependenciesToContext(deps: Map<State, Expression?>) {
        for ((dep, expr) in deps) {
            println("Machine ${dep.machine.label()} is now in state ${dep.label()}")
            context[dep.machine] = dep

            if (expr != null) {
                println("Machine ${dep.machine.label()} can be accessed by expr \"$expr\"")
                dependencies[dep.machine] = expr

                val autoDeps = library1.edges.filterIsInstance<AutoEdge>().filter { edge -> edge.src == dep }
                for (autoDep in autoDeps) {
                    val dstMachine = autoDep.dst.machine
                    println("Additionally machine ${dstMachine.label()} can be accessed by expr \"$expr\"")
                    context[dstMachine] = autoDep.dst
                    dependencies[dstMachine] = expr
                }
            }
        }
    }

//    private fun makeDependenciesFromEdge(edge: Edge) {
//        if (dependencies.containsKey(edge.machine) == false) {
//            makeMissingDependency(edge.machine, edge.src)
//        }
//    }
}