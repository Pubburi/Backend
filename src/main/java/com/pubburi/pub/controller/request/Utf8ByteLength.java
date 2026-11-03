package com.pubburi.pub.controller.request;

import java.lang.annotation.*;
import java.nio.charset.StandardCharsets;
import jakarta.validation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8ByteLength.Validator.class)
public @interface Utf8ByteLength {
    int max();
    String message() default "must fit the UTF-8 byte limit";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<Utf8ByteLength, String> {
        private int max;
        @Override public void initialize(Utf8ByteLength constraint) { max = constraint.max(); }
        @Override public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
