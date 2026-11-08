package com.pubburi.pub.model.service;

import com.pubburi.pub.controller.auth.SessionUser;

/** The PG provider's atomic, server-authored business events. Legacy mutations remain unchanged. */
public interface AccountNotificationActions {
    void completeOrder(int orderId, SessionUser actor);
    void removeComment(int commentId, SessionUser actor);
}
