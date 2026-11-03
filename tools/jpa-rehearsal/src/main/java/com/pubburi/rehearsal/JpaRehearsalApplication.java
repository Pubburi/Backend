package com.pubburi.rehearsal;

import com.pubburi.pub.config.CorsConfig;
import com.pubburi.pub.controller.api.GlobalExceptionHandler;
import com.pubburi.pub.controller.auth.AccessGuard;
import com.pubburi.pub.controller.rest.MarketRestController;
import com.pubburi.pub.controller.rest.ProductRestController;
import com.pubburi.pub.controller.rest.ProductFeatureRestController;
import com.pubburi.pub.controller.rest.OrderRestController;
import com.pubburi.pub.controller.rest.UserRestController;
import com.pubburi.pub.controller.rest.CommentRestController;
import com.pubburi.pub.controller.rest.FavoriteRestController;
import com.pubburi.pub.controller.rest.UserPreferenceRestController;
import com.pubburi.pub.model.service.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({CorsConfig.class,GlobalExceptionHandler.class,AccessGuard.class,MarketRestController.class,
    ProductRestController.class,ProductFeatureRestController.class,ProductServiceImpl.class,ProductSearchOutbox.class,
    UserServiceImpl.class,PasswordHasher.class,MarketServiceImpl.class,OrderRestController.class,OrderServiceImpl.class,
    UserRestController.class,GradeService.class,CommentRestController.class,CommentServiceImpl.class,
    FavoriteRestController.class,FavoriteServiceImpl.class,UserPreferenceRestController.class,UserPreferenceServiceImpl.class})
public class JpaRehearsalApplication {
    public static void main(String[] args){SpringApplication.run(JpaRehearsalApplication.class,args);}
}
