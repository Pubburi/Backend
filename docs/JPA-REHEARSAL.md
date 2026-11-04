# PostgreSQL·JPA 계정/프로필·매장·상품·주문·후기 제공자

`tools/jpa-rehearsal`은 PostgreSQL18.6에 복사한 데이터로 실제 로그인·계정/프로필·매장 CRUD·상품/특성 읽기를
실행하고 상품/특성 쓰기와 같은 TX의 version/outbox/delivery를 생산하는 별도 Java17 애플리케이션이다. 기본 `./scripts/dev.sh`와 옵션을 생략한 통합 로컬 스택은 현재
MySQL/MyBatis API를 계속 실행한다. 이 제공자를 전체 서비스나 DB03 전환 완료로 표시하지 않는다.

준비된 copied PG는 [명시 local release 실행](LOCAL-DEVELOPMENT.md#준비된-postgresql의-명시-실행패키징)으로
패키징/세 서버에 연결할 수 있다. installer/import·기본 스택 전환과 구분하며 기존 runtime 최소 권한을 유지한다.

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
| POST | `/api/users` | 기존 가입 검증·USER/0스탬프·새 hash/epoch·201 |
| GET | `/api/users/id-available` | 기존 ID 사용 여부·설치된 문자열 비교 |
| GET | `/api/users/{id}/profile` | 본인/관리자 현재 사용자·최근10주문·등급 |
| GET | `/api/admin/users` | 현재 관리자 사용자 페이지·공개 DTO |
| PUT | `/api/admin/users/{id}` | 잠금 뒤 생략 필드 유지·자격 변경 시 epoch 회전 |
| DELETE | `/api/admin/users/{id}` | 본인 삭제409·타계정 수명 정리/주문 이력 보존 |
| GET | `/api/products/{productId}/comments` | 공개 후기·ID 내림차순 페이지·없는 상품은 빈 페이지 |
| POST | `/api/comments` | 현재 사용자 작성·단정도 평점/본문·201·원본 next ID 보존 |
| PUT | `/api/comments/{id}` | 작성자/현재 관리자만 평점·본문 수정 |
| DELETE | `/api/comments/{id}` | 작성자/현재 관리자만 삭제·없는 후기404 |
| GET | `/api/admin/comments` | 현재 관리자만 전체 후기 페이지 |
| GET | `/api/users/me/favorites` | 본인만 최신 추가순 현재 상품 페이지·짧은 RR snapshot |
| GET | `/api/users/me/favorites/{productId}` | 한 SQL의 현재 epoch/관계 상태·없음 false/만료401 |
| PUT | `/api/users/me/favorites/{productId}` | 본인 저장·중복은 최초 시각 유지·없는 상품404 |
| DELETE | `/api/users/me/favorites/{productId}` | 본인 해제·없는 관계/삭제 상품도200 false |
| GET | `/api/users/me/preferences` | 본인 epoch의 헤더/값 RR·미입력/null/[]/0 구분 |
| PUT | `/api/users/me/preferences` | 본인 전체 교체·skip/reset·사용자 잠금/epoch 재검증·원자 rollback |
| GET | `/api/recommendations/default` | 공개 비개인 B0·현재 첫1000 ID순/배치 재조회·장애/삭제 시 현재 DB fallback |
| PUT | `/api/users/me/profile` | 본인 이름만 변경·현재 역할/스탬프/자격 보존·잠금 뒤 epoch 재확인 |
| PUT | `/api/users/me/password` | 본인 현재 암호 확인·새 hash/epoch·현재 세션 CAS 유지/다른 기존 세션 회수 |
| POST | `/api/users/me/search-phrases` | 본인 현재 취향·별도 검색 선택만 전달·정확 조건/수명·장애 구분 |
| POST | `/api/users/me/recommendations/content` | 본인 현재 취향·검수 container-DEMO B1/R04·현재 상품/특성·필수조건/수명 재확인 |

매장 DTO는 `id/name/latitude/longitude/image`를 유지한다. 등록되지 않은 다른 추천 등의
경로는 404이며 가짜 성공 응답을 제공하지 않는다. 실제 `/v3/api-docs`의 API 경로는
위 서른일곱 개/마흔네 작업이고 `/docs`는 문서 UI다. 세션은 메모리 저장이므로 프로세스 재시작 뒤 재로그인해야 한다.

원래 매장 Controller·요청/응답 DTO·ResponseMapper·서비스와 인증 AccessGuard·PasswordHasher·
UserService를 소스 whitelist로 함께 컴파일한다. 원래 UserRestController·가입/관리 요청과
GradeService도 같은 실제 User/Order Repository에 연결한다. 초기 세 인증 경로의 얇은
JpaSessionController는 제거하고 원래 Controller 한 곳에서 인증/계정 계약을 제공한다.
공통 DAO 인터페이스의 실제 MyBatis `@Param` 타입은 컴파일에만
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

계정 프로필은 현재 사용자 DTO·최근10주문/일괄 상세·기존 GradeService 결과를 반환한다.
hash/epoch는 공개 DTO에 넣지 않는다. 관리자 생략 필드는 기존 사용자 전체 잠금 뒤 해석하고
비밀번호를 명시한 경우에만 hash/epoch를 바꾼다. 본인 비밀번호 수정은 해당 변경의 epoch만
현재 세션에 반영하고 다른 세션은 회수한다. 사용자 삭제는 메모 정리→계정 삭제와 FK의
개인 자식 cascade/주문 참조 null을 같은 TX로 처리한다. 새 권한/테이블은 추가하지 않는다.

후기는 원래 CommentRestController/CommentServiceImpl·요청/응답 DTO와 실제 Comment Repository를
연결한다. 평점은 기존0.5–5.0 범위의 float32/REAL이며0.5 단위나 정수로 반올림하지 않는다.
HTTP 본문은 NotBlank/최대200자 검증을 유지하고, 과거 nullable 본문은 GET에서 그대로 읽는다.
JPQL DTO projection은 페이지·count·ID 내림차순을 유지한다. UPDATE는 rating/comment만 바꾸고
DELETE는 affected-row 수를 반환해 없어진 행을 다시 만들지 않는다. 부모 사용자/상품 삭제의
FK cascade와 full FOR UPDATE/KEY SHARE 관계는 실제 별도 연결로 검증한다.
후기 쓰기는 상품/회원 합계나 검색 producer를 호출하지 않는다.

찜은 원래 FavoriteRestController/FavoriteServiceImpl·Product7 DTO와 실제 복합 PK entity/Repository를
연결한다. 관리자도 자신의 관계만 읽는다. 단건은 epoch로 제한한 사용자와 EXISTS를 한 SQL에서
읽어 만료 null/관계 없음 false를 구분한다. 페이지는 기존 RR에서 사용자·count·현재 상품을 읽는다.
쓰기의 User→Product full FOR UPDATE/epoch 재검증은 기존 서비스를 재사용한다. INSERT만 명시
ON CONFLICT DO NOTHING과 statement_timestamp()를 사용해 중복의 최초 시각을 유지하고 신규는
MySQL 문장 시각을 보존한다. favorite UPDATE/sequence 권한은 없으며 상품/회원/검색 기록을 바꾸지 않는다.

명시취향은 원래 UserPreferenceRestController/UserPreferenceServiceImpl을 사용한다. 사용자 LEFT JOIN
헤더 projection과 epoch 값 projection을 기존 RR에서 읽어 활성 계정의 미입력과 만료를 구분한다.
기존 사용자 full FOR UPDATE 뒤 헤더 Repository find/saveAndFlush·값 bulk DELETE/insert-only
Persistable 저장을 같은 TX로 묶는다. 헤더에 불필요한 native upsert나 값 UPDATE 권한을 추가하지 않는다.
응답은 원래 DTO이며 생략 unknown·known[]·0·soft/hard·skip/reset과 정규화/정렬을 유지한다.

공개 기본 목록은 원래 DefaultRecommendationController/Service·RecommendationConfig·BaselineHttpClient를
연결한다. 기존 JPA의 ID순 제한 후보/현재 ID 배치 projection을 사용하므로 새 GRANT는 없다.
원격 대기 밖의 짧은 읽기 TX·Product7·size/후보 상한·공개 비개인 표시·현재 상품/삭제 fallback과
전송/본문 제한을 유지한다. 운영자 `PUBBURI_RECOMMENDATION_BASE_URL`의 기본은 원래와 같은
`http://127.0.0.1:8000`이며 세션/취향/찜·이름/가격을 전송하지 않는다.

본인 검색 문구는 공통 SearchPhraseController/Service와 같은 HTTP client를 사용한다.
canonical 취향 RR snapshot의 commit 뒤 원격을 호출하고 현재 snapshot/epoch·세션을 다시 확인한다.
명시 soft 선택과 모든 hard·ordered query pairs를 consumer에서 검증하며 로컬 문구 fallback은 없다.
새 DAO/권한 없이 [본인 문구 호출 계약](SEARCH-PHRASES.md)의400/401/409/500·UNAVAILABLE를 유지한다.

본인 이름·비밀번호 쓰기도 원래 Controller/서비스와 기존 UserDao를 사용한다. 사용자 전체 잠금
뒤 관측 epoch를 다시 확인하며 이름은 자격을 유지하고, 현재 암호 불일치는403·무변경이다.
새 암호는 literal4–100/UTF-8 최대72바이트를 저장 전에 확인하고 기존 hasher와 새 epoch를 쓴다.
현재 암호는 짧은 기존 값도 허용한다. 같은 변경 결과의 epoch만 AccessGuard에서 세션 CAS로
반영하며 동시 로그아웃/다른 로그인 상태를 복원하지 않는다. 새 DB 권한/스키마는 없다.
같은 공유 코드의 기본 MySQL 공급·실제 경합/rollback은 [F09 공급 기록](plans/2026-11-03-self-account-settings.md)과 구분한다.

Hibernate는 실제 사용한 User/Market/Product/Feature/Tag/Order/OrderDetail/Stamp/Comment/Favorite/Preference/PreferenceValue 매핑의 `ddl-auto=validate`, OSIV off다. Flyway는 기존 V1/V2 checksum을
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
- 후기 SELECT·INSERT·DELETE와 `UPDATE(rating,comment)`, 후기 identity sequence USAGE.
- 찜 SELECT·INSERT·DELETE만. 표/어느 컬럼의 UPDATE·TRIGGER/TRUNCATE는 거절하며 sequence를 추가하지 않는다.
- 명시취향 헤더 SELECT·INSERT와 상태/known3·목표/범위5의 아홉 컬럼 UPDATE만, 값 SELECT·INSERT·DELETE만.
  헤더 DELETE/user_id UPDATE·값 UPDATE·두 표 TRIGGER/TRUNCATE는 거절하며 sequence를 추가하지 않는다.
- 태그 표 전체 UPDATE·product_id/kind UPDATE, version DELETE·outbox/delivery UPDATE/DELETE,
  모든 업무표 TRIGGER/TRUNCATE와 상품/outbox sequence SELECT/UPDATE는 기동 guard에서 거절한다.

태그의 한 컬럼 UPDATE 권한은 PostgreSQL의 [행 잠금 SELECT 요구사항](https://www.postgresql.org/docs/18/sql-select.html)이다.
태그 교체 구현은 계속 DELETE/INSERT이며 이 권한을 이벤트나 전달 ACK까지 넓히지 않는다.

주문의 소유자/유형/시각/출처·PK UPDATE, 상세 UPDATE와 스탬프 UPDATE/DELETE, 세 sequence의
SELECT/UPDATE도 기동 guard에서 거절한다. 사용자 삭제의 메모 정리는 order_table 권한을 함께 사용한다.
후기의 전체 UPDATE·id/user_id/product_id UPDATE·TRIGGER/TRUNCATE와 sequence SELECT/UPDATE도 거절한다.
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
[계정/프로필 검증](plans/2026-11-03-jpa-account-profile.md)은 전체 사용자 화면 인수와 구분한다.
[후기 검증](plans/2026-11-03-jpa-comments.md)은 단정도·소유권·실패/경합·부모 수명과 실행 JAR을 다룬다.
검증 도구의 PUBBURI_JPA_EXPECTED_COMMENT_NEXT는 승인 snapshot의 sourceNext.t_comment를 사용한다.
[찜 검증](plans/2026-11-03-jpa-favorites.md)은 본인 수명·멱등성·RR/자격 경합·실패와 실행 JAR을 다룬다.
[명시취향 검증](plans/2026-11-03-jpa-preferences.md)은 두 Repository·전체 교체/실패·RR/계정 수명과 실행 JAR을 다룬다.
[기본 목록 검증](plans/2026-11-03-jpa-default-recommendations.md)은 실제 B0 전송·현재 상품/삭제·DB 오류/fallback 경계를 다룬다.
남은 DB03은 필요한 다른 업무 연동과
전체 MyBatis runtime 제거다. [공개 검색 증분](plans/2026-11-04-public-search-fallback.md)은
기존36경로/43작업을 늘리지 않고 목록 GET의 opt-in 엔진 읽기·현재 RR ID/total 대조·같은 필터
DB 대체를 연결한다. source ID는 실제 target SELECT를 재사용하고 원격 대기는 TX 밖이다.
별도 Psycopg3 relay/CLI가 전달·ACK를 맡으며 공개 앱은 엔진/ACK를 쓰지 않는다.
전체 거래/웹 인수와 cutover/rollback도 DB04–DB06에서 검증한다.
이 실제 부분 제공자의 성공을 전체 전환·운영 성능 개선으로 확대하지 않는다.

[본인 B1 caller](CONTENT-RECOMMENDATIONS.md)는 기존 canonical 취향/상품과 특성 batch projection으로
명시 검수 container-DEMO binding을 RR 전후 대조한다. 기존36경로/43작업에 이 private POST만
추가한37경로/44작업이며 원격 I/O는 TX 밖이다. 새 schema/GRANT·기본 runtime/cutover는 없다.
