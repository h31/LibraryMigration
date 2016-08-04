import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by artyom on 08.06.16.
 */
public class Main {

    public static void main(String[] args) throws IOException {
        java();
        System.out.println("----------------------------------------");
        apache();
    }

    private static String readerToString(URLConnection conn) throws IOException {
        return new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n"));
    }

    private static String responseToString(HttpResponse httpResponse) throws IOException {
        return EntityUtils.toString(httpResponse.getEntity());
    }

    private static void java() throws IOException {
        URL url = new URL("http://api.ipify.org/");
        URLConnection conn = url.openConnection();
        String response = readerToString(conn);
        System.out.println(response);
    }

    private static void apache() throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpget = new HttpGet("http://api.ipify.org/");

        CloseableHttpResponse httpResponse = httpclient.execute(httpget);
        String response = responseToString(httpResponse);
        System.out.println(response);
        httpclient.close();
    }
}