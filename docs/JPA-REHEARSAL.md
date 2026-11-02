# PostgreSQL·JPA 세션/매장·상품 읽기·쓰기 제공자

`tools/jpa-rehearsal`은 PostgreSQL18.6에 복사한 데이터로 실제 로그인·세션·매장 CRUD·상품/특성 읽기를
실행하고 상품/특성 쓰기와 같은 TX의 version/outbox/delivery를 생산하는 별도 Java17 애플리케이션이다. 기본 `./scripts/dev.sh`와 통합 로컬 스택은 현재
MySQL/MyBatis API를 계속 실행한다. 이 제공자를 전체 서비스나 DB03 전환 완료로 표시하지 않는다.

## 제공하는 계약

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/auth/login` | 기존 LoginRequest·해시 검증·세션 교체, UserResponse |
| POST | `/api/auth/logout` | 현재 세션 무효화 |
| GET | `/api/auth/me` | 현재 ID/epoch·역할을 다시 읽은 본인 응답 |
| GET | `/api/markets` | 기존 PageCriteria/PageResponse·ID순 목록 |
| GET | `/api/markets/{id}` | 기존 매장 응답·없는 ID 404 |
| POST | `/api/admin/markets` | 현재 관리자만 생성, 기존 201 응답 |
| PUT | `/api/admin/markets/{id}` | 현재 관리자만 변경, 기존 boolean 응답 |
| DELETE | `/api/admin/markets/{id}` | 현재 관리자만 삭제, 기존 boolean 응답 |
| GET | `/api/products` | 기존 AND 필터·정렬·페이지·items/count |
| GET | `/api/products/popular` | 기존 주문수 내림차순·ID tie-break·최대12개 |
| GET | `/api/products/{id}` | 기존 ProductResponse 일곱 필드·없는 ID404 |
| GET | `/api/products/{id}/features` | 기존 특성 여덟 필드·unknown/검수[]·없는 상품404 |
| POST | `/api/admin/products` | 현재 관리자만 생성·201·같은 TX의 최초 이벤트 |
| PUT | `/api/admin/products/{id}` | 현재 관리자만 전체 수정·boolean·후속 버전 |
| DELETE | `/api/admin/products/{id}` | 현재 관리자만 삭제·이력 FK null·tombstone |
| PUT | `/api/admin/products/{id}/features` | 엄격 입력·특성/태그 전체 교체·같은 TX 이벤트 |
| POST | `/api/orders` | 본인/관리자 주문·중복 수량 정규화·금액/상품 스냅샷·스탬프/원자 이벤트 |
| GET | `/api/users/{userId}/orders` | 본인/관리자 페이지·내림차순 헤더와 일괄 상세 |
| GET | `/api/orders/{id}` | 본인/관리자 단건·삭제된 회원 주문은 관리자만 |
| GET | `/api/admin/orders` | 현재 관리자 전체 페이지·보존된 삭제 이력 |
| PATCH | `/api/admin/orders/{id}/complete` | 현재 관리자 완료 처리·없는 주문404 |
| DELETE | `/api/admin/orders/{id}` | 현재 관리자 삭제·상세/스탬프 행 cascade |

매장 DTO는 `id/name/latitude/longitude/image`를 유지한다. 등록되지 않은 취향·찜·사용자 관리
경로는 404이며 가짜 성공 응답을 제공하지 않는다. 실제 `/v3/api-docs`의 API 경로는
위 스무 개/스물두 작업이고 `/docs`는 문서 UI다. 세션은 메모리 저장이므로 프로세스 재시작 뒤 재로그인해야 한다.

원래 매장 Controller·요청/응답 DTO·ResponseMapper·서비스와 인증 AccessGuard·PasswordHasher·
UserService를 소스 whitelist로 함께 컴파일한다. 원래 UserRestController는 미제공 주문 서비스까지
필요하므로 세 인증 경로의 얇은 `JpaSessionController`만 둔다. 업무 로직/DTO를 복사하거나 주문
서비스 stub를 등록하지 않는다. 공통 DAO 인터페이스의 실제 MyBatis `@Param` 타입은 컴파일에만
사용하고 repackage에서 명시 제외한다. 실행 JAR에는 MyBatis/MySQL 드라이버가 없다.

상품/특성은 원래 ProductRestController·ProductFeatureRestController·ProductServiceImpl·
ProductFeatureServiceImpl·ProductSearchOutbox를 소스 whitelist로 재사용한다. 이전 네 GET의
얇은 Controller는 제거했다. 실제 JPA DAO/Repository와 기존 Criteria DTO 조회를 연결하며
루트 MySQL 구현을 수정하거나 미구현 쓰기 bean을 등록하지 않는다. 특성 GET만 짧은 RR override로
앞선 메타/태그 snapshot 계약을 보존한다.

## 영속성과 잠금

- `JpaUser/JpaMarket/JpaProduct/JpaProductFeature/JpaProductTag`와 Spring Data Repository가 일반 생성·변경·삭제를 담당한다. 읽기는
  JPQL projection에서 기존 mutable DTO로 옮기며 엔티티를 HTTP에 노출하지 않는다.
- 문자열 사용자 ID는 `Persistable`의 신규 상태와 `@PostLoad/@PostPersist`로 insert-only를
  구분한다. 단순 `save`의 merge로 같은 ID·대소문자 동등 ID를 덮어쓰지 않는다.
- 매장 identity는 PostgreSQL에서 생성하고 `saveAndFlush` 뒤 기존 DTO에 돌려준다. 없는 행의
  변경/삭제는 0, 실제 변경은 1로 서비스 계약을 유지하며 SQL 오류를 0/성공으로 바꾸지 않는다.
- 세션 projection은 매 요청의 ID+epoch로 읽는다. DTO의 `withoutPass()`가 영속 hash를 지우지
  않으며 사용자 현재 상태를 엔티티 캐시에서 잘못 재사용하지 않는다.
- 비밀번호 점진 전환은 ID+epoch+이전 hash의 정확한 CAS로 처리한다. 변경/삭제 뒤 같은 ID가
  생겨도 이전 자격이 새 계정을 바꾸지 않는다.
- 사용자 수명 보호는 native `FOR UPDATE`를 유지한다. 현재 Hibernate PostgreSQL translator의
  일반 잠금은 `FOR NO KEY UPDATE`여서 FK의 `KEY SHARE`를 막지 않는다. 이 차이를 숨기지 않고
  실제 별도 연결 잠금으로 검증한다. 일반 CRUD/조회 전체를 native SQL로 옮기지는 않는다.
- 상품 목록은 Criteria constructor projection으로 기존 DTO만 반환한다. 동적 AND 조건과
  태그별 EXISTS를 items/count 양쪽에 적용해 태그로 행/개수를 증식시키지 않는다. 지원 sort와
  ID tie-break, int OFFSET 범위·페이지 정규화는 [기존 검색 계약](PRODUCT-SEARCH.md)과 같다.
- 상품 identity는 DB에서 생성한다. 특성·태그의 할당 PK는 Persistable 신규 상태로 persist하고,
  전체 태그 교체는 flush된 bulk DELETE 뒤 persistence context를 비워 같은 키의 오래된 엔티티를
  재사용하지 않는다. 특성의 product-left-join scalar projection은
  특성 행이 없는 기존 상품도 unknown으로 반환하며 known 플래그가 false인 잔존 태그는 숨긴다.
  검수된 태그는 기존 Java 문자열 정렬을 유지한다. 엔티티·lazy 연관을 JSON으로 직렬화하지 않는다.
- 도수 bound만 float32로 맞추고 REAL 상품 값은 반올림하지 않는다. 이름/type의 설치된 ICU
  collation과 PostgreSQL18의 [nondeterministic LIKE](https://www.postgresql.org/docs/18/functions-matching.html)를
  그대로 사용한다. 명시 backslash escape로 기존 `%`/`_` pattern을 보존하며 ILIKE/임의 lower/정규화로
  바꾸지 않는다. 고정 case/악센트/한글 검증은 모든 Unicode MySQL collation 동등성의 증명이 아니다.
- 상품 items/count와 특성 메타/태그는 각각 짧은 read-only REPEATABLE READ snapshot에서 읽는다.
  실제 별도 연결의 삽입/특성 교체 commit을 두 SELECT 사이에 넣어 같은/다음 요청을 구분한다.
  다른 페이지·다음 요청의 최신 상태까지 동결하거나 외부 검색/추천 IO를 이 트랜잭션에 넣지 않는다.

- 상품/특성 쓰기는 같은 상품의 full native FOR UPDATE로 직렬화한다. PostgreSQL 기본 Hibernate
  잠금으로 약화하지 않으며 FK KEY SHARE와 실제 충돌하는지 확인한다. version은 충돌 안전 INSERT,
  BIGINT 상한의 원자 증가와 잠금 조회로 처리한다. 삭제 후 같은 ID가 새로 생겨도 version은 남는다.
- 공통 producer의 승인 projection·unknown/null/검수[]·정렬을 그대로 사용한다. outbox는 bound String을
  `CAST(... AS json)`으로 저장해 원문을 보존하고 같은 TX에서 PENDING을 INSERT한다. 업무/특성/태그·
  version·event·delivery 중 어느 단계가 실패해도 전체 rollback한다. 삭제의 FK SET NULL/태그 cascade도
  같은 경계다. relay/ACK·OpenSearch 쓰기는 이 모듈에 등록하지 않는다.

주문은 원래 OrderRestController/OrderServiceImpl과 요청/응답 DTO를 재사용한다. 실제
Order/OrderDetail/Stamp Repository와 DTO projection을 연결하며 상품 잠금·가격/수량 정수 계산·
product count/version/event/PENDING·주문 총액·스탬프/회원 합계가 같은 TX에 속한다.
상세는 상품을 다시 join하지 않고 주문 스냅샷을 읽으며 페이지마다 한 IN 질의로 묶는다.
주문 완료는 completed만 바꾸고, 삭제는 한 affected-row DELETE로0/1을 판정한다.
삭제의 detail/stamp cascade와 사용자 stamps/상품 count를 되돌리지 않는 기존 동작을 유지한다.
DB 생성 시각은 Instant에서 기존 Timestamp/Date DTO로 옮기고 nullable 과거 값도 보존한다.
최근1/6개월 DAO는 기존 문장 시각 의미를 statement_timestamp로 보존하며 외부 TX 시작 시각으로 고정하지 않는다.
새 주문도 Hibernate의 DB 생성 표현식 statement_timestamp()를 사용해 INSERT 문장 시각을 보존한다.
기존 V1 default는 변경하지 않으며 JVM/요청의 시각을 저장하지 않는다.

Hibernate는 실제 사용한 User/Market/Product/Feature/Tag/Order/OrderDetail/Stamp 매핑의 `ddl-auto=validate`, OSIV off다. Flyway는 기존 V1/V2 checksum을
**validate만** 한다. migrate/clean/repair/baseline·자동 SQL 초기화는 실행하지 않는다. 모든 업무표의
JPA 매핑을 검증했다거나 스키마 검사만으로 전체 업무 동등성이 확보됐다고 해석하지 않는다.

## 별도 폐기 DB에서 실행

먼저 [snapshot/import 도구](SOURCE-IMPORT.md)로 소유한 빈 대상에 복사하고 검증한다. 기존
사용자 DB를 재설치/초기화하지 않는다. 이 제공자는 numeric loopback의 `*_ci` 데이터베이스,
현재 marker, import receipt와 relay의 동일 source UUID가 필요하다. 검증 wrapper는 컨테이너
ID·label·loopback publish도 확인한다. 다른 source나 target을 자동 채택하지 않는다.

실제 앱에는 소유 migrator와 **다른 runtime 역할**을 사용한다. LOGIN 외 superuser/createdb/
createrole/replication/bypassrls·역할 membership·schema CREATE·객체 소유권·TRIGGER/TRUNCATE를
거절한다. 현재 허용 범위는 다음과 같다.

- `pubburi` USAGE, User/Market SELECT·INSERT·UPDATE·DELETE, 매장 sequence USAGE.
- marker·Flyway history·import receipt·relay source binding SELECT.
- 주문 SELECT·INSERT·DELETE와 `UPDATE(completed,total_price,order_table)`, 상세 SELECT·INSERT·DELETE,
  스탬프 SELECT·INSERT, 주문/상세/스탬프 sequence USAGE.
- 상품/특성 SELECT·INSERT·UPDATE·DELETE, 태그 SELECT·INSERT·DELETE와 `UPDATE(tag)`만.
- version SELECT·INSERT·UPDATE, outbox/delivery SELECT·INSERT, 상품/outbox sequence USAGE.
- 태그 표 전체 UPDATE·product_id/kind UPDATE, version DELETE·outbox/delivery UPDATE/DELETE,
  모든 업무표 TRIGGER/TRUNCATE와 상품/outbox sequence SELECT/UPDATE는 기동 guard에서 거절한다.

태그의 한 컬럼 UPDATE 권한은 PostgreSQL의 [행 잠금 SELECT 요구사항](https://www.postgresql.org/docs/18/sql-select.html)이다.
태그 교체 구현은 계속 DELETE/INSERT이며 이 권한을 이벤트나 전달 ACK까지 넓히지 않는다.

주문의 소유자/유형/시각/출처·PK UPDATE, 상세 UPDATE와 스탬프 UPDATE/DELETE, 세 sequence의
SELECT/UPDATE도 기동 guard에서 거절한다. 사용자 삭제의 메모 정리는 order_table 권한을 함께 사용한다.
마이그레이션/실패 trigger는 별도 migrator/control 연결에서만 수행한다. runtime 역할의 실제
업무 schema CREATE/ALTER/DROP 거절도 확인하며 non-super라는 이름만으로 최소 권한을 주장하지 않는다.

공개 CA와 비밀 환경을 로컬에서 준비한 뒤 예시의 변수명을 사용한다. 비밀번호를 argv·Git·공개
로그에 넣지 않는다. `tools/jpa-rehearsal/.env.example`은 값이 없는 안내이며 자동 로드하지 않는다.

~~~bash
./mvnw --batch-mode --no-transfer-progress -f tools/jpa-rehearsal/pom.xml verify
java -jar tools/jpa-rehearsal/target/pubburi-jpa-rehearsal-0.0.1-SNAPSHOT.jar
~~~

`PUBBURI_JPA_REHEARSAL=1`, PG URL/DB/USER/PASSWORD/RUN_TOKEN/CA, `PUBBURI_JPA_SOURCE_ID`를
명시한다. 서버는 127.0.0.1과 `PUBBURI_JPA_PORT`(기본0: OS 선택)를 사용한다. 기존5173/9987/8000을
재사용하지 않는다. 연결은 TLS verify-full·SCRAM-SHA-256·UTC이며 CA/hostname 검증을 끄지 않는다.
설정으로 자동 DDL·OSIV를 켜거나 명시 guard를 빼면 기동을 거절한다.

## 버전과 검증 범위

별도 모듈은 Boot3.5.16, Spring Data JPA3.5.13, Hibernate6.6.53.Final을 실제 resolve했다.
Java17을 지원하는 [Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html)과
[BOM](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)을 따른다.
Tomcat10.1.61/Jackson BOM2.21.8/JDBC42.7.14/Flyway13.12.0을 명시하고 기존 springdoc2.8.9의
실제 문서 생성을 확인한다. 기본 API POM은 변경하지 않는다. Hibernate6.6의
[제한 유지보수 상태](https://hibernate.org/orm/releases/6.6/)를 전사 지원/취약점0으로 표현하지 않는다.
잠금 선택의 근거는 [실제 translator](https://github.com/hibernate/hibernate-orm/blob/6.6.53/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLSqlAstTranslator.java)와
[PostgreSQL 행 잠금](https://www.postgresql.org/docs/18/explicit-locking.html)이다.

필수 CI는 기존 MySQL HTTP·검색·snapshot/import 검사와 함께 새 PG 두 DB에서 실제 이 제공자를
실행한다. 안전한 요약만 보존하고 통합 XML/원문 SQL·서버 로그/자격·복사 artifact는 업로드하지
않는다. 실제 조건·실패·회귀의 상세 근거는 [최신 상품 쓰기 기록](plans/2026-11-02-jpa-product-writes.md)에 둔다.
검증 HTTP는 연결·쓰기·헤더·본문에 요청 소유 절대 기한을 적용하며 cookie를 유지한다.
각 요청은 최대6초와 전체 남은120초 중 짧은 예산을 사용한다. 실행 supervisor는 작업 종료 뒤에도
소유 session의 식별을 유지해 자식 정리 후 leader를 reap한다. 정상/오류·SIGTERM·기한 초과는
같은 정리 경로를 사용하며 startup/cleanup 시간은 HTTP 지연 성능 수치가 아니다.
group 종료 signal은 비동기이므로 테스트는 소유 listener의 해제를 제한 시간 안에 관찰한다.
기한 뒤 점유와 다른 bind 오류는 계속 실패하며 supervisor wait를 모든 자식의 즉시 회수로 해석하지 않는다.

주문/후반 단계 rollback·동시성·시각/권한·실행 JAR의 검증은 [주문 기록](plans/2026-11-02-jpa-order-transactions.md)을 따른다.
남은 DB03은 후기·계정 관리·취향/찜·추천 연동 등 다른 업무 DAO/API의 실제 JPA 이전과
전체 MyBatis runtime 제거다. 이번 DB05 증분은 상품 변경의 원자 producer이며 Psycopg3 relay·
lease/UUID/ACK·검색 대체는 별도다. 전체 거래/웹 인수와 cutover/rollback도 DB04–DB06에서 검증한다.
이 실제 부분 제공자의 성공을 전체 전환·운영 성능 개선으로 확대하지 않는다.
