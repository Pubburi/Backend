# DB03·DB04 PostgreSQL 주문과 거래 경계

기준 main `75f99af`, 작업 `codex/jpa-order-transactions`다. 별도 JPA 제공자에서 원래 주문
생성·본인 페이지·단건·관리자 페이지·완료·삭제 여섯 작업을 제공한다. 기본 MySQL/MyBatis
runtime·Java17·공통 /api·ApiResponse·세션·OSIV off/validate·Flyway V1/V2는 유지한다.
전체 사용자 API·후기/취향/찜·Psycopg3 relay·기본 실행 cutover·전체 DB03–06은 후속이다.

## 보존 계약과 구현 판단

- 원래 OrderRestController/OrderServiceImpl·요청/응답 DTO·ResponseMapper를 whitelist로 재사용한다.
  DAO 성공 stub나 복제 주문 서비스를 만들지 않는다. Order/OrderDetail/Stamp의 실제
  Spring Data Repository와 DTO projection을 연결한다.
- 주문 헤더→상품 full FOR UPDATE와 정수 범위/가격 snapshot→상품 수/원자 producer→
  상세→총액→스탬프→회원 atomic increment가 한 TX다. 이벤트 원문/UUID·ACK를 다시 쓰지 않는다.
  한 단계의 실패는 앞서 저장한 헤더·상품/version/event/PENDING까지 모두 rollback해야 한다.
- 상세는 저장한 이름/type/img/단가/합계를 읽으며 현재 상품을 join하지 않는다. 헤더의 역 ID순과
  상세의 정 ID순, 페이지당 단일 IN projection을 유지하고 lazy entity를 HTTP에 노출하지 않는다.
- 완료는 completed만 UPDATE하고 삭제는 Repository의 한 affected-row JPQL DELETE다.
  두 삭제가 경합해도 기존0/1 의미를 보존하며 상세/스탬프는 DB cascade로 정리한다.
  삭제 때 이미 적립한 회원 stamps/상품 count를 되돌리지 않는 기존 동작도 보존한다.
- 새 order_time은 DB 문장 시각이 생성하고 Instant에서 Timestamp/Date DTO로 변환한다.
  과거 nullable 시각/completed/총액·LEGACY_ESTIMATE와 참조 null을 그대로 둔다.
  completed의 CHAR JDBC 타입은 명시하며 기존 schema나 검증 정책을 느슨하게 만들지 않는다.
- V1의 CURRENT_TIMESTAMP default는 불변이다. 오래된 외부 TX에서 새 주문을 만들어도 기존
  MySQL INSERT의 문장 시각을 보존하도록 Hibernate @Generated(INSERT,sql="statement_timestamp()")를
  명시한다. Repository가 DB 표현식을 실행하며 JVM 시각/입력 시각을 bind하지 않는다.
- 최근1/6개월 DAO의 기준은 기존 MySQL NOW의 문장 시각이다. PostgreSQL CURRENT_TIMESTAMP를
  그대로 옮기면 이미 시작한 외부 TX의 옛 기준을 쓰므로 이 두 질의만 statement_timestamp와
  make_interval을 명시한다. 일반 조회는 계속 JPQL DTO projection이다.
- runtime에 주문 SELECT/INSERT/DELETE와 UPDATE(completed,total_price,order_table), 상세 SID,
  스탬프 SI와 세 identity USAGE만 추가한다. 주문 immutable 컬럼·상세 UPDATE·스탬프 UPDATE/DELETE,
  sequence reset·업무 DDL/TRIGGER/TRUNCATE는 거절한다. 실패 DDL은 별도 control 역할만 사용한다.

## 실제 실행의 경계

소유한 PG18.6/TLS·nonowner runtime의 정상/실패 두 DB만 사용한다. 승인한 snapshot을 복사한
원본345행은 수정하지 않고 자체 ci_jpa 계정/상품/주문만 만든다. 조정자의 별도 browser DB와
기준 MySQL 실행은 독립 범위다. 테스트가 남긴 version/event/delivery는 불변 기록이므로
삭제하지 않고 소유 폐기 DB의 최종 정리에 맡긴다. 원문/자격/실행 로그는 ignored에만 둔다.

첫 sandbox의 연결 거절은 SQL 검증 전 환경 실패다. 허용된 loopback 환경의 첫 실제 startup은
새 completed String 매핑의 VARCHAR와 기존 PostgreSQL CHAR 차이로 validate가 거절했다.
명시 JdbcTypeCode(CHAR)로 매핑을 바로잡았다. V1 수정·DDL 자동화·검사 생략은 사용하지 않았다.
다음 전체 실행의 두 event 수량 단언은 기존 document 중첩을 빠뜨린 fixture 오류여서
수정했다. DB의 회원/상품 합계와 TX 자체는 통과했으며 제품 장애 개선으로 세지 않는다.

시각 경계는 먼저 고정한 실제 PG 회귀로 확인했다. 외부 TX를 시작하고 별도 control 연결에서
0.25초 뒤 문장 시각의1개월 경계보다0.1초 오래된 자체 주문을 넣었다. 초기 current_timestamp
이식은 이 행을 최근1개월에 포함해 단언이 실패했다. 두 시각의 의미는
[PostgreSQL 공식 시각 함수](https://www.postgresql.org/docs/18/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)를 확인했다.
이것은 새 제공자 개발 중의 실제 이식 결함이며 과거 서비스 장애/시간 성능으로 일반화하지 않는다.

독립 검수에서 새 INSERT의 기존 schema default도 외부 TX 시작 시각임을 확인했다. 별도 회귀는
TX를 시작한 뒤0.25초를 지나 DB 문장 시각의 하한/상한 사이에 새 주문 시각이 있는지 검사하며,
초기 default-only INSERT는 하한보다 과거여서 실패했다. 명시한 Hibernate 생성 표현식으로
보완하고 기존 nullable 행이나 migration default를 덮어쓰지 않는다.

## 검증 상태

- Java17/Python3.12의 `./scripts/check.sh`가 도구86·루트 Java140·이관 도구 단위18·JPA 단위3과
  패키징을 통과했다. DB가 없는 기본 경로의 이관9/JPA26 skip은 실제 DB 성공으로 세지 않는다.
- 실제 별도 PG18.6 정상/실패 DB의 전체25 통합이 통과했다. 기존 인증/매장2·상품 읽기6·
  상품 쓰기5·상품/매장 실패4를 유지하고 새 주문 정상6·후반 실패2를 추가했다.
  별도 fixture 준비1은 이미 준비한 DB여서 skip했고 성공에 합산하지 않는다. JPA 단위3도 통과했다.
- 주문 검수는 중복 수량·스냅샷/소유권·삭제 후 동일 ID 재가입, 한 페이지의 헤더/일괄 상세/count
  세 SQL, 두 동시 주문의 회원/상품 합계와 원자 버전/event/PENDING, 동시 삭제200/404를 확인했다.
  유효 상품 뒤 없는 상품·금액/중복 수량 overflow와 스탬프/회원 합계 뒤 SQL 실패의 전체 불변을
  확인했다. 시각의 두 고정 회귀도 수정 후 성공했으며 null 과거 값과 최소 권한 거절을 유지했다.
- 같은 불변 JAR의 packaged smoke는 실제20경로/22작업·주문201/본인과 관리자 경계·목록/단건·
  재시작/재로그인 후 동일 snapshot·완료·상품 삭제 후 이력·주문 삭제를 통과했다.
  기존 세션/매장/상품/특성, 명시 flag/DDL/marker/source 기동 거절도 유지했다.
  소유 JVM6개와 임시 JAR은 finally 정리됐으며 원문 로그/자격은 공개 artifact에 넣지 않는다.
- 검증 JAR SHA256은 `86929546aa3e76302f6b048f4053df1bc8efc4e4b64edebbe3728a6df51c2bc6`다.
  기본 API 소스/POM과 V1/V2는 변경하지 않았으며 archive에 MyBatis/MySQL/H2 runtime이 없다.

조정자의 독립 대조는 새 MySQL8.4의 원래80상품과 별도 PG18.6 복사 DB에 각각 같은 자체 상품81·
주문5를 만들었다. 고정된21 HTTP의 status/전체 JSON과 여덟 SQL 상태 값/삭제 cascade가 같았다.
유효 상품 뒤 없는 상품의 실패 요청은 주문/상세/상품 수/스탬프/version/event/delivery 모두
요청 전후 상태가 같았고, 중복2+3은 한 상세5·합계60,000원이었다. 가격/이름 수정과 상품 삭제 뒤에도
이 금액/이름 snapshot이 유지됐으며 삭제 후 productId만 null이었다. 완료/삭제와 적립 합계를
되돌리지 않는 기존 동작까지 같았다. 입력 명세 SHA256은
`1ff9743f602533c1896be2e8b73fd4588970270a76fe22525b5d297fd7654ad1`다.

이 대조의 두 JVM은 UTC로 고정했다. 매 실행 새로 생성되는 orderTime은 서로 다른 값이므로
정확 JSON 비교에서 해당 값만 제외하고 각 실행의 UTC 시각 범위로 별도 검증했다. 나머지 JSON을
임의 정규화해 차이를 숨기지 않았다. 최초 harness의 타인 목록200 기대는 실제 계약403으로 고쳤고,
암묵 JVM timezone으로 실행한 최초 시각 단언도 검증 기준에서 제외했다. UTC를 명시한 성공한
고정 baseline만 새 제공자와 비교했다. 두 준비 오류를 기존 제품 결함이나 성과로 계산하지 않는다.

조정자는 같은 JAR과 실제 React production build를 별도 복사 PG에 연결했다. 자체 상품의 단가
7,777원·수량2에서 비회원 상세/특성·장바구니→checkout의 로그인 안내→일반 회원 로그인 후
장바구니 복귀→주문201·ORDER_SNAPSHOT 15,554원·장바구니 비움과 주문 카드의 이름/수량/금액을
확인했다. 실제 GET 단건도 대조했다. 별도 관리자 화면의 상세 펼침→완료 PATCH200/Y→삭제200·
목록에서 제거/GET404와 자체 상품 정리도 성공했다. API mock·예상외 API 오류·pageerror는 없었다.

이 제공자의 미등록 product comments와 member profile GET은 정확한404로 따로 기록했다.
profile 조회 실패와 독립적으로 주문 카드가 렌더된 선택 흐름의 검증이며 전체 고객/프로필·
FE03/C04 인수가 아니다. 최초 자체 상품 준비의 빈 이미지400은 fixture를 유효 이미지로 고친 뒤
같은 소스/JAR/build로 진행한 준비 오류다. 원래 상품/회원 행을 수정하거나 오류를 가짜 성공으로
바꾸지 않았다. 이 검증도 운영 부하·실구매·서비스 성능 결과가 아니다.

새 제공자를 기존 서비스의 과거 버그 수정이나 운영 오류율/지연·처리량 개선으로 표현하지 않는다.
필수 CI·최신 완료 Codex·미해결0·병합/main CI는 로컬 결과와 별도 최종 게이트다.

README 네 사례는 업무 원자성/재전달·세션 수명·두 엔진 원자 복사·주문 이력이다. 이번 이식은
기존 거래/이력 사례의 추가 근거지만 더 강한 독립 영향/원인 근거는 없어 본문과 순서를 보존한다.
