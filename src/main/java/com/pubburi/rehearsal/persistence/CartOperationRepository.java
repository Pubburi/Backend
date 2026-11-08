package com.pubburi.rehearsal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CartOperationRepository extends JpaRepository<JpaCartOperation,JpaCartOperation.Key> { }
