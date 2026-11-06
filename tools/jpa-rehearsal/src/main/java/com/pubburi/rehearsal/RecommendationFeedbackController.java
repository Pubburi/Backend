package com.pubburi.rehearsal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.FeedbackOwner;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.rehearsal.persistence.JpaRecommendationFeedback.Choice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequestMapping(value = "/api/users/me/recommendations/feedback/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class RecommendationFeedbackController {
    private final AccessGuard guard;
    private final RecommendationFeedbackService service;

    public RecommendationFeedbackController(AccessGuard guard, RecommendationFeedbackService service) {
        this.guard = guard;
        this.service = service;
    }

    @ModelAttribute
    void privateResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.addHeader(HttpHeaders.VARY, HttpHeaders.COOKIE);
    }

    @GetMapping
    @Operation(summary = "Read the current account's stored feedback; null if absent",
        parameters = @Parameter(name = FeedbackOwner.HEADER, in = ParameterIn.HEADER, required = true, description = FeedbackOwner.DESCRIPTION))
    public ApiResponse<FeedbackResponse> get(@PathVariable String productId, HttpServletRequest request, HttpSession session) {
        var owner = owner(request, session);
        int id = productId(productId);
        var feedback = service.read(owner, id);
        var current = guard.requireLogin(session);
        if (!owner.id().equals(current.id()) || !owner.authEpoch().equals(current.authEpoch())) throw expired();
        return ApiResponse.ok(new FeedbackResponse(id, feedback));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set the current account's stored feedback for an existing product",
        parameters = @Parameter(name = FeedbackOwner.HEADER, in = ParameterIn.HEADER, required = true, description = FeedbackOwner.DESCRIPTION))
    public ApiResponse<FeedbackResponse> put(@PathVariable String productId,
            @RequestBody FeedbackInput input, HttpServletRequest request, HttpSession session) {
        var owner = owner(request, session);
        int id = productId(productId);
        var choice = input.feedback();
        service.write(owner, id, choice);
        return ApiResponse.ok(new FeedbackResponse(id, choice));
    }

    @DeleteMapping
    @Operation(summary = "Clear the current account's stored feedback",
        parameters = @Parameter(name = FeedbackOwner.HEADER, in = ParameterIn.HEADER, required = true, description = FeedbackOwner.DESCRIPTION))
    public ApiResponse<FeedbackResponse> delete(@PathVariable String productId, HttpServletRequest request, HttpSession session) {
        var owner = owner(request, session);
        int id = productId(productId);
        service.write(owner, id, null);
        return ApiResponse.ok(new FeedbackResponse(id, null));
    }

    private SessionUser owner(HttpServletRequest request, HttpSession session) {
        var owner = guard.requireLogin(session);
        if (!FeedbackOwner.confirmed(request, owner)) {
            throw new IllegalArgumentException("Exactly one canonically encoded feedback owner header is required");
        }
        if (request.getQueryString() != null) throw new IllegalArgumentException("Query parameters are not supported");
        return owner;
    }

    private int productId(String value) {
        if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid product ID");
        return Integer.parseInt(value);
    }

    private ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
    }

    public record FeedbackResponse(int productId,
        @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(types = {"string", "null"}) Choice feedback) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @JsonDeserialize(using = FeedbackInputDeserializer.class)
    public record FeedbackInput(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Choice feedback) { }

    public static class FeedbackInputDeserializer extends JsonDeserializer<FeedbackInput> {
        @Override public FeedbackInput deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken() || !"feedback".equals(parser.nextFieldName())
                    || parser.nextToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(FeedbackInput.class, "Exactly one feedback value is required");
            }
            String value = parser.getText();
            if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                return context.reportInputMismatch(FeedbackInput.class, "Exactly one feedback value is required");
            }
            try {
                return new FeedbackInput(Choice.valueOf(value));
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(FeedbackInput.class, "Invalid feedback choice");
            }
        }
    }
}
