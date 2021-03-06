package migration;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Created by artyom on 03.11.16.
 */
public class OkHttp {
    OkHttpClient client = new OkHttpClient();

    @Test
    public void okhttp() throws IOException {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url("http://kspt.icc.spbstu.ru/media/css/new/forms.css")
                .build();

        Response response = client.newCall(request).execute();
        String hash = DigestUtils.md5Hex(response.body().string());
        Assert.assertEquals(hash, Main.MD5_HASH);
    }

    @Test
    public void okhttpPost() throws IOException {
        OkHttpClient client = new OkHttpClient();

        String data = "Hi!";

        RequestBody requestBody = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), data.getBytes());

        Request request = new Request.Builder()
                .url("http://kspt.icc.spbstu.ru/media/css/new/forms.css")
                .post(requestBody)
                .build();

        Response response = client.newCall(request).execute();
        String hash = DigestUtils.md5Hex(response.body().string());
        Assert.assertEquals(hash, Main.MD5_HASH);
    }
}
