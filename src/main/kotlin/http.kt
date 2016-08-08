/**
 * Created by artyom on 05.07.16.
 */

object HTTPEntities {
    val request: Entity = Entity(name = "Request")
    val url: Entity = Entity(name = "URL")
    val connection: Entity = Entity(name = "Connection")
    val inputStream = Entity(name = "InputStream")
    val body: Entity = Entity(name = "Body")
    val client: Entity = Entity(name = "Client")
}

fun makeJava(): Library {
    val url = StateMachine(entity = HTTPEntities.url)

    val request = StateMachine(entity = HTTPEntities.request)
    val connection = StateMachine(entity = HTTPEntities.connection)

    val hasURL = State(name = "hasURL", machine = request)

    Edge(
            machine = request,
            src = request.getInitState(),
            dst = hasURL,
            action = ConstructorAction(
                    param = Param(
                            machine = url,
                            pos = 0
                    )
            )
    )

    makeLinkedEdge(
            machine = request,
            src = hasURL,
            dst = connection.getInitState(),
            action = CallAction(
                    methodName = "openConnection",
                    param = null
            )
    )

//    val inputStream = StateMachine(entity = HTTPEntities.inputStream)

//    makeLinkedEdge(
//            machine = connection,
//            dst = inputStream.getInitState(),
//            action = CallAction(
//                    methodName = "getInputStream",
//                    param = null
//            )
//    )

    val body = StateMachine(entity = HTTPEntities.body)

    makeLinkedEdge(
            machine = connection,
            dst = body.getInitState(),
            action = CallAction(
                    methodName = "readerToString",
                    param = Param(
                            machine = connection,
                            pos = 0
                    ),
                    className = "Main"
            )
    )

    return Library(
            stateMachines = listOf(url, request, connection, body),
            entityTypes = mapOf(
                    HTTPEntities.request to "URL",
                    HTTPEntities.url to "String",
                    HTTPEntities.connection to "URLConnection",
                    HTTPEntities.inputStream to "InputStream",
                    HTTPEntities.body to "String"
            )
    )
}

fun makeApache(): Library {
    val url = StateMachine(entity = HTTPEntities.url)
    val client = StateMachine(entity = HTTPEntities.client)
    val request = StateMachine(entity = HTTPEntities.request)
    val connection = StateMachine(entity = HTTPEntities.connection)

    val hasURL = State(name = "hasURL", machine = request)

    client.edges.clear()

    Edge(
            machine = client,
            src = client.getInitState(),
            dst = client.getConstructedState(),
            action = CallAction(
                    methodName = "createDefault",
                    param = null,
                    className = "HttpClients"
            )
    )

    Edge(
            machine = request,
            src = request.getInitState(),
            dst = hasURL,
            action = ConstructorAction(
                    param = Param(
                            machine = url,
                            pos = 0
                    )
            )
    )

    val setURI = Edge(
            machine = request,
            src = request.getConstructedState(),
            dst = hasURL,
            action = CallAction(
                    methodName = "setURI",
                    param = Param(
                            machine = url,
                            pos = 0
                    )
            )
    )

    val execute = makeLinkedEdge(
            machine = client,
            dst = connection.getInitState(),
            action = CallAction(
                    methodName = "execute",
                    param = Param(
                            machine = request,
                            state = hasURL,
                            dst = connection.getInitState(),
                            pos = 0
                    )
            )
    )

    val body = StateMachine(entity = HTTPEntities.body)

    makeLinkedEdge(
            machine = connection,
            dst = body.getInitState(),
            action = CallAction(
                    methodName = "responseToString",
                    param = Param(
                            machine = connection,
                            pos = 0
                    ),
                    className = "Main"
            )
    )

    return Library(
            stateMachines = listOf(url, request, client, connection, body),
            entityTypes = mapOf(
                    HTTPEntities.request to "HttpGet",
                    HTTPEntities.url to "String",
                    HTTPEntities.connection to "CloseableHttpResponse",
                    HTTPEntities.body to "String",
                    HTTPEntities.client to "CloseableHttpClient"
            )
    )
}