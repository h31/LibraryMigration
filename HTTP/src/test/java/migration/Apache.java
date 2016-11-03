package migration;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Created by artyom on 03.11.16.
 */
public class Apache {
    @Test
    public void apache() throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpget = new HttpGet("http://kspt.icc.spbstu.ru/media/css/new/forms.css");

        CloseableHttpResponse httpResponse = httpclient.execute(httpget);
        String response = EntityUtils.toString(httpResponse.getEntity());
        String hash = DigestUtils.md5Hex(response);
        Assert.assertEquals(hash, Main.MD5_HASH);
        httpclient.close();
    }
}
