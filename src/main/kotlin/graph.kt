/**
 * Created by artyom on 16.06.16.
 */

object Entities {
    val node: Entity = Entity(name = "Node")
    val nodeList: Entity = Entity(name = "NodeList")
    val num: Entity = Entity(name = "Number")
}

fun makeGraph1(): Library {
    val node = StateMachine(entity = Entities.node)
    val getNode = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getNode",
                    param = Param(
                            entity = Entities.num,
                            pos = 0
                    )
            )
    )

    getNode.createdMachine = node

    return Library(
            stateMachines = listOf(node),
            entityTypes = mapOf(
                    Entities.node to "Node1",
                    Entities.nodeList to "List<Node1>",
                    Entities.num to "int"
            )
    )
}

fun makeGraph2(): Library {
    val list = StateMachine(entity = Entities.nodeList)

    val listGet = Edge(
            machine = list,
            action = CallAction(
                    methodName = "get",
                    param = Param(
                            entity = Entities.num,
                            pos = 0
                    )
            )
    )

    val node = StateMachine(entity = Entities.node)

    val getNode = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getNodeList",
                    param = null
            )
    )

    getNode.createdMachine = list

    listGet.createdMachine = node

    return Library(
            stateMachines = listOf(node, list),
            entityTypes = mapOf(
                    Entities.node to "Node2",
                    Entities.nodeList to "List<Node2>",
                    Entities.num to "int"
            )
    )
}