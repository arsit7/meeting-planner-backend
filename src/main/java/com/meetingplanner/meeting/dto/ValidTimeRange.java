package com.meetingplanner.meeting.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint: {@code endTime} must be strictly after {@code startTime}.
 * The violation is reported against the {@code endTime} field so clients can show it inline.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidTimeRange.Validator.class)
public @interface ValidTimeRange {

    String message() default "End time must be after start time";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidTimeRange, TimeRange> {

        @Override
        public boolean isValid(TimeRange value, ConstraintValidatorContext context) {
            if (value == null || value.startTime() == null || value.endTime() == null) {
                return true; // null fields are reported by @NotNull
            }
            if (value.endTime().isAfter(value.startTime())) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("endTime")
                    .addConstraintViolation();
            return false;
        }
    }
}
