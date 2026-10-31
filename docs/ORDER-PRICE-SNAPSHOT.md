# 주문 시점 가격 보존

신규 데모 주문은 서버 상품에서 이름·종류·이미지 파일명·단가·수량별 합계와 주문 총액을
같은 트랜잭션에 저장한다. 이후 상품 가격/이름 변경은 주문 조회 금액에 반영하지 않는다.
기존 `unitPrice`, `sumPrice`, `name`, `type`, `img` 응답 필드를 유지하며 주문 응답에
`totalPrice`와 `priceBasis`를 추가한다. 금액은 원화 정수이고 합계가 Java/MySQL INT의
범위를 넘으면 요청을 거부하고 트랜잭션을 롤백한다. 실제 결제를 구현한 것은 아니다.

| priceBasis | 의미 |
|---|---|
| ORDER_SNAPSHOT | 새 주문을 생성하며 서버가 확정한 가격 |
| LEGACY_ESTIMATE | 과거 단가를 복구할 수 없어 마이그레이션 때 현재 상품 값으로 고정한 추정값 |

과거 주문은 실제 결제 내역으로 표시하지 않는다. 이미 사라진 상세는 복구할 수 없고,
상세가 하나도 남지 않은 과거 주문의 `totalPrice`는 `null`이다. 초기 `data.sql` 데모 주문도
LEGACY_ESTIMATE로 명시한다. 새로운 주문 생성은 클라이언트가 보낸 가격을 받지 않는다.

## 기존 DB 전환

초기화된 새 폐기 DB는 `data.sql`을 사용한다. **기존 DB에 data.sql을 다시 실행하지 않는다.**
애플리케이션은 마이그레이션을 자동 실행하지 않는다. 이전 스키마에서 새 애플리케이션을
실행하기 전에 아래 절차를 진행한다.

1. 기존 DB를 백업하고 별도 복제/폐기 DB에서 복원과 전환을 검증한다.
2. 주문 생성과 상품 변경을 포함한 애플리케이션 쓰기를 중단한다.
3. 현재 DB가 스냅샷 도입 전 스키마인지 확인하고 아래 사전 검사가 모두 0행인지 확인한다.
   SQL mode에 `STRICT_TRANS_TABLES` 또는 `STRICT_ALL_TABLES`가 있어야 한다.
4. [001-order-price-snapshot.sql](migrations/001-order-price-snapshot.sql)을 한 번 실행한다.
   `mysql --force`로 오류를 무시하지 않는다. MySQL DDL은 암시적으로 커밋하므로 전체 파일을
   일반 트랜잭션처럼 되돌릴 수 없다. 실패하면 중간 스키마/데이터를 점검하고 검증된 백업으로
   복구하거나 완료된 단계부터 검토해 재개한다. 파일 전체 재실행은 중복 컬럼 오류로 중단된다.
5. 상세 수·주문 수, 합계와 priceBasis를 확인한 뒤 새 애플리케이션을 기동한다.

```sql
SELECT @@SESSION.sql_mode;
SELECT d.d_id
FROM t_order_detail d LEFT JOIN t_product p ON p.id = d.product_id
WHERE p.id IS NULL OR d.quantity <= 0 OR p.price < 0
   OR CAST(p.price AS SIGNED) * d.quantity > 2147483647;
SELECT d.order_id
FROM t_order_detail d JOIN t_product p ON p.id = d.product_id
GROUP BY d.order_id
HAVING SUM(CAST(p.price AS SIGNED) * d.quantity) > 2147483647;
```

전환 이후 상품 가격을 바꿔도 신규/전환 주문의 저장 금액이 달라지지 않아야 한다.
상품 변경과 주문 스냅샷 사이에는 SELECT FOR UPDATE로 획득한 InnoDB 행 잠금을
트랜잭션 종료까지 유지한다. 서버 가격을 잠금 조회하고 합계를 검사한 뒤 주문수 UPDATE를 수행한다.
서로 다른 상품마다 가격 캡처 SELECT가 추가되므로 신규 주문 쓰기의 DB 왕복 비용이 늘어난다.
배치 잠금 조회나 INSERT SELECT는 대안이지만 검증/예외 위치와 기존 쓰기 후 실패 롤백 경계를
함께 검토해야 한다. 이번 변경은 정합성 수정이며 지연 개선으로 주장하지 않는다.

상품/회원 삭제로 인한 행 소실은 [BE02 삭제 이력 정책](ORDER-HISTORY-PRESERVATION.md)과
002 전환을 적용해야 보존된다. 001만 적용한 DB의 CASCADE를 가격 스냅샷만으로 막을 수는 없다.
전체 중복/동시 주문 BE06은 별도 작업이다.
