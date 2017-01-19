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
    val request = StateMachine(name = "JavaRequest")

    val hasURL = State(name = "hasURL", machine = urlData)
    val encodedURL = State(name = "encodedURL", machine = url)
    val inputStream = StateMachine(name = "InputStream")
    val contentLength = StateMachine(name = "ContentLength")
    val statusCode = StateMachine(name = "StatusCode")
    val httpConnection = StateMachine(name = "HttpConnection")
    val outputStream = StateMachine(name = "OutputStream")
    val payload = StateMachine(name = "Payload")
    val requestParamName = StateMachine(name = "RequestParamName")
    val requestParamValue = StateMachine(name = "RequestParamValue")

    urlData.states += makeInitState(urlData)
    payload.states += makeInitState(payload)

    val connected = State(name = "Connected", machine = request)

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

    AutoEdge(
            machine = request,
            dst = connected
    )

    val readerToString = TemplateEdge(
            machine = request,
            src = connected,
            template = "new BufferedReader(new InputStreamReader({{ conn }}.getInputStream()))" +
                    ".lines().collect(Collectors.joining(\"\\n\", \"\", \"\\n\"))",
            templateParams = mapOf("conn" to connected),
            additionalTypes = listOf("java.io.BufferedReader", "java.io.InputStreamReader", "java.util.stream.Collectors")
    )

    LinkedEdge(
            machine = request,
            src = connected,
            dst = body.getDefaultState(),
            edge = readerToString
    )

    LinkedEdge(
            machine = request,
            src = connected,
            dst = inputStream.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    src = connected,
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
            param = listOf(ActionParam("headerName"), ActionParam("headerValue")),
            hasReturnValue = false
    )

    CallEdge(
            machine = request,
            methodName = "setDoOutput",
            allowTransition = { map -> map.put("method", "POST"); true },
            hasReturnValue = false,
            param = listOf(ConstParam("true"))
    )

    LinkedEdge(
            machine = request,
            src = connected,
            dst = contentLength.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    src = connected,
                    methodName = "getContentLengthLong"
            )
    )

    LinkedEdge(
            machine = request,
            src = connected,
            dst = outputStream.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    src = connected,
                    methodName = "getOutputStream",
                    allowTransition = { map -> map["method"] == "POST"}
            )
    )

    CallEdge(
            machine = outputStream,
            methodName = "close"
    ) //             allowTransition = { map -> map.put("Closed", true); true }

    CallEdge(
            machine = outputStream,
            methodName = "flush"
    ) //             allowTransition = { map -> map.put("Flushed", true); true }


    TemplateEdge(
            machine = payload,
            src = payload.getInitState(),
            dst = payload.getConstructedState(),
            template = "({{ name }} + \"=\" + {{ value }}).getBytes()",
            templateParams = mapOf("name" to requestParamName.getDefaultState(), "value" to requestParamValue.getDefaultState())
    )

    CallEdge(
            machine = outputStream,
            methodName = "write",
            param = listOf(EntityParam(payload)),
            action = Actions.setPayload,
            hasReturnValue = false
    ) //             allowTransition = { map -> val written = map.contains("Written"); map.put("Written", true); !written },

    LinkedEdge(
            machine = request,
            src = connected,
            dst = httpConnection.getConstructedState(),
            edge = TemplateEdge(
                    machine = request,
                    src = connected,
                    template = "((HttpURLConnection) {{ response }})",
                    templateParams = mapOf("response" to connected)
            )
    )

    AutoEdge(
            machine = httpConnection,
            dst = connected
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
            stateMachines = listOf(url, urlData, request, body, inputStream, contentLength, statusCode, httpConnection, outputStream, payload, requestParamName, requestParamValue),
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
                    payload to "byte[]",
                    requestParamName to "String",
                    requestParamValue to "String"
            )
    )
}

fun makeApache(): Library {
    val url = StateMachine(name = "URL")
    val client = StateMachine(name = "Client")
    val request = StateMachine(name = "GetRequest")
    val postRequest = StateMachine(name = "PostRequest")
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
    postRequest.states += makeInitState(postRequest)
    byteArrayEntity.states += makeInitState(byteArrayEntity)
    entityUtils.states += makeInitState(entityUtils)
    httpClientFactory.states += makeInitState(httpClientFactory)
    request.migrateProperties = { oldProps -> oldProps[StateMachine(name = "JavaRequest")] ?: oldProps[StateMachine(name = "Builder")] ?: mapOf() }
    postRequest.migrateProperties = { oldProps -> oldProps[StateMachine(name = "JavaRequest")] ?: oldProps[StateMachine(name = "Builder")] ?: mapOf() }

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

    ConstructorEdge(
            machine = postRequest,
            src = postRequest.getInitState(),
            dst = postRequest.getConstructedState(),
            param = listOf(EntityParam(
                    machine = url,
                    state = encodedURL
            )
            ),
            allowTransition = {props -> props["method"] == "POST"}
    )

    CallEdge(
            machine = request,
            methodName = "addHeader",
            action = Actions.setHeader,
            param = listOf(ActionParam("headerName"), ActionParam("headerValue")),
            hasReturnValue = false
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

    makeLinkedEdge(
            machine = client,
            dst = response.getDefaultState(),
            methodName = "execute",
            param = listOf(EntityParam(
                    machine = request
            )
            )
    )

    makeLinkedEdge(
            machine = client,
            dst = response.getDefaultState(),
            methodName = "execute",
            param = listOf(EntityParam(
                    machine = postRequest
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
            machine = postRequest,
            methodName = "setEntity",
            param = listOf(EntityParam(byteArrayEntity)),
            action = Actions.setPayload,
            hasReturnValue = false
    )

    makeLinkedEdge(
            machine = response,
            dst = entity.getDefaultState(),
            methodName = "getEntity"
    )

    makeLinkedEdge(
            machine = entityUtils,
            src = entityUtils.getInitState(),
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
                    inputStream, contentLength, entity, entityUtils, statusCode, byteArrayEntity, payload, postRequest),
            machineTypes = mapOf(
                    url to "String",
                    request to "org.apache.http.client.methods.HttpGet",
                    postRequest to "org.apache.http.client.methods.HttpPost",
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
    val formBodyBuilder = StateMachine(name = "FormBodyBuilder")
    val requestParamName = StateMachine(name = "RequestParamName")
    val requestParamValue = StateMachine(name = "RequestParamValue")
    val contentType = StateMachine(name = "ContentType")
    val mediaType = StateMachine(name = "MediaType")
    val payload = StateMachine(name = "Payload")

    client.states += makeInitState(client)
    builder.states += makeInitState(builder)
    requestBody.states += makeInitState(requestBody)
    contentType.states += makeInitState(contentType)
    mediaType.states += makeInitState(mediaType)
    client.states += makeFinalState(client)
    formBodyBuilder.states += makeInitState(formBodyBuilder)

    val encodedURL = State(name = "encodedURL", machine = url)
    val builderHasURL = State(name = "hasURL", machine = builder)
    val formMade = State(name = "FormMade", machine = formBodyBuilder)

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
            machine = mediaType,
            src = mediaType.getInitState(),
            dst = mediaType.getConstructedState(),
            methodName = "parse",
            isStatic = true,
            param = listOf(EntityParam(machine = contentType))
    )

    CallEdge(
            machine = requestBody,
            src = requestBody.getInitState(),
            dst = requestBody.getConstructedState(),
            methodName = "create",
            isStatic = true,
            param = listOf(EntityParam(mediaType), EntityParam(payload))
    )

    ConstructorEdge(
            machine = formBodyBuilder,
            src = formBodyBuilder.getInitState(),
            dst = formBodyBuilder.getConstructedState()
    )

    CallEdge(
            machine = formBodyBuilder,
            dst = formMade,
            methodName = "add",
            param = listOf(EntityParam(requestParamName), EntityParam(requestParamValue))
    )

    makeLinkedEdge(
            machine = formBodyBuilder,
            src = formMade,
            dst = requestBody.getConstructedState(),
            methodName = "build"
    )

    CallEdge(
            machine = builder,
            src = builderHasURL,
            methodName = "post",
            param = listOf(EntityParam(machine = requestBody)),
            action = Actions.setPayload,
            allowTransition = { map -> map.put("method", "POST"); true },
            hasReturnValue = true
    )

    CallEdge(
            machine = builder,
            src = builderHasURL,
            methodName = "header",
            action = Actions.setHeader,
            callActionParams = {node ->
                val name = node.args[0].toString()
                val value = node.args[1].toString()
                mapOf("headerName" to name, "headerValue" to value)
            },
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
                    inputStream, contentLength, entity, builder, call, statusCode, requestBody, contentType, payload,
                    mediaType, formBodyBuilder, requestParamName, requestParamValue),
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
                    formBodyBuilder to "okhttp3.FormBody\$Builder",
                    requestParamName to "String",
                    requestParamValue to "String",
                    contentType to "String",
                    mediaType to "okhttp3.MediaType",
                    payload to "String",
                    builder to "okhttp3.Request\$Builder",
                    call to "okhttp3.Call",
                    statusCode to "int"
            )
    )
}