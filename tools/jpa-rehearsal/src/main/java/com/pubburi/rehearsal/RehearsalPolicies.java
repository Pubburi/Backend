package com.pubburi.rehearsal;

import java.util.Map;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
class RehearsalPolicies {
    static void validate(Environment env) {
        if(!"validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")) || env.getProperty("spring.jpa.open-in-view",Boolean.class,true)
                || env.getProperty("spring.jpa.generate-ddl",Boolean.class,false) || env.getProperty("spring.flyway.enabled",Boolean.class,true)
                || !"never".equals(env.getProperty("spring.sql.init.mode")))throw new IllegalArgumentException("jpa_rehearsal_schema_policy_rejected");
    }
    @Bean HibernatePropertiesCustomizer validateOnlyHibernate(){return properties->{
        if(properties.keySet().stream().anyMatch(key->key.startsWith("jakarta.persistence.schema-generation") || key.startsWith("javax.persistence.schema-generation")))throw new IllegalArgumentException("jpa_rehearsal_schema_policy_rejected");
        properties.put("hibernate.hbm2ddl.auto","validate");
    };}
}
