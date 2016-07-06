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

fun generateCode(edge: Edge, obj: String?): String = when (edge.action) {
    is CallAction -> obj + "." + edge.action.methodName
    is ConstructorAction -> "new " + obj
    is AutoAction -> obj ?: throw Exception()
    else -> throw Exception()
}

fun main(args: Array<String>) {
    val machine = StateMachine(entity = Entity("test"), type = "test")
    val edge = Edge(machine = machine, action = CallAction("method", null))

    println(fillPlaceholders("Hello {{ obj }}", params = mapOf("obj" to edge), variables = mapOf(machine to "object")))
}