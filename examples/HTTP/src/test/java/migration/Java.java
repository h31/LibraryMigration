package migration;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.stream.Collectors;

/**
 * Created by artyom on 03.11.16.
 */
public class Java {
    @Test
    public void java() throws IOException {
        URL url = new URL("http://kspt.icc.spbstu.ru/media/css/new/forms.css");
        URLConnection conn = url.openConnection();
        String response = new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n", "", "\n"));
        String hash = DigestUtils.md5Hex(response);
        Assert.assertEquals(hash, Main.MD5_HASH);
    }

    @Test
    public void javaOfficial() throws IOException {
        URL oracle = new URL("http://kspt.icc.spbstu.ru/media/css/new/forms.css");
        URLConnection yc = oracle.openConnection();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                yc.getInputStream()));
        String response = "";
        String inputLine;
        while ((inputLine = in.readLine()) != null)
            response += inputLine + "\n";
        in.close();
        String hash = DigestUtils.md5Hex(response);
        Assert.assertEquals(hash, Main.MD5_HASH);
    }

    @Test
    public void javaIf() throws IOException {
        URL url = new URL("http://kspt.icc.spbstu.ru/media/css/new/forms.css");
        URLConnection conn = url.openConnection();
        if (conn.getContentLengthLong() > 0) {
            String response = new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n", "", "\n"));
            String hash = DigestUtils.md5Hex(response);
            Assert.assertEquals(hash, Main.MD5_HASH);
        } else {
            Assert.fail("Error!");
        }
    }

    @Test
    public void javaHeader() throws IOException {
        URL url = new URL("http://kspt.icc.spbstu.ru/media/css/new/forms.css");
        URLConnection conn = url.openConnection();
        conn.setRequestProperty("X-Header", "Test");
        String response = new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n", "", "\n"));
        String hash = DigestUtils.md5Hex(response);
        Assert.assertEquals(hash, Main.MD5_HASH);
    }

    @Test
    public void javaPost() throws IOException {
        URL url = new URL("http://kspt.icc.spbstu.ru/media/css/new/forms.css");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        String data = "Hi!";
        OutputStream os = conn.getOutputStream();
        os.write(data.getBytes());
        os.flush();
        os.close();
        String response = new BufferedReader(new InputStreamReader(conn.getInputStream())).lines().collect(Collectors.joining("\n", "", "\n"));
        String hash = DigestUtils.md5Hex(response);
        Assert.assertEquals(hash, Main.MD5_HASH);
    }
}
