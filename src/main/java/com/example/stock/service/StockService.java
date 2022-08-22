package com.example.stock.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.stock.domain.Stock;
import com.example.stock.domain.StockRepository;

@Service
public class StockService {

    private final StockRepository stockRepository;

    public StockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /**
     * Stock 조회
     * 재고 감소
     * 저장
     */
    @Transactional
    public void decrease(Long id, Long quantity) {
        Stock stock = stockRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("재고가 존재하지 않습니다."));

        stock.decrease(quantity);
        stockRepository.saveAndFlush(stock); // saveAndFlush를 하는 이유가 뭘까?? 더티 체킹으로 하면 안되나??
    }
}
