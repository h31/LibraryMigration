import com.github.javaparser.ASTHelper
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.DirectedPseudograph

/**
 * Created by artyom on 22.08.16.
 */

class Migration(val library1: Library,
                val library2: Library,
                val codeElements: CodeElements,
                val graph1: DirectedPseudograph<State, Edge> = toJGrapht(library1),
                val graph2: DirectedPseudograph<State, Edge> = toJGrapht(library2)) {

    fun makeRoute(src: State, dst: State) {
        val route = DijkstraShortestPath.findPathBetween(graph2, src, dst)
        println("Route from %s to %s: ".format(src.stateAndMachineName(),
                dst.stateAndMachineName()))
        for (state in route.withIndex()) {
            val action = if (state.value.action.type() == ActionType.LINKED) {
                (state.value.action as LinkedAction).edge.action
            } else {
                state.value.action
            }
            println("%d: %s".format(state.index, action.label(library2)))
        }
    }

    fun doMigration() {
        val edges = library1.stateMachines.flatMap { it -> it.edges }.asSequence()
        for (edge in edges) {
            println("Processing " + edge.label(library1) + "... ")
            if (edge.src == edge.dst) {
                println("  Makes a loop, skipping")
                continue
            }
            if (edge.action is AutoAction) {
                println("  Has an auto action, skipping")
                continue
            }
            if (edge.action is CallAction) {
                println("  Has a call action")
                val route = findRoute(graph2, edge.src, edge.dst)
                migrateMethodCall(edge, route)
            }
            if (edge.action is LinkedAction) {
                println("  Has a linked action")
                val dependency = edge.action.edge
                val route = findRoute(graph2, edge.src, edge.dst)
                migrateMethodCall(dependency, route)
            }
        }
    }

    private fun migrateMethodCall(edge: Edge, route: List<Edge>) {
        if (edge.action is CallAction) {
            val usages = getUsages(edge.action.methodName)
            if (usages.isNotEmpty()) {
                println("  Has %d usage(s)!".format(usages.size))
            }
            for (usage in usages) {
                val dependencies = getDependencies(edge, usage)
                applySteps(route, usage, dependencies)
            }
        }
    }

    private fun applySteps(steps: List<Edge>, methodCall: MethodCallExpr, dependencies: Map<StateMachine, Expression>) {
        val blockStmt = getBlockStmt(methodCall)
//    var insertionPoint = InsertionPoint(scope = methodCall.scope, parent = methodCall.parentNode)
//    removeMethodCall(methodCall)
        val pendingStmts = mutableListOf<Statement>()

        for (step in steps) {
            println("    Step: " + step.label(library2))

            val newExpressions: List<Expression> = when (step.action) {
                is CallAction -> makeCallExpression(step.action, dependencies, step, blockStmt)
                is ConstructorAction -> TODO()
                is AutoAction -> listOf()
                is LinkedAction -> {
                    val linkedEdge = step.action.edge
                    val expressions = makeCallExpression(linkedEdge.action as CallAction, dependencies, linkedEdge, blockStmt)
                    addLinkedAction(step.action, expressions, step)
                }
                is MakeArrayAction -> TODO() // makeArray(step.action)
                is TemplateAction -> TODO()
                is UsageAction -> makeCallExpression(step.action.edge.action as CallAction, dependencies, step.action.edge, blockStmt)
                else -> error("Unknown action!")
            }
            println("Received expressions: " + newExpressions.toString())
            val newStatements = newExpressions.map { expr -> ExpressionStmt(expr) }
            pendingStmts.addAll(newStatements)
        }
        blockStmt.stmts.addAll(0, pendingStmts)
    }

    private fun addLinkedAction(action: LinkedAction, expressions: List<Expression>, step: Edge): List<Expression> {
        val type = "CloseableHttpClient" // checkNotNull(library.entityTypes[machine.entity])
        val name = "newLinkedAction"
        val expr = expressions.first()
        val newVariable = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType(type), name)
        newVariable.vars.first().init = expr
        return listOf(newVariable)
    }

    private fun removeMethodCall(methodCall: MethodCallExpr) {
        val parent = methodCall.parentNode
        parent.childrenNodes.remove(methodCall)
        if (parent is MethodCallExpr) {
            parent.scope = methodCall.scope
        } else if (parent is ExpressionStmt) {
            parent.expression = methodCall.scope
        }
    }

    private fun unpackCallAction(edge: Edge): CallAction? {
        if (edge.action is LinkedAction) {
            return edge.action.edge.action as CallAction
        }
        else if (edge.action is UsageAction) {
            return edge.action.edge.action as CallAction
        }
        else if (edge.action is CallAction) {
            return edge.action
        }
        else {
            return null
        }
    }

    private fun makeCallExpressionDeps(action: CallAction, dependencies: Map<StateMachine, Expression>, edge: Edge): CallExpressionParams {
        val scope = if (action.className != null) {
            NameExpr(action.className)
        } else if (edge.machine in dependencies) {
            dependencies[edge.machine]
        } else {
            throw NeedDependencyException(edge.machine)
        }

        val args = if (action.param != null) {
            if (action.param.pos != 0) TODO()
            val argExpression = dependencies[action.param.machine]
            if (argExpression == null) throw NeedDependencyException(action.param.machine)
            listOf(argExpression)
        } else {
            listOf()
        }

        return CallExpressionParams(scope, args)
    }

    private fun makeMissingDependency(machine: StateMachine, blockStmt: BlockStmt, dependencies: Map<StateMachine, Expression>): Expression {
        val type = "CloseableHttpClient" // checkNotNull(library.entityTypes[machine.entity])
        val name = "newMachine"
        val newVariable = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType(type), name)
        blockStmt.stmts.add(0, ExpressionStmt(newVariable))

        val step = machine.edges.first { edge -> edge.src == machine.getInitState() && edge.dst == machine.getConstructedState() }
        val initExpr = makeCallExpression(step.action as CallAction, dependencies, step, blockStmt)
        newVariable.vars.first().init = initExpr.first()
        return NameExpr(name)
    }

    private fun makeCallExpression(action: CallAction, dependencies: Map<StateMachine, Expression>, step: Edge, blockStmt: BlockStmt): List<Expression> {
        var currentDeps = dependencies
        while (true) {
            try {
                val callParams = makeCallExpressionDeps(action, currentDeps, step)
                val expr = MethodCallExpr(callParams.scope, action.methodName, callParams.args)
                return listOf(expr)
            } catch (ex: NeedDependencyException) {
                val expr = makeMissingDependency(ex.machine, blockStmt, currentDeps)
                currentDeps += (ex.machine to expr)
            }
        }
    }

    private fun getBlockStmt(initialNode: Node): BlockStmt {
        var node: Node = initialNode
        while (node.parentNode is BlockStmt == false) {
            node = node.parentNode
        }
        return node.parentNode as BlockStmt
    }

    private fun findRoute(graph: DirectedPseudograph<State, Edge>, src: State, dst: State): List<Edge> {
        println("  Searching route from %s to %s".format(src.stateAndMachineName(), dst.stateAndMachineName()))
        return DijkstraShortestPath.findPathBetween(graph, src, dst)
    }

    private fun getUsages(methodName: String) =
            codeElements.methodCalls.filter { methodCall -> methodCall.name == methodName }

    private fun getDependencies(edge: Edge, methodCall: MethodCallExpr): Map<StateMachine, Expression> {
        val scope = methodCall.scope
        val callAction = unpackCallAction(edge)
        if (callAction != null && callAction.param != null) {
            val arg = methodCall.args.first()
            return mapOf(edge.machine to scope, callAction.param.machine to arg)
        } else {
            return mapOf(edge.machine to scope)
        }
    }
}