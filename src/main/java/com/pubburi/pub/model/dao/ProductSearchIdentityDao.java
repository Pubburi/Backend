package com.pubburi.pub.model.dao;

/** Read the existing immutable database source lifetime; never create/reset it. */
public interface ProductSearchIdentityDao {
    String selectSourceId();
}
