# DB05 기존 공개 상품 검색과 현재 DB fallback

기준 main `aeb0949`에는 source-bound relay와 DB 필터가 있지만 공개 API는 엔진을 읽지 않았다.
이번은 기존 `/api/products`의 opt-in 엔진 읽기와 PostgreSQL/MySQL 현재값 대조를 연결한다.
새 경로/응답 JSON·Nori 의미 검색·추천/순위·기본 DB/스키마 전환은 추가하지 않는다.

## 구현 전 계약

- 기본 false는 기존 DB 서비스/JSON을 유지한다. 입력/정규화/LIKE·네 정렬/ID tie·페이지/total과
  필수 범위·known 태그 AND는 그대로다. 원격 source/URL/index/계정을 요청으로 선택하지 않는다.
- 실제 source ID DAO와 물리 generation을 확인하고 엔진은 ID/정확 total만 반환하게 한다.
  원격 밖 canonical RR의 현재 페이지/total과 같을 때 현재 DB 필드로 응답한다. 불일치/장애는
  같은 모든 조건의 DB fallback이며 DB 오류는500이다. DB 확인 비용을 유지하는 첫 연결 단계다.
- source-bound 물리 index와 기존 runtime 최소 역할을 사용한다. 새 schema/V1/V2·source/ACK/
  sequence reset·원문 event 수정·원본 상품 변경은 하지 않는다. 엔진 생명주기는 조정자 소유다.
- 기존80 실제 PG 회귀와 기본/packaged MySQL을 유지하고 새 공개 HTTP·remote 밖 TX·필수조건/
  누락·stale 가격/태그/삭제·wrong source/물리 generation·partial/기한/상한·빈 결과를 검증한다.
- 기존 relay restart/교체의 완료 ACK 전행 검증 뒤 별도 공개 검색 인수를 추가해 옛 분모와 원문을
  보존한다. 실제 엔진과 DB-only 프로세스의 전체 HTTP JSON을 동일 상태에서 대조한다.

첫 기본 검사의169개 중 한 오류는 Mockito throw stub을 when(...).thenReturn으로 재설정하는
테스트 setup에서 났다. doReturn으로 바로잡고 전체 기본 검사를 통과했다. 첫 실제 PG의 신규2개는
Java valueToTree(FloatNode)와 HTTP 파싱(DoubleNode)의 비교 차이로 같은 JSON을 거절했다.
기대도 실제 JSON 직렬화/파싱 경로로 대조해 새4회귀와 전체 실제 회귀를 다시 확인했다.
처음 전체 XML/로그는 보존하며 제품 오류나 자연 오류율/성능으로 계산하지 않는다.

기본 MySQL·/api/세션·원본80/가격 이력·불변 JSON/
UUID/ACK와 README 네 사례를 보존한다. 이번은 기존 원자 전달 사례의 인접 필수 연결이며 더 강한
독립 과거 오류/영향 근거가 없으므로 네 본문/순서를 교체하지 않는다. 전체20개 MVP12월31일·
DB/React11월22일과 추가 최적화는 MVP 뒤1월1일부터라는 기준을 유지한다.

## 최종 검증과 실제 인수

| 범위 | 실제 결과 |
|---|---|
| 기본 검사 | 도구99, 기존 Java169, migration 단위18, JPA 단위3 통과. DB 없는 skip은 실제 성공과 분리 |
| 실제 PostgreSQL18.6/TLS·최소 runtime | 기존80+신규4=84 통합, 단위3 통과·준비2 skip. 원격 대기 중 실제 가격/태그/삭제 commit과 canonical RR 결과 검증 |
| 동일 실행 JAR | JPA36경로/43작업·재시작/재로그인/기존 업무 smoke 통과. 기본 MySQL은 기존12그룹·Swagger/OpenAPI·비개인 fallback 통과 |
| 실제 OpenSearch3.9.0 | PostgreSQL·MySQL 각각34개 HTTP 상태와 전체 ApiResponse JSON이 변경 전 DB-only JAR와 일치. 정규화/시간 제외0 |
| 보존/정리 | 정상/실패 DB와 relay business의 승인 원본264행/14표를 PK·자료형 값으로 대조. 원본12업무표·원문 event/version/source UUID 유지. 자체 상품/계정·실패 DDL/프로세스 정리 확인 |

실제 엔진 비교는 네 정렬/ID tie·페이지/total, 원화/float32 경계·실제0·known[]·쉼표/내부개행
원자 태그 AND, 지연 PENDING·누락·stale 가격/태그/삭제·재전달/삭제 표식, 기존 LIKE/깊은 offset,
strict400과 미기동 origin을 포함한다. 엔진과 ID/total이 같아도 응답 가격은 현재 DB 값이다.
양쪽 실행 JAR의 소스/해시를 동결하고 원본 관리자/상품이 아닌 별도 작성 fixture만 수정했다.
앱이 source UUID/ACK/sequence를 초기화하거나 색인 생명주기를 변경한 횟수는0이다.
이 비교는 부하/지연·검색 품질 측정이나 전체 색인의 정합성 보증이 아니다.

## 첫 보존 검사 실패와 근거의 한계

첫 PG 공개검색의 마지막 업무표 동등 비교는 실패했다. 이 실행은 비교 전 RAM 값을 파일로
저장하지 않았으므로 그 전 값을 복원했다거나 차이가 오직 하나였다고 단정하지 않는다.
준비 도구는 별도 작성 관리자와3계정을 legacy SHA256으로 생성했고, 현재 관측은 그 관리자만
기존 로그인 CAS로 bcrypt가 된 상태다. 기존 relay helper는 로그인 뒤 보존 기준을 잡았지만 새
helper는 앞서 잡았다. 제품 해시 정책은 변경하지 않았다.

승인된 불변 snapshot을 현재 원본 PK에 투영해6원본 계정의 hash/epoch·80상품의 float bits·
원문 JSON을 포함한14표264행을 직접 대조했고 모두 같았다. 추가 작성4계정과 신규 불변
version/event, 허용된 PENDING→ACK는 별도 분모다. 원래 승인 자료의 DELIVERED는0이었다.
첫 실행이 전달한91개를 새 PG 검증의 기존 완료 ACK로 잡아 전행 그대로 유지했고, 추가11개
전달 뒤102개가 됐다. MySQL은0→127이며 이 수를 원래 이관 ACK나 사용자 이용량으로 꾸미지 않는다.

새 출력에는 로그인 전/직후/종료의 모든 업무행을 각각 저장했다. 정확한 작성 관리자 ID의
legacy SHA256→bcrypt 한 필드만 로그인 전이로 허용하고, 그 외 계정/epoch/역할/이름/스탬프·
상품 변경을 거절하는 단위를 추가했다. 새 PG는 로그인 전이0, 새 MySQL은 그 hash 한 필드의
전이를 실제 확인한 뒤 나머지 전체 업무행과 기존 event/version/완료 ACK를 대조해 통과했다.
첫 실패/원본 투영·새 비교 근거는 분리 보존하며 실패를 재시도로 지우지 않았다.

최종 정리 감사는 소유10개 PID의 부재/포트 해제와 정상/실패 DB의 실패 trigger/function0·
작성4계정 외 잔존 ci계정0을 확인했다. 도구 전체 검사를 권한 없는 실행으로 호출한 시도는
loopback 소켓 제한으로 중단하고 로그를 보존했다. 같은 소스를 허용된 로컬 소켓 권한으로
실행한99개가 통과했다. 정리 감사 도구의 fixture 배열 해석 오류도 읽기 단계에만 발생했고
정확한 ID 열 투영으로 고쳐 감사 결과를 따로 저장했다. 이는 제품 결함/성능 근거가 아니다.

## 남은 범위와 사례 판단

기본 MySQL/MyBatis runtime·기본 비활성·/api/ApiResponse/세션을 유지한다. 별도 JPA의 경로/
작업 수는36/43으로 같으며 이번은 기존 GET 연결이다. S02 재색인/유실 복구·자동완성/검수
동의어/오타·개인화 검색·전체 DB03–06/기본 PostgreSQL cutover·전체 F02/S03–S05는 미완료다.
현재 DB 확인 비용을 유지하므로 성능/부하 감소를 주장하지 않는다. 다음 필수 단위는 남은
공개 검색 계약/재색인·필수 장애 인수 또는 승인된 추천 owned caller 연결이며 공통 우선순위로 정한다.

README 네 사례의 본문과 순서를 그대로 비교·보존했다. 이번은 원자 전달 사례의 인접 연결이며
새 독립 제품 장애의 원인/영향·대체안 근거가 기존 사례보다 강하지 않다. fixture/assertion 오류를
별도 과거 제품 문제로 포장하거나 개수/참신함만으로 교체하지 않는다. 전체20개 MVP12월31일,
DB/React11월22일·최종2월5일과 추가 최적화는 MVP 이후1월1일부터라는 기준을 유지한다.
