library HttpURLConnection {
  types {
    URLString (string);
    URL (java.net.URL);
    InputStream (java.io.InputStream);
    ContentLength (long);
    StatusCode (int);
    OutputStream (java.io.OutputStream);
    Payload (byte[]);
    StringBody (String); // TODO: Cited nowhere in the model, is it OK?
    RequestParamName (String);
    RequestParamValue (String);
    ResponseHeaderName (String);
    ResponseHeaderValue (String);
    RequestMethod (String);
    //Request (HttpURLConnection);
  }
  converters {
template "new BufferedReader(new InputStreamReader( {{ is }} )).lines().collect(Collectors.joining(\"\\n\", \"\", \"\\n\"))" -> String {
 with is -> InputStream;
 import java.io.BufferedReader;
 import java.io.InputStreamReader;
 import java.util.stream.Collectors;
}
template "( {{ name }} + \"=\" + URLEncoder.encode( {{ value }}, \"UTF-8\" )).getBytes()" -> Payload { // Вместо “->” можно поставить “:”
 with name: RequestParamName;
 with value: RequestParamValue;
}
method new URL(string: URLString); // TODO: Constructor? Just "new"? Возвращает URL
method Converters.str2int(str: String): int; // str, int - семантические типы. 
// method - синтаксически то же самое, что и fun, но без поддержки actions и т.д.
// Опять же, можно “->” вместо “:”


-- Старый вариант --


    URL <- new URL(<URLString>); // TODO: Is there a better way to specify constructors?
    Request.Created <- <URL>.openConnection();
    String <- "new BufferedReader(new InputStreamReader(<InputStream>))" +
                    ".lines().collect(Collectors.joining(\"\\n\", \"\", \"\\n\"))"; // TODO: Template keyword?
    // TODO: additionalTypes = listOf("java.io.BufferedReader", "java.io.InputStreamReader", "java.util.stream.Collectors");
    StringPayload <- "(<RequestParamName> + \"=\" + URLEncoder.encode( <RequestParamValue>, \"UTF-8\" )).getBytes()";
    // TODO: additionalTypes = listOf("java.net.URLEncoder")
    // TODO: Also, < and > may be used in the code (for example, ArrayList<String>). Maybe use {{ and }} ?
  }


  automaton Request { 
    state Created;
    state Connected;
    shift Created -> Connected (connect);
    shift any -> Connected (getStatusCode, getInputStream,getHeaderField, getContentLengthLong);
    shift Created -> self (setRequestProperty, setRequestMethod, getOutputStream);
  }


  automaton OutputStream { // TODO: Should be placed in Java stdlib description, it's not really an HTTP library element
    state Created;
    state Flushed;
    finishstate Closed;
    shift Created -> Flushed (flush);
    shift any -> Closed (close); // TODO: Is Closed -> Closed allowed?
    shift Create -> self (write);
    shift Flushed -> Create (write); // TODO: Uses the same fun twice, is it OK?
  }


//  fun Request.connect(); // HttpURLConnection д.б. семантическим типом


  fun HttpURLConnection.connect();
  fun HttpURLConnection.getStatusCode(): STATUS_CODE;
  fun HttpURLConnection.getInputStream(): InputStream;
  fun HttpURLConnection.setRequestProperty(name: RequestHeaderName, value: RequestHeaderValue) { // TODO: Will break current migration mechanism.
    action SET_HEADER(name, value);
  }
  fun HttpURLConnection.setDoOutput(flag: Boolean) {
    action USE_POST();
    when flag == true -> property "method" = "POST"; // TODO: Maybe use "modified" keyword here?
  }
  fun HttpURLConnection.setRequestMethod(method: RequestMethod) {
    property "method" = method; // TODO: Won't work if method is a variable (not a literal)
  }
  fun HttpURLConnection.getContentLengthLong(): ContentLength;
  fun HttpURLConnection.getOutputStream(): OutputStream {
    requires property "method" == "POST"; // TODO: Requires, right?
  }
  fun HttpURLConnection.getHeaderField(name: ResponseHeaderName): ResponseHeaderValue;
   
  fun OutputStream.flush();
  fun OutputStream.close();


  fun OutputStream.write(payload: Payload) {
    action SET_PAYLOAD(); // TODO: OutputStream is a common Java class, but SET_PAYLOAD is an HTTP-specific action
  }
}