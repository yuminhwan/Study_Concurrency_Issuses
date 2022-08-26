# [재고시스템으로 알아보는 동시성이슈 해결방법] 1. 들어가며

## 재고 감소 로직 구현
- 재고시스템으로 동시성 이슈 해결법을 알아보자

### Domain
```java
@Entity
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;

    private Long quantity;

    protected Stock() {
    }

    public Stock(Long productId, Long quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public void decrease(Long quantity) {
        if (this.quantity < quantity) {
            throw new IllegalArgumentException("재고가 부족합니다.");
        }
        this.quantity = this.quantity - quantity;
    }

    public Long getQuantity() {
        return quantity;
    }
}
```

```java
public interface StockRepository extends JpaRepository<Stock, Long> {
}
```

### Service

```java
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
```

### Test

```java
@SuppressWarnings("NonAsciiCharacters") // 한글 경고 무시
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class) // _부분 공백으로 처리
@SpringBootTest
class StockServiceTest {

    @Autowired
    private StockService stockService;

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
}
```

- 100의 재고에서 1개의 재고를 뺀다면 99개가 될 것이다.

## 문제점
- 지금의 테스트 케이스는 요청이 하나씩 들어올때이다.
- 만약 동시에 여러개가 들어온다면?

```java
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
```

- 동시에 재고를 1개 감소시키는 요청이 100건이 들어온다고 하면 재고는 0이 되어야 할것이다. 정말 그럴까??

![](https://i.imgur.com/1Cg2HV5.png)
- 테스트는 실패하게 되고 재고는 89개로 남아있게 된다.


### 왜 그럴까?
#### 나의 생각
![](https://i.imgur.com/Pr21upf.jpg)
- 예측해보면, 기본적인 스프릥의 트랙잭션 격리수준이 `READ_COMMITTED`이기때문에 한 스레드에서 재고를 SELECT(100)하고 감소(99)까지 하였지만 해당 트랙잭션이 아직 커밋되지 않은 상황에서 다른 스레드에서 재고를 SELECT(100)하였기 때문에 해당 감소로직이 중복되는 문제일 것 같다.
- 즉, A라는 Thread와 B라는 Thread가 있을 시 A에서 Stock을 조회하고 감소시키고 커밋하기 전에 이미 B에서 조회를 했다면 그 Stock은 100개의 수량을 가지고 있어 결국, 두번의 1 감소 로직을 거쳤지만 99의 결과를 받게 되는 것이다.


### 문제점
- 결론은 **레이스 컨디션(Race Condition)**때문이다.
    - 둘 이상의 스레드가 공유 데이터에 엑세스할 수 있고 동시에 변경하려고 할 때 발생하는 문제
![](https://i.imgur.com/XefHiPW.jpg)


## 결론 
- 트랜잭션 격리수준보단 스레드가 공유 데이터에 동시에 접근한다는 관점에서 문제점이 발생하는 것 같다. 
- 해당 문제점을 보면서 [싱글톤의 동시성 문제점](https://studyhardd.tistory.com/37?category=1006152)과 동일하게 느껴졌다. 
- 동시성 문제를 어떻게 해결할까에 대한 궁금증이 많았는 데 해당 강의를 통해 궁금증을 어느정도 해소했으면 좋겠다.

## Reference 
- [재고시스템으로 알아보는 동시성이슈 해결방법](https://www.inflearn.com/course/%EB%8F%99%EC%8B%9C%EC%84%B1%EC%9D%B4%EC%8A%88-%EC%9E%AC%EA%B3%A0%EC%8B%9C%EC%8A%A4%ED%85%9C/dashboard)

---

# [재고시스템으로 알아보는 동시성이슈 해결방법] 2. Synchronized

## 동시성 이슈 해결방법 
- 이번 시간에는 Application Level에서의 해결방법을 알아보자.


## 해결법1. Synchronized
- Java에서 지원하는 방법 ( 어플리케이션 단 )
- 해당 키워드는 한 개의 스레드만 접근이 가능하도록 해준다.

```java
@Transactional
public synchronized void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("재고가 존재하지 않습니다."));

    stock.decrease(quantity);
    
	stockRepository.saveAndFlush(stock);
}
```

`synchronized`를 추가하고 테스트를 돌려보면?



![](https://i.imgur.com/TCqolCG.png)
그래도 실패한다….. 


### 왜 그럴까?

이유는 스프링의 `@Transactional`에 있다.

스프링의 트랜잭션 어노테이션은 AOP이므로 프록시를 통해 프록시 객체를 만들어서 수행하게 된다. 

대략적으로 아래와 같은 모습으로 진행하게 된다. 

```java
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
```

- 여기서 문제점이 decrease 메서드는 `synchronized`처리를 통해 한 스레드만 접근할 수 있지만 decrease매서드가 끝남과 동시에는 다른 스레드가 접근할 수 있다.
![](https://i.imgur.com/eTg4gwJ.jpg)
- 즉, A라는 스레드가 decrease 메서드를 끝낸 후 endTransaction메서드를 수행하려 할 때 B 스레드는 decrease 메서드에 접근할 수 있게 되고 앞써 본 문제점이 동일하게 나타나 테스트가 실패하게 되는 것이다.


### 해결하려면?

- 간단하다. `@Transactional`을 없애면 된다!
- 그렇게 되면 decrease 메서드는 무조건 한 스레드만 접근할 수 있게 되니 동시성 문제가 사라지게 된다.


![](https://i.imgur.com/Adh105l.png)
- 테스트도 통과한 모습


### `@Transactional`이 없어도 동작하는 이유
- 처음에 강의에서 더티 체킹을 하지 않고 `saveAndFlush`메서드로 업데이트하길래 왜 그런가 생각했다.
- 알고보니 해당 예제를 보여주기 위해서 인 것 같다.
- 그럼 트랜잭션 어노테이션이 없더라도 업데이트가 되는 이유가 뭘까?
- 사실 `JpaRepository`의 `saveAndFlush`메서드는 자체적으로 `@Transactional`을 가지고 있다.
    - 구현체인 `SimpleJpaRepository`에서 확인할 수 있다.
	![](https://i.imgur.com/vOpI4zL.png)
	- `save` 메서드도 마찬가지!
	![](https://i.imgur.com/moEe6oP.png)
    - save에 어노테이션이 붙어있는 데 saveAll에도 붙어있는 이유는 `self invocation`때문이다.
- 그렇기에 더티체킹으로 할 경우 테스트는 실패하게 된다.

### 문제점
- 단일서버일 때는 문제가 되지 않는다.
- 만약 다중 서버라면?
    - `synchronized`는 각 인스턴스안에서만 `thread-safe`가 보장이 된다.
    - 그렇기 때문에 다중 서버일땐 레이스 컨디션이 발생하게 된다.
        - 위의 문제점에서 A,B Thread를 A,B 서버로 생각하면 된다.
- 요즘 서비스는 다중 서버를 사용하기 때문에 `synchronized`는 거의 사용되지 않는다.

## 결론 
- 자바에서 지원하는 `Synchronized` 키워드를 통해 어플리케이션 단에서 쉽게 동시성 문제를 해결할 수 있다. 
- 하지만 해당 문제는 단일 서버에서의 해결방법일뿐 다중 서버에서는 해결되지 않는다. 

## Reference 
- [재고시스템으로 알아보는 동시성이슈 해결방법](https://www.inflearn.com/course/%EB%8F%99%EC%8B%9C%EC%84%B1%EC%9D%B4%EC%8A%88-%EC%9E%AC%EA%B3%A0%EC%8B%9C%EC%8A%A4%ED%85%9C/dashboard)

---

# [재고시스템으로 알아보는 동시성이슈 해결방법] 3. Database Lock
## 동시성 이슈 해결방법 
- 이번 시간에는 Database Lock을 활용한 해결방법을 알아보자.


## 해결법2. Database ( MySQL ) 
- 데이터베이스의 락을 이용해 동시성 문제를 해결해보자


### 1. Pessimistic Lock (비관적 락)

> 자원 요청에 따른 동시성문제가 발생할 것이라고 예상하고 미리 락을 걸어버리는 방법론

- 트랜잭션의 충돌이 발생한다고 가정합니다.
- 하나의 트랜잭션이 자원에 접근시 락을 걸고, 다른 트랜잭션은 접근하지 못하도록 한다.
- 데이터베이스의 **Shared Lock**(공유, 읽기 잠금)이나 **Exclusive Lock**(배타, 쓰기잠금)을 사용한다.
    - `SELECT c1 FROM WHERE c1 = 10 FOR UPDATE` (배타 잠금)
        - 이는 레코드 락으로 인덱스 레코드에 락을 건다고 한다. 
- **Shared Lock**의 경우, 다른 트랜잭션에서 읽기만 가능하다.
    - Exclusive lock 적용이 불가능하다. ( 읽는 동안 변경하는 것을 막기 위해서 ! )
- **Exclusive lock**의 경우, 다른 트랜잭션에서 읽기,쓰기 모두 불가능하다.
    - Shared, Excluisive lock 적용이 추가로 불가능하다. ( 쓰는 동안 읽거나, 다른 쓰기를 막기 위해서 ! )


![](https://i.imgur.com/Nj0KdwQ.jpg)
- DB에 id가 1이고 name이 Yu인 컬럼이 있다고 가정해보자
- 트랜잭션 A가 Shared Lock으로 SELECT을 수행할 때 트랜잭션 B가 SELECT로 조회는 가능하다.
- 하지만 트랜잭션 A가 커밋되기 전에 트랜잭션 B의 update 요청은 Shared Lock에 의해 Blocking 되어 대기하고 있다가 트랜잭션 A가 커밋이 된다면 update를 실행하게 된다.

#### 장점
- **충돌이 자주 발생하는 환경**에 대해서는 롤백의 횟수를 줄일 수 있어 성능에서 유리하다.
- 데이터 무결성을 보장하는 수준이 높다.
#### 단점
- 데이터 자체에 락을 걸어버리기 때문에 동시성이 떨어져 성능 손해를 보게 된다.
	- 특히, 읽기가 많이 이루어지는 데이터베이스의 경우에는 손해가 크다.
- 서로 자원이 필요한 경우에, 락으로 인해 데드락이 일어날 가능성이 있다.

<br>

### 2. Optimistic Lock ( 낙관적 락 )

> 자원에 락을 걸어서 선점하지 말고, 동시성 문제가 발생하면 그때가 가서 처리 하자는 방법론> 
- 트랜잭션의 충돌이 발생하지 않는다고 가정합니다.
- 충돌이 나는 것을 막지 않고, 충돌이 난것을 감지하면 그때 처리한다.
    - 그렇기 때문에, 트랜잭션을 커밋하기 전까지는 트랜잭션의 충돌을 알 수 없다.
- 일반적으로 version의 상태를 보고 충돌을 확인하고, 충돌이 확인된 경우 롤백을 진행한다.
    - version말고도 hashcode, timestamp를 이용해서 확인도 가능하다.
- DB단에서 해결하는 것이 아닌 어플리케이션단에서 처리한다.



![](https://i.imgur.com/KJNLH9n.jpg)

- DB에 id가 1이고 name이 Yu, version이 1인 컬럼이 있다고 가정해보자
- 트랜잭션 A,B가 id가 1인 row를 SELECT한다. ( name = Yu, version = 1 )
- 이후 트랜잭션 B가 해당 row 값을 갱신한다. ( name = Hwan, version = 2 )
- 이 상황에서 만약 트랜잭션 A가 row값을 갱신하려고 한다면 version이 다르기 때문에 해당 row를 갱신하지 못하게 된다. ( 이미 version이 2로 갱신되었기 때문에 )

#### 장점
- 충돌이 안난다는 가정하에, 동시 요청에 대해서 처리 성능이 좋다.
#### 단점
- 잦은 충돌이 일어나는 경우 롤백처리에 대한 비용이 많이 들어 오히려 성능에서 손해를 볼 수 있다.
- 롤백 처리를 구현하는 게 복잡할 수 있다.
	- 개발자가 수동으로 해줘야 한다.

<br>

### 3. Named Lock
- 이름과 함께 lock을 획득한다.
- 해당 lock은 다른 세션에서 획득 및 해제가 불가능하다.
- 주의할 점으로는 트랜잭션이 종료될 때 해당 락이 자동으로 해제되지 않기 때문에 수동으로 해제를 하거나 선점시간이 끝나야 해제가 된다.
- pessimistic lock과 유사하지만 pessimistic lock은 row나 테이블단위로 락을 걸지만 named lock은 이름으로 락을 건다.


### 정리
- 낙관적 락은 락을 미리 걸지 않기 때문에 성능적으로 비관적 락보다 더 좋다. ( 충돌이 많이 나지 않는다면 )
- 하지만 충돌이 많이 예상된다면 비용이 많이 들어가는 단점이 있다.
- 그렇기 때문에 비관적락은 데이터의 무결성이 중요하고, 충돌이 많이 발생하여 잦은 롤백으로 인한 효율성 문제가 발생하는 것이 예상되는 시나리오에 좋다.
- 낙관적락은 실제로 데이터 충돌이 자주 일어나지 않을 것이라고 예상되는 시나리오에 좋다.



## 해결법2 - 적용

### 1. Pessimistic Lock (비관적 락)
- JPA에서는 `@Lock` 어노테이션을 이용해 비관적 락을 쉽게 구현할 수 있다.
    - **PESSIMISTIC_READ**
        - Shared Lock을 획득하고 데이터가 update, delete 되는 것을 방지한다.
    - **PESSIMISTIC_WRITE**
        - Exclusive Lock을 획득하고 데이터를 다른 트랜잭션에서 read,update,delete하는 것을 방지한다.
    - **PESSIMISTIC_FORCE_INCREMENT**
        - `PESSIMISTIC_WRITE`와 유사하지만 `@Version`이 지정된 Entity와 협력하기 위해 도입되어 `PESSIMISTIC_FORCE_INCREMENT`락을 획득할 시 버전이 업데이트 된다.
        

```java
@Lock(value = LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Stock s WHERE s.id = :id")
Stock findByIdWithPessimisticLock(@Param("id") Long id);
```

```java
@Service
public class PessimisticLockStockService {

    private final StockRepository stockRepository;

    public PessimisticLockStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional
    public void decrease(Long id, Long quantity) {
        Stock stock = stockRepository.findByIdWithPessimisticLock(id);

        stock.decrease(quantity);

        stockRepository.saveAndFlush(stock);
    }
}
```


![](https://i.imgur.com/azqHYwm.png)
- 테스트가 통과하는 것을 볼 수 있고 쿼리를 보면 `for update` 가 붙는 것을 확인할 수 있다.

- **PESSIMISTIC_READ**라면? ( Shared Lock )
    
	![](https://i.imgur.com/B5Ishtp.png)

    - 테스트가 실패하며 `for share` 가 붙는 것을 확인할 수 있다.
    - 쓰기는 불가능하지만 읽는 것 까진 가능하기 때문에 처음 발생한 문제가 발생하여 테스트가 실패하게 된다.
        - update시 blocking 되지만 조회한 stock의 수량이 update 되기 전 일 수 있기 때문에

#### 결론
- 잦은 충돌이 일어난다면 낙관적 락보다 성능이 우수하며 데이터 무결성을 보장하지만 별도의 락을 잡기 때문에 성능 감소가 있을 수 있다.

<br>

### 2. Optimistic Lock ( 낙관적 락 )
- JPA에서 낙관적 락을 사용하기 위해서는 `version`컬럼을 추가하고 `@Version` 어노테이션을 붙여야 한다.
- 비관적 락과 동일하게 `@Lock` 어노테이션을 사용한다.
    - **NONE**
        - 락 옵션을 적용하지 않아도 엔티티에 `@Version`이 적용된 필드가 있다면 낙관적 락이 적용된다.
        - 암시적 잠금
            - `@Version`이 붙은 필드가 존재하거나 `@OptimisticLocking` 어노테이션이 설정되어 있을 경우 자동적으로 잠금이 실행된다. ( JPA가 해줌 )
            - 추가로 삭제 쿼리가 발생할 시 암시적으로 해당 row에 대한 Exclusive Lock을 건다.
    - **OPTIMISTIC**
        - 읽기시에도 낙관적 락이 걸린다.
        - 버전을 체크하고 트랜잭션이 종료될 때까지 다른 트랜잭션에서 변경하지 않음을 보장한다.
        - 이를 통해 dirty read와 non-repeatable read를 방지한다.
    - **OPTIMISTIC_FORCE_INCREMENT**
        - 낙관적 락을 사용하면서 버전 정보를 강제로 증가시킨다.
        - 논리적인 단위의 엔티티 묶음을 관리할 수 있다.
            - 예를 들어, 양방향 연관관계에서 주인인 엔티티만 변경했을 때 주인이 아닌 엔티티는 변경되지 않았지만 논리적으로 변경되었으므로 버전을 증가시켜준다.
    - **READ, WRITE**
        - READ는 OPTIMISTIC와 같고 WRITE는 OPTIMISTIC_FORCE_INCREMENT와 같다.
        - JPA 1.0의 호환성을 유지하기 위해 존재한다.
        ![](https://i.imgur.com/V2eNizo.png)


- 발생하는 예외
    - javax.persistence.OptimisticLockException(JPA 예외)
    - org.hibernate.StaleObjectStateException(하이버네이트 예외)
    - org.springframework.orm.ObjectOptimisticLockingFailureException(스프링 예외 추상화)

```java
@Entity
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;

    private Long quantity;

    @Version
    private Long version;

		// ...
}
```

```java
@Lock(value = LockModeType.OPTIMISTIC)
@Query("SELECT s FROM Stock s WHERE s.id = :id")
Stock findByIdWithOptimisticLock(@Param("id") Long id);
```

```java
@Service
public class OptimisticLockStockService {

    private final StockRepository stockRepository;

    public OptimisticLockStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional
    public void decrease(Long id, Long quantity) {
        Stock stock = stockRepository.findByIdWithOptimisticLock(id);

        stock.decrease(quantity);

        stockRepository.saveAndFlush(stock);
    }
}
```

- 서비스 로직까지는 위와 동일하지만 비관적 락은 충돌이 발생했을 때 직접 재시도 로직을 구현해야하기 때문에 Facade 패턴을 사용해서 구현한다.

```java
@Service
public class OptimisticLockStockFacade {

    private final OptimisticLockStockService optimisticLockStockService;

    public OptimisticLockStockFacade(OptimisticLockStockService optimisticLockStockService) {
        this.optimisticLockStockService = optimisticLockStockService;
    }

    public void decrease(Long id, Long quantity) throws InterruptedException {
        while (true) {
            try {
                optimisticLockStockService.decrease(id, quantity);

                break;
            } catch (Exception e) {
                Thread.sleep(50);
            }
        }
    }
}
```

- optimisticLockStockService의 decrease 메서드를 호출하며 충돌이 일어날 경우 ( 예외가 발생한 경우 ) 50밀리초 이후 재시도 하도록 한다.


![](https://i.imgur.com/QTYP50U.png)

![](https://i.imgur.com/Hf0lTJc.png)

- 테스트가 통과하는 것을 볼 수 있고 update 쿼리를 보면 version이 조건으로 붙는 것을 확인할 수 있다.
- 또한, `ObjectOptimisticLockingFailureException` 이 발생하는 것을 확인할 수 있다.

- `@Lock` 어노테이션이 없다면?
	![](https://i.imgur.com/6pKdp6s.png)
    - `@Version`필드가 있기 때문에 자동적으로 낙관적 락이 적용된다.

- **NONE**이라면?
    
	![](https://i.imgur.com/qyWIwds.png)

    - 마찬가지로 통과한다.

#### 결론
- 별도의 락을 잡지 않으므로 낙관적 락보다 성능적으로 우수하지만 충돌이 일어날 경우 개발자가 직접 재시도 로직을 구현해야 한다.
- 또한, 충돌이 빈번하게 일어난다면 낙관적 락을 이용하는 것이 성능상 이점이 있다.
- 즉, 충돌이 빈번하게 일어나지 않는다면 비관적 락을 사용하고 빈번하게 일어난다면 낙관적 락을 사용하는 것이 좋다.


### 3. Named Lock
- 테이블 자체에 Lock을 거는 것이 아닌 별도의 공간에 Lock을 걸게 된다.
- 한 세션이 Lock을 획득한다면 다른 세션은 해당 세션이 Lock을 해제한 이후 획득할 수 있다.


![](https://i.imgur.com/SCWjOj4.jpg)

- **get_lock(str, timeout)**
    - A 세션이 1이라는 문자로 1000초동안 잠금 획득을 시도한다.
    - 만약 A 세션이 획득을 성공하면 B세션은 동일한 이름의 잠금을 획득할 수 없다.
    - get_lock을 이용한 잠금은 트랜잭션이 커밋되거나 롤백되어도 해제되지 않는다.
    - get_lock의 결과값은 1, 0, null을 반환한다.
        - 1 : 잠금 획득 성공
        - 0 : timeout 초 동안 잠금 획득 실패
        - null : 잠금 획득 중 에러가 발생했을 때
- **release_lock(str)**
    - 이름의 잠금의 해제한다.
    - 결과값으로 1,0,null을 반환한다.
        - 1 : 잠금 해제 성공
        - 0 : 잠금이 해제되지는 않았지만, 현재 쓰레드에서 획득한 잠금이 아닌 경우
        - null : 잠금이 존재하지 않을 때
    
- 현재는 예제이니 동일한 DataSource를 사용하지만 실제로 사용할 경우 분리하여 사용해야한다.
    - 같은 DataSource를 사용한다면 ConnectionPool이 부족할 수 있기 때문에 다른 서비스에 영향을 끼칠 수 있기 때문이다.
    - 별도의 JDBC를 사용하거나 등의 방법이 있다.

```java
public interface LockRepository extends JpaRepository<Stock, Long> {

    @Query(value = "SELECT GET_LOCK(:key, 3000)", nativeQuery = true)
    void getLock(@Param("key") String key);

    @Query(value = "SELECT RELEASE_LOCK(:key)", nativeQuery = true)
    void releaseLock(@Param("key") String key);
}
```

- lock을 얻기 위해 nativeQuery를 사용한다.

```java
@Component
public class NamedLockStockFacade {

    private final LockRepository lockRepository;
    private final NamedLockStockService namedLockStockService;

    public NamedLockStockFacade(LockRepository lockRepository, NamedLockStockService namedLockStockService) {
        this.lockRepository = lockRepository;
        this.namedLockStockService = namedLockStockService;
    }

    @Transactional
    public void decrease(Long id, Long quantity) {
        try {
            lockRepository.getLock(id.toString());
            namedLockStockService.decrease(id, quantity);
        } finally {
            lockRepository.releaseLock(id.toString());
        }
    }
}
```

- 락을 얻고 해제하는 로직을 위해 Facade 패턴을 사용해서 구현한다.

```java
@Service
public class NamedLockStockService {

    private final StockRepository stockRepository;

    public NamedLockStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decrease(Long id, Long quantity) {
        Stock stock = stockRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("재고가 존재하지 않습니다."));

        stock.decrease(quantity);

        stockRepository.saveAndFlush(stock);
    }
}
```

- 여기서 주의해야할 점이 StockService의 경우 부모의 트랜잭션과 별도로 실행되어야 하기 때문에 propagation을 REQUIRES_NEW로 설정해야 한다.
    - GET_LOCK()을 수행하는 쿼리가 실행 되고 트랜잭션이 종료되게 되면 pool에서 얻어온

```yaml
spring:
  datasource:
		# ...
    hikari:
      maximum-pool-size: 40
```

- 또한, 같은 DataSource를 사용하기 때문에 커넥션 풀을 늘려줘야 한다.


![](https://i.imgur.com/ANLr9PA.png)

- 테스트가 통과하는 것을 볼 수 있고 get_lock, release_lock 쿼리가 나가는 것을 볼 수 있다.


## 결론
- Named Lock은 주로 분산 락을 구현할 때 사용한다.
- 비관적 락은 timeout을 구현하기 어렵지만 Named Lock은 쉽게 구현이 가능하다.
- 그외에도 데이터 정합성을 맞춰야 할 때 사용할 수 도 있다.
- 하지만 이 방법은 트랜잭션 종료 시 락 해제와 세션 관리를 잘 해줘야 하기 때문에 주의해야 하며 실제로 구현방법이 복잡할 수 있다.
- 데이터베이스 Lock에 대해 어떤 원리로 이루어지고 어떻게 동작하는 지 깊게는 아직 모르겠지만 어느정도 흐름을 알 수 있었다. Lock에 대해 깊게 파보고 정리하는 시간을 가져봐야 겠다. 
	- 어렵긴 하다.....


## Reference 
-  [재고시스템으로 알아보는 동시성이슈 해결방법](https://www.inflearn.com/course/%EB%8F%99%EC%8B%9C%EC%84%B1%EC%9D%B4%EC%8A%88-%EC%9E%AC%EA%B3%A0%EC%8B%9C%EC%8A%A4%ED%85%9C/dashboard)

- [MySQL 트랜잭션과 락 - InnoDB 락, 이렇게 동작한다!](https://jeong-pro.tistory.com/241)

- [낙관적(Optimistic) 락과 비관적(Pessimisitc)락](https://unluckyjung.github.io/db/2022/03/07/Optimistic-vs-Pessimistic-Lock/)

- [Mysql innoDB Lock, isolation level과 Lock 경쟁](https://taes-k.github.io/2020/05/17/mysql-transaction-lock/)

- [[데이터베이스] MySQL 트랜잭션 격리 수준](https://steady-coding.tistory.com/562)
- [MySQL을 이용한 분산락으로 여러 서버에 걸친 동시성 관리 | 우아한형제들 기술블로그](https://techblog.woowahan.com/2631/
- [[MySQL] User Level Lock (GET_LOCK, RELEASE_LOCK)](https://moonsiri.tistory.com/114)
- [DB 트랜잭션과 커넥션 이해하기](https://jiwondev.tistory.com/163)

---

# [재고시스템으로 알아보는 동시성이슈 해결방법] 3. Redis Distributed Lock

## 동시성 이슈 해결방법 
- 이번 시간에는 Redis Distributed Lock을 활용한 해결방법을 알아보자.

## 해결법3. Redis

### Lettue
![](https://i.imgur.com/Cwk86nK.jpg)

- setnex 명령어를 활용한 분산락
    - **setnex**
        - 데이터베이스에 동일한 key가 없을 경우에만 저장
        - 반환값 : 1, 0
            - 1 : 성공
            - 0 : 실패
- **spin lock** 방식으로 실패시 처리로직을 구현해야한다.
    - 다른 스레드가 lock을 소유하고 있다면 그 lock이 반환될 때까지 계속 확인하며 기다리는 것


### Redission

![](https://i.imgur.com/jV7kQCa.jpg)

- pub-sub 기반으로 Lock 구현 제공
- 실패에 따른 처리 로직을 구현할 필요가 없다.

#### pub-sub인데 채널은 어떻게 정해지지?

![](https://i.imgur.com/qdtIsPn.png)
- tryLock으로 락을 획득할 때 subscribe가 진행된다.

![](https://i.imgur.com/gp7gnd2.png)
![](https://i.imgur.com/cdVCqFv.png)
![](https://i.imgur.com/jrqlpwf.png)
채널 이름은 Redis의 정보에서 뭔가 가져와서 하는 것 같은 데 정확히는 모르겠다. 좀 더 찾아봐야 할듯 !


<br>


## 해결법3 - 적용
### 1. Lettue

![](https://i.imgur.com/6qLqBQU.png)
- 먼저 간단하게 redis-cli로 setnx을 사용해보자.
- 처음 setnx를 통해 키가 1이고 값이 lock인 값을 저장하려고 하면 키가 1인 값이 없기 때문에 저장하고 반환값으로 1을 받게 된다.
- 하지만 그 다음 다시 setnx를 통해 저장하려고 하면 이미 해당 키로 저장이 되어있기 때문에 반환값으로 0을 받으며 저장을 할 수 없게 된다.
- 이러한 방식을 통해 lock을 사용하며 MySQL의 Named Lock와 유사한 점이 있다.
    - 차이점이라면 Session을 신경쓰지 않아도 되는 것이다.


```groovy
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

```java
@Component
public class RedisLockRepository {

    private final RedisTemplate<String, String> redisTemplate;

    public RedisLockRepository(RedisTemplate<String, String> redisTemplate) {![[Untitled 11.png]]
        this.redisTemplate = redisTemplate;
    }

    public Boolean lock(Long key) {
        return redisTemplate.opsForValue()
            .setIfAbsent(generateKey(key), "lock", Duration.ofMillis(3_000));
    }

    public Boolean unlock(Long key) {
        return redisTemplate.delete(generateKey(key));
    }

    private String generateKey(Long key) {
        return key.toString();
    }
}
```

- 강의에서는 RedisTemplate를 사용했는 데 StringRedisTemplate를 사용해도 무방하다.
    - 또한, `localhost:6379` 라면 따로 Redis설정을 하지 않아도 Spring에서 자동적으로 잡게 된다.
- Lettuce 방식도 실패 로직에 대해 구현을 해줘야 하기 때문에 Facade 패턴을 적용한다.

```java
@Component
public class LettuceLockStockFacade {

    private final RedisLockRepository redisLockRepository;
    private final StockService stockService;

    public LettuceLockStockFacade(RedisLockRepository redisLockRepository, StockService stockService) {
        this.redisLockRepository = redisLockRepository;
        this.stockService = stockService;
    }

    public void decrease(Long id, Long quantity) throws InterruptedException {
        while (!redisLockRepository.lock(id)) {
            Thread.sleep(100); // 부하를 줄이기 위한 텀
        }

        try {
            stockService.decrease(id, quantity);
        } finally {
            redisLockRepository.unlock(id);
        }
    }
}
```

- lock을 획득하는 과정에서 Redis에게 부하가 있을 수 있으니 100ms 이후에 다시 요청하도록 텀을 준다.

![](https://i.imgur.com/4HGHrti.png)

- 이후 테스트를 해보면 통과하는 것을 확인할 수 있다.
- Lettuce를 사용하면 구현이 간단하다는 장점이 있지만 spin lock방식이므로 Redis에 부하를 줄 수 있다.
    - 그렇기 때문에 lock 획득 재시도 간에 텀을 주도록 하였다.

<br>

### 2. Redisson

> Lettuce와 같은 자바 레디스 클라이언트이며 Netty를 사용해서, 비동기 논블록킹 I/O를 제공하는데, 특이하게도 레디스의 명령어를 직접 제공하지 않고 Lock과 같은 특정한 구현체의 형태를 제공한다.

![](https://i.imgur.com/Ia5mmUK.png)

- 먼저 간단하게 redis-cli로 pub-sub을 사용해보자.
- `subscribe 채널명` 명령어로 구독을 하고 다른 쪽에서 `publish 채널명 메시지` 명령어로 메시지를 보내면 구독을 한 곳에서 메시지를 받는 것을 확인할 수 있다.
- redisson은 자신이 점유하던 lock을 해제할 시 채널에 메시지를 보내줌으로써 락을 획득해야하는 스레드들에게 락을 획득하라고 전달해주게 된다.
- 그러면 lock을 획득해야하는 스레드들은 메시지를 받을 때 락 획득을 시도한다.
- Lettuce는 lock획득을 계속 시도하는 반면 Redisson은 락 해제가 되었을 때나 그외 몇번만 시도하기 때문에 Redis에 부하를 줄여주게 된다.

```groovy
implementation 'org.redisson:redisson-spring-boot-starter:3.17.5'
```

```java
@Component
public class RedissonLockStockFacade {

    private static final Logger log = LoggerFactory.getLogger(RedissonLockStockFacade.class);

    private final RedissonClient redissonClient;
    private final StockService stockService;

    public RedissonLockStockFacade(RedissonClient redissonClient, StockService stockService) {
        this.redissonClient = redissonClient;
        this.stockService = stockService;
    }

    public void decrease(Long key, Long quantity) {
        RLock lock = redissonClient.getLock(key.toString());

        try {
            boolean available = lock.tryLock(5, 1, TimeUnit.SECONDS);

            if (!available) {
                log.info("락 획득 실패");
                return;
            }

            stockService.decrease(key, quantity);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
}
```

- Redisson은 별도의 Repository가 필요없고 RedissonClient를 사용하면 된다.
![](https://i.imgur.com/CebSsei.png)

- Lua Script를 사용해서 자체 TTL를 적용하는 것을 확인할 수 있다.
	- `hincrby`는 해당 필드가 없으면 increment값을 설정한다.
	- `perpire`는 지정된 시간 후 key를 자동 삭제하게 된다.
		- 여기서는 lock을 삭제할 때 사용하는 것 같다.
- `tryLock(long waitTime**,** long leaseTime**,** TimeUnit unit)`
    - 락을 사용할 수 있을 때 까지 waitTime 시간까지 대기
    - leaseTime 시간 동안 락을 점유하는 시도
    - leaseTime 시간이 지나면 자동으로 락이 해제
    - 즉, 선행 락 점유 스레드가 존재한다면 waitTime동안 락 점유를 기다리며 leaseTime 시간 이후로는 자동으로 락이 해제되기 때문에 다른 스레드도 일정 시간이 지난 후 락을 점유할 수 있다.


### 단, 장점
- pub-sub 기반으로 redis의 부하를 줄여준다는 장점이 있지만 Lettuce에 비해서 별도의 라이브러리 사용과 구현이 복잡하다는 점이 있다.
      

### 정리
-   Lettuce
    -   구현이 간단하며 spring-data-redis를 이용하면 Lettuce가 기본이기 때문에 별도의 라이브러리를 추가하지 않아도 된다.
    -   하지만 spin lock 방식이기 때문에 동시에 많은 스레드가 lock 획득 대기 상태라면 redis에 부하가 갈 수 있다.
-   Redisson
    -   락 획득 재시도를 기본으로 제공한다.
    -   pub-sub 방식으로 구현이 되어있기 때문에 Lettuce와 비교했을 때 Redis에 부하가 덜 간다.
    -   하지만 별도의 라이브러리 추가가 필요하며 lock을 라이브러리 차원에서 제공하는 것이기 때문에 사용법을 공부해야 한다.
-   실무에선?
    -   재시도가 필요하지 않는 lock은 Lettuce
    -   재시도가 필요한 경우에는 redisson


## MySQL과 Redis 간단 비교
-   MySQL
    -   이미 데이터베이스로 MySQL를 사용하고 있다면 별도의 비용없이 사용이 가능하다.
    -   어느정도의 트래픽까지는 문제없이 활용이 가능하다.
    -   Redis보다는 성능이 좋지 않다.
-   Redis
    -   활용중인 Redis가 없다면 별도로 구축해야하기 때문에 인프라 관리비용이 발생한다.
    -   MySQL보다 성능이 좋다.

## 마무리 
- 동시성을 처리하는 방법을 총 3가지 배웠다. 
	- Application 단 
	- Database 단 
	- Redis 단 
- 각각 마다 장,단점이 달라 상황에 맞는 방황을 사용하면 될 것 같다. 
- 이전부터 동시성은 어떻게 제어할 지 궁금하고 속으로 끙끙앍고 있었는 데 이런 강의를 만나게 되어서 너무 기쁘다. 
- 하지만 대략적인 개념들만 파악했기에 깊이 있게 공부해봐야겠다.
	- 프로젝트에서도 Redis를 사용했기 때문에 Redis도 정리해봐야 겠다.


## Reference 
-  [재고시스템으로 알아보는 동시성이슈 해결방법](https://www.inflearn.com/course/%EB%8F%99%EC%8B%9C%EC%84%B1%EC%9D%B4%EC%8A%88-%EC%9E%AC%EA%B3%A0%EC%8B%9C%EC%8A%A4%ED%85%9C/dashboard)
- [[redis] redisson을 통한 분산 락](https://kkambi.tistory.com/196)
- [Redisson 분산락을 이용한 동시성 제어](https://velog.io/@hgs-study/redisson-distributed-lock)