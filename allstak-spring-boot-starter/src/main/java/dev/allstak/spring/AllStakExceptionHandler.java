package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.internal.SdkLogger;
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler that automatically captures unhandled exceptions to AllStak.
 * Does NOT suppress the exception — it re-throws after capture so Spring's normal
 * error handling still applies.
 */
@ControllerAdvice
public class AllStakExceptionHandler {

    private final AllStakClient client;
    private final boolean captureValidation;

    public AllStakExceptionHandler(AllStakClient client) {
        this(client, true);
    }

    public AllStakExceptionHandler(AllStakClient client, boolean captureValidation) {
        this.client = client;
        this.captureValidation = captureValidation;
    }

    @ExceptionHandler(Exception.class)
    public void handleException(Exception ex) throws Exception {
        try {
            Map<String, Object> metadata = captureValidation ? validationMetadata(ex) : Map.of();
            if (metadata.isEmpty()) {
                client.captureException(ex);
            } else {
                metadata.put("exception.original_class", ex.getClass().getName());
                client.captureException(new IllegalArgumentException("Validation failed"), "warning", metadata);
            }
        } catch (Exception captureError) {
            SdkLogger.debug("Failed to capture exception in global handler: {}", captureError.getMessage());
        }
        // Re-throw so Spring's default error handling still works
        throw ex;
    }

    private static Map<String, Object> validationMetadata(Exception ex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (ex instanceof MethodArgumentNotValidException manve) {
            metadata.put("error.category", "validation");
            metadata.put("validation.source", "method_argument_not_valid");
            metadata.put("validation.error_count", manve.getBindingResult().getErrorCount());
            metadata.put("validation.fields", manve.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField)
                    .distinct()
                    .collect(Collectors.joining(",")));
        } else if (ex instanceof BindException bindException) {
            metadata.put("error.category", "validation");
            metadata.put("validation.source", "bind_exception");
            metadata.put("validation.error_count", bindException.getBindingResult().getErrorCount());
            metadata.put("validation.fields", bindException.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField)
                    .distinct()
                    .collect(Collectors.joining(",")));
        } else if (ex instanceof ConstraintViolationException cve) {
            metadata.put("error.category", "validation");
            metadata.put("validation.source", "constraint_violation");
            metadata.put("validation.error_count", cve.getConstraintViolations().size());
            metadata.put("validation.fields", cve.getConstraintViolations().stream()
                    .map(violation -> String.valueOf(violation.getPropertyPath()))
                    .distinct()
                    .collect(Collectors.joining(",")));
        }
        return metadata;
    }
}
