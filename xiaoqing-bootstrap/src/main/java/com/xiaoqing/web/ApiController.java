package com.xiaoqing.web;

import lombok.Data;
import org.springframework.service.SpringContext;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;

@RestController
public class ApiController {
    @Data
    public static class Item {
        @NotNull
        String name;
        Integer age;
    }

    @RequestMapping("/pValid")
    public Object postV(@Validated @RequestBody Item item) {
        return item;
    }

    @RequestMapping("/gValid")
    public Object getV(@Validated @RequestBody Item item) {
        return item;
    }

    @PostConstruct
    public void init() {
        SpringContext.exceptionHandle = (e, m) -> {
            if (e instanceof MethodArgumentNotValidException) {
                FieldError fieldError = ((MethodArgumentNotValidException) e).getFieldError();
                if (fieldError != null) {
                    return String.format("Field '%s' %s", fieldError.getField(), fieldError.getDefaultMessage());
                }
            }
            return e;
        };
    }
}
