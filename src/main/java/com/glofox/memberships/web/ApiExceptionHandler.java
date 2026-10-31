package com.glofox.memberships.web;

import static java.util.Map.entry;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PAYMENT_REQUIRED;

import com.glofox.memberships.payment.PaymentGateway.PaymentDeclinedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentFailedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentGatewayUnavailableException;
import com.glofox.memberships.service.BlankMemberNameException;
import com.glofox.memberships.service.PlanInactiveException;
import com.glofox.memberships.service.PlanNotFoundException;
import com.glofox.memberships.service.PlanSoldOutException;
import com.glofox.memberships.service.PurchaseAlreadyCancelledException;
import com.glofox.memberships.service.PurchaseNotFoundException;
import com.glofox.memberships.service.StartDateInPastException;
import com.glofox.memberships.service.UnlimitedMembershipExistsException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The single place an exception becomes an HTTP status.
 *
 * <p>Every error the API can produce is one row in the table below, which is
 * what keeps status decisions out of the controller and rules out of both.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private record Mapping(HttpStatus status, String code) {
    }

    /**
     * Exception to status and machine-readable code.
     *
     * <p><strong>{@code Map.ofEntries}, not {@code Map.of}.</strong> The
     * overloads of {@code Map.of} stop at ten pairs and this table has exactly
     * ten; the cancel endpoint's eleventh row would simply fail to compile.
     * Better to start past the cliff than to discover it later.
     *
     * <p><strong>Lookup is by exact class, not {@code instanceof}.</strong> The
     * two payment subtypes extend {@code PaymentFailedException}, so a hierarchy
     * walk would need ordering rules to stop the base row shadowing the specific
     * ones. Exact matching plus an explicit base row is unambiguous.
     */
    private static final Map<Class<? extends Throwable>, Mapping> MAPPINGS = Map.ofEntries(
            entry(PlanNotFoundException.class, new Mapping(NOT_FOUND, "plan_not_found")),
            entry(PurchaseNotFoundException.class, new Mapping(NOT_FOUND, "purchase_not_found")),
            entry(PlanInactiveException.class, new Mapping(CONFLICT, "plan_inactive")),
            entry(UnlimitedMembershipExistsException.class,
                  new Mapping(CONFLICT, "unlimited_membership_exists")),
            entry(PlanSoldOutException.class, new Mapping(CONFLICT, "plan_sold_out")),
            // The eleventh row, and the reason this table is Map.ofEntries: the
            // Map.of overloads stop at ten pairs, which the ten others fill.
            entry(PurchaseAlreadyCancelledException.class,
                  new Mapping(CONFLICT, "purchase_already_cancelled")),
            entry(BlankMemberNameException.class, new Mapping(BAD_REQUEST, "validation_failed")),
            entry(StartDateInPastException.class, new Mapping(BAD_REQUEST, "start_date_in_past")),
            entry(PaymentDeclinedException.class,
                  new Mapping(PAYMENT_REQUIRED, "payment_declined")),
            entry(PaymentGatewayUnavailableException.class,
                  new Mapping(BAD_GATEWAY, "payment_gateway_error")),
            // Fallback: an adapter that cannot distinguish the two throws the
            // base type, and a declined charge is the safer assumption.
            entry(PaymentFailedException.class,
                  new Mapping(PAYMENT_REQUIRED, "payment_declined")));

    /**
     * Codes whose {@code detail} must not echo anything the caller supplied.
     *
     * <p>Only one entry, and deliberately not a blanket policy. Echoing the
     * member name back on a membership conflict would turn an unauthenticated
     * 409 into a membership-existence oracle: probe a name, learn whether that
     * person holds a membership at this studio. For this one code, "a useful
     * detail for the client" and "a useful detail for an attacker" are the same
     * string. The machine-readable {@code code} is unchanged, so a legitimate
     * client loses nothing.
     */
    private static final Map<String, String> GENERIC_DETAIL = Map.of(
            "unlimited_membership_exists",
            "This member already holds an active unlimited membership.");

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail handleDomain(RuntimeException ex, HttpServletRequest request) {
        Mapping mapping = MAPPINGS.get(ex.getClass());

        if (mapping == null) {
            // Everything to the log, nothing to the client. An unexpected
            // exception's message can carry a stack-trace fragment, a query, or
            // a file path. Domain exceptions get their message through because
            // those messages were written deliberately; unknown ones do not.
            log.error("unhandled exception", ex);
            return problem(new Mapping(INTERNAL_SERVER_ERROR, "internal_error"),
                           "Unexpected error", request);
        }
        return problem(mapping, ex.getMessage(), request);
    }

    @ExceptionHandler(RequestValidationException.class)
    ProblemDetail handleValidation(RequestValidationException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(
                new Mapping(BAD_REQUEST, "validation_failed"),
                "The request contains " + ex.errors().size() + " invalid field(s).",
                request);
        // The errors array appears only here. Every other problem concerns the
        // request as a whole rather than a named field.
        problem.setProperty("errors", ex.errors());
        return problem;
    }

    /**
     * Binding failures: a malformed body, or a path variable that cannot be a
     * UUID.
     *
     * <p>Without this handler Spring renders a bare 400 with no {@code code},
     * which is the kind of contract gap only a test finds. The detail is
     * deliberately generic — parser internals name classes and offsets.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    ProblemDetail handleBinding(Exception ex, HttpServletRequest request) {
        return problem(new Mapping(BAD_REQUEST, "validation_failed"),
                       "Request could not be read.", request);
    }

    private static ProblemDetail problem(Mapping mapping, String detail,
                                         HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(mapping.status());
        problem.setTitle(titleFor(mapping.code()));
        problem.setDetail(GENERIC_DETAIL.getOrDefault(mapping.code(), detail));
        // type is derived from code, so the two can never disagree.
        problem.setType(URI.create("urn:glofox:problem:" + mapping.code().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", mapping.code());
        return problem;
    }

    /** {@code plan_sold_out} to {@code "Plan sold out"}. */
    private static String titleFor(String code) {
        String spaced = code.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
