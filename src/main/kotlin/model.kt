/**
 * Created by artyom on 16.06.16.
 */

enum class ActionType {
    CONSTRUCTOR, METHOD_CALL, STATIC_CALL, AUTO, LINKED, MAKE_ARRAY, TEMPLATE, USAGE
}

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
        edges += Edge(
                machine = this,
                src = getInitState(),
                dst = getConstructedState(),
                action = AutoAction())
    }

    fun getInitState() = states.first { state -> state.name == "Init" }
    fun getConstructedState() = states.first { state -> state.name == "Constructed" }

    fun getDisplayedEdges() = edges.filterNot { it -> it.action is LinkedAction }

    override fun label(library: Library) = name + ": " + type(library)

    fun inherit(name: String): StateMachine {
        val copy = copy(name = name, inherits = this)
        copy.edges += Edge(
                machine = copy,
                dst = getConstructedState(),
                action = AutoAction()
        )
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

interface Action : Labelable {
    fun type(): ActionType
    override fun label(library: Library): String
}

data class CallAction(val methodName: String,
                      val param: Param?,
                      val className: String? = null) : Action {
    override fun label(library: Library) = "%s %s(%s)".format(className ?: "", methodName, param?.label(library) ?: "")
    override fun type() = ActionType.METHOD_CALL
}

class AutoAction : Action {
    override fun label(library: Library) = ""
    override fun type() = ActionType.AUTO
}

data class ConstructorAction(val param: Param?) : Action {
    var constructedMachine: StateMachine? = null
    override fun label(library: Library) = "new %s(%s)".format(constructedMachine?.label(library) ?: "Unknown", param?.label(library) ?: "")
    override fun type() = ActionType.CONSTRUCTOR
}

data class LinkedAction(val edge: Edge) : Action {
    override fun label(library: Library) = "return " + edge.getLinkedEdge().dst.machine.type(library) + "()"
    override fun type() = ActionType.LINKED
}

data class MakeArrayAction(val getSize: CallAction,
                           val getItem: CallAction) : Action {
    override fun type() = ActionType.MAKE_ARRAY
    override fun label(library: Library) = "Array of %s with size %s".format(getItem.label(library), getSize.label(library))
}

data class TemplateAction(val template: String,
                          val params: Map<String, Edge>) : Action {
    override fun type() = ActionType.TEMPLATE
    override fun label(library: Library) = template + " with " + params.toString()
}

data class UsageAction(val edge: Edge) : Action {
    override fun type() = ActionType.USAGE
    override fun label(library: Library) = "Usage in " + edge.label(library)
}

data class Edge(val machine: StateMachine,
                val src: State = makeConstructedState(machine),
                val dst: State = src,
                val action: Action) : Labelable {
    val linkedEdges = mutableSetOf<Edge>()

    init {
        machine.edges += this
        if (action is LinkedAction) {
            action.edge.linkedEdges += this
        }

        if (action is ConstructorAction) {
            action.constructedMachine = dst.machine
        }

        val param = when (action) {
            is ConstructorAction -> action.param
            is CallAction -> action.param
            else -> null
        }

        if (param != null) {
            val usageDst = param.dst ?: dst

            val usage = Edge(
                    machine = param.machine,
                    src = param.state,
                    dst = usageDst,
                    action = UsageAction(
                            edge = this
                    )
            )
            linkedEdges += usage
        }
    }

    fun getLinkedEdge() = linkedEdges.first { it -> it.action is LinkedAction }
    override fun label(library: Library) = action.label(library)
}

fun makeLinkedEdge(machine: StateMachine,
                   src: State = makeConstructedState(machine),
                   dst: State,
                   action: Action): Edge {
    val actionEdge = Edge(
            machine = machine,
            src = src,
            dst = src,
            action = action
    )
    val linkedEdge = Edge(
            machine = machine,
            src = src,
            dst = dst,
            action = LinkedAction(
                    edge = actionEdge
            )
    )

    return actionEdge
}

data class Param(val machine: StateMachine,
                 val state: State = machine.getConstructedState(),
                 val dst: State? = null,
                 val pos: Int = 0) : Labelable {
    override fun toString() = machine.name
    override fun label(library: Library) = machine.label(library)
}