package org.rx.crawler.task.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class CrawlResultValidator {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private CrawlResultValidator() {
    }

    public static List<String> validateRequired(String name, Object value) {
        List<String> errors = new ArrayList<String>();
        if (value == null) {
            errors.add(name + " must not be null");
            return errors;
        }
        errors.addAll(validate(name, value));
        return errors;
    }

    public static List<String> validateItems(String name, Collection<?> values) {
        List<String> errors = new ArrayList<String>();
        if (values == null || values.isEmpty()) {
            return errors;
        }
        int index = 0;
        for (Object value : values) {
            errors.addAll(validate(name + "[" + index + "]", value));
            index++;
        }
        return errors;
    }

    private static List<String> validate(String name, Object value) {
        List<String> errors = new ArrayList<String>();
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(value);
        for (ConstraintViolation<Object> violation : violations) {
            errors.add(name + "." + violation.getPropertyPath() + " " + violation.getMessage());
        }
        return errors;
    }
}
