package com.pubburi.rehearsal;

import com.pubburi.pub.config.CorsConfig;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.rest.MarketRestController;
import com.pubburi.pub.model.service.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({CorsConfig.class,GlobalExceptionHandler.class,AccessGuard.class,MarketRestController.class,
    UserServiceImpl.class,PasswordHasher.class,MarketServiceImpl.class})
public class JpaRehearsalApplication {
    public static void main(String[] args){SpringApplication.run(JpaRehearsalApplication.class,args);}
}
