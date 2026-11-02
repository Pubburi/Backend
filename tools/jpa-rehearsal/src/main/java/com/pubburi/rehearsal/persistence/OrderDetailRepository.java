package com.pubburi.rehearsal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderDetailRepository extends JpaRepository<JpaOrderDetail, Integer> { }
