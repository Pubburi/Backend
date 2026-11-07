package com.pubburi.pub.controller.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.UrlPathHelper;

/** Private cache and bounded bodies also cover routing/authentication/input failures. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class NotebookPrivacyFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES=65536;
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !path.equals("/api/users/me/notebook") && !path.startsWith("/api/users/me/notebook/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Cookie");
        if(request.getMethod().equals("POST") || request.getMethod().equals("PUT")){
            if(request.getContentLengthLong()>MAX_BODY_BYTES){
                response.setStatus(413);response.setContentType("application/json");
                response.getWriter().write("{\"data\":null,\"message\":null,\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Notebook request is too large\",\"fields\":{}}}");return;
            }
            request=new ContentCachingRequestWrapper(request,MAX_BODY_BYTES){
                @Override protected void handleContentOverflow(int limit){throw new IllegalArgumentException("Notebook request body is too large");}
            };
        }
        chain.doFilter(request,response);
    }
}
