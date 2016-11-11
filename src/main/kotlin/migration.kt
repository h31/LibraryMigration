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

data class Replacement(val oldNode: Node,
                       val pendingExpressions: List<PendingExpression>,
                       val removeOldNode: Boolean = pendingExpressions.isEmpty(),
                       var makeVariable: Boolean = true)

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
        val extractor = RouteExtractor(library1, codeElements, functionName, file)
        val path = extractor.extractFromJSON(traceFile)
//        checkRoute(path)

        fillContextWithInit()
        val replacements: MutableList<Replacement> = mutableListOf()
        for (usage in path) {
            val edge = usage.edge
            println("Processing " + edge.label() + "... ")
            if (edge.src == edge.dst) {
                println("  Makes a loop, skipping")
                continue
            }
            extractDependenciesFromNode(edge, usage.node)
            if (edge.dst.machine in library2.stateMachines == false) {
                println("No such machine, skipped")
                continue
            }
            when (edge) {
                is AutoEdge -> println("  Has an auto action, skipping") // TODO: Should be error
                is CallEdge -> {
                    println("  Has a call action")
                    val route = findRoute(context.values.toSet(), edge.dst)
                    val replacement = migrateMethodCall(edge, route, usage.node as MethodCallExpr)
                    replacements += replacement;
//                    applyReplacement(replacement)
                }
                is ConstructorEdge -> {
                    println("  Has a constructor action")
                    val route = findRoute(context.values.toSet(), edge.dst)
                    val replacement = migrateConstructorCall(edge, route, usage.node as ObjectCreationExpr)
                    replacements += replacement;
//                    applyReplacement(replacement)
                }
                is LinkedEdge -> {
                    println("  Has a linked action")
                    val dependency = edge.edge
                    if (dependency is CallEdge) {
                        val route = findRoute(context.values.toSet(), edge.dst)
                        val replacement = migrateMethodCall(dependency, route, usage.node as MethodCallExpr)
                        replacements += replacement;
//                        applyReplacement(replacement)
                    }
                }
            }
        }
        val performer = ReplacementPerformer(replacements, library2)
        performer.apply()
    }

    private fun fillContextWithInit() {
        context += library2.stateMachines.flatMap { machine -> machine.states }.filter(State::isInit).map { state -> state.machine to state }
    }

    private fun associateEdges(edges: Collection<Edge>, node: Node) = edges.map { edge -> edge to node }

    private fun migrateMethodCall(edge: CallEdge, route: List<Edge>, usage: MethodCallExpr): Replacement {
            val oldVarName = getVariableNameFromExpression(usage)
            // makeDependenciesFromEdge(edge)
            val pendingExpressions = applySteps(route, oldVarName)
        if (pendingExpressions.isNotEmpty()) {
            return Replacement(oldNode = usage, pendingExpressions = pendingExpressions)
        } else {
            if (edge.linkedEdge != null) {
                val newNode = dependencies[edge.linkedEdge!!.dst.machine] ?: error("No such dependency")
                return Replacement(oldNode = usage, pendingExpressions = listOf(PendingExpression(expression = newNode, edge = edge.linkedEdge!!)), makeVariable = false)
            } else {
                return Replacement(oldNode = usage, pendingExpressions = listOf())
            }
//            val (statement, blockStmt) = getBlockStmt(usage)
//            if (statement != usage.parentNode) TODO()
//            blockStmt.stmts.remove(statement)
        }
    }

    private fun migrateConstructorCall(edge: ConstructorEdge, route: List<Edge>, usage: ObjectCreationExpr): Replacement {
        val oldVarName = getVariableNameFromExpression(usage)
        // makeDependenciesFromEdge(edge)
        val pendingStmts = applySteps(route, oldVarName)
        println("Pending !!! $pendingStmts")
        return Replacement(oldNode = usage, pendingExpressions = pendingStmts)
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

    private fun getVariableNameFromExpression(methodCall: Node): String? {
        val parent = methodCall.parentNode
        return if (parent is VariableDeclarator) parent.id.name else null
    }

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

    private fun makeCallExpression(step: CallEdge): Expression {
        val callParams = makeCallExpressionParams(step)
        val expr = MethodCallExpr(callParams.scope, step.methodName, callParams.args)
        return expr
    }

    private fun makeTemplateExpression(step: TemplateEdge): Expression {
        val stringParams = step.params.mapValues { state -> checkNotNull(dependencies[state.value.machine].toString()) }
        return templateIntoAST(fillPlaceholders(step.template, stringParams))
    }

    private fun makeConstructorExpression(step: ConstructorEdge): Expression {
        val params = step.param.map { param -> checkNotNull(dependencies[param.machine]) }
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(step.dst.machine.type()), params)
        return expr
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

    private fun extractDependenciesFromNode(edge: Edge, node: Node) = when {
        node is MethodCallExpr && edge is CallEdge -> extractDependenciesFromMethod(edge, node);
        node is MethodCallExpr && edge is LinkedEdge -> extractDependenciesFromMethod(edge.edge as CallEdge, node);
        node is ObjectCreationExpr && edge is ConstructorEdge -> extractDependenciesFromConstructor(edge, node);
        else -> TODO()
    }

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
            if (context.contains(dep.machine)) {
                continue
            }
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
}

class ReplacementPerformer(val replacements: List<Replacement>,
                           val library2: Library) {
    fun apply() {
        for (replacement in replacements) {
            applyReplacement(replacement)
        }
    }

    fun applyReplacement(replacement: Replacement) {
        val oldExpr = replacement.oldNode
        val (statement, blockStmt) = getBlockStmt(oldExpr) ?: return
        val statements = blockStmt.stmts

        if (replacement.pendingExpressions.isNotEmpty()) {
            val pos = statements.indexOf(statement)
            val pushedPendingExpressions = if (replacement.makeVariable) replacement.pendingExpressions else replacement.pendingExpressions.dropLast(1)
            val pendingStatements = pushedPendingExpressions.map { pending -> makeNewStatement(pending) }
            statements.addAll(pos, pendingStatements)
            for (stmt in pushedPendingExpressions) {
                stmt.expression.parentNode = blockStmt
            }

            val newExpr = if (replacement.makeVariable) {
                NameExpr(getNewVarName(replacement.pendingExpressions))
            } else {
                replacement.pendingExpressions.last().expression
            }
            replaceNode(newExpr, oldExpr)
        } else {
            statements.remove(statement)
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

    private fun getNewVarName(pendingStmts: List<PendingExpression>): String = pendingStmts.map { stmt -> stmt.provides }.lastOrNull() ?: error("New statement should provide a variable")

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
}

class RouteExtractor(val library1: Library,
                     val codeElements: CodeElements,
                     val functionName: String,
                     val sourceFile: File) {
    fun extractFromJSON(traceFile: File): List<LocatedEdge> {
        val invocations = ObjectMapper().registerKotlinModule().readValue<List<Invocation>>(traceFile)
        val localInvocations = invocations.filter { inv -> inv.callerName == functionName && inv.filename == this.sourceFile.name }

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
            }
        }
        val usedEdgesCleanedUp = usedEdges.distinct()
        if (usedEdgesCleanedUp.isNotEmpty()) {
            println("--- Used edges:")
            for (edge in usedEdgesCleanedUp) {
                println(edge.edge.label())
            }
            println("---")
            graphvizRender(toDOT(library1, usedEdgesCleanedUp.map { usage -> usage.edge }), "extracted_" + functionName)
        }
        return usedEdgesCleanedUp.distinct()
    }

    fun checkRoute(route: List<Edge>) {
        route.filterNot { edge -> edge is UsageEdge }.fold(listOf<Edge>(), { visited, edge ->
            if (visited.isEmpty() || visited.any { prevEdge -> edge.src == prevEdge.dst }) {
                visited + edge
            } else {
                error("Unexpected edge! Visited: ${printRoute(visited)}, edge: ${edge.label()}")
            }
        })
    }

    private fun printRoute(route: List<Edge>) {
//        println("Route from %s to %s: ".format(src.stateAndMachineName(),
//                dst.stateAndMachineName()))
        for (state in route.withIndex()) {
            println("%d: %s".format(state.index, state.value.label()))
        }
    }

    data class LocatedEdge(
            val edge: Edge,
            val node: Node,
            val nodeLine: Int = node.endLine,
            val nodeColumn: Int = node.endColumn
    )

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
}