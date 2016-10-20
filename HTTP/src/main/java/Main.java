import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
        javaOfficial();
        System.out.println("----------------------------------------");
        javaIf();
        System.out.println("----------------------------------------");
        apache();
        System.out.println("----------------------------------------");
        okhttp();
    }

    private static void java() throws IOException {
        URL url = new URL("http://api.ipify.org/");
        URLConnection conn = url.openConnection();
        String response = new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n"));
        System.out.println(response);
    }

    private static void javaOfficial() throws IOException {
        URL oracle = new URL("http://api.ipify.org/");
        URLConnection yc = oracle.openConnection();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                yc.getInputStream()));
        String inputLine;
        while ((inputLine = in.readLine()) != null)
            System.out.println(inputLine);
        in.close();
    }

    private static void javaIf() throws IOException {
        URL url = new URL("http://api.ipify.org/");
        URLConnection conn = url.openConnection();
        if (conn.getContentLengthLong() > 0) {
            String response = new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n"));
            System.out.println(response);
        } else {
            System.out.println("Error!");
        }
    }

    private static void apache() throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpget = new HttpGet("http://api.ipify.org/");

        CloseableHttpResponse httpResponse = httpclient.execute(httpget);
        String response = EntityUtils.toString(httpResponse.getEntity());
        System.out.println(response);
        httpclient.close();
    }

    private static void okhttp() throws IOException {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url("http://api.ipify.org/")
                .build();

        Response response = client.newCall(request).execute();
        System.out.println(response.body().string());
    }
}