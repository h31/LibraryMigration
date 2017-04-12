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
    val usePost = Action("usePost")
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

    LinkedEdge(
            dst = request.getDefaultState(),
            edge = CallEdge(
                    machine = urlData,
                    src = hasURL,
                    methodName = "openConnection"
            )
    )

    val body = StateMachine(name = "Body")

    AutoEdge(
            machine = request,
            dst = connected
    )

    CallEdge(
            machine = request,
            dst = connected,
            methodName = "connect"
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
            actions = listOf(Actions.setHeader),
            param = listOf(ActionParam("headerName"), ActionParam("headerValue")),
            hasReturnValue = false
    )

    CallEdge(
            machine = request,
            methodName = "setDoOutput",
            actions = listOf(Actions.usePost),
            propertyModifier = { map -> map + Pair("method", "POST") },
            hasReturnValue = false,
            param = listOf(ConstParam("true"))
    )

    CallEdge(
            machine = request,
            methodName = "setRequestMethod"
    )

    LinkedEdge(
            dst = contentLength.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    src = connected,
                    methodName = "getContentLengthLong"
            )
    )

    LinkedEdge(
            dst = outputStream.getDefaultState(),
            edge = CallEdge(
                    machine = request,
                    src = connected,
                    methodName = "getOutputStream",
                    allowTransition = { map -> map["method"] == "POST" }
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
            actions = listOf(Actions.setPayload),
            hasReturnValue = false
    ) //             allowTransition = { map -> val written = map.contains("Written"); map.put("Written", true); !written },

    CastEdge(
            machine = request,
            src = connected,
            dst = httpConnection.getConstructedState()
    )

    AutoEdge(
            machine = httpConnection,
            dst = connected
    )

    LinkedEdge(
            dst = statusCode.getConstructedState(),
            edge = CallEdge(
                    machine = httpConnection,
                    methodName = "getResponseCode"
            )
    )

    CallEdge(
            machine = httpConnection,
            methodName = "setRequestProperty",
            actions = listOf(Actions.setHeader),
            param = listOf(ActionParam("headerName"), ActionParam("headerValue")),
            hasReturnValue = false
    )

    CallEdge(
            machine = httpConnection,
            methodName = "setRequestMethod"
    )

    CallEdge(
            machine = httpConnection,
            methodName = "setDoOutput",
            actions = listOf(Actions.usePost),
            propertyModifier = { map -> map + Pair("method", "POST") },
            hasReturnValue = false,
            param = listOf(ConstParam("true"))
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
    val contentType = StateMachine(name = "ContentTypeApache")
    val statusCode = StateMachine(name = "StatusCode")

//    val hasURL = State(name = "hasURL", machine = request)
    val encodedURL = State(name = "encodedURL", machine = url)
    val payload = StateMachine(name = "Payload")
    val byteArrayEntity = StateMachine(name = "ByteArrayEntity")

    val requestParamName = StateMachine(name = "RequestParamName")
    val requestParamValue = StateMachine(name = "RequestParamValue")

    LinkedEdge(
            dst = client.getConstructedState(),
            edge = CallEdge(
                    machine = httpClientFactory,
                    src = httpClientFactory.getInitState(),
                    methodName = "createDefault",
                    isStatic = true
            )
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
            actions = listOf(Actions.usePost),
            propertyModifier = { props -> props + Pair("method", "POST")}
    )

    CallEdge(
            machine = request,
            methodName = "addHeader",
            actions = listOf(Actions.setHeader),
            param = listOf(ActionParam("headerName"), ActionParam("headerValue")),
            hasReturnValue = false
    )

    CallEdge(
            machine = postRequest,
            methodName = "addHeader",
            actions = listOf(Actions.setHeader),
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

    LinkedEdge(
            dst = response.getDefaultState(),
            edge = CallEdge(
                    machine = client,
                    methodName = "execute",
                    param = listOf(EntityParam(
                            machine = request
                    )
                    )
            )
    )

    LinkedEdge(
            dst = response.getDefaultState(),
            edge = CallEdge(
                    machine = client,
                    methodName = "execute",
                    param = listOf(EntityParam(
                            machine = postRequest
                    )
                    )
            )
    )

    CallEdge(
            machine = client,
            src = client.getDefaultState(),
            dst = client.getFinalState(),
            methodName = "close"
    )

    TemplateEdge(
            machine = payload,
            src = payload.getInitState(),
            dst = payload.getConstructedState(),
            template = "({{ name }} + \"=\" + {{ value }}).getBytes()",
            templateParams = mapOf("name" to requestParamName.getDefaultState(), "value" to requestParamValue.getDefaultState())
    )

    TemplateEdge(
            machine = contentType,
            src = contentType.getInitState(),
            dst = contentType.getConstructedState(),
            template = "ContentType.create(\"application/x-www-form-urlencoded\")", // TODO
            templateParams = mapOf()
    )

    ConstructorEdge(
            machine = byteArrayEntity,
            src = byteArrayEntity.getInitState(),
            dst = byteArrayEntity.getConstructedState(),
            param = listOf(EntityParam(payload), EntityParam(contentType))
    )

    CallEdge(
            machine = postRequest,
            methodName = "setEntity",
            param = listOf(EntityParam(byteArrayEntity)),
            actions = listOf(Actions.setPayload),
            hasReturnValue = false
    )

    LinkedEdge(
            dst = entity.getDefaultState(),
            edge = CallEdge(
                    machine = response,
                    methodName = "getEntity"
            )
    )

    LinkedEdge(
            dst = body.getDefaultState(),
            edge = CallEdge(
                    machine = entityUtils,
                    src = entityUtils.getInitState(),
                    methodName = "toString",
                    param = listOf(EntityParam(machine = entity)),
                    isStatic = true
            )
    )

    LinkedEdge(
            dst = inputStream.getDefaultState(),
            edge = CallEdge(
                    machine = entity,
                    methodName = "getContent"
            )
    )

    LinkedEdge(
            dst = contentLength.getDefaultState(),
            edge = CallEdge(
                    machine = entity,
                    methodName = "getContentLength"
            )
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

    return Library(
            name = "apache",
            stateMachines = listOf(url, request, client, response, body, httpClientFactory,
                    inputStream, contentLength, entity, entityUtils, statusCode, byteArrayEntity, payload, postRequest,
                    requestParamName, requestParamValue, contentType),
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
                    payload to "String",
                    requestParamName to "String",
                    requestParamValue to "String",
                    contentType to "org.apache.http.entity.ContentType"
            )
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
            param = listOf(EntityParam(machine = url, state = encodedURL)),
            hasReturnValue = true
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
            param = listOf(EntityParam(machine = contentType)),
            hasReturnValue = true
    )

    CallEdge(
            machine = requestBody,
            src = requestBody.getInitState(),
            dst = requestBody.getConstructedState(),
            methodName = "create",
            isStatic = true,
            param = listOf(EntityParam(mediaType), EntityParam(payload)),
            hasReturnValue = true
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

    LinkedEdge(
            dst = requestBody.getConstructedState(),
            edge = CallEdge(
                    machine = formBodyBuilder,
                    src = formMade,
                    methodName = "build"
            )
    )

    CallEdge(
            machine = builder,
            src = builderHasURL,
            methodName = "post",
            param = listOf(EntityParam(machine = requestBody)),
            actions = listOf(Actions.usePost, Actions.setPayload),
            hasReturnValue = true,
            propertyModifier = { map -> map + Pair("method", "POST") }
    )

    CallEdge(
            machine = builder,
            src = builderHasURL,
            methodName = "header",
            actions = listOf(Actions.setHeader),
            param = listOf(ActionParam("headerName"), ActionParam("headerValue"))
    )

    LinkedEdge(
            dst = request.getConstructedState(),
            edge = CallEdge(
                    machine = builder,
                    src = builderHasURL,
                    methodName = "build"
            )
    )

    LinkedEdge(
            dst = call.getDefaultState(),
            edge = CallEdge(
                    machine = client,
                    methodName = "newCall",
                    param = listOf(EntityParam(machine = request))
            )
    )

    AutoEdge(
            machine = client,
            dst = client.getFinalState()
    )

    LinkedEdge(
            dst = response.getDefaultState(),
            edge = CallEdge(
                    machine = call,
                    methodName = "execute"
            )
    )

    LinkedEdge(
            dst = entity.getDefaultState(),
            edge = CallEdge(
                    machine = response,
                    methodName = "body"
            )
    )

    LinkedEdge(
            dst = body.getDefaultState(),
            edge = CallEdge(
                    machine = entity,
                    methodName = "string"
            )
    )

    LinkedEdge(
            dst = inputStream.getDefaultState(),
            edge = CallEdge(
                    machine = entity,
                    methodName = "byteStream"
            )
    )

    LinkedEdge(
            dst = contentLength.getDefaultState(),
            edge = CallEdge(
                    machine = entity,
                    methodName = "contentLength"
            )
    )

    LinkedEdge(
            dst = statusCode.getConstructedState(),
            edge = CallEdge(
                    machine = response,
                    methodName = "code"
            )
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