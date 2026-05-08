package org.rx.crawler.controller;

import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.jd.JdUnionBatchRequest;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/custom")
public class CustomCrawlController {
    private final JdUnionPromotionTask jdUnionPromotionTask;

    @PostMapping("/jd-union/getPromotionUrl")
    public Result<JdUnionPromotionResult> getPromotionUrl(@Valid @RequestBody JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = jdUnionPromotionTask.getPromotionUrl(request);
        return wrap(result);
    }

    @PostMapping("/jd-union/promotion")
    public Result<JdUnionPromotionResult> promotion(@Valid @RequestBody JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = jdUnionPromotionTask.promotion(request);
        return wrap(result);
    }

    @PostMapping("/jd-union/login/check")
    public Result<JdUnionPromotionResult> loginCheck(@RequestBody(required = false) JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = jdUnionPromotionTask.loginCheck(request);
        return wrap(result);
    }

    @PostMapping("/jd-union/promotion/batch")
    public Result<List<JdUnionPromotionResult>> batch(@RequestBody JdUnionBatchRequest request) {
        List<JdUnionPromotionResult> results = jdUnionPromotionTask.batch(request);
        return Result.success(results);
    }

    @PostMapping("/profile/close")
    public Result<Boolean> closeProfile(@RequestParam(required = false) String profileName) {
        boolean closed = jdUnionPromotionTask.closeProfile(Strings.isEmpty(profileName) ? "common" : profileName);
        return Result.success(closed);
    }

    private Result<JdUnionPromotionResult> wrap(JdUnionPromotionResult result) {
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            return Result.success(result);
        }
        return Result.fail(result.getStatus().name(), result.getMessage(), result);
    }
}
