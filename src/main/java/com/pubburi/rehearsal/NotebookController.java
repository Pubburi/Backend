package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.*;
import com.pubburi.pub.controller.auth.*;
import com.pubburi.rehearsal.persistence.JpaNotebook.Experience;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping(value="/api/users/me/notebook",produces=MediaType.APPLICATION_JSON_VALUE)
public class NotebookController {
    public static final String OWNER_HEADER="X-Pubburi-Account-Owner";
    private static final String ACTOR=NotebookController.class.getName()+".owner";
    private final AccessGuard guard;private final NotebookService notes;
    public NotebookController(AccessGuard guard,NotebookService notes){this.guard=guard;this.notes=notes;}
    @ModelAttribute void authenticated(HttpServletRequest request,HttpSession session){
        var owner=guard.requireLogin(session);
        if(!FeedbackOwner.confirmed(request,owner,OWNER_HEADER))throw new IllegalArgumentException("Exactly one expected account owner header is required");
        request.setAttribute(ACTOR,owner);
    }
    @GetMapping
    @Operation(summary="Read only the current account's private notebook",
        parameters=@Parameter(name=OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<PageResponse<NotebookService.Entry>> page(@RequestParam(required=false) String page,@RequestParam(required=false) String size,
        @RequestParam(required=false) String experience,HttpServletRequest request,HttpSession session){
        queries(request,"page","size","experience");int p=positive(page,1),s=positive(size,20);if(s>100)throw invalid();
        try{Math.multiplyExact(p-1,s);}catch(ArithmeticException error){throw invalid();}
        Experience kind=null;if(experience!=null){try{kind=Experience.valueOf(experience);}catch(IllegalArgumentException error){throw invalid();}}
        var owner=owner(request);var result=notes.page(owner,new PageCriteria(p,s),kind);current(session,owner);return ApiResponse.ok(result);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Create one private record for an existing product",
        parameters=@Parameter(name=OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotebookService.Entry> create(@RequestBody NotebookInput.Create input,HttpServletRequest request){
        queries(request);return ApiResponse.ok(notes.create(owner(request),input));
    }
    @GetMapping("/by-product/{productId}")
    @Operation(summary="Read this account's record for a current product, null if absent",
        parameters=@Parameter(name=OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotebookService.Entry> product(@PathVariable String productId,HttpServletRequest request,HttpSession session){
        queries(request);var owner=owner(request);var result=notes.product(owner,positive(productId,0));current(session,owner);return ApiResponse.ok(result);
    }
    @GetMapping("/{id}")
    @Operation(summary="Read one private notebook entry, including retained deleted-product records",
        parameters=@Parameter(name=OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotebookService.Entry> get(@PathVariable String id,HttpServletRequest request,HttpSession session){
        queries(request);var owner=owner(request);var result=notes.get(owner,id(id));current(session,owner);return ApiResponse.ok(result);
    }
    @PutMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Replace private observations if the note revision still matches",
        parameters=@Parameter(name=OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<NotebookService.Entry> change(@PathVariable String id,@RequestBody NotebookInput.Change input,HttpServletRequest request){
        queries(request);return ApiResponse.ok(notes.change(owner(request),id(id),input));
    }
    @DeleteMapping("/{id}")
    @Operation(summary="Delete this account's record at its expected note revision",
        parameters=@Parameter(name=OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<Void> delete(@PathVariable String id,@RequestParam(required=false) String expectedVersion,HttpServletRequest request){
        queries(request,"expectedVersion");long version;
        try{if(expectedVersion==null || !expectedVersion.matches("0|[1-9][0-9]{0,15}"))throw invalid();version=Long.parseLong(expectedVersion);}catch(NumberFormatException error){throw invalid();}
        if(version>NotebookInput.MAX_VERSION)throw invalid();notes.delete(owner(request),id(id),version);return ApiResponse.ok(null);
    }
    private SessionUser owner(HttpServletRequest request){return (SessionUser)request.getAttribute(ACTOR);}
    private void current(HttpSession session,SessionUser expected){var actual=guard.requireLogin(session);if(!actual.id().equals(expected.id()) || !actual.authEpoch().equals(expected.authEpoch()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");}
    private static UUID id(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}catch(IllegalArgumentException error){throw invalid();}}
    private static int positive(String value,int defaultValue){
        if(value==null){if(defaultValue>0)return defaultValue;throw invalid();}
        try{if(!value.matches("[1-9][0-9]{0,9}"))throw invalid();int number=Integer.parseInt(value);if(number<1)throw invalid();return number;}catch(NumberFormatException error){throw invalid();}
    }
    private static void queries(HttpServletRequest request,String...allowed){
        var keys=Set.of(allowed);for(var entry:request.getParameterMap().entrySet())if(!keys.contains(entry.getKey()) || entry.getValue().length!=1)throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid notebook request");}
}
