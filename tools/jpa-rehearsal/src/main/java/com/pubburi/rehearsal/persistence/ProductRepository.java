package com.pubburi.rehearsal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<JpaProduct, Integer> { }
