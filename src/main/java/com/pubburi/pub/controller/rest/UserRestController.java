package com.pubburi.pub.controller.rest;

import java.util.List;

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
import com.pubburi.pub.controller.request.AdminUserRequest;
import com.pubburi.pub.controller.request.JoinUserRequest;
import com.pubburi.pub.controller.request.LoginRequest;
import com.pubburi.pub.controller.response.IdAvailableResponse;
import com.pubburi.pub.controller.response.OrderResponse;
import com.pubburi.pub.controller.response.ProfileResponse;
import com.pubburi.pub.controller.response.ResponseMapper;
import com.pubburi.pub.controller.response.UserResponse;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.service.GradeService;
import com.pubburi.pub.model.service.OrderService;
import com.pubburi.pub.model.service.UserService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class UserRestController {

	private final UserService userService;
	private final OrderService orderService;
	private final AccessGuard accessGuard;
	private final GradeService gradeService;

	public UserRestController(UserService userService, OrderService orderService, AccessGuard accessGuard,
			GradeService gradeService) {
		this.userService = userService;
		this.orderService = orderService;
		this.accessGuard = accessGuard;
		this.gradeService = gradeService;
	}

	@PostMapping("/auth/login")
	public ResponseEntity<ApiResponse<UserResponse>> login(@Valid @RequestBody LoginRequest request,
			HttpSession session) {
		User loginUser = new User();
		loginUser.setId(request.id());
		loginUser.setPass(request.pass());
		User user = userService.login(loginUser);
		if (user == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
		}
		session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(user));
		return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.user(user)));
	}

	@PostMapping("/auth/logout")
	public ResponseEntity<ApiResponse<Void>> logout(HttpSession session) {
		session.invalidate();
		return ResponseEntity.ok(ApiResponse.message(null, "logged out"));
	}

	@GetMapping("/auth/me")
	public ResponseEntity<ApiResponse<UserResponse>> me(HttpSession session) {
		SessionUser user = accessGuard.requireLogin(session);
		UserResponse response = new UserResponse(user.id(), user.name(), user.stamps(), user.role(), user.admin());
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@PostMapping("/users")
	public ResponseEntity<ApiResponse<Boolean>> joinUser(@Valid @RequestBody JoinUserRequest request) {
		User user = new User();
		user.setId(request.id());
		user.setName(request.name());
		user.setPass(request.pass());
		user.setRole("USER");
		int result = userService.addUser(user);
		if (result <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User could not be created");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.message(true, "created"));
	}

	@GetMapping("/users/id-available")
	public ResponseEntity<ApiResponse<IdAvailableResponse>> isAvailable(@RequestParam String id) {
		return ResponseEntity.ok(ApiResponse.ok(new IdAvailableResponse(!userService.isUsedId(id))));
	}

	@GetMapping("/users/{id}/profile")
	public ResponseEntity<ApiResponse<ProfileResponse>> profile(@PathVariable String id, HttpSession session) {
		accessGuard.requireSelfOrAdmin(session, id);
		User user = userService.getUserById(id);
		if (user == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
		}
		List<OrderResponse> orders = orderService.getOrdersByUserId(id, PageCriteria.of(1, 10, 10)).items().stream()
				.map(ResponseMapper::order).toList();
		ProfileResponse profile = new ProfileResponse(ResponseMapper.user(user), orders,
				gradeService.gradeFor(user.getStamps()));
		return ResponseEntity.ok(ApiResponse.ok(profile));
	}

	@GetMapping("/admin/users")
	public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> users(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, HttpSession session) {
		accessGuard.requireAdmin(session);
		PageResponse<UserResponse> users = userService.getUserPage(PageCriteria.of(page, size, 20))
				.map(ResponseMapper::user);
		return ResponseEntity.ok(ApiResponse.ok(users));
	}

	@PutMapping("/admin/users/{id}")
	public ResponseEntity<ApiResponse<Boolean>> updateUser(@PathVariable String id,
			@Valid @RequestBody AdminUserRequest request, HttpSession session) {
		SessionUser admin = accessGuard.requireAdmin(session);
		User existing = userService.getUserById(id);
		if (existing == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
		}
		if (admin.id().equals(id) && request.role() != null && !"ADMIN".equals(request.role())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Current admin role cannot be removed");
		}
		User user = new User();
		user.setId(id);
		user.setName(request.name() == null ? existing.getName() : request.name());
		user.setPass(request.pass());
		user.setStamps(request.stamps() == null ? existing.getStamps() : request.stamps());
		user.setRole(request.role() == null ? existing.getRole() : request.role());
		if (userService.modifyUser(user) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
		}
		if (admin.id().equals(id)) {
			session.setAttribute(AccessGuard.SESSION_USER, SessionUser.from(userService.getUserById(id)));
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

	@DeleteMapping("/admin/users/{id}")
	public ResponseEntity<ApiResponse<Boolean>> deleteUser(@PathVariable String id, HttpSession session) {
		SessionUser admin = accessGuard.requireAdmin(session);
		if (admin.id().equals(id)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Current admin account cannot be deleted");
		}
		if (userService.removeUser(id) <= 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
		}
		return ResponseEntity.ok(ApiResponse.ok(true));
	}

}
