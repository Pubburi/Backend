package com.pubburi.rehearsal;

import com.pubburi.pub.controller.auth.SessionUser;
import com.pubburi.pub.model.dao.ProductDao;
import com.pubburi.pub.model.dao.UserDao;
import com.pubburi.rehearsal.persistence.JpaRecommendationFeedback.Choice;
import com.pubburi.rehearsal.persistence.RecommendationFeedbackRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationFeedbackService {
    private final UserDao users;
    private final ProductDao products;
    private final RecommendationFeedbackRepository feedback;

    public RecommendationFeedbackService(UserDao users, ProductDao products, RecommendationFeedbackRepository feedback) {
        this.users = users;
        this.products = products;
        this.feedback = feedback;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Choice read(SessionUser session, int productId) {
        if (users.selectSessionIdentity(session.id(), session.authEpoch()) == null) {
            throw expired();
        }
        return feedback.choice(session.id(), productId).orElse(null);
    }

    @Transactional
    public void write(SessionUser session, int productId, Choice choice) {
        var owner = users.selectByIdForUpdate(session.id());
        if (owner == null || !session.authEpoch().equals(owner.getAuthEpoch())) {
            throw expired();
        }
        if (choice == null) {
            feedback.clearChoice(owner.getId(), productId);
            return;
        }

        // Match the order flow's user then product lock order and serialize with deletion.
        if (products.selectByIdForUpdate(productId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        feedback.saveChoice(owner.getId(), productId, choice.name());
    }

    private ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account session is no longer current");
    }
}
