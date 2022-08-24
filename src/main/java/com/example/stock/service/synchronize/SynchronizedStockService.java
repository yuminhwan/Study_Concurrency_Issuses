package com.example.stock.service.synchronize;

import org.springframework.stereotype.Service;

import com.example.stock.domain.Stock;
import com.example.stock.domain.StockRepository;

@Service
public class SynchronizedStockService {

    private final StockRepository stockRepository;

    public SynchronizedStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /**
     * Stock 조회
     * 재고 감소
     * 저장
     */
    // @Transactional
    public synchronized void decrease(Long id, Long quantity) {
        Stock stock = stockRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("재고가 존재하지 않습니다."));

        stock.decrease(quantity);
        stockRepository.saveAndFlush(stock);
    }
}
