import com.github.javaparser.ast.expr.MethodCallExpr

/**
 * Created by artyom on 05.07.16.
 */

object HttpModels {
    val java: Library = makeJava()
    val apache: Library = makeApache()
    val okhttp: Library = makeOkHttp()

    fun withName() = listOf(java, apache, okhttp).associateBy(Library::name)
}

object Actions {
    val setHeader = Action("setHeader")
    val setPayload = Action("setPayload")
}

fun makeJava(): Library {
    val url = StateMachine(name = "URL")

    val urlData = StateMachine(name = "URLData")
    val request = StateMachine(name = "Request")

    val hasURL = State(name = "hasURL", machine = urlData)
    val encodedURL = State(name = "encodedURL", machine = url)
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")
    val statusCode = StateMachine(name = "StatusCode")
    val httpConnection = StateMachine(name = "HttpConnection")
    val outputStream = StateMachine(name = "OutputStream")
    val payload = StateMachine(name = "Payload")

    urlData.states += makeInitState(urlData)

    AutoEdge(
            machine = url,
            src = url.getConstructedState(),
            dst = encodedURL
    )

    ConstructorEdge(
            machine = urlData,
            src = urlData.getInitState(),
            dst = hasURL,
            param = listOf(EntityParam(
                    machine = url,
                    state = encodedURL
            )
            )
    )

    makeLinkedEdge(
            machine = urlData,
            src = hasURL,
            dst = request.getDefaultState(),
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
            machine = request,
            template = "new BufferedReader(new InputStreamReader({{ conn }}.getInputStream()))" +
                    ".lines().collect(Collectors.joining(\"\\n\", \"\", \"\\n\"))",
            templateParams = mapOf("conn" to request.getDefaultState()),
            additionalTypes = listOf("java.io.BufferedReader", "java.io.InputStreamReader", "java.util.stream.Collectors")
    )

    LinkedEdge(
            machine = request,
            dst = body.getDefaultState(),
            edge = readerToString
    )

    LinkedEdge(
            machine = request,
            dst = inputStream.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    methodName = "getInputStream"
            )
    )

    CallEdge(
            machine = request,
            methodName = "setRequestProperty",
            action = Actions.setHeader,
            callActionParams = {node ->
                val name = node.args[0].toString()
                val value = node.args[1].toString()
                mapOf("headerName" to name, "headerValue" to value)
            },
            param = listOf(ActionParam("headerName"), ActionParam("headerValue"))
    )

    CallEdge(
            machine = request,
            methodName = "setDoOutput",
            allowTransition = { map -> map.put("method", "POST"); true }
    )

    LinkedEdge(
            machine = request,
            dst = contentLength.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    methodName = "getContentLengthLong"
            )
    )

    LinkedEdge(
            machine = request,
            dst = outputStream.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    methodName = "getOutputStream",
                    action = Actions.setPayload
            )
    )

    CallEdge(
            machine = outputStream,
            methodName = "close",
            allowTransition = { map -> map.put("Closed", true); true }
    )

    CallEdge(
            machine = outputStream,
            methodName = "flush",
            allowTransition = { map -> map.put("Flushed", true); true }
    )

    CallEdge(
            machine = outputStream,
            methodName = "write",
            param = listOf(EntityParam(payload)),
            allowTransition = { map -> map.put("Written", true); true }
    )

    LinkedEdge(
            machine = request,
            dst = httpConnection.getConstructedState(),
            edge = TemplateEdge(
                    machine = request,
                    template = "((HttpURLConnection) {{ response }})",
                    templateParams = mapOf("response" to request.getConstructedState())
            )
    )

    AutoEdge(
            machine = httpConnection,
            dst = request.getConstructedState()
    )

    LinkedEdge(
            machine = httpConnection,
            dst = statusCode.getConstructedState(),
            edge = CallEdge(
                    machine = httpConnection,
                    methodName = "getResponseCode"
            )
    )

    CallEdge(
            machine = httpConnection,
            methodName = "setRequestProperty"
    )

    CallEdge(
            machine = httpConnection,
            methodName = "setDoOutput"
    )

    return Library(
            name = "java",
            stateMachines = listOf(url, urlData, request, body, inputStream, contentLength, statusCode, httpConnection, outputStream, payload),
            machineTypes = mapOf(
                    urlData to "java.net.URL",
                    url to "String",
                    request to "java.net.URLConnection",
                    inputStream to "java.io.InputStream",
                    contentLength to "long",
                    body to "String",
                    statusCode to "int",
                    httpConnection to "java.net.HttpURLConnection",
                    outputStream to "java.io.OutputStream",
                    payload to "String"
            )
    )
}

fun makeApache(): Library {
    val url = StateMachine(name = "URL")
    val client = StateMachine(name = "Client")
    val request = StateMachine(name = "Request")
    val response = StateMachine(name = "Response")
    val httpClientFactory = StateMachine(name = "HttpClientFactory")
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")
    val entity = StateMachine(name = "Entity")
    val entityUtils = StateMachine(name = "EntityUtils")
    val body = StateMachine(name = "Body")
    val statusCode = StateMachine(name = "StatusCode")

//    val hasURL = State(name = "hasURL", machine = request)
    val encodedURL = State(name = "encodedURL", machine = url)
    val payload = StateMachine(name = "Payload")
    val byteArrayEntity = StateMachine(name = "ByteArrayEntity")

//    client.edges.clear()
    client.states += makeFinalState(client)
    request.states += makeInitState(request)
    byteArrayEntity.states += makeInitState(byteArrayEntity)
    httpClientFactory.states += makeInitState(httpClientFactory)
    request.migrateProperties = { oldProps -> oldProps[request] ?: mapOf() }

    makeLinkedEdge(
            machine = httpClientFactory,
            src = httpClientFactory.getInitState(),
            dst = client.getConstructedState(),
            methodName = "createDefault",
            isStatic = true
    )

    TemplateEdge(
            machine = url,
            src = url.getConstructedState(),
            dst = encodedURL,
            template = "{{ url }}.replace(\"{\", \"%7B\").replace(\"}\", \"%7D\")",
            templateParams = mapOf("url" to url.getConstructedState()),
            additionalTypes = listOf("org.apache.http.client.methods.HttpPost") // TODO!!!
    )

    ConstructorEdge(
            machine = request,
            src = request.getInitState(),
            dst = request.getConstructedState(),
            param = listOf(EntityParam(
                    machine = url,
                    state = encodedURL
            )
            )
    )

    CallEdge(
            machine = request,
            methodName = "addHeader",
            action = Actions.setHeader,
            param = listOf(ActionParam("headerName"), ActionParam("headerValue"))
    )

//    val setURI = CallEdge(
//            machine = request,
//            src = request.getConstructedState(),
//            dst = hasURL,
//            methodName = "setURI",
//            param = listOf(EntityParam(
//                    machine = url
//            )
//            )
//    )

    val execute = makeLinkedEdge(
            machine = client,
            dst = response.getDefaultState(),
            methodName = "execute",
            param = listOf(EntityParam(
                    machine = request
            )
            )
    )

    CallEdge(
            machine = client,
            src = client.getDefaultState(),
            dst = client.getFinalState(),
            methodName = "close"
    )

    ConstructorEdge(
            machine = byteArrayEntity,
            src = byteArrayEntity.getInitState(),
            dst = byteArrayEntity.getConstructedState(),
            param = listOf(EntityParam(payload))
    )

    CallEdge(
            machine = request,
            methodName = "setEntity",
            param = listOf(EntityParam(byteArrayEntity)),
            action = Actions.setPayload
    )

    makeLinkedEdge(
            machine = response,
            dst = entity.getDefaultState(),
            methodName = "getEntity"
    )

    makeLinkedEdge(
            machine = entityUtils,
            dst = body.getDefaultState(),
            methodName = "toString",
            param = listOf(EntityParam(machine = entity)),
            isStatic = true
    )

//    val toStringTemplate = TemplateEdge(
//            machine = main,
//            template = "EntityUtils.toString({{ httpResponse }}.getEntity())",
//            templateParams = mapOf("httpResponse" to connection.getConstructedState()),
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
//                    templateParams = mapOf("httpResponse" to connection.getConstructedState())
//            )
//    )

    makeLinkedEdge(
            machine = entity,
            dst = contentLength.getDefaultState(),
            methodName = "getContentLength"
    )

    LinkedEdge(
            machine = response,
            dst = statusCode.getConstructedState(),
            edge = TemplateEdge(
                    machine = response,
                    template = "{{ conn }}.getStatusLine().getStatusCode()",
                    templateParams = mapOf("conn" to response.getDefaultState())
            )
    )

//    LinkedEdge(
//            machine = connection,
//            dst = contentLength.getDefaultState(),
//            edge = TemplateEdge(
//                    machine = connection,
//                    template = "{{ httpResponse }}.getEntity().getContentLength()",
//                    templateParams = mapOf("httpResponse" to connection.getConstructedState())
//            )
//    )

    return Library(
            name = "apache",
            stateMachines = listOf(url, request, client, response, body, httpClientFactory,
                    inputStream, contentLength, entity, entityUtils, statusCode, byteArrayEntity, payload),
            machineTypes = mapOf(
                    url to "String",
                    request to "org.apache.http.client.methods.HttpGet",
                    client to "org.apache.http.impl.client.CloseableHttpClient",
                    response to "org.apache.http.client.methods.CloseableHttpResponse",
                    body to "String",
                    httpClientFactory to "org.apache.http.impl.client.HttpClients",
                    inputStream to "java.io.InputStream",
                    contentLength to "long",
                    entity to "org.apache.http.HttpEntity",
                    entityUtils to "org.apache.http.util.EntityUtils",
                    statusCode to "int",
                    byteArrayEntity to "org.apache.http.entity.ByteArrayEntity",
                    payload to "String"
            ),
            typeGenerator = { machine, props ->
                when {
                    machine.name == "Request" && props["method"] == "POST" -> "HttpPost"
                    machine.name == "Request" && props["method"] == "GET" -> "HttpGet"
                    else -> null
                }
            }
    )
}

fun makeOkHttp(): Library {
    val url = StateMachine(name = "URL")
    val client = StateMachine(name = "Client")
    val request = StateMachine(name = "Request")
    val builder = StateMachine(name = "Builder")
    val call = StateMachine(name = "Call")
    val response = StateMachine(name = "Response")
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")
    val entity = StateMachine(name = "Entity")
    val body = StateMachine(name = "Body")
    val statusCode = StateMachine(name = "StatusCode")
    val static = StateMachine(name = "Static")
    val requestBody = StateMachine(name = "RequestBody")
    val contentType = StateMachine(name = "ContentType")
    val payload = StateMachine(name = "Payload")

    client.states += makeInitState(client)
    builder.states += makeInitState(builder)
    requestBody.states += makeInitState(requestBody)
    contentType.states += makeInitState(contentType)
    client.states += makeFinalState(client)

    val encodedURL = State(name = "encodedURL", machine = url)
    val builderHasURL = State(name = "hasURL", machine = builder)

    AutoEdge(
            machine = url,
            dst = encodedURL
    )

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
            param = listOf(EntityParam(machine = url, state = encodedURL))
    )

    TemplateEdge(
            machine = contentType,
            src = contentType.getInitState(),
            dst = contentType.getConstructedState(),
            template = "\"application/x-www-form-urlencoded\"",
            templateParams = mapOf()
    )

    CallEdge(
            machine = requestBody,
            src = requestBody.getInitState(),
            dst = requestBody.getConstructedState(),
            methodName = "create",
            isStatic = true,
            param = listOf(EntityParam(contentType), EntityParam(payload))
    )

    CallEdge(
            machine = builder,
            src = builderHasURL,
            methodName = "post",
            param = listOf(EntityParam(machine = requestBody)),
            action = Actions.setPayload
    )

    CallEdge(
            machine = builder,
            src = builderHasURL,
            methodName = "header",
            action = Actions.setHeader,
            param = listOf(ActionParam("headerName"), ActionParam("headerValue"))
    )

    makeLinkedEdge(
            machine = builder,
            src = builderHasURL,
            dst = request.getConstructedState(),
            methodName = "build"
    )

    makeLinkedEdge(
            machine = client,
            dst = call.getDefaultState(),
            methodName = "newCall",
            param = listOf(EntityParam(machine = request))
    )

    AutoEdge(
            machine = client,
            dst = client.getFinalState()
    )

    val execute = makeLinkedEdge(
            machine = call,
            dst = response.getDefaultState(),
            methodName = "execute"
    )

    makeLinkedEdge(
            machine = response,
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
            machine = response,
            dst = statusCode.getConstructedState(),
            methodName = "code"
    )

    return Library(
            name = "okhttp",
            stateMachines = listOf(url, request, client, response, body,
                    inputStream, contentLength, entity, builder, call, statusCode, requestBody, contentType, payload),
            machineTypes = mapOf(
                    url to "String",
                    request to "okhttp3.Request",
                    client to "okhttp3.OkHttpClient",
                    response to "okhttp3.Response",
                    body to "String",
                    inputStream to "java.io.InputStream",
                    contentLength to "long",
                    entity to "okhttp3.ResponseBody",
                    requestBody to "okhttp3.RequestBody",
                    contentType to "String",
                    payload to "String",
                    builder to "okhttp3.Request\$Builder",
                    call to "okhttp3.Call",
                    statusCode to "int"
            )
    )
}