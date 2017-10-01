library OkHttp {
 types {
   URLString (string);
   Client (okhttp3.OkHttpClient);
   Request (okhttp3.Request);
   Builder (okhttp3.Request$Builder);
   Call (okhttp3.Call);
   Response (okhttp3.Response);
   InputStream (java.io.InputStream);
   ContentLength (long);
   Entity (okhttp3.ResponseBody);
   StringBody (String);
   StatusCode (int);
   RequestBody (okhttp3.RequestBody);
   FormBodyBuilder (okhttp3.FormBody$Builder);
   RequestParamName (String);
   RequestParamValue (String);
   RequestHeaderName (String);
   RequestHeaderValue (String);
   ResponseHeaderName (String);
   ResponseHeaderValue (String);
   ContentType (String);
   MediaType (okhttp3.MediaType);
   Payload (String);
 }
 converters {
   Client <- new Client();
   Builder <- new Builder(); // TODO: Where?
   FormBodyBuilder <- new FormBodyBuilder();
   ContentType <- "\"application/x-www-form-urlencoded\""; // TODO: Fallback option
   MediaType <- MediaType.parse(<ContentType>);
   RequestBody <- RequestBody.create(<MediaType>, <Payload>);
   RequestBody <- <FormBodyBuilder>.build();
   Request <- <Builder>.build();
   Call <- <Client>.newCall();
   Response <- <Call>.execute();
   Entity <- <Response>.body();
   ResponseHeaderValue <- <Response>.header(<ResponseHeaderName>);
   StringBody <- <Entity>.string();
   InputStream <- <Entity>.byteStream();
   ContentLength <- <Entity>.contentLength();
   StatusCode <- <Response>.code();
 }


 automaton Builder {
   state Created; // TODO: Implicit declaration?
   state HasURL;
   shift Created -> HasURL (url);
   shift HasURL -> self (post, header);
 }


 fun Builder.url(url: URLString);
 fun Builder.post(body: RequestBody) {
   action USE_POST();
   action SET_PAYLOAD();
   property "method" = "POST";
 }


 fun Builder.header(name: RequestHeaderName, value: RequestHeaderValue) {
   action SET_HEADER(name, value);
 }


 automaton FormBodyBuilder {
   state Created;
   state FormMade;
   shift Created -> FormMade (add);
 }


 fun FormBodyBuilder.add(name: RequestParamName, value: RequestParamValue);
}