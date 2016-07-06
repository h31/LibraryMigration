/**
 * Created by artyom on 16.06.16.
 */

enum class ActionType {
    CONSTRUCTOR, METHOD_CALL, STATIC_CALL, AUTO, LINKED, MAKE_ARRAY, TEMPLATE
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
                        val type: String,
                        val inherits: StateMachine? = null) {
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

    fun label() = name + ": " + type

    fun inherit(name: String): StateMachine {
        val copy = copy(name = name, inherits = this)
        copy.edges += Edge(
                machine = copy,
                dst = getConstructedState(),
                action = AutoAction()
        )
        return copy
    }
}

data class State(val name: String,
                 val machine: StateMachine) {
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

    fun id() = name + "_" + machine.name
    fun label() = name
}

fun makeInitState(machine: StateMachine) = State("Init", machine)
fun makeConstructedState(machine: StateMachine) = State("Constructed", machine)

interface Action {
    fun type(): ActionType
    fun label(): String
}

data class CallAction(val methodName: String,
                      val param: Param?) : Action {
    override fun label() = "%s(%s)".format(methodName, param?.label() ?: "")
    override fun type() = ActionType.METHOD_CALL
}

data class StaticCallAction(val methodName: String,
                            val param: Param) : Action {
    override fun label() = "%s(%s)".format(methodName, param.label())
    override fun type() = ActionType.STATIC_CALL
}

class AutoAction : Action {
    override fun label() = ""
    override fun type() = ActionType.AUTO
}

data class ConstructorAction(val param: Param?) : Action {
    var constructedMachine: StateMachine? = null
    override fun label() = "new %s(%s)".format(constructedMachine?.type ?: "Unknown", param ?: "")
    override fun type() = ActionType.CONSTRUCTOR
}

data class LinkedAction(val edge: Edge) : Action {
    override fun label() = "return " + edge.getLinkedEdges().first().dst.machine.type + "()"
    override fun type() = ActionType.LINKED
}

data class MakeArrayAction(val getSize: CallAction,
                           val getItem: CallAction) : Action {
    override fun type() = ActionType.MAKE_ARRAY
    override fun label() = "Array of %s with size %s".format(getItem.label(), getSize.label())
}

data class TemplateAction(val template: String,
                          val params: Map<String, Edge>) : Action {
    override fun type() = ActionType.TEMPLATE
    override fun label() = template + " with " + params.toString()
}

data class Edge(val machine: StateMachine,
                val src: State = makeConstructedState(machine),
                val dst: State = src,
                val action: Action,
                val autoRegister: Boolean = true) {

    init {
        if (autoRegister) {
            machine.edges += this
        }
        if (action is ConstructorAction) {
            action.constructedMachine = dst.machine
        }
    }

    fun getLinkedEdges() = machine.edges.filter { it -> it.action is LinkedAction && it.action.edge == this }
    fun label() = action.label()
}

fun makeLinkedEdge(machine: StateMachine,
                   src: State = makeConstructedState(machine),
                   dst: State,
                   action: Action): Edge {
    val actionEdge = Edge(
            machine = machine,
            src = src,
            action = action
    )
    val linkedEdge = Edge(
            machine = machine,
            dst = dst,
            action = LinkedAction(
                    edge = actionEdge
            )
    )

    return actionEdge
}

data class Param(val machine: StateMachine,
                 val pos: Int) {
    override fun toString() = label()
    fun label() = machine.label()
}