package org.rx.crawler.controller;

import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.LoginKeepAliveResult;
import org.rx.crawler.task.common.LoginKeepAliveService;
import org.rx.crawler.task.jd.JdUnionBatchRequest;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionUrlRequest;
import org.rx.crawler.task.tb.TbPromotionUrlResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/custom")
public class CustomCrawlController {
    private final CustomCrawlQueueService taskQueueService;
    private final LoginKeepAliveService loginKeepAliveService;

    @PostMapping("/jd-union/getPromotionUrl")
    public Result<JdUnionPromotionResult> getPromotionUrl(@Valid @RequestBody JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = taskQueueService.submitAndWait("getPromotionUrl", request, JdUnionPromotionResult.class);
        return wrap(result);
    }

    @PostMapping("/jd-union/getPromotionOrders")
    public Result<JdUnionPromotionOrdersResult> getPromotionOrders(@Valid @RequestBody JdUnionPromotionOrdersRequest request) {
        JdUnionPromotionOrdersResult result = taskQueueService.submitAndWaitOrders("getPromotionOrders", request);
        return wrap(result);
    }

    @PostMapping("/tb/getPromotionOrders")
    public Result<TbPromotionOrdersResult> getTbPromotionOrders(@Valid @RequestBody TbPromotionOrdersRequest request) {
        TbPromotionOrdersResult result = taskQueueService.submitAndWaitTbOrders("getTbPromotionOrders", request);
        return wrap(result);
    }

    @PostMapping("/tb/getPromotionUrl")
    public Result<TbPromotionUrlResult> getTbPromotionUrl(@Valid @RequestBody TbPromotionUrlRequest request) {
        TbPromotionUrlResult result = taskQueueService.submitAndWaitTbPromotionUrl("getTbPromotionUrl", request);
        return wrap(result);
    }

    @PostMapping("/jd-union/login/check")
    public Result<JdUnionPromotionResult> loginCheck(@RequestBody(required = false) JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = taskQueueService.submitAndWait("loginCheck", request, JdUnionPromotionResult.class);
        return wrap(result);
    }

    @PostMapping("/jd-union/login/keepAlive")
    public Result<LoginKeepAliveResult> jdLoginKeepAlive() {
        LoginKeepAliveResult result = loginKeepAliveService.checkJd();
        return wrap(result);
    }

    @PostMapping("/tb/login/keepAlive")
    public Result<LoginKeepAliveResult> tbLoginKeepAlive() {
        LoginKeepAliveResult result = loginKeepAliveService.checkTb();
        return wrap(result);
    }

    @PostMapping("/jd-union/promotion/batch")
    public Result<List<JdUnionPromotionResult>> batch(@RequestBody JdUnionBatchRequest request) {
        List<JdUnionPromotionResult> results = taskQueueService.batch(request);
        return Result.success(results);
    }

    @PostMapping("/profile/close")
    public Result<Boolean> closeProfile(@RequestParam(required = false) String profileName) {
        boolean closed = taskQueueService.closeProfile(Strings.isEmpty(profileName) ? "common" : profileName);
        return Result.success(closed);
    }

    private Result<JdUnionPromotionResult> wrap(JdUnionPromotionResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<JdUnionPromotionOrdersResult> wrap(JdUnionPromotionOrdersResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<TbPromotionOrdersResult> wrap(TbPromotionOrdersResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<TbPromotionUrlResult> wrap(TbPromotionUrlResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }

    private Result<LoginKeepAliveResult> wrap(LoginKeepAliveResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }
}
