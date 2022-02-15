package com.xiaoqing.service;

import com.xiaoqing.util.BaiduApi;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.Strings;
import org.rx.crawler.Browser;
import org.rx.crawler.RegionFlags;
import org.rx.crawler.RemoteBrowser;
import org.rx.exception.InvalidException;
import org.rx.io.IOStream;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.rx.core.Extends.eq;

@Service
public class FirefightingService {
    private static final String cookieRegion = RemoteBrowser.buildCookieRegion("Firefighting", RegionFlags.DOMAIN_TOP.flags());

    public void doLogin(String uid, String pwd) {
        RemoteBrowser.invoke(p -> {
            String uidXpath = "//*[@id=\"app\"]/div/div[1]/div[2]/div/form/div[1]/div/div/input";
            String pwdXpath = "//*[@id=\"app\"]/div/div[1]/div[2]/div/form/div[2]/div/div/input";
            String validImgXpath = "//*[@id=\"app\"]/div/div[1]/div[2]/div/form/div[3]/div/div/div[2]/img";
            String validCodeXpath = "//*[@id=\"app\"]/div/div[1]/div[2]/div/form/div[3]/div/div/div[1]/input";
            String btnXpath = "//*[@id=\\\"app\\\"]/div/div[1]/div[2]/div/form/button";

            p.navigateUrl("https://xfhyjd.119.gov.cn/#/userLogin/", Browser.BODY_SELECTOR);
            p.elementPress(uidXpath, uid);
            p.elementPress(pwdXpath, pwd);

            List<String> code = new BaiduApi().ocr(IOStream.wrap("validCode", p.screenshotAsBytes(validImgXpath)));
            if (CollectionUtils.isEmpty(code)) {
                throw new InvalidException("Ocr validCode fail");
            }
            p.elementPress(validCodeXpath, code.get(0));
            p.elementClick(btnXpath);
            p.createWait(10).until(stat -> Strings.startsWith(p.getCurrentUrl(), "https://xfhyjd.119.gov.cn/#/signup/choosePlan"));
        }, cookieRegion);
    }

    public void register() {
        RemoteBrowser.invoke(p -> {
            String baomingXpath = ".el-button--primary";
            String dianjiXpath = "//*[@id=\"app\"]/div/div[1]/div[2]/button";

            p.navigateUrl("https://xfhyjd.119.gov.cn/#/signup/choosePlan", Browser.BODY_SELECTOR);

            p.elementClick(baomingXpath);
            p.createWait(30).until(stat -> eq(p.elementText(dianjiXpath), "点击报名"));
            p.elementClick(dianjiXpath);

            Map<String, String> xpaths = new HashMap<>();
            xpaths.put("民族", "//*[@id=\"app\"]/div/div[1]/div[2]/div[2]/div[1]/div/div/div[1]/div/div/div/form/div[8]/div/div/div/input");
            xpaths.put("", "");
            xpaths.put("", "");
            xpaths.put("", "");
            xpaths.put("", "");
            xpaths.put("", "");

        }, cookieRegion);
    }
}
