package com.example.stock.domain;

import javax.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, Long> {

    @Lock(value = LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.id = :id")
    Stock findByIdWithPessimisticLock(@Param("id") Long id);

    @Lock(value = LockModeType.OPTIMISTIC)
    @Query("SELECT s FROM Stock s WHERE s.id = :id")
    Stock findByIdWithOptimisticLock(@Param("id") Long id);
}
