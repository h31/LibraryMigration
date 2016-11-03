/**
 * Created by artyom on 05.07.16.
 */

fun makeJava(): Library {
    val url = StateMachine(name = "URL")

    val request = StateMachine(name = "Request")
    val connection = StateMachine(name = "Connection")

    val hasURL = State(name = "hasURL", machine = request)
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")

    request.states += makeInitState(request)

    ConstructorEdge(
            machine = request,
            src = request.getInitState(),
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

    val body = StateMachine(name = "Body")

    val readerToString = TemplateEdge(
            machine = connection,
            template = "new BufferedReader(new InputStreamReader({{ conn }}.getInputStream())).lines().collect(Collectors.joining(\"\\n\"))",
            params = mapOf("conn" to connection.getDefaultState())
    )

    LinkedEdge(
            machine = connection,
            dst = body.getDefaultState(),
            edge = readerToString
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
            name = "java",
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
    val url = StateMachine(name = "URL")
    val client = StateMachine(name = "Client")
    val request = StateMachine(name = "Request")
    val connection = StateMachine(name = "Connection")
    val httpClients = StateMachine(name = "httpClients")
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")
    val entity = StateMachine(name = "Entity")
    val entityUtils = StateMachine(name = "EntityUtils")
    val body = StateMachine(name = "Body")
    val statusCode = StateMachine(name = "StatusCode")

    val hasURL = State(name = "hasURL", machine = request)

//    client.edges.clear()
    client.states += makeFinalState(client)
    request.states += makeInitState(request)
    httpClients.states += makeInitState(httpClients)

    makeLinkedEdge(
            machine = httpClients,
            src = httpClients.getInitState(),
            dst = client.getConstructedState(),
            methodName = "createDefault",
            isStatic = true
    )

    ConstructorEdge(
            machine = request,
            src = request.getInitState(),
            dst = hasURL,
            param = listOf(Param(
                    machine = url
            )
            )
    )

//    val setURI = CallEdge(
//            machine = request,
//            src = request.getConstructedState(),
//            dst = hasURL,
//            methodName = "setURI",
//            param = listOf(Param(
//                    machine = url
//            )
//            )
//    )

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

    CallEdge(
            machine = client,
            src = client.getDefaultState(),
            dst = client.getFinalState(),
            methodName = "close"
    )

    makeLinkedEdge(
            machine = connection,
            dst = entity.getDefaultState(),
            methodName = "getEntity"
    )

    makeLinkedEdge(
            machine = entityUtils,
            dst = body.getDefaultState(),
            methodName = "toString",
            param = listOf(Param(machine = entity)),
            isStatic = true
    )

//    val toStringTemplate = TemplateEdge(
//            machine = main,
//            template = "EntityUtils.toString({{ httpResponse }}.getEntity())",
//            params = mapOf("httpResponse" to connection.getConstructedState()),
//            isStatic = true
//    )

//    LinkedEdge(
//            machine = main,
//            dst = body.getDefaultState(),
//            edge = toStringTemplate
//    )

    makeLinkedEdge(
            machine = entity,
            dst = inputStream.getDefaultState(),
            methodName = "getContent"
    )

//    LinkedEdge(
//            machine = connection,
//            dst = inputStream.getDefaultState(),
//            edge = TemplateEdge(
//                    machine = connection,
//                    template = "{{ httpResponse }}.getEntity().getContent()",
//                    params = mapOf("httpResponse" to connection.getConstructedState())
//            )
//    )

    makeLinkedEdge(
            machine = entity,
            dst = contentLength.getDefaultState(),
            methodName = "getContentLength"
    )

    LinkedEdge(
            machine = connection,
            dst = statusCode.getConstructedState(),
            edge = TemplateEdge(
                    machine = connection,
                    template = "{{ conn }}.getStatusLine().getStatusCode()",
                    params = mapOf("conn" to connection.getDefaultState())
            )
    )

//    LinkedEdge(
//            machine = connection,
//            dst = contentLength.getDefaultState(),
//            edge = TemplateEdge(
//                    machine = connection,
//                    template = "{{ httpResponse }}.getEntity().getContentLength()",
//                    params = mapOf("httpResponse" to connection.getConstructedState())
//            )
//    )

    return Library(
            name = "apache",
            stateMachines = listOf(url, request, client, connection, body, httpClients,
                    inputStream, contentLength, entity, entityUtils, statusCode),
            machineTypes = mapOf(
                    url to "String",
                    request to "org.apache.http.client.methods.HttpGet",
                    client to "org.apache.http.impl.client.CloseableHttpClient",
                    connection to "org.apache.http.client.methods.CloseableHttpResponse",
                    body to "String",
                    httpClients to "org.apache.http.impl.client.HttpClients",
                    inputStream to "InputStream",
                    contentLength to "long",
                    entity to "org.apache.http.HttpEntity",
                    entityUtils to "org.apache.http.util.EntityUtils",
                    statusCode to "int"
            )
    )
}

fun makeOkHttp(): Library {
    val url = StateMachine(name = "URL")
    val client = StateMachine(name = "Client")
    val request = StateMachine(name = "Request")
    val builder = StateMachine(name = "Builder")
    val call = StateMachine(name = "Call")
    val connection = StateMachine(name = "Connection")
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")
    val entity = StateMachine(name = "Entity")
    val body = StateMachine(name = "Body")
    val statusCode = StateMachine(name = "StatusCode")
    val static = StateMachine(name = "Static")

    request.states.clear()
    client.states += makeInitState(client)
    builder.states += makeInitState(builder)
    client.states += makeFinalState(client)

    val hasURL = State(name = "hasURL", machine = request)
    val builderHasURL = State(name = "hasURL", machine = builder)

    ConstructorEdge(
            machine = client,
            src = client.getInitState(),
            dst = client.getConstructedState()
    )

    ConstructorEdge(
            machine = builder,
            src = builder.getInitState(),
            dst = builder.getConstructedState()
    )

    CallEdge(
            machine = builder,
            dst = builderHasURL,
            methodName = "url",
            param = listOf(Param(machine = url))
    )

    makeLinkedEdge(
            machine = builder,
            src = builderHasURL,
            dst = hasURL,
            methodName = "build"
    )

    makeLinkedEdge(
            machine = client,
            dst = call.getDefaultState(),
            methodName = "newCall",
            param = listOf(Param(machine = request, state = hasURL))
    )

    AutoEdge(
            machine = client,
            dst = client.getFinalState()
    )

    val execute = makeLinkedEdge(
            machine = call,
            dst = connection.getDefaultState(),
            methodName = "execute"
    )

    makeLinkedEdge(
            machine = connection,
            dst = entity.getDefaultState(),
            methodName = "body"
    )

    makeLinkedEdge(
            machine = entity,
            dst = body.getDefaultState(),
            methodName = "string"
    )

    makeLinkedEdge(
            machine = entity,
            dst = inputStream.getDefaultState(),
            methodName = "byteStream"
    )

    makeLinkedEdge(
            machine = entity,
            dst = contentLength.getDefaultState(),
            methodName = "contentLength"
    )

    makeLinkedEdge(
            machine = connection,
            dst = statusCode.getConstructedState(),
            methodName = "code"
    )

    return Library(
            name = "okhttp",
            stateMachines = listOf(url, request, client, connection, body,
                    inputStream, contentLength, entity, builder, call, statusCode),
            machineTypes = mapOf(
                    url to "String",
                    request to "okhttp3.Request",
                    client to "okhttp3.OkHttpClient",
                    connection to "okhttp3.Response",
                    body to "String",
                    inputStream to "java.io.InputStream",
                    contentLength to "long",
                    entity to "okhttp3.ResponseBody",
                    builder to "Request\$Builder",
                    call to "okhttp3.Call",
                    statusCode to "int"
            )
    )
}