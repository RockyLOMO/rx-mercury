package org.rx.crawler.task.tb;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 阿里妈妈/淘宝联盟滑块验证通用处理器。
 * 负责检测页面是否弹出滑块验证、模拟拟人拖拽滑块、处理验证失败重试点击等。
 * 可被不同的爬虫任务复用，不耦合具体业务流程。
 */
@Slf4j
public class SliderVerifyHandler {

    /** 滑块验证页面特征关键词 */
    private static final String[] SLIDER_KEYWORDS = {
            "请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块", "请按住滑块",
            "滑块验证", "安全验证", "访问验证", "行为验证", "请完成验证", "验证失败"
    };

    /** 验证失败重试特征关键词 */
    private static final String[] VERIFY_FAILED_KEYWORDS = {
            "验证失败", "点击框体重试"
    };

    /**
     * 检测当前页面是否为滑块验证页（通过页面文字特征、URL 或特定元素判断）。
     */
    public boolean isSliderVerifyPage(Browser browser) {
        try {
            String url = browser.getCurrentUrl();
            if (containsAny(url, "/punish", "x5sec", "_____tmd_____", "captcha")) {
                return true;
            }

            String body = readBodySnippet(browser);
            if (containsAny(body, SLIDER_KEYWORDS)) {
                return true;
            }

            Boolean detected = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                    "function visible(el){" +
                    "  var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                    "  return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;" +
                    "}" +
                    "var body=norm(document.body ? (document.body.innerText || document.body.textContent || '') : '');" +
                    "var words=['请拖动下方滑块完成验证','拖动滑块','拖到最右边','按住滑块','滑块验证','安全验证','访问验证','行为验证','请完成验证','请按住滑块'];" +
                    "for(var i=0;i<words.length;i++){ if(body.indexOf(words[i])>=0){return true;} }" +
                    "var nodes=Array.prototype.slice.call(document.querySelectorAll('iframe,div,span,input,button'));" +
                    "for(var j=0;j<nodes.length;j++){" +
                    "  var e=nodes[j];" +
                    "  var meta=[e.id,e.className,e.name,e.src,e.getAttribute('title'),e.getAttribute('aria-label'),e.getAttribute('data-spm')].join(' ');" +
                    "  if(!/(nc_|nc-|awsc|captcha|punish|baxia|滑块|验证码|安全验证|x5sec)/i.test(meta)){continue;}" +
                    "  if(e.tagName==='IFRAME' || visible(e)){return true;}" +
                    "}" +
                    "return false;");
            return Boolean.TRUE.equals(detected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测并处理滑块验证，最多重试指定次数。
     * 验证成功或无验证时返回 true；全部重试失败返回 false。
     *
     * @param browser     浏览器实例
     * @param stepTag     当前步骤标签（用于日志和快照命名）
     * @param maxAttempts 最大重试次数（通常 3）
     * @param retryDelay  每次重试等待毫秒数
     * @param snapshot    快照回调 (browser, snapshotName)，可为 null
     * @return true=验证通过或无验证，false=全部重试失败
     */
    public boolean checkAndHandle(Browser browser, String stepTag, int maxAttempts,
            long retryDelay, BiConsumer<Browser, String> snapshot) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
            // 发现验证页
            log.info("Slider verify detected, step={}, attempt={}", stepTag, attempt + 1);
            doSnapshot(snapshot, browser, stepTag + "-before-slide-" + (attempt + 1));

            boolean slid = simulateSlideDrag(browser, snapshot, stepTag, attempt + 1);
            Extends.sleep(Math.max(1500, retryDelay * 2));
            doSnapshot(snapshot, browser, stepTag + "-after-slide-" + (attempt + 1));

            if (slid && !isSliderVerifyPage(browser)) {
                log.info("Slider verify passed, step={}, attempt={}", stepTag, attempt + 1);
                return true;
            }
            log.warn("Slider verify not resolved, step={}, attempt={}, slid={}", stepTag, attempt + 1, slid);

            // 检测"验证失败，点击框体重试"文案，先点击框体触发重置
            clickRetryContainerIfPresent(browser, snapshot, stepTag, attempt + 1);

            // 等待一段时间再重试
            Extends.sleep(Math.max(2000, retryDelay * 3));
        }
        log.warn("Slider verify failed after {} attempts, step={}", maxAttempts, stepTag);
        return false;
    }

    /**
     * 模拟人工缓慢向右拖拽滑块验证的滑块按钮。
     * 通过 JS 找到滑块元素坐标，再用 Playwright 原生 Mouse API 模拟拖拽。
     * 返回 true 表示已成功模拟拖拽（不代表验证通过），false 表示未找到滑块。
     */
    public boolean simulateSlideDrag(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt) {
        // 找到滑块按钮，返回 {x, y, width, height, trackWidth}
        Map<String, Object> sliderInfo = browser.executeScript(
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                // 1) 精确匹配阿里云 NoCaptcha (NC) 滑块把手: class 含 btn_slide
                "var handles=Array.prototype.slice.call(document.querySelectorAll('.btn_slide,[class*=btn_slide]')).filter(visible);" +
                // 2) NC 轨道: .nc_scale
                "var tracks=Array.prototype.slice.call(document.querySelectorAll('.nc_scale,[class*=nc_scale],[class*=nc_wrapper]')).filter(function(e){" +
                "  if(!visible(e)){return false;}var r=e.getBoundingClientRect();return r.width>100;" +
                "});" +
                // 3) 通用备选: class 含 slider/handle/drag 且宽度 <= 80
                "if(handles.length===0){" +
                "  handles=Array.prototype.slice.call(document.querySelectorAll(" +
                "    '[class*=slider],[class*=Slider],[class*=handle],[class*=Handle],[class*=drag],[class*=Drag]'" +
                "  )).filter(function(e){" +
                "    if(!visible(e)){return false;}var r=e.getBoundingClientRect();" +
                "    return r.width>0&&r.width<=80&&r.height>0&&r.height<=80;" +
                "  });" +
                "}" +
                // 4) 轨道备选: 宽度较大且 class 含 track/rail/groove
                "if(tracks.length===0){" +
                "  tracks=Array.prototype.slice.call(document.querySelectorAll(" +
                "    '[class*=track],[class*=Track],[class*=rail],[class*=Rail],[class*=groove],[class*=Groove]'" +
                "  )).filter(function(e){" +
                "    if(!visible(e)){return false;}var r=e.getBoundingClientRect();return r.width>100;" +
                "  });" +
                "}" +
                // 5) 最终兜底: 找到文字含'拖'的父容器内第一个小方块
                "if(handles.length===0){" +
                "  var verifyBox=null;" +
                "  var allDivs=Array.prototype.slice.call(document.querySelectorAll('div,span,section'));" +
                "  for(var i=0;i<allDivs.length;i++){" +
                "    var t=norm(allDivs[i].innerText||allDivs[i].textContent||'');" +
                "    if(t.indexOf('\\u62d6')>=0&&t.indexOf('\\u6ed1\\u5757')>=0){verifyBox=allDivs[i];break;}" +
                "  }" +
                "  if(verifyBox){" +
                "    var kids=Array.prototype.slice.call(verifyBox.querySelectorAll('*'));" +
                "    for(var j=0;j<kids.length;j++){" +
                "      var kr=kids[j].getBoundingClientRect();" +
                "      if(visible(kids[j])&&kr.width>10&&kr.width<=80&&kr.height>10&&kr.height<=80){" +
                "        handles.push(kids[j]);break;" +
                "      }" +
                "    }" +
                "  }" +
                "}" +
                "if(handles.length===0){return null;}" +
                "var handle=handles[0];" +
                "var hr=handle.getBoundingClientRect();" +
                "var trackWidth=hr.width;" +
                "if(tracks.length>0){" +
                "  var tr=tracks[0].getBoundingClientRect();" +
                "  trackWidth=tr.width;" +
                "}" +
                "return {x:hr.left+hr.width/2,y:hr.top+hr.height/2,width:hr.width,height:hr.height,trackWidth:trackWidth};");

        if (sliderInfo == null) {
            log.warn("Slider handle not found, cannot simulate drag, step={}, attempt={}", stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-no-handle-" + attempt);
            return false;
        }

        double startX = toDouble(sliderInfo.get("x"));
        double startY = toDouble(sliderInfo.get("y"));
        double handleWidth = toDouble(sliderInfo.get("width"));
        double trackWidth = toDouble(sliderInfo.get("trackWidth"));

        // 坐标合法性校验
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || startX < 1 || startY < 1) {
            log.warn("Slider handle coordinates invalid, startX={}, startY={}, step={}, attempt={}", startX, startY, stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-slide-invalid-coord-" + attempt);
            return false;
        }

        // 拖拽距离 = 轨道宽 - 滑块宽，兜底用视口宽度的 70%
        double dragDistance;
        if (trackWidth > handleWidth + 10) {
            dragDistance = trackWidth - handleWidth - 2;
        } else {
            double viewportWidth = toDouble(browser.executeScript("return window.innerWidth || 800;"));
            dragDistance = Math.max(200, viewportWidth * 0.70 - handleWidth);
        }

        log.info("Simulate slide drag, startX={}, startY={}, handleWidth={}, trackWidth={}, dragDistance={}",
                startX, startY, handleWidth, trackWidth, dragDistance);

        // 使用 Playwright 原生 Mouse API 模拟拖拽
        double endX = startX + dragDistance;
        browser.mouseDrag(startX, startY, endX, startY, 30);
        return true;
    }

    /**
     * 检测页面是否出现"验证失败，点击框体重试"提示，若有则点击 NC 验证框体触发重置。
     */
    public void clickRetryContainerIfPresent(Browser browser, BiConsumer<Browser, String> snapshot,
            String stepTag, int attempt) {
        try {
            String body = readBodySnippet(browser);
            if (!containsAny(body, VERIFY_FAILED_KEYWORDS)) {
                return;
            }
            log.info("Slider verify failed hint detected, clicking container to retry, step={}, attempt={}", stepTag, attempt);
            doSnapshot(snapshot, browser, stepTag + "-verify-failed-" + attempt);

            // 通过 JS 找到 NC 验证容器框体坐标
            Map<String, Object> containerInfo = browser.executeScript(
                    "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                    "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                    "var c=document.querySelector('.nc-container,.nc_wrapper,.sm-pop-inner');" +
                    "if(c&&visible(c)){var r=c.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height};}" +
                    "var all=Array.prototype.slice.call(document.querySelectorAll('div,span,p'));" +
                    "for(var i=0;i<all.length;i++){" +
                    "  var t=(all[i].innerText||all[i].textContent||'').trim();" +
                    "  if(t.indexOf('验证失败')>=0&&visible(all[i])){" +
                    "    var r=all[i].getBoundingClientRect();" +
                    "    if(r.width>50&&r.height>20){return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height};}" +
                    "  }" +
                    "}" +
                    "return null;");

            if (containerInfo == null) {
                log.warn("Verify-failed container not found, step={}, attempt={}", stepTag, attempt);
                return;
            }

            double cx = toDouble(containerInfo.get("x"));
            double cy = toDouble(containerInfo.get("y"));
            if (cx < 1 || cy < 1) {
                log.warn("Verify-failed container coordinates invalid, x={}, y={}", cx, cy);
                return;
            }

            log.info("Clicking verify-failed container at ({}, {}), step={}, attempt={}", cx, cy, stepTag, attempt);
            // 用 mouseDrag 原地点击（起点终点相同 = 点击效果）
            browser.mouseDrag(cx, cy, cx, cy, 1);
            Extends.sleep(1500);
            doSnapshot(snapshot, browser, stepTag + "-verify-retry-clicked-" + attempt);
        } catch (Exception e) {
            log.warn("Click retry container error, step={}, attempt={}, error={}", stepTag, attempt, e.getMessage());
        }
    }

    /**
     * 读取页面 body 文本摘要（最多 2000 字符），带导航重试。
     */
    public String readBodySnippet(Browser browser) {
        String body = "";
        for (int i = 0; i < 3; i++) {
            try {
                body = browser.executeScript("return document.body ? document.body.innerText : '';");
                break;
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && (message.contains("Execution context was destroyed")
                        || message.contains("because of a navigation"))) {
                    log.debug("Read body while navigating, retry={}", i + 1);
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                log.warn("Read body fail, currentUrl={}, error={}", browser.getCurrentUrl(), message);
                return "";
            }
        }
        if (Strings.isEmpty(body) || body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }

    // ---- 内部工具方法 ----

    private void doSnapshot(BiConsumer<Browser, String> snapshot, Browser browser, String name) {
        if (snapshot != null) {
            snapshot.accept(browser, name);
        }
    }

    /** Object -> double 工具方法，兼容 Integer / Long / Double / String */
    private double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0; }
    }

    private boolean containsAny(String value, String... keys) {
        if (Strings.isEmpty(value)) {
            return false;
        }
        for (String key : keys) {
            if (value.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
