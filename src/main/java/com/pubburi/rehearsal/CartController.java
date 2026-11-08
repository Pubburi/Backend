package com.pubburi.rehearsal;

import com.pubburi.pub.controller.api.ApiResponse;
import com.pubburi.pub.controller.auth.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping(value="/api/users/me/cart",produces=MediaType.APPLICATION_JSON_VALUE)
public class CartController {
    private static final String ACTOR=CartController.class.getName()+".owner";
    private final AccessGuard guard;private final CartService carts;
    public CartController(AccessGuard guard,CartService carts){this.guard=guard;this.carts=carts;}
    @ModelAttribute void authenticated(HttpServletRequest request,HttpSession session){
        var owner=guard.requireLogin(session);if(!FeedbackOwner.confirmed(request,owner,NotebookController.OWNER_HEADER))throw invalid();request.setAttribute(ACTOR,owner);
    }
    @GetMapping @Operation(summary="Read only the current account's cart",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Cart> get(HttpServletRequest request,HttpSession session){queries(request);var owner=owner(request);var result=carts.get(owner);current(session,owner);return ApiResponse.ok(result);}
    @GetMapping("/operations/{operationId}") @Operation(summary="Read an existing account-owned operation without replaying any mutation",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Result> operation(@PathVariable String operationId,HttpServletRequest request,HttpSession session){queries(request);var owner=owner(request);var result=carts.operation(owner,operationId(operationId));current(session,owner);return ApiResponse.ok(result);}
    @PutMapping(value="/items/{productId}",consumes=MediaType.APPLICATION_JSON_VALUE) @Operation(summary="Set an absolute cart quantity at its expected revision",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Cart> put(@PathVariable String productId,@RequestBody CartInput.Quantity input,HttpServletRequest request,HttpSession session){queries(request);var owner=owner(request);var result=carts.put(owner,productId(productId),input);current(session,owner);return ApiResponse.ok(result);}
    @DeleteMapping("/items/{productId}") @Operation(summary="Remove this account's item, including an unavailable product",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Cart> remove(@PathVariable String productId,@RequestParam(required=false) String expectedVersion,HttpServletRequest request,HttpSession session){queries(request,"expectedVersion");var owner=owner(request);var result=carts.remove(owner,productId(productId),version(expectedVersion));current(session,owner);return ApiResponse.ok(result);}
    @DeleteMapping @Operation(summary="Clear item rows while retaining the cart revision and replay receipts",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Cart> clear(@RequestParam(required=false) String expectedVersion,HttpServletRequest request,HttpSession session){queries(request,"expectedVersion");var owner=owner(request);var result=carts.remove(owner,null,version(expectedVersion));current(session,owner);return ApiResponse.ok(result);}
    @PostMapping(value="/merge",consumes=MediaType.APPLICATION_JSON_VALUE) @Operation(summary="Atomically merge an explicitly submitted guest cart exactly once",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Result> merge(@RequestBody CartInput.Merge input,HttpServletRequest request,HttpSession session){queries(request);var owner=owner(request);var result=carts.merge(owner,input);current(session,owner);return ApiResponse.ok(result);}
    @PostMapping(value="/checkout",consumes=MediaType.APPLICATION_JSON_VALUE) @Operation(summary="Atomically create one demo order and consume submitted cart quantities exactly once",parameters=@Parameter(name=NotebookController.OWNER_HEADER,in=ParameterIn.HEADER,required=true,description=FeedbackOwner.DESCRIPTION))
    public ApiResponse<CartService.Result> checkout(@RequestBody CartInput.Checkout input,HttpServletRequest request,HttpSession session){queries(request);var owner=owner(request);var result=carts.checkout(owner,input);current(session,owner);return ApiResponse.ok(result);}
    private SessionUser owner(HttpServletRequest request){return (SessionUser)request.getAttribute(ACTOR);}
    private void current(HttpSession session,SessionUser expected){var actual=guard.requireLogin(session);if(!actual.id().equals(expected.id()) || !actual.authEpoch().equals(expected.authEpoch()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account session is no longer current");}
    private static int productId(String value){try{if(!value.matches("[1-9][0-9]{0,9}"))throw invalid();int id=Integer.parseInt(value);if(id<1)throw invalid();return id;}catch(NumberFormatException error){throw invalid();}}
    private static UUID operationId(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}catch(IllegalArgumentException error){throw invalid();}}
    private static long version(String value){try{if(value==null || !value.matches("0|[1-9][0-9]{0,15}"))throw invalid();long version=Long.parseLong(value);if(version>NotebookInput.MAX_VERSION)throw invalid();return version;}catch(NumberFormatException error){throw invalid();}}
    private static void queries(HttpServletRequest request,String...allowed){var keys=Set.of(allowed);for(var item:request.getParameterMap().entrySet())if(!keys.contains(item.getKey()) || item.getValue().length!=1)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid cart request");}
}
