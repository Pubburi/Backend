package com.pubburi.rehearsal.persistence;

import com.pubburi.rehearsal.persistence.JpaRecommendationFeedback.Choice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RecommendationFeedbackRepository
        extends JpaRepository<JpaRecommendationFeedback, JpaRecommendationFeedback.Key> {
    @Query("select f.feedback from JpaRecommendationFeedback f where f.userId=:userId and f.productId=:productId")
    Optional<Choice> choice(@Param("userId") String userId, @Param("productId") int productId);

    @Modifying
    @Query(value = """
        INSERT INTO pubburi.t_user_recommendation_feedback AS stored (user_id, product_id, feedback)
        VALUES (:userId, :productId, :feedback)
        ON CONFLICT (user_id, product_id) DO UPDATE SET feedback=excluded.feedback
        WHERE stored.feedback IS DISTINCT FROM excluded.feedback
        """, nativeQuery = true)
    void saveChoice(@Param("userId") String userId, @Param("productId") int productId,
                    @Param("feedback") String feedback);

    @Modifying
    @Query("delete from JpaRecommendationFeedback f where f.userId=:userId and f.productId=:productId")
    void clearChoice(@Param("userId") String userId, @Param("productId") int productId);
}
