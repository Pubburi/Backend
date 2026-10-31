# 상품·회원 삭제와 주문 이력 분리

상품과 회원의 물리 삭제는 유지하면서 주문과 상세의 외래키를 nullable/ON DELETE SET NULL로
바꾼다. 주문 시점 상품 표현·단가·라인 합계·총액·priceBasis는 BE01 스냅샷에서 계속 읽는다.
상품 삭제가 이미 만들어진 주문의 행을 지우거나 회원 삭제가 모든 주문을 지우지 않게 한다.

## API·개인정보 계약

| 응답 값 | 의미·처리 |
|---|---|
| OrderResponse.userId = null | 삭제된 회원과 연결이 끊긴 주문. 일반 회원은 단건 접근 불가, 관리자만 열람 |
| details[].productId = null | 삭제된 상품의 상세. snapshot name/type/img와 unitPrice/sumPrice·수량은 유지 |
| OrderResponse.orderTable = null | 회원 삭제 API가 주문의 자유 입력 요청/테이블 메모를 지움 |

삭제 참조를 0이나 가짜 ID로 치환하지 않는다. Frontend는 삭제 상태를 표시하고 해당 상품의
링크/재주문을 비활성화하되 주문 당시 이름·금액은 보여준다. 신규 주문의 상품 ID는 계속 양수이며
null/없는 상품은 거부한다. 상품·회원이 남아 있는 정상 주문의 응답 계약은 바뀌지 않는다.

회원 이름/비밀번호를 주문 스냅샷으로 복제하지 않는다. 회원 삭제 API는 같은 트랜잭션에서
해당 회원의 order_table을 먼저 지운 뒤 계정을 삭제한다. FK가 user_id를 null로 바꿔 같은 문자열
ID로 재가입해도 과거 주문이 새 계정에 연결되지 않는다. 후기·스탬프 등 기존 회원 종속 데이터의
삭제 정책은 유지한다. SQL로 계정을 직접 지우면 FK 분리는 적용되지만 자유 입력 정리는 서비스
경로를 거치지 않으므로 운영 삭제 수단으로 사용하지 않는다.

삭제 트랜잭션은 회원을 FOR UPDATE로 먼저 잠근다. 회원 FK를 잡는 새 주문이 메모 정리와
계정 삭제 사이에 끼어들지 않게 하며 삭제 결과가 기대와 다르면 예외로 종료한다.

BE03의 기존 세션 재조회/권한 회수와 재가입 세션 인증 문제는 별도 과제다. 이번 변경은
null 소유자에 대한 접근을 차단하며 모든 삭제 회원 세션을 즉시 무효화했다고 주장하지 않는다.
이미 CASCADE로 사라진 과거 행은 복구하지 못한다. 관리자가 명시적으로 주문 자체를 삭제하는
기존 API의 삭제 동작도 유지한다. 실제 결제·배송과 법정 보관 정책을 구현한 것은 아니다.

## 기존 BE01 DB 전환

새 폐기 DB는 현재 data.sql을 사용한다. 기존 DB는 [001 전환](ORDER-PRICE-SNAPSHOT.md)을
먼저 완료하고 [002-order-history-preservation.sql](migrations/002-order-history-preservation.sql)을
수동 적용한다. 애플리케이션 시작 시 자동 실행하거나 기존 DB에 data.sql을 다시 적용하지 않는다.

1. 백업/복원과 아래 변경을 별도 복제·폐기 DB에서 검증하고 애플리케이션 쓰기를 중단한다.
2. SHOW CREATE TABLE t_order / t_order_detail과 아래 조회로 FK 이름/대상·삭제 규칙을 확인한다.
   파일은 이전 fk_order_user, fk_order_detail_product를 삭제하고 새로운
   fk_order_history_user, fk_order_history_product를 추가한다. 이름/스키마가 다르면 변경 SQL을
   검토하고 자동 추정으로 다른 FK를 삭제하지 않는다. foreign_key_checks를 끄지 않는다.
3. 변경 전 주문 수·상세 수·총액 합계 및 주문별 totalPrice/라인 합계와 priceBasis를 기록한다.
   002 앞부분은 BE01 필드를 조회하므로 001이 없으면 ALTER 전에 실패한다. mysql --force를 쓰지 않는다.
4. 002를 한 번 적용한다. FK DROP과 MODIFY/ADD를 분리한 네 DDL을 사용한다. MySQL DDL은
   암시적으로 커밋하므로 전체 파일을 일반 트랜잭션처럼 되돌릴 수 없다. 중간에는 FK가 없는 상태가
   가능하므로 애플리케이션 쓰기를 재개하지 않는다. 실패 시 FK/nullable·참조 정합성을 확인하고
   백업 복구 또는 검토한 단계부터 재개하며 두 history FK를 모두 확인한 뒤에만 기동한다.
5. 새 history FK 두 개가 SET NULL, 해당 컬럼이 nullable인지 확인하고 변경 전후 개수/금액이 같은지 대조한다.
   별도 테스트 상품/회원 삭제 뒤 행·스냅샷 보존·참조 null·본인/타인 차단·관리자 조회를 검증한다.

```sql
SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND CONSTRAINT_NAME IN ('fk_order_user', 'fk_order_detail_product',
    'fk_order_history_user', 'fk_order_history_product');
SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 't_order' AND COLUMN_NAME = 'user_id')
    OR (TABLE_NAME = 't_order_detail' AND COLUMN_NAME = 'product_id'));
SELECT COUNT(*) AS orders, SUM(total_price) AS total_price FROM t_order;
SELECT COUNT(*) AS details, SUM(sum_price) AS line_total FROM t_order_detail;
```

전체 파일을 반복하면 기존 FK가 이미 없어 첫 DROP에서 오류로 중단된다. 오류를 무시하지 않고
현재 제약/컬럼 상태를 확인하며 이미 적용됐다면 재실행하지 않는다. 이 파일은 삭제나 초기화 SQL을 포함하지 않으며 원래 이력을 복원하는
도구도 아니다. 실제 삭제/마이그레이션 검증 결과는 작업 계획과 PR에 남긴다.

같은 ALTER에서 FK를 제거·추가하는 방식은 COPY 알고리즘에서 지원되지 않는다는
[MySQL 8.4 공식 제약](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)에 따라
제거와 추가를 분리했다. 이 때문에 쓰기 중단/백업과 중간 FK 누락 상태의 확인이 필요하다.
