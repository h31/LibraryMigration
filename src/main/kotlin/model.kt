import com.github.javaparser.ast.expr.MethodCallExpr

/**
 * Created by artyom on 16.06.16.
 */

interface Labelable {
    fun label(): String
}

interface Identifiable {
    fun id(): String
}

//data class Entity(val name: String)

data class Library(val name: String,
                   val stateMachines: List<StateMachine>,
                   val machineTypes: Map<StateMachine, String>,
                   val typeGenerator: (StateMachine, Map<String, Any>) -> String? = {machine, props -> null}) {
    val machineSimpleTypes: Map<StateMachine, String> get() = machineTypes.mapValues { entry -> simpleType(entry.value) }
    val edges: List<Edge> = stateMachines.flatMap(StateMachine::edges)
    private val additionalTypes: List<String> = edges.filterIsInstance<TemplateEdge>().flatMap(TemplateEdge::additionalTypes)
    init {
        for (machine in stateMachines) {
            machine.library = this
        }
        if (machineTypes.size != stateMachines.size) error("Types: ${machineTypes.size}, machines: ${stateMachines.size}")
    }

    fun simpleType(type: String) = type.substringAfterLast('.').replace('$', '.')

    fun getType(machine: StateMachine, props: Map<String, Any>?): String = if (props != null) typeGenerator(machine, props) ?: machineSimpleTypes[machine] ?: TODO() else machineSimpleTypes[machine] ?: TODO()

    fun allTypes() = machineTypes.values + additionalTypes

    fun states() = stateMachines.flatMap(StateMachine::states)
}

//data class Type(val entity: Entity,
//                val type: String)

data class StateMachine(val name: String,
                        val inherits: StateMachine? = null) : Labelable {
    val states: MutableSet<State> = mutableSetOf()
    val edges: MutableSet<Edge> = mutableSetOf()
    var migrateProperties: (Map<StateMachine, Map<String, Any>>) -> Map<String, Any> = { mapOf() }
    lateinit var library: Library

    init {
//        states += makeInitState(this)
        states += makeConstructedState(this)
//        edges += AutoEdge(
//                machine = this,
//                src = getInitState(),
//                dst = getConstructedState()
//        )
    }

    fun getInitState() = states.first { state -> state.name == "Init" }
    fun getConstructedState() = states.first { state -> state.name == "Constructed" }
    fun getFinalState() = states.first { state -> state.name == "Final" }
    fun getDefaultState() = getConstructedState()

    fun getDisplayedEdges() = edges.filterNot { edge -> edge is LinkedEdge }

    override fun label() = name + ": " + type()

    fun inherit(name: String): StateMachine {
        val copy = copy(name = name, inherits = this)
        return copy
    }

    fun type() = checkNotNull(library.machineSimpleTypes[this])
}

data class State(val name: String,
                 val machine: StateMachine) : Labelable, Identifiable {
    init {
        machine.states.add(this)
    }

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

    override fun id() = name + "_" + machine.name
    override fun label() = name
    override fun toString() = stateAndMachineName()
    fun stateAndMachineName() = machine.name + "." + name
    fun isInit() = name == "Init"
    fun isFinal() = name == "Final"
}

fun makeInitState(machine: StateMachine) = State("Init", machine)
fun makeConstructedState(machine: StateMachine) = State("Constructed", machine)
fun makeFinalState(machine: StateMachine) = State("Final", machine)

interface Edge : Labelable {
    override fun label(): String
    val machine: StateMachine
    val src: State
    val dst: State
    val action: Action?
    var allowTransition: (MutableMap<String, Any>) -> Boolean
    val actionParams: (Any) -> Map<String, Any>

    fun getSubsequentAutoEdges() = dst.machine.edges.filter { edge -> edge is AutoEdge && edge.src == dst }
    fun getStyle(): String
    fun canBeSkipped(): Boolean {
        val loop = src == dst
        val withSideEffects = action?.withSideEffects == true
        val map = mutableMapOf<String, Any>()
        allowTransition(map)
        return loop && !withSideEffects // && map.isEmpty()
    }
    fun getNonSideEffectsActions() = if (action?.withSideEffects == false) listOfNotNull(action) else listOf()
}

data class Requirements(val allowTransition: (Map<String, Any>) -> Boolean = {true},
                        val migrateProperties: (Map<StateMachine, Map<String, Any>>) -> Map<String, Any> = {mapOf()},
                        val actionParams: (Any) -> Map<String, Any> = {mapOf()}
)

interface ExpressionEdge : Edge {
    var linkedEdge: LinkedEdge?
    val isStatic: Boolean
    val param: List<Param>

    fun createUsageEdges(params: List<Param>, dst: State) = params.filterIsInstance<EntityParam>().map { param ->
        UsageEdge(
                machine = param.machine,
                src = param.state,
                dst = dst,
                edge = this
        )
    }
}

data class CallEdge(override val machine: StateMachine,
                    override val src: State = makeConstructedState(machine),
                    override val dst: State = src,
                    override val action: Action? = null,
                    override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                    val callActionParams: (MethodCallExpr) -> Map<String, Any> = {mapOf()},

                    val methodName: String,
                    override val param: List<Param> = listOf(),
                    override val isStatic: Boolean = false,
                    val hasReturnValue: Boolean = true) : ExpressionEdge {
    override fun getStyle() = "bold"

    override var linkedEdge: LinkedEdge? = null
    val usageEdges: MutableSet<UsageEdge> = mutableSetOf()
    override val actionParams: (Any) -> Map<String, Any> = {node -> callActionParams(node as MethodCallExpr)}

    init {
        machine.edges += this
        usageEdges += createUsageEdges(param, dst)
    }

    override fun label() = "%s(%s)".format(methodName, param.map(Param::label).joinToString())
    override fun toString() = label()
}

data class AutoEdge(override val machine: StateMachine,
                    override val src: State = makeConstructedState(machine),
                    override val dst: State = src,
                    override val action: Action? = null,
                    override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                    override val actionParams: (Any) -> Map<String, Any> = {mapOf()}) : Edge {
    override fun getStyle() = "solid"

    init {
        machine.edges += this
    }

    override fun label() = ""
}

data class ConstructorEdge(override val machine: StateMachine,
                           override val src: State = makeConstructedState(machine),
                           override val dst: State = src,
                           override val action: Action? = null,
                           override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                           override val actionParams: (Any) -> Map<String, Any> = {mapOf()},

                           override val param: List<Param> = listOf()) : ExpressionEdge {
    override fun getStyle() = "bold"

    var constructedMachine: StateMachine? = null
    override var linkedEdge: LinkedEdge? = null
    val usageEdges: MutableSet<UsageEdge> = mutableSetOf()
    override val isStatic: Boolean = true

    init {
        machine.edges += this
        constructedMachine = dst.machine
        usageEdges += createUsageEdges(param, dst)
    }

    override fun label() = "new %s(%s)".format(constructedMachine?.label(), param.map(Param::label).joinToString())
}

data class LinkedEdge(val edge: ExpressionEdge,
                      override val machine: StateMachine = edge.machine,
                      override val src: State = edge.src,
                      override val dst: State,
                      override val action: Action? = null,
                      override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                      override val actionParams: (Any) -> Map<String, Any> = {mapOf()}

                      ) : Edge {
    override fun getStyle() = "dotted"

    init {
        machine.edges += this

        if (edge.linkedEdge != null) {
            error("Edge already linked")
        }
        edge.linkedEdge = this
    }

    override fun label() = "return " + edge.linkedEdge?.dst?.machine?.type() + "()"
    override fun toString() = label()
}

data class MakeArrayEdge(override val machine: StateMachine,
                         override val src: State = makeConstructedState(machine),
                         override val dst: State = src,
                         override val action: Action? = null,
                         override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                         override val actionParams: (Any) -> Map<String, Any> = {mapOf()},

                         val getSize: CallEdge,
                         val getItem: CallEdge) : Edge {
    override fun getStyle() = "solid"

    init {
        machine.edges += this
    }

    override fun label() = "Array of %s with size %s".format(getItem.label(), getSize.label())
}

data class TemplateEdge(override val machine: StateMachine,
                        override val src: State = makeConstructedState(machine),
                        override val dst: State = src,
                        override val action: Action? = null,
                        override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                        override val actionParams: (Any) -> Map<String, Any> = {mapOf()},

                        val template: String,
                        val templateParams: Map<String, State>,
                        override val isStatic: Boolean = false,
                        val additionalTypes: List<String> = listOf()) : ExpressionEdge {
    override fun getStyle() = "bold"
    override val param: List<Param> = templateParams.map { EntityParam(it.value.machine, it.value) }

    override var linkedEdge: LinkedEdge? = null
    val usageEdges: MutableSet<UsageEdge> = mutableSetOf()

    init {
        machine.edges += this
        for (param in templateParams) {
            if (param.value == src) {
                continue
            }
            usageEdges += UsageEdge(
                    machine = param.value.machine,
                    src = param.value,
                    dst = dst,
                    edge = this
            )
        }
    }

    override fun label() = "Template"
}

data class UsageEdge(override val machine: StateMachine,
                     override val src: State = makeConstructedState(machine),
                     override val dst: State = src,
                     override val action: Action? = null,
                     override var allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                     override val actionParams: (Any) -> Map<String, Any> = {mapOf()},

                     val edge: ExpressionEdge) : Edge {
    override fun getStyle() = "dashed"

    init {
        machine.edges += this
    }

    override fun label() = "Usage in " + edge.label()
    override fun toString() = label()
}

fun makeLinkedEdge(machine: StateMachine,
                   src: State = makeConstructedState(machine),
                   dst: State,

                   methodName: String,
                   param: List<Param> = listOf(),
                   isStatic: Boolean = false,
                   allowTransition: (MutableMap<String, Any>) -> Boolean = {true},
                   actionParams: (Any) -> Map<String, Any> = {mapOf()}): CallEdge {
    val callEdge = CallEdge(
            machine = machine,
            src = src,
            dst = src,
            methodName = methodName,
            param = param,
            isStatic = isStatic
    )
    LinkedEdge(
            machine = machine,
            src = src,
            dst = dst,
            edge = callEdge,
            allowTransition = allowTransition,
            actionParams = actionParams
    )

    return callEdge
}

interface Param : Labelable

data class EntityParam(val machine: StateMachine,
                 val state: State = machine.getConstructedState()) : Param {
    override fun toString() = machine.name
    override fun label() = machine.label()
}

data class PropertyParam(val propertyName: String) : Param {
    override fun label() = toString()
}

data class ActionParam(val propertyName: String) : Param {
    override fun label() = toString()
}

data class ConstParam(val value: String) : Param {
    override fun label() = value
}

data class Action(val name: String,
                  val feature: String = "Main",
                  val withSideEffects: Boolean = false): Comparable<Action> {
    override fun compareTo(other: Action) = name.compareTo(other.name)
    override fun toString() = name
}