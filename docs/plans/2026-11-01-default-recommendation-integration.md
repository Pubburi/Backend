# BE05 공개 기본 추천 연결 증가분

기준 main `6860c51`, 작업 codex/default-recommendation-integration이다. 사용자 취향/학습을
기다리지 않는 비개인 B0 연결을 제공하고 원격 응답과 현재 업무 데이터를 분리한다. 상세 모델/
데이터/학습은 Recommendation 정본에 둔다. BE07 지원 수명/잔여 보안을 완료 처리하지 않는다.

## 선택·계약

[정본](../DEFAULT-RECOMMENDATIONS.md)의 public GET /api/recommendations/default?size=4,
data={items:Product7[],source:BASELINE|FALLBACK}와 정수1..12를 따른다. 후보는 현재 첫1,000ID순,
available은 판매 상태가 아닌 행 존재 의미다. 사용자 선택자·취향·이미지/이름/가격은 원격에 보내지 않는다.

JDK17 HttpClient의 연결200ms·전체 body700ms와32KiB subscriber를 사용한다. streaming InputStream을
헤더 시점에 반환해 나중에 무제한 read하는 대안 대신, body completion future를 deadline 안에
기다리고 미완료 exchange/subscription을 취소한다. redirect/retry는 없으며 JDK 취소는 자원 해제의
정확한 시점까지 동기로 보장하지 않는다. 새로운 프레임워크/유료 서비스/AWS 의존성은 없다.

원격 호출은 DB 트랜잭션 밖이다. 성공 ID는 범위/순서·알고리즘·이유·JSON 계약을 검증하고 현재
DB 상품을 배치 재조회한다. 가격/이름은 현재값, 삭제가 있으면 current ID순 FALLBACK이다.
빈 최초 DB는 원격 없이 FALLBACK[], DB 예외는500이다. 스키마/seed/기존 API는 바꾸지 않는다.

## 검증

Java17 Maven verify115개·패키징과 Python 도구/안전 게이트28개·저장소/문법/diff 검사를 통과했다.
가짜 upstream은 이 테스트가 소유한 loopback/임시port0이며 종료/정리한다. 정상명시카탈로그와
비정상 body·중복키/trailing JSON·wrong ID/algorithm/eligible_count/reasons·503/비JSON/302 미추적,
chunked body 제한·연결 거부를 검증했다. 부분 응답은 헤더와 첫 chunk 도착을 확인한 뒤 끝나지
않는 body의 전체 완료 deadline을 검사했다. 별도로 직접 subscription의 취소·초과도 확인했다.
대기 테스트의3초 실패 예산은 CI 정합성 경계이며 제품 응답 속도 측정치가 아니다.

동결 뒤 테스트만 보강해 알 수 없는 길이의 chunked 응답에 정상 B0 JSON과 JSON 공백을 사용했다.
32,768B는 수용하고 32,769B는 거절하며 일반 JSON parser로 두 본문의 의미가 같음을 확인했다.
처음의 0바이트 fixture는 JSON 오류만으로도 거절될 수 있어 이 경계를 설명하기에 약했다.
선택한 HTTP client 테스트 7개가 통과했고 제품 jar는 재생성하지 않아 SHA가 동일하다. 직접
subscriber 검사는 별도로 cap 초과 시 실제 소유 subscription 취소를 확인한다.

DAO fake는 호출 뒤 가격 변경·상품 삭제 fallback·마지막 DB 실패 전파·빈 카탈로그를 확인한다.
이것을 실제 DB 동시성/rollback 근거로 쓰지 않는다. MVC는 공개/다른 소유자·URL 파라미터 미신뢰·
두 필드/Product7·400을 확인하고 실행 OpenAPI/Swagger를 유지한다. 첫 Java 테스트 작성 중 제네릭
추론의 컴파일 오류는 명시 타입으로 수정했으며 제품 HTTP 실패나 성능 분모로 세지 않았다.

최종 불변 jar SHA-256: `23f5920ad66a64ed42258b53d6371f06543c284d2540c0a9ca37ec8b3575deda`.
조정자는 같은 jar와 전용 MySQL8.4에서 전체 HTTP smoke의 11범주를 통과했다. 추천 서버를
기동하지 않은 기본 목록의 FALLBACK뿐 아니라 기존 세션/권한·주문 금액/rollback·삭제 이력·
계정 수명·특성·비공개 명시취향·찜과 실행 OpenAPI/Swagger도 함께 검증했다. 이 범주 수는
서비스 품질이나 성능의 개선 수치가 아니다. 기존/공유 DB·포트는 사용하지 않았다.

실제 B0 FastAPI와 React 프로덕션 화면·Backend·전용 DB를 연결한 동일 여섯 단계 흐름은
BASELINE과 추천 서버 중단 후 FALLBACK에서 각각 통과했다. 공개 기본 목록/현재 상품 상세와
SKIPPED·COMPLETED·새 계정 NOT_STARTED의 저장 의미를 확인했고 390px에서 pageerror·
예상외 API 오류가 없었다. API mock·외부 AI 서비스는 사용하지 않았다. 이는 선택한 기본 목록
흐름의 통합 근거이며 전체 C04/E2E·실사용자 선호·추천 품질 완료와 구분한다.

마지막으로 정상 B0 계약을 반환하는 제어 fixture가 후보를 받은 뒤 응답을 보류하도록 했다.
그 대기 중 **실제 관리자 API**로 첫 상품의 이름/가격을 바꾸자 BASELINE의 마지막 DB 재조회가
변경값을 반환했다. 별도 요청에서 선택 상품을 관리자 API로 삭제한 뒤 B0 응답을 풀자 FALLBACK의
현재 네 상품을 반환하고 삭제 ID를 제외했다. 두 조건이 통과했으며 DB 직접 수정만을 실제 업무
API 검증으로 포장하지 않는다. 이 제어한 순서는 임의 동시 고객 부하·지연/처리량 측정이 아니다.
실제 FastAPI 통합과 제어 fixture의 상품 변경 경계 검증도 서로 구분한다.

CI는 추천 서버 없이 기본 목록의 FALLBACK/현재 상품/범위와 실행 문서를 필수 검사한다.
별도 upstream unit fixture와 실제3서버 검증은 서로 다른 경계다. 실행은 TESTING의 격리 환경과
./scripts/check.sh·PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh를 따른다.
추천 서버 미기동 시 `PUBBURI_SMOKE_EXPECT_RECOMMENDATION_SOURCE=FALLBACK`으로 대체 경로를
명시 검사한다. 상품 변경을 재현할 때는 폐기 가능한 별도 DB에서 대기 가능한 B0 fixture를
운영자 origin으로 지정하고, 후보 수신 확인→관리자 상품 PUT 또는 DELETE 성공→정상 B0 응답
완료→기본 목록의 값/source/삭제 ID 제외를 확인한다. 사용자 DB에서 삭제 실험하지 않는다.

## 사례 비교·남은 범위

현재 README 네 사례와 이번 연결의 원격 수명/현재 데이터 경계를 비교했다. 새 API의 예방적
설계가 기존 권한 회수·주문 금액/이력 유실을 전후 재현한 사례보다 강한 업무 영향·측정 근거를
확보한 것은 아니므로 네 사례와 순서를 유지하고 기능 표만 갱신한다. 비교 이유는
[사례 register](../case-study-register.md)에 남긴다. 이를 과거 서비스 장애 해결·지연/부하
최적화로 포장하지 않는다.

저장 취향의 개인화·B1 호출·판매/재고 상태·전체 R01/R02·BE05 장애/부하 인수는 별도다.
원격이 느릴 때 fallback을 제공한 것이 모델 품질·CTR·구매 전환 성과는 아니다. 최신 PR CI·완료된
Codex 리뷰·미해결 지적 없음·병합/main CI·브랜치 정리는 조정자 게이트다.
