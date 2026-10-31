# Backend 문제 해결 사례 등록부

이전 사례 기준 소스는 main `defb45a`, 이번 BE01 수정 기준은 `a563c369`다. README에는 실제 현재 구현에 근거한 대표 사례 **4개**를 유지한다.
이번에는 주문 시점 스냅샷을 구현하고 Java 검증·패키징, Python 도구·저장소 검사를 실행했다.
실제 DB 전후·마이그레이션 검증은 [BE01 계획](plans/2026-10-31-order-price-snapshot.md)에 상태와 근거를 구분해 기록한다.
개인별 구현 기여나 관측하지 않은 과거 장애를 추정하지 않는다. 소스 출처 기록은 PROVENANCE.md에 보존한다.

## 현재 선정

| ID | README 사례 | 채용 관점의 선정 이유 | 근거 범위 | 실측 성능 |
|---|---|---|---|---|
| BE-CS01 | 주문 페이지와 상세 일괄 조립 | 일대다 조회에서 페이지 경계·DB 왕복·메모리 조립의 선택을 설명한다. | 서비스·SQL 및 기존 가짜 DAO 검증 | 미측정 |
| BE-CS02 | 로그인 시 해시 점진 전환 | 원문이 없는 저장 데이터의 전환 시점과 인증 응답 경계를 다룬다. | 해시·서비스 및 기존 로그인 검증 | 미측정·성능 향상 주장 없음 |
| BE-CS03 | 중복 주문 상품 정규화 | 입력 단위와 저장·집계 단위의 차이, 여러 쓰기의 책임 경계를 드러낸다. | 서비스·SQL 및 기존 가짜 DAO 검증 | 미측정 |
| BE-CS04 | 주문 시점 가격·총액과 추정 자료 구분 | 핵심 업무 데이터의 시간 경계·잠금·서버 가격 신뢰·비파괴 전환과 API 호환성을 실제 원인/결과로 설명한다. | 실제 MySQL 8.4/불변 jar HTTP 전후·이전 스키마 전환 | 금액 불변 위반 3/3→0/3은 정확성; 지연 성능 미측정 |

주문 읽기(BE-CS01)와 쓰기(BE-CS03)는 같은 클래스 안에 있지만 각각 페이지 단위의 관계 조립과 입력/쓰기 경계라는 독립 문제다. 단순히 변경 줄 수를 나눠 사례 수를 채우지 않는다.

## 근거와 한계

### BE-CS01

- 구현: [OrderServiceImpl.attachDetails](../src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java), [order.xml](../src/main/resources/mappers/order.xml).
- 기존 근거: [OrderServiceImplTests](../src/test/java/com/pubburi/pub/model/service/OrderServiceImplTests.java)의 `pagedOrdersAttachDetailsWithOneBatchQuery`, `emptyOrderPagesSkipDetailBatchQuery`. 가짜 DAO에서 ID 전달·상세 연결·빈 페이지의 호출 생략을 확인한다.
- 경계: 가짜 DAO 호출 횟수는 실제 DB 쿼리 실측이나 응답 시간 전후 값이 아니다. 데이터 규모를 통제한 MySQL 측정 없이 N배 개선·메모리 절감·처리량 향상을 기재하지 않는다.
- 추가 확보: 기준 구현과 비교 구현, 주문당 상세 분포, 페이지 크기, 캐시·워밍업·반복·표본 제외 기준을 먼저 고정한 뒤 쿼리와 지연·메모리 분포를 비교한다.

### BE-CS02

- 구현: [PasswordHasher](../src/main/java/com/pubburi/pub/model/service/PasswordHasher.java), [UserServiceImpl.login](../src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java).
- 기존 근거: [PasswordHasherTests](../src/test/java/com/pubburi/pub/model/service/PasswordHasherTests.java)의 BCrypt 일치/불일치 및 구형 해시 판별, [UserServiceImplTests](../src/test/java/com/pubburi/pub/model/service/UserServiceImplTests.java)의 `loginMigratesLegacySha256Password`.
- 경계: 서비스 검증은 가짜 DAO를 사용한다. 실제 DB 업데이트 실패·행 미존재·구형 형식 종료 정책이 검증된 것은 아니다. 권한 회수/계정 변경의 기존 세션 반영(BE03)은 이 사례의 완료 성과에 포함하지 않는다.
- 추가 확보: 실제 MySQL에서 올바른/틀린 암호 및 갱신 실패의 보존 여부를 확인하고 구형 형식 종료 기준을 문서화한다. BCrypt 계산 비용은 보안 전환 결과와 분리해 측정한다.

### BE-CS03

- 구현: [OrderServiceImpl.normalizeDetails/makeOrder](../src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java), [상품 수량 증가](../src/main/resources/mappers/product.xml), [사용자 스탬프 증가](../src/main/resources/mappers/user.xml), [상세 일괄 삽입](../src/main/resources/mappers/orderDetail.xml).
- 기존 근거: [OrderServiceImplTests](../src/test/java/com/pubburi/pub/model/service/OrderServiceImplTests.java)의 `makeOrderAggregatesDuplicateProductsAndIncrementsStampsAtomically`, `missingProductStopsOrderBeforeDetailsAndRewardsAreCreated`. 중복 수량·DAO 전달 값·예외 이후 진행 중단을 확인한다.
- 경계: 메서드 이름의 Atomically와 가짜 DAO 증가 결과는 실제 DB 원자성·롤백·동시성 실증이 아니다. `@Transactional` 선언과 SQL 증가식은 구현 사실로만 설명한다.
- 추가 근거: [실제 HTTP 검증](../scripts/api_smoke.py)에서 폐기용 MySQL의 성공 주문과 헤더/첫 상품 쓰기 후 없는 상품 오류의 롤백을 확인했다. 이 경로에서 주문 목록·상품 주문수·회원 스탬프가 변경 전과 같은지 검증한다. 상세 결과는 테스트 계획/PR에 기록하며 새 성능 수치나 별도 README 사례로 집계하지 않는다.
- 미완료: 스탬프 저장 이후를 포함한 나머지 단계 실패/롤백·동시·중복 재시도(BE06), 상품 삭제 뒤 이력 보존(BE02). 부분 검증을 전체 과제 완료로 확대하지 않는다.

### BE-CS04

- 구현: [OrderServiceImpl.makeOrder](../src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java), [FOR UPDATE](../src/main/resources/mappers/product.xml), [저장 스냅샷 조회](../src/main/resources/mappers/order.xml), [전환 SQL](migrations/001-order-price-snapshot.sql).
- 확인한 결과: 같은 초기 데이터의 두 상품 가격을 각각 1,000원 올리는 세 주문 시나리오에서 금액 불변 위반 3/3→0/3, 최대 금액 차이 3,000원→0원. 신규 단건/목록 DTO와 헤더/라인 합계 일치. [측정 원값·재현·무효 시도](measurements/2026-10-31-order-price-invariance.md)를 참고한다.
- 전환: 주문 6개·상세 8개 보존, 기존 주문은 LEGACY_ESTIMATE. 파일 전체 재실행은 중복 컬럼 오류로 중단되고 행 수/총액 추가 변화 없음. 자동 멱등 마이그레이션으로 포장하지 않는다.
- 한계: 세 로컬 데모 주문의 정확성 실험이며 실사용자 장애율·성능 결과가 아니다. 추가 SELECT의 비용, BE02 삭제 이력과 Frontend 가격 출처 표시, 전체 동시 요청 검증은 남는다.

### 이번 교체 결정

기존 BE-CS04 로컬 실행기 소유권은 프로세스 수명/TOCTOU를 다룬 유효한 사례이며 [이전 작업 기록](plans/2026-10-31-local-automation.md)과 [PR #2](https://github.com/Pubburi/Backend/pull/2)에 보존한다. 이번 가격 사례는 상품 수정이 과거 업무 금액을 바꾸는 실제 결함을 DB/HTTP로 재현하고, 잠금·스냅샷·추정 자료의 의미·비파괴 전환과 원값 비교까지 연결했다. 프로젝트의 DB/거래 포트폴리오 우선 기준에서 더 직접적인 업무 영향을 설명하므로 이 한 항목을 교체한다. 최신성·줄 수·테스트 총수로 선정하지 않았다. 페이지 조립·인증 전환·입력 정규화의 세 사례는 서로 다른 문제이므로 유지해 총 네 개다.

## 교체 후보와 개선 순서

| 후보 | 확보할 근거 | 현재 사례와 비교할 기술 판단 |
|---|---|---|
| BE02 상품 삭제 뒤 이력 보존 | 실제 FK·삭제/중지·조회·마이그레이션 보존 결과 | 현재 주문 읽기/쓰기 사례보다 이력 경계의 난도와 대안 판단 근거가 충분한지 |
| BE03 권한 회수 세션 반영 | 기존 세션의 접근 거부·동시 세션·계정 삭제 결과 | 해시 전환 사례와 다른 인증 상태 경계를 실제로 해결했는지 |
| BE06 실제 주문 롤백·동시성 | 실제 MySQL 실패 주입·중복/동시 요청·원자성 결과 | 가짜 DAO에 한정된 BE-CS03을 더 강한 실제 데이터 근거로 교체할 수 있는지 |
| 주문 조회 최적화 실측 | 동일 데이터·캐시·빌드·부하의 전후 원값과 분포 | BE-CS01의 구현 설명을 실제 병목 해소 근거로 강화할 수 있는지 |

새 후보가 현재 사례보다 채용 관점에서 강하면 해당 항목만 즉시 교체한다. 네 개가 모두 모일 때까지 기다리지 않고 한 번의 실행에서 교체 수를 제한하지 않는다. 원인 규명 증거·영향·대안 판단·구현 기여·재현 결과를 비교해 이유를 계획에 남기며, 최신성·코드량·테스트 수로 우수성을 점수화하지 않는다.
README 코드는 실제 현재 함수와 링크를 동기화하고, 성능 전후 값이 없으면 수치나 빈 성과표를 추가하지 않는다. 노션 필수 기능 완료 뒤에도 기존 기능 범위의 결함 재현·원인 분석·수정·측정 과제를 계속 발굴한다.
