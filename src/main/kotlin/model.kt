/**
 * Created by artyom on 16.06.16.
 */

interface Labelable {
    fun label(library: Library): String
}

interface Identifiable {
    fun id(): String
}

data class Entity(val name: String)

data class Library(val stateMachines: List<StateMachine>,
                   val entityTypes: Map<Entity, String>) {
//    init {
//        for ((key, value) in entityTypes) {
//            stateMachines.first { it -> it.entity == key }.type = value
//        }
//    }
}

data class Type(val entity: Entity,
                val type: String)

data class StateMachine(val entity: Entity,
                        val name: String = entity.name,
                        val inherits: StateMachine? = null) : Labelable {
    val states: MutableSet<State> = mutableSetOf()
    val edges: MutableSet<Edge> = mutableSetOf()

    init {
        states += makeInitState(this)
        states += makeConstructedState(this)
        edges += AutoEdge(
                machine = this,
                src = getInitState(),
                dst = getConstructedState()
        )
    }

    fun getInitState() = states.first { state -> state.name == "Init" }
    fun getConstructedState() = states.first { state -> state.name == "Constructed" }

    fun getDisplayedEdges() = edges.filterNot { edge -> edge is LinkedEdge || edge is UsageEdge }

    override fun label(library: Library) = name + ": " + type(library)

    fun inherit(name: String): StateMachine {
        val copy = copy(name = name, inherits = this)
        return copy
    }

    fun type(library: Library) = library.entityTypes[entity]
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
    override fun label(library: Library) = name
    fun label() = name
    fun stateAndMachineName() = machine.name + "." + name
}

fun makeInitState(machine: StateMachine) = State("Init", machine)
fun makeConstructedState(machine: StateMachine) = State("Constructed", machine)

interface Edge : Labelable {
    override fun label(library: Library): String
    val machine: StateMachine
    val src: State
    val dst: State

    fun createUsageEdges(params: List<Param>) = params.map { param -> UsageEdge(
                machine = param.machine,
                src = param.state,
                dst = param.state,
                edge = this
    )
    }
}

data class CallEdge(override val machine: StateMachine,
                    override val src: State = makeConstructedState(machine),
                    override val dst: State = src,

                    var linkedEdge: LinkedEdge? = null,

                    val methodName: String,
                    val param: List<Param> = listOf(),
                    val usageEdges: MutableSet<UsageEdge> = mutableSetOf(),
                    val className: String? = null) : Edge {

    init {
        machine.edges += this
        usageEdges += createUsageEdges(param)
    }

    override fun label(library: Library) = "%s %s(%s)".format(className ?: "", methodName, param.map { it.label(library) }.joinToString())
}

data class AutoEdge(override val machine: StateMachine,
                    override val src: State = makeConstructedState(machine),
                    override val dst: State = src) : Edge {
    init { machine.edges += this }

    override fun label(library: Library) = ""
}

data class ConstructorEdge(override val machine: StateMachine,
                           override val src: State = makeConstructedState(machine),
                           override val dst: State = src,

                           val param: Param?) : Edge {
    var constructedMachine: StateMachine? = null

    init {
        machine.edges += this
        constructedMachine = dst.machine
    }

    override fun label(library: Library) = "new %s(%s)".format(constructedMachine?.label(library) ?: "Unknown", param?.label(library) ?: "")
}

data class LinkedEdge(override val machine: StateMachine,
                      override val src: State = makeConstructedState(machine),
                      override val dst: State = src,

                      val edge: CallEdge) : Edge {
    init {
        machine.edges += this

        if (edge.linkedEdge != null) {
            error("Edge already linked")
        }
        edge.linkedEdge = this
    }

    override fun label(library: Library) = "return " + edge.linkedEdge?.dst?.machine?.type(library) + "()"
}

data class MakeArrayEdge(override val machine: StateMachine,
                         override val src: State = makeConstructedState(machine),
                         override val dst: State = src,

                         val getSize: CallEdge,
                           val getItem: CallEdge) : Edge {
    init { machine.edges += this }

    override fun label(library: Library) = "Array of %s with size %s".format(getItem.label(library), getSize.label(library))
}

data class TemplateEdge(override val machine: StateMachine,
                        override val src: State = makeConstructedState(machine),
                        override val dst: State = src,

                        val template: String,
                          val params: Map<String, Edge>) : Edge {
    init { machine.edges += this }

    override fun label(library: Library) = template + " with " + params.toString()
}

data class UsageEdge(override val machine: StateMachine,
                     override val src: State = makeConstructedState(machine),
                     override val dst: State = src,

                     val edge: Edge) : Edge {
    init { machine.edges += this }

    override fun label(library: Library) = "Usage in " + edge.label(library)
}

fun makeLinkedEdge(machine: StateMachine,
                   src: State = makeConstructedState(machine),
                   dst: State,

                   methodName: String,
                   param: List<Param> = listOf()): CallEdge {
    val callEdge = CallEdge(
            machine = machine,
            src = src,
            dst = src,
            methodName = methodName,
            param = param
    )
    val linkedEdge = LinkedEdge(
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
    override fun label(library: Library) = machine.label(library)
}