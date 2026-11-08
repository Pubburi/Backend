package com.pubburi.pub.controller.rest;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import com.pubburi.pub.model.service.AccountNotificationActions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.api.PageCriteria;
import com.pubburi.pub.controller.api.PageResponse;
import com.pubburi.pub.controller.api.CommentSort;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.CommentCreateRequest;
import com.pubburi.pub.controller.request.CommentUpdateRequest;
import com.pubburi.pub.controller.response.CommentResponse;
import com.pubburi.pub.controller.response.CommentPageResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.model.service.CommentService;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api")
public class CommentRestController {

	private final CommentService commentService;
	private final AccessGuard accessGuard;
	private final Optional<AccountNotificationActions> notifications;

	public CommentRestController(CommentService commentService, AccessGuard accessGuard) {
		this(commentService,accessGuard,Optional.empty());
	}
	@Autowired
	public CommentRestController(CommentService commentService, AccessGuard accessGuard, Optional<AccountNotificationActions> notifications) {
		this.commentService = commentService;
		this.accessGuard = accessGuard;
		this.notifications = notifications;
	}

	@GetMapping("/products/{productId}/comments")
	@Operation(parameters = {
		@Parameter(name = "sort", in = ParameterIn.QUERY, schema = @Schema(allowableValues = {"latest", "rating_desc", "rating_asc"}), description = "Default latest; equal ratings use descending ID. Repeated values return 400."),
		@Parameter(name = "includeSummary", in = ParameterIn.QUERY, schema = @Schema(type = "boolean"), description = "Exact true adds ratingSummary for all reviews, not only this page. Omitted/false keeps the original six fields. Repeated or malformed values return 400."),
        @Parameter(name = "includeTasteTags", in = ParameterIn.QUERY, schema = @Schema(type = "boolean"), description = "Exact true adds member opinion tasteTags to each review; omitted/false preserves five review fields. Repeated or malformed values return 400.")})
	public ResponseEntity<ApiResponse<CommentPageResponse<CommentResponse>>> comments(@PathVariable int productId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			HttpServletRequest request) {
		var sort = CommentSort.fromQuery(request.getParameterValues("sort"));
		boolean includeSummary = includeOption(request.getParameterValues("includeSummary"));
        boolean includeTasteTags = includeOption(request.getParameterValues("includeTasteTags"));
		var comments = commentService
				.getCommentsPage(productId, PageCriteria.of(page, size, 10), sort, includeSummary, includeTasteTags)
                .map(value -> includeTasteTags
                    ? new CommentResponse(value.getId(), value.getUserId(), value.getProductId(), value.getRating(), value.getComment(), value.getTasteTags())
                    : ResponseMapper.comment(value));
		return ResponseEntity.ok(ApiResponse.ok(comments));
	}

	private static boolean includeOption(String[] values) {
		if (values == null) return false;
		if (values.length == 1 && (values[0].equals("true") || values[0].equals("false"))) {
			return values[0].equals("true");
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review include option");
	}

	@PostMapping("/comments")
	public ResponseEntity<ApiResponse<CommentResponse>> addComment(@Valid @RequestBody CommentCreateRequest request,
			HttpSession session) {
		SessionUser user = accessGuard.requireLogin(session);
		Comment comment = new Comment();
		comment.setUserId(user.id());
		comment.setProductId(request.productId());
		comment.setRating(request.rating());
		comment.setComment(request.comment());
        comment.setTasteTags(request.tasteTags());
		if (commentService.addComment(comment) <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment could not be created");
		}
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.message(ResponseMapper.comment(comment), "created"));
	}

	@PutMapping("/comments/{id}")
	public ResponseEntity<ApiResponse<CommentResponse>> updateComment(@PathVariable int id,
			@Valid @RequestBody CommentUpdateRequest request, HttpSession session) {
		Comment existing = findComment(id);
		accessGuard.requireSelfOrAdmin(session, existing.getUserId());
		existing.setRating(request.rating());
		existing.setComment(request.comment());
        existing.setTasteTags(request.tasteTags());
		if (commentService.updateComment(existing) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.comment(existing)));
	}

	@DeleteMapping("/comments/{id}")
	public ResponseEntity<ApiResponse<Boolean>> deleteComment(@PathVariable int id, HttpSession session) {
		if (notifications.isPresent()) {
			notifications.get().removeComment(id,accessGuard.requireLogin(session));
			return ResponseEntity.ok(ApiResponse.ok(true));
		}
		Comment existing = findComment(id);
		accessGuard.requireSelfOrAdmin(session, existing.getUserId());
		if (commentService.removeComment(id) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	@GetMapping("/admin/comments")
	public ResponseEntity<ApiResponse<PageResponse<CommentResponse>>> allComments(
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			HttpSession session) {
		accessGuard.requireAdmin(session);
		PageResponse<CommentResponse> comments = commentService.getAllComments(PageCriteria.of(page, size, 20))
				.map(ResponseMapper::comment);
		return ResponseEntity.ok(ApiResponse.ok(comments));
	}

	private Comment findComment(int id) {
		Comment existing = commentService.getCommentById(id);
		if (existing == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
		}
		return existing;
	}
}
