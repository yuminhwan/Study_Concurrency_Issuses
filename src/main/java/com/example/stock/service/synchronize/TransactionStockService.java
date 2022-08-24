package com.example.stock.service.synchronize;

/*
프록시 객체
 */
public class TransactionStockService {

    private final SynchronizedStockService synchronizedStockService;

    public TransactionStockService(SynchronizedStockService synchronizedStockService) {
        this.synchronizedStockService = synchronizedStockService;
    }

    public void decrease(Long id, Long quantity) {
        startTransaction();

        synchronizedStockService.decrease(id, quantity);

        endTransaction();
    }

    private void startTransaction() {
        // 트랙잭션 시작
    }

    private void endTransaction() {
        // 트랙잭션 끝 ( 커밋 )
    }
}
