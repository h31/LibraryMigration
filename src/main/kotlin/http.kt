/**
 * Created by artyom on 05.07.16.
 */

object HTTPEntities {
    val request: Entity = Entity(name = "Request")
    val url: Entity = Entity(name = "URL")
    val connection: Entity = Entity(name = "Connection")
//    val inputStream = Entity(name = "InputStream")
    val body: Entity = Entity(name = "Body")
    val client: Entity = Entity(name = "Client")
}

fun makeJava(): Library {
    val url = StateMachine(entity = HTTPEntities.url)

    val request = StateMachine(entity = HTTPEntities.request)
    val connection = StateMachine(entity = HTTPEntities.connection)

    val hasURL = State(name = "hasURL", machine = request)

    ConstructorEdge(
            machine = request,
            src = request.getInitState(),
            dst = hasURL,
                    param = Param(
                            machine = url
                    )
    )

    makeLinkedEdge(
            machine = request,
            src = hasURL,
            dst = connection.getInitState(),
                    methodName = "openConnection"
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
                    methodName = "readerToString",
                    param = listOf(Param(
                            machine = connection
                    )
                    )
    )

    return Library(
            stateMachines = listOf(url, request, connection, body),
            machineTypes = mapOf(
                    request to "URL",
                    url to "String",
                    connection to "URLConnection",
//                    inputStream to "InputStream",
                    body to "String"
            )
    )
}

fun makeApache(): Library {
    val url = StateMachine(entity = HTTPEntities.url)
    val client = StateMachine(entity = HTTPEntities.client)
    val request = StateMachine(entity = HTTPEntities.request)
    val connection = StateMachine(entity = HTTPEntities.connection)
    val httpClients = StateMachine(entity = Entity("httpClients"))

    val hasURL = State(name = "hasURL", machine = request)

    client.edges.clear()

    makeLinkedEdge(
            machine = httpClients,
            dst = client.getConstructedState(),
                    methodName = "createDefault",
            isStatic = true
    )

    ConstructorEdge(
            machine = request,
            src = request.getInitState(),
            dst = hasURL,
                    param = Param(
                            machine = url
                    )
    )

    val setURI = CallEdge(
            machine = request,
            src = request.getConstructedState(),
            dst = hasURL,
                    methodName = "setURI",
                    param = listOf(Param(
                            machine = url
                    )
                    )
    )

    val execute = makeLinkedEdge(
            machine = client,
            dst = connection.getInitState(),
                    methodName = "execute",
                    param = listOf(Param(
                            machine = request,
                            state = hasURL
                    )
                    )
    )

    val body = StateMachine(entity = HTTPEntities.body)

    makeLinkedEdge(
            machine = connection,
            dst = body.getInitState(),
                    methodName = "responseToString",
                    param = listOf(Param(
                            machine = connection
                    )
                    )
    )

    return Library(
            stateMachines = listOf(url, request, client, connection, body, httpClients),
            machineTypes = mapOf(
                    request to "HttpGet",
                    url to "String",
                    connection to "CloseableHttpResponse",
                    body to "String",
                    client to "CloseableHttpClient",
                    httpClients to "HttpClients"
            )
    )
}