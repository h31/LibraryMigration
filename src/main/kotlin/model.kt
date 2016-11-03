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
                   val machineTypes: Map<StateMachine, String>) {
    val machineSimpleTypes: MutableMap<StateMachine, String> = mutableMapOf()
    init {
        for (machine in stateMachines) {
            machine.library = this
        }
        if (machineTypes.size != stateMachines.size) error("Types: ${machineTypes.size}, machines: ${stateMachines.size}")
        machineTypes.mapValuesTo(machineSimpleTypes, { entry -> simpleType(entry.value)})
    }

    private fun simpleType(type: String) = type.substringAfterLast('.').replace('$', '.')
}

//data class Type(val entity: Entity,
//                val type: String)

data class StateMachine(val name: String,
                        val inherits: StateMachine? = null) : Labelable {
    val states: MutableSet<State> = mutableSetOf()
    val edges: MutableSet<Edge> = mutableSetOf()
    var library: Library? = null

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

    fun type() = library?.machineSimpleTypes?.get(this) ?: error("No such type")
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
    fun stateAndMachineName() = machine.name + "." + name
    fun isInit() = name == "Init"
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

    fun getSubsequentAutoEdges() = dst.machine.edges.filter { edge -> edge is AutoEdge && edge.src == dst }
    fun getStyle(): String
}

interface ExpressionEdge : Edge {
    var linkedEdge: LinkedEdge?
    val isStatic: Boolean

    fun createUsageEdges(params: List<Param>, dst: State) = params.map { param ->
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

                    val methodName: String,
                    val param: List<Param> = listOf(),
                    override val isStatic: Boolean = false) : ExpressionEdge {
    override fun getStyle() = "bold"

    override var linkedEdge: LinkedEdge? = null
    val usageEdges: MutableSet<UsageEdge> = mutableSetOf()

    init {
        machine.edges += this
        usageEdges += createUsageEdges(param, dst)
    }

    override fun label() = "%s(%s)".format(methodName, param.map(Param::label).joinToString())
}

data class AutoEdge(override val machine: StateMachine,
                    override val src: State = makeConstructedState(machine),
                    override val dst: State = src,
                    override val action: Action? = null) : Edge {
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

                           val param: List<Param> = listOf()) : ExpressionEdge {
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

data class LinkedEdge(override val machine: StateMachine,
                      override val src: State = makeConstructedState(machine),
                      override val dst: State = src,
                      override val action: Action? = null,

                      val edge: ExpressionEdge) : Edge {
    override fun getStyle() = "dotted"

    init {
        machine.edges += this

        if (edge.linkedEdge != null) {
            error("Edge already linked")
        }
        edge.linkedEdge = this
    }

    override fun label() = "return " + edge.linkedEdge?.dst?.machine?.type() + "()"
}

data class MakeArrayEdge(override val machine: StateMachine,
                         override val src: State = makeConstructedState(machine),
                         override val dst: State = src,
                         override val action: Action? = null,

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

                        val template: String,
                        val params: Map<String, State>,
                        override val isStatic: Boolean = false) : ExpressionEdge {
    override fun getStyle() = "bold"

    override var linkedEdge: LinkedEdge? = null
    val usageEdges: MutableSet<UsageEdge> = mutableSetOf()

    init {
        machine.edges += this
        for (param in params) {
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

                     val edge: ExpressionEdge) : Edge {
    override fun getStyle() = "dashed"

    init {
        machine.edges += this
    }

    override fun label() = "Usage in " + edge.label()
}

fun makeLinkedEdge(machine: StateMachine,
                   src: State = makeConstructedState(machine),
                   dst: State,

                   methodName: String,
                   param: List<Param> = listOf(),
                   isStatic: Boolean = false): CallEdge {
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
            edge = callEdge
    )

    return callEdge
}

data class Param(val machine: StateMachine,
                 val state: State = machine.getConstructedState()) : Labelable {
    override fun toString() = machine.name
    override fun label() = machine.label()
}

data class Action(val name: String,
                  val feature: String)