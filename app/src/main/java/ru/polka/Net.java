package ru.polka;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Интернет на Android 4.2: включаем TLS 1.2 (по умолчанию выключен)
 * и добавляем к системным свежие корневые сертификаты из assets/certs.
 * Проверка сертификатов и имени сайта остаётся полной.
 */
final class Net {
    static final String UPDATE_URL = "https://github.com/dets1602-web/polka/releases/download/latest/Polka.apk";

    private static SSLSocketFactory factory;

    private Net() {}

    static synchronized SSLSocketFactory factory(Context c) throws Exception {
        if (factory != null) return factory;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int n = 0;
        TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sys.init((KeyStore) null);
        for (TrustManager tm : sys.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                for (X509Certificate x : ((X509TrustManager) tm).getAcceptedIssuers()) ks.setCertificateEntry("sys" + (n++), x);
            }
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String[] files = c.getAssets().list("certs");
        if (files != null) {
            for (String f : files) {
                InputStream in = c.getAssets().open("certs/" + f);
                for (Certificate cert : cf.generateCertificates(in)) ks.setCertificateEntry("app" + (n++), cert);
                in.close();
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        factory = new Tls12(ctx.getSocketFactory());
        return factory;
    }

    /** Открывает адрес, сам проходит переадресации (GitHub отдаёт файл с другого сервера). */
    static HttpURLConnection open(Context c, String url) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            if (con instanceof HttpsURLConnection) ((HttpsURLConnection) con).setSSLSocketFactory(factory(c));
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "Polka/1 (ONYX; Android 4.2)");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new Exception("переадресация без адреса");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new Exception("сервер ответил " + code);
            }
            return con;
        }
        throw new Exception("слишком много переадресаций");
    }

    static void download(Context c, String url, File to) throws Exception {
        HttpURLConnection con = open(c, url);
        File part = new File(to.getAbsolutePath() + ".part");
        InputStream in = con.getInputStream();
        OutputStream os = new FileOutputStream(part);
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
        os.close();
        in.close();
        con.disconnect();
        if (to.exists() && !to.delete()) throw new Exception("не удалось заменить " + to.getName());
        if (!part.renameTo(to)) throw new Exception("не удалось сохранить " + to.getName());
    }

    /** Обёртка: на каждом соединении включает TLS 1.2 и 1.1, если они есть. */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Tls12(SSLSocketFactory d) { this.d = d; }

        private Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> supported = Arrays.asList(ss.getSupportedProtocols());
                List<String> want = new ArrayList<>();
                for (String p : new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}) if (supported.contains(p)) want.add(p);
                if (!want.isEmpty()) ss.setEnabledProtocols(want.toArray(new String[0]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException { return fix(d.createSocket(s, host, port, autoClose)); }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException { return fix(d.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress lh, int lp) throws java.io.IOException { return fix(d.createSocket(host, port, lh, lp)); }
        @Override public Socket createSocket(InetAddress h, int port) throws java.io.IOException { return fix(d.createSocket(h, port)); }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws java.io.IOException { return fix(d.createSocket(a, port, la, lp)); }
    }
}
