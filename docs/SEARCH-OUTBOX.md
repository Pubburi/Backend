# 상품 검색 변경 기록의 생산 경계

상품 생성·기본 수정·특성 전체 교체·데모 주문의 상품 주문 수 증가·상품 삭제는 기존 업무
트랜잭션 안에서 검색 변경 기록을 함께 쓴다. `ProductSearchOutbox`는
[MANDATORY 전파](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/annotation/Propagation.html#MANDATORY)를 사용한다.
별도 트랜잭션/afterCommit 이벤트나 엔진 HTTP를 사용하지 않는다. 기록 저장이 실패하면 해당
상품·태그·주문·스탬프 변경도 실패한다. `/api` 응답·상품 검색의 MySQL 경로는 유지한다.
[명시 실행 relay](SEARCH-RELAY.md)는 개별 claim·ack/retry를 제공한다. 기본 기동에 자동으로
추가하지 않으며 재색인 세대 전환·retention과 공개 검색은 아직 구현하지 않았다.

## 버전과 투영

- `t_product_search_version`의 상품별 signed BIGINT가 정본이다. 상품 row → 현재 특성 header/tags
  → 버전 row 순서로 잠근다. 여러 상품을 다루는 기존 업무의 교착 가능성이 사라진다는 뜻은 아니다.
- 버전을 1씩 증가시키고 `(product_id, source_version)` UNIQUE인 outbox에 불변 JSON을 INSERT한다.
  version의 0은 최초 트랜잭션 안의 중간 상태이며 성공 이벤트는 양수다. 상한에 닿으면 업무를 거절한다.
- 같은 TX에서 해당 event의 delivery PENDING 행도 INSERT한다. 예약 실패가 업무/버전/이벤트를
  함께 rollback하며, 전송 상태 변경이 불변 payload를 수정하지 않는다.
- 삭제는 더 높은 버전·`deleted=true, document=null`이다. 두 표는 상품 FK가 없으므로 상품 삭제로
  사라지지 않는다. outbox는 version 표를 RESTRICT로 참조한다. 카운터를 지우거나 초기화하지 않는다.
  삭제 뒤 명시적으로 같은 ID를 다시 만들더라도 버전은 이어져야 한다.
- payload는 [관리 CLI](LOCAL-SEARCH.md)의 `productId/sourceVersion/deleted/document` 이벤트다.
  document는 name/type/priceKrw/abvPercent/orderCount/tasteTags/aromaTags만 포함한다.
  unknown null·검수[]·정렬한 태그를 보존하며 이미지·출처 본문·회원/주문 정보를 넣지 않는다.
- 특성도 current locking read로 읽는다. 다품목 주문의 앞선 consistent read에서 만들어진
  REPEATABLE READ snapshot으로 뒤 상품의 오래된 태그를 복사하지 않는다.
- outbox auto-increment ID는 commit 순서·전역 완료 watermark가 아니다. 서로 다른 상품의
  트랜잭션은 늦게 commit할 수 있다. relay는 개별 claim/ack와 만료 lease 재처리를 사용하며
  `MAX(event_id)`만 저장하고 낮은 ID를 영구 건너뛰면 안 된다.

기록은 승인된 **업무 서비스 쓰기**를 포괄한다. 운영자가 직접 SQL로 상품/특성을 바꾸는 것은
이 생산 경계를 우회한다. 직접 DB 쓰기·구버전 앱을 혼용하지 않고, 필요하면 쓰기를 중단한 뒤
별도 정합성 복구 계획을 세운다. 재시도한 업무 명령을 동일 명령으로 판별하는 기능은 아니므로,
같은 PUT도 새 버전을 만들 수 있다. 이것을 exactly-once 주문 처리로 표현하지 않는다.

## 007 수동 전환

1. 001–006까지 적용된 MySQL 8.4의 원본 전체와 현재 테이블/UTF-8 값을 백업·기록한다.
   앱·수동 도구 등 모든 쓰기를 중단한다. 실제 DB에 `data.sql`을 다시 실행하지 않는다.
2. [007 SQL](migrations/007-product-search-outbox.sql)을 선택한 DB에 한 번 적용한다. 새 두 표를
   만들고, 단일 DML 트랜잭션에서 현재 상품 각각에 버전1·현재 투영 이벤트1개를 backfill한다.
   기존 데이터는 UPDATE/DELETE하지 않으며 과거에 없어진 상품/변경 이력은 복원하지 않는다.
3. 기존 표의 전체 행/해시·상품 수와 새 버전/이벤트 수·null/[]·태그 값·버전1 일치를 확인하고
   [008 delivery 전환](SEARCH-RELAY.md)을 이어서 적용한 뒤 새 앱을 기동한다. 새 설치의 data.sql도
   모든 기존 시드 작성이 끝난 상태를 같은 방식으로 기록하고 delivery를 만든다.
4. [DDL implicit commit](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html) 때문에 스키마와
   backfill 전체가 하나의 rollback 단위는 아니다. 실패 시 쓰기를 계속 중단하고 생성된 새 표/
   DML commit 여부를 확인한다. `--force`/`IF NOT EXISTS`/중복 무시로 재실행하거나 같은 버전1의
   다른 내용을 덮지 않는다. DML 실패는 연결을 rollback/종료하고 새 표가 비었는지 확인한 뒤
   백업과 대조해 수동 복구한다. 앱을 이미 기동했거나 이벤트가 있으면 새 표를 버리지 않는다.

서로 다른 표현의 FLOAT 직렬화로 backfill과 Java의 표기가 달라질 수 있다. 이벤트는 당시 저장한
payload 그대로 재생하며 같은 버전을 현재 값으로 다시 구성하지 않는다. 실제 도수를 두 자리로
반올림하거나 맛을 추정하지 않는다.

## 명시적인 재생 입력

선택한 상품/버전 범위를 읽기 전용으로 검수해 최대1,000개·4MiB 이내의 batch로 내보낼 수 있다.
예시의 ID/범위는 로컬 대상에 맞춰 정한다. 결과가 없거나 상한을 넘으면 성공으로 취급하지 않는다.

~~~sql
SELECT JSON_OBJECT('schemaVersion', 1, 'dataKind', 'backend_product_events',
                   'events', JSON_ARRAYAGG(payload))
FROM (SELECT payload FROM t_product_search_outbox
      WHERE product_id = 101 AND source_version BETWEEN 1 AND 5
      ORDER BY source_version) AS chosen;
~~~

원문은 ignored 로컬 파일에만 저장하고 [명시 CLI 재생](LOCAL-SEARCH.md)을 사용한다. 별도 batch
export는 수동이며 [relay watch](SEARCH-RELAY.md)는 DB pending 행을 직접 소비한다. 응답이
불명확하면 이력을 지우거나 버전을 새로 만들지 않고 같은 payload를
재전달한다. [external version](https://docs.opensearch.org/latest/api-reference/document-apis/index-document/)과
영속 tombstone이 오래된 입력의 부활을 막는다. 이 생산 경계와 로컬 엔진의 재생 검증을 전체
검색 동기화/사용자 검색 품질·성능 완료로 합산하지 않는다.

CI의 폐기 HTTP는 직접 작성한 상품의 실제 이벤트 batch만 `.pubburi`에 만들고 다음 엔진 단계에
`PUBBURI_SEARCH_OUTBOX_INPUT`으로 전달한다. 엔진 검증은 입력을 private 경로에 고정한 뒤 CLI로
재생하고, 삭제 뒤 오래된 갱신 거절·volume 재시작을 확인한다. 공개 artifact에는 원문/receipt를
올리지 않는다. 자료 성격은 실제 사용자 이력이 아닌 허구 검증 입력이다.

실제 조건과 검증은 [생산 계획](plans/2026-11-01-product-search-outbox.md)과
[relay 계획](plans/2026-11-01-durable-search-relay.md)에 기록한다.
