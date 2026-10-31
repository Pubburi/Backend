# F01 계정 찜 API 증가분

기준 main은 `e0c9ad0`이다. 기존 세션·상품·주문·명시취향 계약 위에 계정의 찜 관계만 추가한다.
현재 기획의 F01은 계정 저장/해제/조회·다른 세션의 같은 상태와 화면 연결을 포함하므로 이 API만으로
F01 전체 완료가 아니다. 화면/추천 점수·모델·실사용자 인기/행동 수집은 이번 구현이 아니다.

## 계약·선택

- GET `/api/users/me/favorites`, PUT/DELETE `/api/users/me/favorites/{productId}`만 제공한다.
  [제공자 계약](../ACCOUNT-FAVORITES.md)·[부분 명세](../openapi/account-favorites.json)를 따른다.
  관리자를 포함해 본인만 접근하고 요청 body/query의 소유 ID를 선택자로 사용하지 않는다.
- 상태를 뒤집는 POST 대신 원하는 상태를 PUT/DELETE로 명시한다. PK(user_id, product_id)와
  중복 INSERT no-op으로 최초 created_at을 보존한다. 반복 PUT으로 최근 추가 순서를 갱신하지 않는다.
  반대 작업이 경합할 때 마지막 클릭 의도까지 보장하는 revision 설계는 별도 UI/연동 과제다.
- GET은 현재 ProductResponse 일곱 필드와 PageResponse 여섯 필드를 유지한다. 현재 가격/이름은
  주문 스냅샷과 다르며 찜 저장은 누적 주문수·회원 스탬프를 바꾸지 않는다. 비공개 owner/epoch를 노출하지 않는다.
- GET의 현재 계정 존재·count·items는 RR 트랜잭션과 id+epoch에 묶는다. 유효한 빈 목록과
  만료 인스턴스 401을 구분하며 DB 예외를 빈 페이지로 숨기지 않는다. offset int overflow는 SQL 전 거부한다.
- 쓰기는 User FOR UPDATE→epoch 확인 후 PUT만 Product FOR UPDATE한다. 기존 주문과 같은
  잠금 순서다. Guard를 통과했어도 그 뒤 삭제/동일 ID 재생성으로 epoch가 다르면 401이며 새 계정에 쓰지 않는다.
- 두 FK CASCADE는 사라진 계정/상품의 찜만 제거한다. 상품이 사라진 DELETE도 200 false로
  멱등 처리한다. FK SET NULL인 주문 이력·가격 스냅샷과 다른 관계 수명이다. 가짜 참조 ID는 만들지 않는다.
- 006은 새 빈 테이블만 추가한다. 기존 001..005 DB를 수동 전환하며 자동 reset/DDL rollback·
  재실행 멱등성을 주장하지 않는다. 기존 데이터를 추정 선호로 변환하지 않는다.

## 검증·근거 경계

Java 17 `./mvnw --batch-mode --no-transfer-progress verify`에서 86개 단위/MockMvc·실행 MVC 문서
검사가 통과했다. 서비스 fake DAO는 소유/epoch·잠금 순서·재시도 상태·누락 상품·예외 전파를 확인하고
실제 DB rollback/동시성 근거로 사용하지 않는다. MockMvc는 본인/관리자·페이지·현재 필드·숫자 오류·
만료/누락 구분·미디어/메서드 경계를 확인한다. 문서는 실제 MVC 생성 JSON에서 페이지/상품/관계
스키마·세 연산·본문 없음·타인 경로 없음을 확인한다. 서버 포트/기존 DB를 사용하지 않았다.

새 경로에 POST를 보내는 단위 회귀를 먼저 실행했을 때 기대 405와 달리 실제 500이었다.
기존 catch-all Exception 처리기가 HttpRequestMethodNotSupportedException을 내부 오류로 처리한
원인을 확인했다. 해당 예외에만 405·METHOD_NOT_ALLOWED·Spring Allow 헤더를 보존하는 핸들러를
추가했다. 같은 단위 요청은 405와 Allow PUT/DELETE를 통과했다. 이 N=1의 **서블릿 단위 재현**은
실제 HTTP의 전후 오류율·운영 장애율이나 성능 개선이 아니다. 라우팅 matcher/정상 API는 바꾸지 않았다.

Python 도구/안전 게이트 26개도 통과했다. 첫 샌드박스 실행은 loopback bind/ps 금지로 5개 검사가
실행 제약에 실패했고, 임시 소켓·자기 프로세스 정리에 필요한 승인된 실행 범위에서 다시 통과했다.
제품 결함 감소로 집계하지 않는다. 저장소 추적 검사·Python 문법·diff 공백 검사도 통과했다.

최종 불변 jar SHA-256: `de467019ce964e5a87bdd3b6cca094061a9ec1b69307ed4f197c0669408c99f4`.
006 SHA-256: `e724c020267c4530cd540e41826fecd299431b4700deab75ff51a6e65d839970`.
006의 실제 MySQL 8.4 전환에서 기존 11개 테이블의 전체 UTF-8 byte hash가 같았다. 회원 6·상품 80·
주문 3·상세 4를 보존했고 새 t_user_favorite는 0행이었다. 별도 폐기 환경에서 확인했으며 기존
개발/사용자 DB는 변경하지 않았다.

조정자가 같은 불변 jar·MySQL 8.4·별도 폐기 DB·생성한 계정/상품으로 실제 HTTP를 확인했다.

| 고정 검증 조건 | 실제 결과 |
|---|---|
| 같은 계정의 독립 로그인 여섯 세션 동시 PUT | 6/6 요청 200, 관계 1행. 반복 PUT의 created_at 불변 |
| 새 상품 추가·페이지·관리자 상품 수정 | 최근 추가 순서·페이지 경계 일치, 현재 수정 가격을 목록에 반영 |
| 타회원·관리자 본인 격리 | 각 본인 목록은 비어 있고 타인 찜/인증 필드 노출 없음 |
| 잘못된 입력 | 8개 요청 400 후 저장 상태 불변. 공개 smoke 입력 집합과 별도 |
| 없는 상품/관계 | PUT 404, DELETE 200 false |
| 상품 삭제·해제 | 상품 삭제의 찜 CASCADE, 여섯 세션 동시 DELETE 성공 뒤 관계 0행 |
| 실행 문서 | 실제 OpenAPI JSON·본인 경로/페이지/기존 상품/관계 스키마·Swagger UI 통과 |

별도 **제어한 사용자 행 잠금 인터리빙 한 시나리오**에서는 이전 PUT이 Guard를 통과한 뒤
사용자 행 잠금에서 대기하도록 했다. 잠금 보유 트랜잭션이 기존 계정을 삭제하고 같은 ID·새 epoch로
재생성한 뒤 커밋했다. 대기 PUT은 401, 재생성 계정의 새 로그인 세션 찜은 0행이었다. 사용자/상품
잠금 경계의 정확성 확인이며 실제 과거 동시성 장애를 고쳤다는 전후 통계나 자연 부하/처리량·
지연 성과가 아니다. 여섯 요청과 한 인터리빙으로 전체 동시성·모든 진행 요청 취소를 입증하지 않는다.

최종 같은 jar의 `scripts/smoke.sh`도 전용 MySQL에서 완전히 통과했다. 기존 주문·가격·쓰기 후
실패 rollback·삭제 이력·세션·상품 특성·명시취향과 새 찜 경로를 함께 확인했다. 새 공개 smoke의
POST 405·Allow PUT/DELETE와 Accept 406 뒤에도 저장 상태는 그대로였다. 405의 사전 재현은
위에 기록한 단위 요청이며 실제 HTTP 전후 오류율로 합산하지 않는다.

실행 방법은 [TESTING](../TESTING.md)의 폐기 컨테이너·marker 안전 게이트와 `./scripts/check.sh`,
`PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh`다. 공개 필수 smoke는 고유 fixture 회원/상품으로
본인·다른 세션·멱등 페이지·현재 상품·삭제/재가입·405 Allow/406·상태 불변을 확인한다.

## 사례 비교·인계

현재 README 네 사례는 실제 기존 권한/계정 회수·주문 이력/금액 결함과 정규화/페이지 조립을 다룬다.
찜 증가분의 새 상태/계정 수명 방어와 메서드 오류 회귀만으로 더 강한 문제 해결이라고 평가할
근거가 없으므로 네 항목/순서를 유지했다. API 수·테스트 수·동시 요청 수를 채용 성과나 서비스
성능으로 바꾸지 않는다. 이번 결과는 [사례 후보 기록](../case-study-register.md)에 보존한다.

F01 화면/연동·클릭 요청 경쟁 UI·전체 C04/BE06 부하·다른 업무의 in-flight 취소는 남아 있다.
최신 PR head의 CI·완료된 Codex 리뷰·미해결 지적 없음·보호 정책 및 병합 후 main CI는 조정자
게이트다. 한국어 단일 목적 커밋/PR로 전달하며 사용자 작업·기존 이력을 보존한다.
