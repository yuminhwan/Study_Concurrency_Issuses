package com.example.stock.service.synchronize;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.stock.domain.Stock;
import com.example.stock.domain.StockRepository;
import com.example.stock.service.LockTest;
import com.example.stock.service.pessimisticlock.PessimisticLockStockService;

@SuppressWarnings("NonAsciiCharacters") // 한글 경고 무시
@LockTest
class SynchronizedStockServiceTest {

    @Autowired
    private PessimisticLockStockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @BeforeEach
    void setUp() {
        Stock stock = new Stock(1L, 100L);

        stockRepository.saveAndFlush(stock);
    }

    @AfterEach
    void tearDown() {
        stockRepository.deleteAllInBatch();
    }

    @Test
    void 재고를_감소한다() {
        // given
        // when
        stockService.decrease(1L, 1L);

        // then
        Stock stock = stockRepository.findById(1L).get();
        assertThat(stock.getQuantity()).isEqualTo(99L);
    }

    @Test
    void 동시에_100건의_요청() throws InterruptedException {
        // given
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    stockService.decrease(1L, 1L);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // then
        Stock stock = stockRepository.findById(1L).get();
        assertThat(stock.getQuantity()).isZero();
    }
}
