package com.example.stock.service;

/*
프록시 객체
 */
public class TransactionStockService {

    private final StockService stockService;

    public TransactionStockService(StockService stockService) {
        this.stockService = stockService;
    }

    public void decrease(Long id, Long quantity) {
        startTransaction();

        stockService.decrease(id, quantity);

        endTransaction();
    }

    private void startTransaction() {
        // 트랙잭션 시작
    }

    private void endTransaction() {
        // 트랙잭션 끝 ( 커밋 )
    }
}
