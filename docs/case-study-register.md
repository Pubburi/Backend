# Backend 문제 해결 사례 등록부

이전 사례 기준 소스는 main `defb45a`, BE01 수정 기준은 `a563c369`, BE02 수정 기준은 `56a76d1`, BE03 수정 기준은 `0a3ddeec`다. README에는 실제 현재 구현에 근거한 대표 사례 **4개**를 유지한다.
BE01 가격 스냅샷에 이어 BE02의 삭제 이력 분리와 개인정보 정리를 구현하고 Java 검증·패키징,
Python 도구·저장소 검사와 실제 HTTP/DB 전후·전환을 검증했다. [BE01 계획](plans/2026-10-31-order-price-snapshot.md)과
[BE02 계획](plans/2026-10-31-order-history-preservation.md)에 각 기준·범위·근거를 구분한다.
BE03은 현재 계정/권한 확인과 세션 자격 버전·재가입 경계를 구현하고 실제 HTTP 전후·003 전환과
표준 smoke를 확인했다. [BE03 계획](plans/2026-11-01-session-authority.md)의 구현/검증과 향후 PR/main 게이트를 구분한다.
개인별 구현 기여나 관측하지 않은 과거 장애를 추정하지 않는다. 소스 출처 기록은 PROVENANCE.md에 보존한다.

## 현재 선정

| ID | README 사례 | 채용 관점의 선정 이유 | 근거 범위 | 실측 성능 |
|---|---|---|---|---|
| BE-CS01 | 주문 페이지와 상세 일괄 조립 | 일대다 조회에서 페이지 경계·DB 왕복·메모리 조립의 선택을 설명한다. | 서비스·SQL 및 기존 가짜 DAO 검증 | 미측정 |
| BE-CS02 | 같은 ID 재가입·권한 변경의 기존 세션 구분 | 계정 인스턴스와 자격 수명, 실권한·표시·DB 실패·세션 경합을 구분하고 재발급의 버전 경계를 설명한다. | 실제 MySQL 8.4/불변 jar HTTP 전후·003 전환, 제어한 단위 인터리빙 | 독립 경계 각각 1/1→0/1은 정확성; 성능 미측정 |
| BE-CS03 | 중복 주문 상품 정규화 | 입력 단위와 저장·집계 단위의 차이, 여러 쓰기의 책임 경계를 드러낸다. | 서비스·SQL 및 기존 가짜 DAO 검증 | 미측정 |
| BE-CS04 | 가격·참조 삭제에서 주문 이력 분리 | 업무 기록과 활성 상품/회원의 수명·잠금·스냅샷·nullable/권한·개인정보·FK 전환 판단을 연결한다. | 실제 MySQL 8.4/불변 jar HTTP 전후·기존 스키마 전환 | 가격 3/3→0/3, 상세 2/3→0/3·주문 유실 3/3→0/3은 각 실험의 정확성; 성능 미측정 |

주문 읽기(BE-CS01)와 쓰기(BE-CS03)는 같은 클래스 안에 있지만 각각 페이지 단위의 관계 조립과 입력/쓰기 경계라는 독립 문제다. 단순히 변경 줄 수를 나눠 사례 수를 채우지 않는다.

## 근거와 한계

### BE-CS01

- 구현: [OrderServiceImpl.attachDetails](../src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java), [order.xml](../src/main/resources/mappers/order.xml).
- 기존 근거: [OrderServiceImplTests](../src/test/java/com/pubburi/pub/model/service/OrderServiceImplTests.java)의 `pagedOrdersAttachDetailsWithOneBatchQuery`, `emptyOrderPagesSkipDetailBatchQuery`. 가짜 DAO에서 ID 전달·상세 연결·빈 페이지의 호출 생략을 확인한다.
- 경계: 가짜 DAO 호출 횟수는 실제 DB 쿼리 실측이나 응답 시간 전후 값이 아니다. 데이터 규모를 통제한 MySQL 측정 없이 N배 개선·메모리 절감·처리량 향상을 기재하지 않는다.
- 추가 확보: 기준 구현과 비교 구현, 주문당 상세 분포, 페이지 크기, 캐시·워밍업·반복·표본 제외 기준을 먼저 고정한 뒤 쿼리와 지연·메모리 분포를 비교한다.

### BE-CS02

- 구현: [AccessGuard](../src/main/java/com/pubburi/pub/controller/auth/AccessGuard.java), [SessionUser](../src/main/java/com/pubburi/pub/controller/auth/SessionUser.java), [잠금 안의 수정/버전 캡처](../src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java), [UserUpdateResult](../src/main/java/com/pubburi/pub/model/dto/UserUpdateResult.java), [계정 조회/CAS SQL](../src/main/resources/mappers/user.xml), [003](migrations/003-session-auth-epoch.sql).
- 실제 근거: 같은 초기 업무 시드의 실제 MySQL 8.4/JDK 17에서 강등 후 관리자 API 허용, 삭제 계정 인증, 동일 ID 재가입의 dormant 쿠키 접근, 비밀번호 변경 후 구세션 허용을 각각 1/1→0/1로 확인했다. 별도 표시 시나리오의 stale name/stamps도 1/1→0/1이다. [원값·SHA·재현·전환](measurements/2026-11-01-session-authority.md).
- 전환: 기존 회원 6/주문 3/상세 4와 사용자 기존 필드 바이트를 보존했다. 6 distinct epoch·NULL 0·NOT NULL을 확인했고, 비밀번호/epoch 원문은 근거에 공개하지 않았다. CI 기본 collation 새 폐기 DB에서 표준 smoke·본인 비밀번호 수정 현재 세션 유지/다른 기존 세션 401을 확인했다.
- 제어한 경합 근거: [AccessGuardTests](../src/test/java/com/pubburi/pub/controller/auth/AccessGuardTests.java)의 잠금 전/커밋 뒤 자격 회전, 프로필 갱신 epoch 미채택·로그인 전환/로그아웃·DB 실패 보존과 [UserSessionRefreshTests](../src/test/java/com/pubburi/pub/controller/rest/UserSessionRefreshTests.java)의 컨트롤러 사후 재조회 금지. 가짜 DAO 인터리빙을 실제 MySQL 동시 부하 결과로 바꾸지 않는다.
- 경계: 각각 N=1의 로컬 고정 정확성 결과다. 신규 로그인/구 비밀번호 차단은 기존에도 동작한 대조 조건이고, 이를 새 성과로 세지 않는다. 전체 요청의 인증과 쓰기를 계정 삭제와 원자적으로 묶거나 모든 in-flight 작업을 취소한 것은 아니다. Guard DB 읽기/수정 잠금 비용·분산 세션·전체 동시성은 미측정/미완료다.
- 남은 근거: 실제 DB 동시 실행·실패 주입을 BE06/C04의 업무 경계와 맞춰 확장하고 요청별 조회 비용을 성능 조건부터 정해 측정한다. 구형 해시 형식 종료 정책·실제 DB의 재해시 경합은 별도 확인하며 보안 형식 전환을 지연 개선으로 표현하지 않는다.

### BE-CS03

- 구현: [OrderServiceImpl.normalizeDetails/makeOrder](../src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java), [상품 수량 증가](../src/main/resources/mappers/product.xml), [사용자 스탬프 증가](../src/main/resources/mappers/user.xml), [상세 일괄 삽입](../src/main/resources/mappers/orderDetail.xml).
- 기존 근거: [OrderServiceImplTests](../src/test/java/com/pubburi/pub/model/service/OrderServiceImplTests.java)의 `makeOrderAggregatesDuplicateProductsAndIncrementsStampsAtomically`, `missingProductStopsOrderBeforeDetailsAndRewardsAreCreated`. 중복 수량·DAO 전달 값·예외 이후 진행 중단을 확인한다.
- 경계: 메서드 이름의 Atomically와 가짜 DAO 증가 결과는 실제 DB 원자성·롤백·동시성 실증이 아니다. `@Transactional` 선언과 SQL 증가식은 구현 사실로만 설명한다.
- 추가 근거: [실제 HTTP 검증](../scripts/api_smoke.py)에서 폐기용 MySQL의 성공 주문과 헤더/첫 상품 쓰기 후 없는 상품 오류의 롤백을 확인했다. 이 경로에서 주문 목록·상품 주문수·회원 스탬프가 변경 전과 같은지 검증한다. 상세 결과는 테스트 계획/PR에 기록하며 새 성능 수치나 별도 README 사례로 집계하지 않는다.
- 미완료: 스탬프 저장 이후를 포함한 나머지 단계 실패/롤백·동시·중복 재시도(BE06). 부분 검증을 전체 과제 완료로 확대하지 않는다.

### BE-CS04

- 구현: [OrderServiceImpl.makeOrder](../src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java), [FOR UPDATE](../src/main/resources/mappers/product.xml), [저장 스냅샷 조회](../src/main/resources/mappers/order.xml), [가격 전환](migrations/001-order-price-snapshot.sql), [삭제 FK 전환](migrations/002-order-history-preservation.sql), [회원 메모 정리·삭제](../src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java).
- 확인한 결과: 같은 초기 데이터의 두 상품 가격을 각각 1,000원 올리는 세 주문 시나리오에서 금액 불변 위반 3/3→0/3, 최대 금액 차이 3,000원→0원. 신규 단건/목록 DTO와 헤더/라인 합계 일치. [측정 원값·재현·무효 시도](measurements/2026-10-31-order-price-invariance.md)를 참고한다.
- 전환: 주문 6개·상세 8개 보존, 기존 주문은 LEGACY_ESTIMATE. 파일 전체 재실행은 중복 컬럼 오류로 중단되고 행 수/총액 추가 변화 없음. 자동 멱등 마이그레이션으로 포장하지 않는다.
- BE02 결과: 기준 56a76d의 동일 세 주문에서 상품 A 삭제→회원 삭제를 순차 관찰했다. 상품 상세 보존 위반 2/3→0/3(상세 4→2가 4→4로 변경), 이어 회원 삭제의 주문 유실 3/3→0/3(주문 3→0이 3→3으로 변경). 의도된 productId/userId/회원 메모 null은 오류로 세지 않는다. [삭제 측정 원값·조건](measurements/2026-10-31-order-history-preservation.md).
- BE02 전환: 첫 실제 1826 FK 이름/동일 ALTER 실패를 기록하고 공식 MySQL 제약을 확인해 제거/추가를 분리·새 이름으로 수정했다. 실제 재검증에서 기존 주문 3개·상세 4개·59,100원과 SET NULL을 보존했다.
- 한계: 로컬 세 주문의 단계별 정확성 실험이며 실사용자 장애율·성능 결과가 아니다. 이 실험 시점 BE03 세션 회수는 후속이었으며 현재 결과는 별도 BE-CS02/측정 문서를 따른다. 추가 SELECT/삭제 잠금 비용·전체 동시성은 남아 있다. 과거 이미 삭제된 행을 복구했다고 주장하지 않는다.

### BE01 교체 결정

기존 BE-CS04 로컬 실행기 소유권은 프로세스 수명/TOCTOU를 다룬 유효한 사례이며 [이전 작업 기록](plans/2026-10-31-local-automation.md)과 [PR #2](https://github.com/Pubburi/Backend/pull/2)에 보존한다. 이번 가격 사례는 상품 수정이 과거 업무 금액을 바꾸는 실제 결함을 DB/HTTP로 재현하고, 잠금·스냅샷·추정 자료의 의미·비파괴 전환과 원값 비교까지 연결했다. 프로젝트의 DB/거래 포트폴리오 우선 기준에서 더 직접적인 업무 영향을 설명하므로 이 한 항목을 교체한다. 최신성·줄 수·테스트 총수로 선정하지 않았다. 페이지 조립·인증 전환·입력 정규화의 세 사례는 서로 다른 문제이므로 유지해 총 네 개다.

### BE02 보강 비교

삭제 이력은 가격 스냅샷만으로 보장되지 않음을 실제 FK/HTTP에서 확인했다. soft-delete와 nullable FK의 보관/조회 비용을 비교하고, 회원 자유 입력 정리·재가입 소유권·중간 DDL 실패를 같은 업무 이력의 수명 경계로 설명할 근거가 늘었다. BE-CS04를 가격 단독 사례에서 이 이력 보존 사례로 보강하고 기존 입력 정규화·페이지 조립·인증 사례는 유지한다. 같은 주문 이력을 두 슬롯으로 늘리지 않으며 총 네 개다. BE01 가격 실험과 BE02 순차 삭제 실험은 기준 커밋과 분모가 달라 측정 원값을 따로 관리한다.

### BE03 교체 결정

기존 BE-CS02의 SHA-256→BCrypt 점진 전환은 원문 없는 저장 데이터의 전환 시점을 설명한 유효한
사례이며 기준 소스 `0a3ddeec`의 README와 해시/서비스 기록에 보존한다. 이번에는 강등·삭제·
동일 ID 재생성 뒤 구세션 접근을 실제 DB/HTTP에서 재현해 업무 영향이 더 직접적이다. 단순 ID
확인과 전체 세션 색인을 비교하고, 잠금 안/뒤 버전 경계·공개 계약·DB 실패·수동 전환 비용까지
연결한다. 첫 수정이 순차 HTTP를 통과해도 재발급 경합을 놓쳤음을 기록하고 제어한 인터리빙으로
수정 범위를 좁힌 증거도 있다. 이 실제 영향·원인 분석·대안 판단·구현/재현 근거가 더 강하므로
기존 인증 슬롯 하나만 교체한다. 해시 지원 자체는 유지하고 다른 세 사례/순서는 보존했다.
최신성·코드량·테스트 총수로 순위를 정하거나 같은 인증 문제를 다섯 번째로 늘리지 않는다.

## 교체 후보와 개선 순서

### embedded Tomcat 보안 패치 비교

[패치 계획](plans/2026-11-01-tomcat-security-patch.md)은 공식 영향 조건과 실제 runtime/jar의
버전을 대조하고 소량 multipart header의 라이브러리 수용/상한 거부를 확인했다. 실사용자 침해나
대량 DoS·메모리 성능을 재현한 것은 아니다. 현재 인증/거래의 실제 업무 결함·상태 경계보다 더
강한 교체 근거가 없어 README 네 사례와 순서를 유지한다. 취약점 건수·테스트 수로 선정하지 않는다.

### 계정 찜 증가분 비교

[계정 찜 계획](plans/2026-11-01-account-favorites.md)은 현재 상품 페이지·반복 저장 순서·고유키와
계정 인스턴스/삭제 수명을 다룬다. 잘못된 POST가 catch-all에서 500이 되는 경계도 단위 재현해
405/Allow로 좁혔다. 실제 여섯 세션 중복 저장의 관계 1행과 삭제 뒤 0행·제어한 사용자 행 잠금의
동일 ID 재생성 뒤 옛 PUT 401도 확인했다. 신규 기능의 상태 설계와 프로토콜 회귀는 유효하지만, 실제 기존 권한 회수·
금액/이력 유실을 재현한 네 사례보다 더 강한 교체 근거는 없다. 네 사례와 순서를 보존한다.
반복/동시 검증 횟수나 API 추가 자체를 성능·실사용자 인기 성과로 바꾸지 않는다.

[상세용 상태 GET](plans/2026-11-01-account-favorite-state.md)은 같은 계정 인스턴스와 EXISTS를
한 SQL에서 읽는 추가 계약이다. 전체 목록을 사용하지 않는 설계 사실을 실측 성능 개선으로
승격하지 않으며 기존 네 사례를 교체할 더 강한 근거로 보지 않는다.

### 본인 전용 명시취향 증가분 비교

[명시취향 계획](plans/2026-11-01-explicit-user-preferences.md)의 저장 경계는 실제 사용자 행 잠금에서
Guard 뒤 DELETE/동일 ID 재가입을 제어해 구 PUT 401·새 계정 NOT_STARTED/저장 0행을 확인했다.
기존 BE-CS02의 계정 수명 문제에 유효한 보강 자료지만 새 API의 방어 설계이며, 기존에 관측한
세션 결함 전후보다 강한 교체 사례로 포장하지 않는다. README 네 사례와 순서를 유지한다.

### 상품 특성 증가분 비교

[C03/BE04/F03 계획](plans/2026-11-01-product-feature-contract.md)의 상품 특성 API는 unknown과
검수한 빈 태그·공개 출처·권한·전체 교체/삭제 관계를 실제 코드로 다룬다. 신규 API 검증 중 실제
OpenAPI 500과 라이브러리 호환·생성 명세 불일치, 숫자/boolean의 문자열 태그 변환을 발견해 수정했다.
실제 17개 잘못된 입력의 상태 보존·DB 쓰기 중 실패 rollback·삭제 정합성도 확인했지만, 현재 네 사례보다 강한 업무 영향/
상태 경계·실측 개선이 확보됐다는 근거는 없다. 기존 네 사례와
순서를 보존한다. 이전에 없던 GET의 404→200을 오류 감소로 포장하거나 스키마/API 설명만으로
인증/거래 사례를 밀어내지 않는다. 후속 DB 실패·동시 변경의 실제 문제 해결 근거를 얻으면 다시 비교한다.

| 후보 | 확보할 근거 | 현재 사례와 비교할 기술 판단 |
|---|---|---|
| 세션·업무 경계 동시성 확장 | 실제 DB 동시 요청·자격 변경/로그아웃 순서와 권한/쓰기 결과 | BE-CS02의 제어한 인터리빙 경계를 실제 DB 재현으로 강화할 수 있는지 |
| BE06 실제 주문 롤백·동시성 | 실제 MySQL 실패 주입·중복/동시 요청·원자성 결과 | 가짜 DAO에 한정된 BE-CS03을 더 강한 실제 데이터 근거로 교체할 수 있는지 |
| 주문 조회 최적화 실측 | 동일 데이터·캐시·빌드·부하의 전후 원값과 분포 | BE-CS01의 구현 설명을 실제 병목 해소 근거로 강화할 수 있는지 |

새 후보가 현재 사례보다 채용 관점에서 강하면 해당 항목만 즉시 교체한다. 네 개가 모두 모일 때까지 기다리지 않고 한 번의 실행에서 교체 수를 제한하지 않는다. 원인 규명 증거·영향·대안 판단·구현 기여·재현 결과를 비교해 이유를 계획에 남기며, 최신성·코드량·테스트 수로 우수성을 점수화하지 않는다.
README 코드는 실제 현재 함수와 링크를 동기화하고, 성능 전후 값이 없으면 수치나 빈 성과표를 추가하지 않는다. 노션 필수 기능 완료 뒤에도 기존 기능 범위의 결함 재현·원인 분석·수정·측정 과제를 계속 발굴한다.
