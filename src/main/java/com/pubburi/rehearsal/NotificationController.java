package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value="/api/users/me/notifications",produces=MediaType.APPLICATION_JSON_VALUE)
public class NotificationController {
    private static final String ACTOR=NotificationController.class.getName()+".owner";
    private final AccessGuard guard;private final NotificationService notifications;
    public NotificationController(AccessGuard guard,NotificationService notifications){this.guard=guard;this.notifications=notifications;}
    @ModelAttribute void authenticated(HttpServletRequest request,HttpSession session){
        var owner=guard.requireLogin(session);if(!FeedbackOwner.confirmed(request,owner,NotebookController.OWNER_HEADER))throw invalid();request.setAttribute(ACTOR,owner);
    }
    @GetMapping @Operation(summary="Read current account notifications and its whole unread count",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotificationService.Page> page(@RequestParam(required=false) String page,@RequestParam(required=false) String size,HttpServletRequest request,HttpSession session){
        queries(request,"page","size");int p=positive(page,1),s=positive(size,20);if(s>100)throw invalid();
        try{Math.multiplyExact(p-1,s);}catch(ArithmeticException error){throw invalid();}
        var owner=owner(request);var result=notifications.page(owner,new PageCriteria(p,s));current(session,owner);return ApiResponse.ok(result);
    }
    @PutMapping("/{id}/read") @Operation(summary="Mark one current account notification read without changing its first read time",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotificationService.ReadResult> read(@PathVariable String id,@RequestBody(required=false) byte[] body,HttpServletRequest request,HttpSession session){
        queries(request);if(body!=null && body.length>0)throw invalid();var owner=owner(request);var result=notifications.read(owner,List.of(NotificationInput.id(id)));current(session,owner);return ApiResponse.ok(result);
    }
    @PostMapping(value="/read",consumes=MediaType.APPLICATION_JSON_VALUE) @Operation(summary="Atomically mark the explicitly submitted page IDs read; no implicit read-all",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotificationService.ReadResult> readPage(@RequestBody NotificationInput.Read input,HttpServletRequest request,HttpSession session){
        queries(request);var owner=owner(request);var result=notifications.read(owner,input.ids());current(session,owner);return ApiResponse.ok(result);
    }
    private SessionUser owner(HttpServletRequest request){return (SessionUser)request.getAttribute(ACTOR);}
    private void current(HttpSession session,SessionUser expected){var actual=guard.requireLogin(session);if(!actual.id().equals(expected.id()) || !actual.authEpoch().equals(expected.authEpoch()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");}
    private static int positive(String value,int fallback){if(value==null)return fallback;try{if(!value.matches("[1-9][0-9]{0,9}"))throw invalid();return Integer.parseInt(value);}catch(NumberFormatException error){throw invalid();}}
    private static void queries(HttpServletRequest request,String...allowed){var keys=Set.of(allowed);for(var entry:request.getParameterMap().entrySet())if(!keys.contains(entry.getKey()) || entry.getValue().length!=1)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid notification request");}
}
