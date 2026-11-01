package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.pub.controller.request.LoginRequest;
import com.pubburi.pub.controller.response.*;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Only the existing session contract; other user/order/product routes are intentionally not registered. */
@RestController
@RequestMapping(value="/api/auth",produces=MediaType.APPLICATION_JSON_VALUE)
public class JpaSessionController {
    private final UserService users;private final AccessGuard guard;
    public JpaSessionController(UserService users,AccessGuard guard){this.users=users;this.guard=guard;}
    @PostMapping(value="/login",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<UserResponse>> login(@Valid @RequestBody LoginRequest request,HttpSession session) {
        User input=new User();input.setId(request.id());input.setPass(request.pass());User user=users.login(input);
        if(user==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        guard.establishSession(session,user);return ResponseEntity.ok(ApiResponse.ok(ResponseMapper.user(user)));
    }
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpSession session){synchronized(session){session.invalidate();}return ResponseEntity.ok(ApiResponse.message(null,"logged out"));}
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me(HttpSession session){SessionUser u=guard.requireLogin(session);return ResponseEntity.ok(ApiResponse.ok(new UserResponse(u.id(),u.name(),u.stamps(),u.role(),u.admin())));}
}
