package com.xiaofan0408.wxcheck.component;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xiaofan0408.wxcheck.component.model.CheckResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;


import javax.annotation.PostConstruct;
import javax.net.ssl.*;
import java.net.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class WxCheckComponent {

    private static final String CHECK_URL = "http://mp.weixinbridge.com/mp/wapredirect?url=%s&action=appmsg_redirect&uin=&biz=MzUxMTMxODc2MQ==&mid=100000007&idx=1&type=1&scene=0";

    private Pattern pattern = Pattern.compile("var cgiData =(.*);");

    private OkHttpClient okHttpClient;

    @PostConstruct
    public void init() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(1024);
        dispatcher.setMaxRequestsPerHost(1024);
        okHttpClient = new OkHttpClient().newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2,TimeUnit.SECONDS)
                .sslSocketFactory(createSSLSocketFactory(),new TrustAllManager())
                .hostnameVerifier(hostnameVerifier())
                .build();
    }

    private HostnameVerifier hostnameVerifier(){
        return new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                return true;
            }
        };
    }

    private SSLSocketFactory createSSLSocketFactory () {

        SSLSocketFactory sSLSocketFactory = null;

        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            sSLSocketFactory = sc.getSocketFactory();
        } catch (Exception e) {
            log.info(e.getMessage(), e);
        }

        return sSLSocketFactory;
    }

    private  class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] x509Certificates,
                                       String s) throws java.security.cert.CertificateException {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] x509Certificates,
                                       String s) throws java.security.cert.CertificateException {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public CheckResult checkUrl(String url) throws Exception {
        CheckResult checkResult = new CheckResult();
        try {
            String checkUrl = String.format(CHECK_URL,url);
            Connection connection = Jsoup.connect(checkUrl);
            connection.followRedirects(true);
            connection.timeout(5000);
            Connection.Response response = connection.execute();
            String sourceUrl = getDomain(url);
            String resultUrl = response.url().getHost();
            if (resultUrl.contains("weixin110.qq.com")) {
                String body = response.body();
                String json = getErrorJson(body);
                JSONObject jsonObject = JSON.parseObject(json);
                JSONObject detail = new JSONObject();
                detail.put("type",jsonObject.get("type"));
                detail.put("title",jsonObject.get("title"));
                detail.put("desc", jsonObject.get("desc"));
                checkResult.setResult(2);
                checkResult.setDetail(detail);
            } else {
                checkResult.setResult(1);
            }
            return checkResult;
        }catch (Exception e){
            if ((e instanceof SocketTimeoutException) || (e instanceof UnknownHostException) || (e instanceof SocketException)) {
                checkResult.setResult(1);
                return checkResult;
            }else {
                throw new Exception(e);
            }
        }
    }

    private String getDomain(String url) throws MalformedURLException {
        if (url != null) {
            URL u = new URL(url);
            return u.getHost();
        }
        return "";
    }

    private String getErrorJson(String body){
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()){
            return matcher.group(1);
        }
        return null;
    }

    public Mono<CheckResult> checkUrlOkHttp(String url,Boolean isShowDetail) {

        return Mono.create(new Consumer<MonoSink<CheckResult>>() {
            @Override
            public void accept(MonoSink<CheckResult> sink) {
                CheckResult checkResult = new CheckResult();
                try {
                    String checkUrl = String.format(CHECK_URL,url);
                    Request request = new Request.Builder()
                            .url(checkUrl)
                            .get()
                            .build();
                    Response response = okHttpClient.newCall(request).execute();
                    String resultUrl = response.request().url().host();
                    if (resultUrl.contains("weixin110.qq.com")) {
                        if (isShowDetail) {
                            String body = response.body().string();
                            String json = getErrorJson(body);
                            JSONObject jsonObject = JSON.parseObject(json);
                            JSONObject detail = new JSONObject();
                            detail.put("type",jsonObject.get("type"));
                            detail.put("title",jsonObject.get("title"));
                            detail.put("desc", jsonObject.get("desc"));
                            checkResult.setDetail(detail);
                        }
                        checkResult.setResult(2);
                    } else {
                        checkResult.setResult(1);
                    }
                    sink.success(checkResult);
                    response.close();
                } catch (Throwable e){
                    if ((e instanceof SocketTimeoutException) || (e instanceof UnknownHostException)|| (e instanceof SocketException)) {
                        checkResult.setResult(1);
                        sink.success(checkResult);
                    }else {
                        sink.error(e);
                    }
                }
            }
        });
    }

    public static void main(String[] args) throws Exception {

        WxCheckComponent wxCheckComponent = new WxCheckComponent();
        CheckResult checkResult = wxCheckComponent.checkUrl("http://69296n.cn");
        System.out.println(checkResult.getResult());
    }
}
