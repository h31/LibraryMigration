import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.samskivert.mustache.Mustache
import java.io.FileReader

/**
 * Created by artyom on 05.07.16.
 */

fun fillPlaceholders(template: String, params: Map<String, Edge>, variables: Map<StateMachine, String>): String {
    val stringParams = params.mapValues { it -> generateCode(it.value, variables[it.value.machine]) }

    val compiled = Mustache.compiler().escapeHTML(false).compile(template)
    return compiled.execute(stringParams)
}

fun templateIntoAST(template: String) = IntegerLiteralExpr(template)

fun generateCode(edge: Edge, obj: String?): String = when (edge) {
    is CallEdge -> obj + "." + edge.methodName
    is ConstructorEdge -> "new " + obj
    is AutoEdge -> obj ?: throw Exception()
    else -> throw Exception()
}

fun main(args: Array<String>) {
    val machine = StateMachine(entity = Entity("test"))
    val edge = CallEdge(machine = machine, methodName = "method")

    println(fillPlaceholders("Hello {{ obj }}", params = mapOf("obj" to edge), variables = mapOf(machine to "object")))
}