package shop.billing;

import javax.net.ssl.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;

public class PaymentClient {

    private final HttpClient http;

    public PaymentClient() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        this.http = HttpClient.newBuilder().sslContext(ctx).build();
    }

    public int charge(String customerId, long amountCents) throws Exception {
        String body = "customer=" + java.net.URLEncoder.encode(customerId, "UTF-8") + "&amount=" + amountCents;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://payments.example.com/charge"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
