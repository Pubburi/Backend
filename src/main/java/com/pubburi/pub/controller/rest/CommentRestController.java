package com.pubburi.pub.controller.rest;

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
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.controller.request.CommentCreateRequest;
import com.pubburi.pub.controller.request.CommentUpdateRequest;
import com.pubburi.pub.controller.response.CommentResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.model.dto.Comment;
import com.pubburi.pub.model.service.CommentService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CommentRestController {

	private final CommentService commentService;
	private final AccessGuard accessGuard;

	public CommentRestController(CommentService commentService, AccessGuard accessGuard) {
		this.commentService = commentService;
		this.accessGuard = accessGuard;
	}

	@GetMapping("/products/{productId}/comments")
	public ResponseEntity<ApiResponse<PageResponse<CommentResponse>>> comments(@PathVariable int productId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		PageResponse<CommentResponse> comments = commentService
				.getCommentsByProductId(productId, PageCriteria.of(page, size, 10)).map(ResponseMapper::comment);
		return ResponseEntity.ok(ApiResponse.ok(comments));
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
		if (commentService.updateComment(existing) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.comment(existing)));
	}

	@DeleteMapping("/comments/{id}")
	public ResponseEntity<ApiResponse<Boolean>> deleteComment(@PathVariable int id, HttpSession session) {
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
