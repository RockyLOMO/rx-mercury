package org.rx.test;

import lombok.SneakyThrows;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.rx.core.Cache;
import org.rx.core.Linq;
import org.rx.core.Tasks;
import org.rx.crawler.Application;
import org.rx.crawler.BrowserAsyncTopic;
import org.rx.crawler.BrowserPoolListener;
import org.rx.crawler.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.BrowserPool;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.io.Files;
import org.rx.io.IOStream;
import org.rx.net.http.HttpClient;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonObject;

@SpringBootTest(classes = Application.class)
public class BrowserTests {
    @Resource
    AppConfig appConfig;
    @Resource
    BrowserAsyncTopic asyncTopic;
//    @Resource
//    BrowserService browserService;

    @SneakyThrows
    @Test
    public void poolListener() {
        Remoting.register(new BrowserPool(appConfig.getBrowser(), asyncTopic), 1210, false);

        Tasks.schedulePeriod(() -> {
            BrowserPoolListener listener = Remoting.createFacade(BrowserPoolListener.class, RpcClientConfig.statefulMode("127.0.0.1:1210", 0));
            System.out.println(listener.nextIdleId(BrowserType.IE));
            tryClose(listener);
        }, 1000);

        System.in.read();
    }

    @Test
    public void innerScript() {
        String baseScript = Cache.getOrSet("WebBrowser.baseScript", k -> {
            InputStream stream = WebBrowser.class.getResourceAsStream("/bot/base.js");
            if (stream == null) {
                System.out.println("resource is null");
                return "";
            }
            return IOStream.readString(stream, StandardCharsets.UTF_8) + "\n";
        });
        System.out.println(baseScript);
    }

    @SneakyThrows
    @Test
    public void download() {
        String refUrl = "https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5HmjmiY#!/report/detail/taoke";
        String rawCookie = "isg=BCEhEApn2ciykHU8ek0CJg5-OO07zpXAjSi_woP2HSiH6kG8yx6lkE-4StxJOS34; t=70824e08423cb52e5173c58b0dee1a93; cna=6N84E+HCOwcCAXngjNKxcUwW; l=aB7DTtLdyUaWZyQpDMaPsVhISxrxygBPpkTZBMaLzTqGdP8vhtS1fjno-VwkQ_qC5f9L_XtiI; cookie2=1391a802ada07c947d4f6dc4f332bfaa; v=0; _tb_token_=fe5b3865573ee; alimamapwag=TW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV09XNjQ7IFRyaWRlbnQvNy4wOyBydjoxMS4wKSBsaWtlIEdlY2tv; cookie32=da263646f8b7310d20a6241569cb21ca; alimamapw=TgoJAwYAAgoEBmsJUVQABQMFDglSAwAIBVpQBQEKUwMHUVMFCF8EB1NUVQ%3D%3D; cookie31=MzcxNTE0NDcsdzM5NTExNTMyMyxyb2NreXdvbmcuY2huQGdtYWlsLmNvbSxUQg%3D%3D; login=Vq8l%2BKCLz3%2F65A%3D%3D";
        HttpClient.COOKIES.saveFromResponse(HttpUrl.get(refUrl), HttpClient.decodeCookie(HttpUrl.get(refUrl), rawCookie));
        String url = "https://pub.alimama.com/report/getTbkPaymentDetails.json?spm=a219t.7664554.1998457203.10.19ef35d9uFsIOb&queryType=1&payStatus=&DownloadID=DOWNLOAD_REPORT_INCOME_NEW&startTime=2019-01-05&endTime=2019-01-11";
        HttpClient caller = new HttpClient();
//        caller.setHeaders(HttpCaller.parseOriginalHeader("Accept: text/html, application/xhtml+xml, image/jxr, */*\n" +
////                "Referer: https://pub.alimama.com/myunion.htm?spm=a219t.7900221/1.a214tr8.2.2a8f75a5khPITz\n" +
////                "Accept-Language: en-US,en;q=0.8,zh-Hans-CN;q=0.5,zh-Hans;q=0.3\n" +
////                "User-Agent: Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko\n" +
////                "Accept-Encoding: gzip, deflate\n" +
////                "Host: pub.alimama.com\n" +
////                "Connection: Keep-Alive\n" +
//                "Cookie: "+rawCookie));
        caller.get(url).toFile("D:\\a.xls");
    }

    @SneakyThrows
    @Test
    public void fiddler() {
        String path = "D:\\app_rebate\\fiddler\\VipGoods_1584849184132.txt";
        String p = Linq.from(Files.readLines(path)).last();
        String u = HttpClient.decodeUrl(toJsonObject(p).getString("page_url"));
        Map<String, String> queryString = HttpClient.decodeQueryString(u);
        u = queryString.get("$route");
        queryString = HttpClient.decodeQueryString(u);
        System.out.println(queryString);
        String goodsId = queryString.get("brandId") + "-" + queryString.get("goodsId");
        System.out.println(goodsId);
    }

    @Test
    public void other() {
//        UrlGenerator generator = new UrlGenerator("http://free-proxy.cz/zh/proxylist/country/CN/socks5/uptime/level1/[1-5]");
//        for (String url : generator) {
//            System.out.println(url);
//        }
    }
}
