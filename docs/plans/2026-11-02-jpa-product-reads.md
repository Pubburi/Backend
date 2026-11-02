# DB03 PostgreSQL 상품·특성 읽기 제공자

기준 main `904dc54`, 작업 `codex/jpa-product-reads`다. 기존 별도 JPA 세션·매장 제공자에
상품 목록/인기/상세와 특성 조회만 연결한다. 기본 MySQL API, 기존 Flyway V1/V2와 원본 데이터,
7개 경로의 세션·매장 동작은 보존한다. 전체 DB03·상품 쓰기/outbox·cutover 완료가 아니다.

## 구현 전 계약과 선택

- 새 경로는 기존 `/api/products`, `/api/products/popular`, `/api/products/{id}`,
  `/api/products/{id}/features`의 GET뿐이다. 기존 DTO/파서/PageResponse/ResponseMapper를 재사용한다.
- 원 ProductRestController 전체를 등록하면 쓰기까지 노출하고, ProductServiceImpl/특성 서비스에는
  outbox 의존성이 있다. 미구현 DAO/쓰기 stub를 채우지 않고 얇은 읽기 transport와 실제 JPA
  projection/Criteria 조회 서비스만 둔다. 쓰기 경로와 주문·개인 API는 계속 미등록404다.
- product/feature/tag의 immutable 매핑을 실제 조회에 사용한다. 상품 엔티티를 직렬화하지 않고
  세 테이블의 SELECT만 runtime 역할에 추가한다. 상품 DML·sequence·DDL 권한은 부여하지 않는다.
- 가격·주종·q·단정도 도수·모든 맛/향 태그는 기존 AND 의미다. unknown은 태그를 만족하지 않고,
  `null`과 검수된 `[]`를 구분한다. sort의 ID tie-break·페이지 정규화/offset 거절을 유지한다.
- 이름/type은 설치된 ICU collation을 사용하고 `%`, `_`, backslash의 LIKE pattern 의미를
  바꾸지 않는다. 고정 대소문자·악센트·한글·정렬/특수 pattern 검증과 전체 Unicode 동등성은 구분한다.
- 목록 items/count는 같은 REPEATABLE READ 트랜잭션에서 조회한다. 특성 메타/태그도 같은
  snapshot에서 반환하고 실제 동시 삽입/특성 교체로 읽기의 일관성을 확인한다.

성능 최적화 과제가 아니라 실행 가능한 영속 계층 이전이다. 새 비교 수치/운영 오류율·기존
MySQL의 결함을 만들지 않는다. 실제 소유 PG의 typed 복사본에서 자체 fixture만 추가/정리하고
원본/source행·source UUID·수령증/epoch를 초기화하지 않는다.

## 검증 계획

실제 HTTP는 기존 Product7/특성8, 빈 결과·없는 ID·잘못된 입력·미지원 쓰기, 인기/네 정렬·tie와
페이지/total, 소수 도수 경계, all-tag AND와 unknown/[]·쉼표 literal, pattern/collation을 확인한다.
테스트 제어 연결은 별도 migrator이며 runtime 역할의 상품/특성 INSERT/UPDATE/DELETE와 DDL은
실제로 거절돼야 한다. 테스트 전용 Hibernate statement gate로 items/count 및 특성/태그 사이에
별도 연결의 commit을 넣고 기존/다음 요청 snapshot을 구분한다.

기본 check·관련 회귀·새 packaged JAR HTTP와 최신 head CI/Codex·병합/main 검증은 각각 다른
게이트다. 아직 구현/실행 결과를 완료로 기록하지 않는다. 실제 결과·실패와 artifact는 작업 후 갱신한다.

## 구현과 실제 검증 결과

네 GET은 `JpaProductReadController`, 실제 조회는 `JpaProductQueries`에 구현했다. product/feature/tag
immutable 매핑을 projection/EXISTS에 사용하고, Hibernate의 scalar left join은 특성 행이 없는 상품을
보존한다. 공통 파서/DTO/mapper·오류 envelope를 유지했다. native SQL로 조회 전체를 복사하거나
미사용 Repository·쓰기 stub를 만들지 않았다. 추가 runtime 권한은 세 테이블 SELECT뿐이며
상품 sequence의 USAGE/SELECT/UPDATE와 세 테이블 DML/TRIGGER/TRUNCATE는 기동 guard에서 거절한다.

Java17·PostgreSQL18.6 TLS/SCRAM의 별도 소유 복사본을 사용했다. test control은 별도 비superuser
migrator이고, 매회 고유한 `ci_jpa_read_` 상품/특성/태그만 만들고 finally 삭제했다. 원본/source행을
수정하거나 sequence/UUID/수령증을 reset하지 않았다. 이 입력은 허구 검증 자료이며 실제 맛/판매나
원천 권리·추천 학습 성과로 사용하지 않는다.

| 실제 경계 | 확인한 결과 |
|---|---|
| 목록/DTO·범위/태그 | Product7/PageResponse·특성8, 같은 AND의 items/count, 모든 맛/향과 literal comma, known/unknown/검수[] 보존 |
| REAL/정렬·페이지 | 5.1/5.55/5.555 저장형 경계와0 가격/도수, 네 정렬/ID tie·높은 지원 페이지의 빈 결과, 초과 offset/잘못된 필터400 |
| 문자 pattern | case/악센트·한글과 `%`/`_`/backslash pattern, 설치된 ICU 이름 정렬을 실제 SQL/HTTP에서 확인 |
| 현재 상태·권한 | 소유 상품 삭제 후 상세/특성404, 미등록 상품/특성 쓰기404, runtime 상품/특성 DML42501·상품 sequence 권한 없음 |
| 목록 snapshot | items 뒤·count 앞에 별도 삽입 commit을 넣어 현재 응답6/다음 요청7의 각각 일관된 items/count 확인 |
| 특성 snapshot | 메타 뒤·태그 앞에 별도 교체 commit을 넣어 현재 원래750/기존 태그, 다음 요청1000/새 태그의 조합을 확인 |
| 실행 문서 | 실제 OpenAPI11경로12작업, Product7/특성8·반복 query의 form/explode 유지 |

위 두 snapshot 조건은 테스트 전용 Hibernate statement inspector로 실제 SELECT 사이에 다른
연결의 commit을 넣었다. 운영 지연·장애율/성능 측정이 아니라 DB 격리 의미의 고정 정확성 조건이다.
없는/알 수 없는 맛을 만들어 정답으로 쓰거나 여러 조건을 자연 사용자 오류율의 분모로 합산하지 않는다.

`./scripts/check.sh`의 명시 Java17/Python3.12 도구86·기존 API Java140·migration 단위18·JPA 단위3이
통과했다. 기본 검사에서 migration 실제9개/JPA DB/fixture9개의 skip은 실제 DB 통과로 세지 않는다.
별도 opt-in PG 실행에서는 새 상품읽기6개와 기존 세션/매장2개가 모두 통과했고 fixture 준비1개는
기존 소유 계정을 다시 만들지 않기 위해 skip했다. 최종 package의 실제11경로·공개상품/인기 전체
동일·상세/특성·입력 거절·기존 쿠키/매장 CRUD·기동 거절·재시작도 통과했다. 소유 JVM/사설 JAR
복사본은 정리했고 raw SQL/통합 XML·자격·행 내용은 공개 artifact에 넣지 않는다.

조정자가 별도 고정 명세를 두 엔진에서 독립 실행했다. 기존 API jar와 폐기 MySQL의 같은80상품,
동일 source에서 복사한 별도 PG와 동결 새 JPA jar를 사용해 같은31개 path/query/status와 **전체 JSON
본문이31/31 일치**했다. 목록/인기/상세/특성·정렬·pattern·가격/도수·400/404를 포함한다. 원문
결과/고정 입력은 ignored에 보존하고 성공 응답을 합성하지 않았다. 이는 이 입력 집합의 상호 엔진
호환 확인이며 성능 개선/전체 Unicode·업무 API 동등성이나 전체 DB02–06 완료가 아니다.

최종 JPA JAR SHA256은 `f0aa57bf7357fa37dcda1df703238495afa6e734071c18c6ff99e5e4eee11efd`다.
기존 API jar `dcb73c35b4db…`·루트 POM/Java·V1/V2는 불변이다. 필수 CI는 기존 MySQL 회귀·PG schema/
source/import와 새 상품 실제 회귀를 함께 유지한다. 최신 head Codex/필수 CI·main CI와 브랜치 정리는
조정자의 후속 게이트이며 이번 로컬 성공을 그 승인으로 대체하지 않는다.

## 비용과 남은 범위

페이지 요청은 projection과 COUNT, 특성 요청은 scalar projection과 태그의 두 SELECT를 사용한다.
짧은 RR snapshot의 connection/조회 비용과 DTO transport 중복 유지 비용은 있고 지연을 실측하지
않았다. 임의의 모든 Unicode collation이나 다른 페이지/계속 갱신되는 상태의 전역 snapshot을
보장하지 않는다. 상품/특성 쓰기·outbox/relay·주문/개인 API·원본 실전환/복구와 전체 FE 인수는 후속이다.

## README 판단

현재 네 사례의 업무 원자성·세션 수명·두 엔진 원자 복사·주문 이력 보존과 비교한다. 읽기 이전
자체를 과거 장애나 실측 성능으로 포장하지 않으며 더 강한 실제 영향 근거가 없으면 본문/순서를 보존한다.
