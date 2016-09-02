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
    val contentLength: Entity = Entity(name = "ContentLength")
}

fun makeJava(): Library {
    val url = StateMachine(entity = HTTPEntities.url)

    val request = StateMachine(entity = HTTPEntities.request)
    val connection = StateMachine(entity = HTTPEntities.connection)

    val hasURL = State(name = "hasURL", machine = request)
    val inputStream = StateMachine(entity = HTTPEntities.inputStream)
    val contentLength = StateMachine(entity = HTTPEntities.contentLength)

    ConstructorEdge(
            machine = request,
            src = request.getDefaultState(),
            dst = hasURL,
            param = listOf(Param(
                    machine = url
            )
            )
    )

    makeLinkedEdge(
            machine = request,
            src = hasURL,
            dst = connection.getDefaultState(),
            methodName = "openConnection"
    )

//    val inputStream = StateMachine(entity = HTTPEntities.inputStream)

//    makeLinkedEdge(
//            machine = connection,
//            dst = inputStream.getDefaultState(),
//            action = CallAction(
//                    methodName = "getInputStream",
//                    param = null
//            )
//    )

    val body = StateMachine(entity = HTTPEntities.body)

    makeLinkedEdge(
            machine = connection,
            dst = body.getDefaultState(),
            methodName = "readerToString",
            param = listOf(Param(
                    machine = connection
            )
            )
    )

    LinkedEdge(
            machine = connection,
            dst = inputStream.getDefaultState(),
            edge = CallEdge(
                    machine = connection,
                    methodName = "getInputStream"
            )
    )

    LinkedEdge(
            machine = connection,
            dst = contentLength.getDefaultState(),
            edge = CallEdge(
                    machine = connection,
                    methodName = "getContentLengthLong"
            )
    )

    return Library(
            stateMachines = listOf(url, request, connection, body, inputStream, contentLength),
            machineTypes = mapOf(
                    request to "URL",
                    url to "String",
                    connection to "URLConnection",
                    inputStream to "InputStream",
                    contentLength to "long",
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
    val inputStream = StateMachine(entity = HTTPEntities.inputStream)
    val contentLength = StateMachine(entity = HTTPEntities.contentLength)

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
            src = request.getDefaultState(),
            dst = hasURL,
            param = listOf(Param(
                    machine = url
            )
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
            dst = connection.getDefaultState(),
            methodName = "execute",
            param = listOf(Param(
                    machine = request,
                    state = hasURL
            )
            )
    )

    val body = StateMachine(entity = HTTPEntities.body)
    val main = StateMachine(entity = Entity("Main"))

    val toStringTemplate = TemplateEdge(
            machine = main,
            template = "EntityUtils.toString({{ httpResponse }}.getEntity())",
            params = mapOf("httpResponse" to connection.getConstructedState()),
            isStatic = true
    )

    LinkedEdge(
            machine = main,
            dst = body.getDefaultState(),
            edge = toStringTemplate
    )

    LinkedEdge(
            machine = connection,
            dst = inputStream.getDefaultState(),
            edge = TemplateEdge(
                    machine = connection,
                    template = "{{ httpResponse }}.getEntity().getContent()",
                    params = mapOf("httpResponse" to connection.getConstructedState())
            )
    )

    LinkedEdge(
            machine = connection,
            dst = contentLength.getDefaultState(),
            edge = TemplateEdge(
                    machine = connection,
                    template = "{{ httpResponse }}.getEntity().getContentLength()",
                    params = mapOf("httpResponse" to connection.getConstructedState())
            )
    )

    return Library(
            stateMachines = listOf(url, request, client, connection, body, httpClients, main, inputStream, contentLength),
            machineTypes = mapOf(
                    request to "HttpGet",
                    url to "String",
                    connection to "CloseableHttpResponse",
                    inputStream to "InputStream",
                    contentLength to "long",
                    body to "String",
                    client to "CloseableHttpClient",
                    httpClients to "HttpClients",
                    main to "String"
            )
    )
}