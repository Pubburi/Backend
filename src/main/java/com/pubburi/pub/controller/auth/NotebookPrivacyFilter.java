package com.pubburi.pub.controller.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cache protection also covers routing, authentication and body-validation failures. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class NotebookPrivacyFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/users/me/notebook") && !path.startsWith("/api/users/me/notebook/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Cookie");chain.doFilter(request,response);
    }
}
