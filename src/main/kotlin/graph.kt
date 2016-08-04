/**
 * Created by artyom on 16.06.16.
 */

object GraphEntities {
    val node: Entity = Entity(name = "Node")
    val nodeList: Entity = Entity(name = "NodeList")
    val num: Entity = Entity(name = "Number")
}

fun makeGraph1(): Library {
    val node = StateMachine(entity = GraphEntities.node)
    val parent = node.inherit(name = "parent")
    val child = node.inherit(name = "child")
    val getNode = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getNode",
                    param = Param(
                            machine = StateMachine(entity = GraphEntities.num),
                            pos = 0
                    )
            )
    )

    val getNum = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getNodeNum",
                    param = null
            )
    )

    Edge(
            machine = node,
            dst = child.getInitState(),
            action = LinkedAction(
                    edge = getNode
            )
    )

    val getParent = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getParent",
                    param = null
            )
    )

    Edge(
            machine = node,
            dst = parent.getInitState(),
            action = LinkedAction(
                    edge = getParent
            )
    )

    val list = StateMachine(entity = GraphEntities.nodeList)

    Edge(
            machine = node,
            dst = list.getInitState(),
            action = MakeArrayAction(
                    getSize = getNum.action as CallAction,
                    getItem = getNode.action as CallAction
            )
    )

//    Edge(
//            machine = list,
//            dst = child.getInitState(),
//            action = CallAction(
//                    methodName = "get",
//                    param = Param(
//                            entity = GraphEntities.num,
//                            pos = 0
//                    )
//            )
//    )

    return Library(
            stateMachines = listOf(node, list, parent, child),
            entityTypes = mapOf(
                    GraphEntities.node to "Node1",
                    GraphEntities.nodeList to "List<Node1>",
                    GraphEntities.num to "int"
            )
    )
}

fun makeGraph2(): Library {
    val list = StateMachine(entity = GraphEntities.nodeList)

    val listGet = Edge(
            machine = list,
            action = CallAction(
                    methodName = "get",
                    param = Param(
                            machine = StateMachine(entity = GraphEntities.num),
                            pos = 0
                    )
            )
    )

    val node = StateMachine(entity = GraphEntities.node)
    val parent = node.inherit(name = "parent")
    val child = node.inherit(name = "child")

    val getNode = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getNodeList",
                    param = null
            )
    )

    val getParent = Edge(
            machine = node,
            action = CallAction(
                    methodName = "getParentNode",
                    param = null
            )
    )

    Edge(
            machine = node,
            dst = parent.getInitState(),
            action = LinkedAction(
                    edge = getParent
            )
    )

    val listNodeCreate = Edge(
            machine = node,
            dst = list.getInitState(),
            action = LinkedAction(
                    edge = getNode
            )
    )

    val nodeCreate = Edge(
            machine = list,
            dst = child.getInitState(),
            action = LinkedAction(
                    edge = listGet
            )
    )

    return Library(
            stateMachines = listOf(node, list, parent, child),
            entityTypes = mapOf(
                    GraphEntities.node to "Node2",
                    GraphEntities.nodeList to "List<Node2>",
                    GraphEntities.num to "int"
            )
    )
}