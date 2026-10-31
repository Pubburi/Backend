# C03/BE04/R01 본인 전용 명시취향 API 증가분

기준 main은 `75b30c32a51685dec8be8120343c1d7c0e7644a7`이다. 기존 고객/관리자·상품 특성·
세션 계약을 유지하며 본인의 명시 입력만 저장/조회한다. 관리자도 타인 취향을 읽는 예외가 없다.
R01 화면·추천 호출·모델/사용자 학습·추정 행동은 이번 증가분이 아니며 전체 R01/BE04 완료가 아니다.

## 결정·구현

- GET/PUT `/api/users/me/preferences`, JSON·ApiResponse·BE03 현재 계정/epoch 인증을 사용한다.
  [계약](../EXPLICIT-PREFERENCES.md)·[OpenAPI](../openapi/explicit-preferences.json)가 제공자 정본이다.
- surveyStatus=NOT_STARTED/SKIPPED/COMPLETED, softPreferences와 hardConstraints를 분리한다.
  soft categories/태그/목표 도수를 필수 필터로 바꾸지 않는다. 목표가 hard 범위 밖이어도 허용 범위는 그대로다.
- null은 미입력, []는 명시 선호 없음, 0은 실제 수치다. 전체 PUT에서 생략 필드는 unknown이며
  건너뛰기/초기화는 모든 값 null만 허용한다. COMPLETED all-unknown도 명시 상태로 유지한다.
- 소유 ID/epoch는 세션에서만 전달한다. body/query의 userId/authEpoch로 타인을 선택하지 않는다.
  본문·기존 회원/타인 profile/관리자 응답에 취향이나 내부 인증 정보를 추가하지 않는다.
- 읽기는 id+epoch의 LEFT JOIN과 같은 버전의 값 조회를 RR 트랜잭션에 둔다. 살아 있는 사용자에
  취향 행이 없는 경우만 NOT_STARTED이며 삭제/epoch 불일치는 401이다. DB 장애를 빈 설문으로 위장하지 않는다.
- 쓰기는 사용자 FOR UPDATE 후 현재 epoch를 비교한다. Guard 뒤 삭제/같은 ID 재가입·자격 회전이
  끝났으면 그 새로운 계정에 구요청을 쓰지 않는다. 헤더/목록 전체 교체는 한 트랜잭션이며 값 쓰기
  실패는 예외로 rollback한다. 회원 삭제는 새 두 테이블을 CASCADE로 제거한다.
- 005는 두 빈 테이블만 생성한다. 원래 9개 테이블/상품 INSERT·개인/인증 값은 바꾸거나 추정하지 않는다.
  정수/문자열/수치 타입·범위/정밀도·역전·중복/주종 검증은 DB 반올림/잘림 전에 수행한다.

## 검증·조건·원값

JDK 17의 Java 단위/MockMvc·실제 MVC 문서 회귀 72개, Maven verify/package와 Python 도구/안전
게이트 24개를 통과했다. 구현 세부 순서 자체보다 응답의 소유권·상태·값과 실패/공개 경계를 확인한다.
가짜 DAO의 쓰기 실패는 실제 DB rollback 증거와 구분한다. 보안/정합성 과제이며 성능 개선율은 산출하지 않았다.

최종 불변 jar SHA-256: `9d561468cae93d7e5ba7e8e251b77292c02886cbe52b11632f945f6c970cfe8c`.
005 SHA-256: `345fcc37ed818db71f08c4b6db393e1904b6854ea714aaba8c611e3ae27d2951`.
조정자가 동일 jar·MySQL 8.4·독립 폐기 DB와 새 세션으로 다음 고정 기능 조건을 검증했다.

| 범위 | 실제 결과 |
|---|---|
| 두 회원·관리자 | 본인 데이터 격리, body/query 소유자 미신뢰, 응답에 소유/인증 필드 없음 |
| 상태/값 | null·[]·0, skip clear·반복 전체 PUT, soft target 15.25와 hard max 10을 그대로 보존 |
| 입력 실패 | 23개 invalid 요청 400, 그 뒤 기존 헤더/값 유지 |
| 쓰기 중 DB 실패 | 헤더 교체/이전 값 삭제 뒤 INSERT trigger 실패 500. 기존 헤더와 네 값 모두 복구 |
| 계정 수명 | 삭제 후 데이터 제거·같은 ID 재가입 fresh NOT_STARTED·구쿠키 거부 |
| 실행 명세 | 본인 경로·enum·JSON/nullable·Swagger UI 확인 |

별도 **제어한 행 잠금 인터리빙 한 시나리오**에서는 옛 계정의 PUT이 Guard를 지난 뒤 사용자 행
잠금에서 대기하도록 했다. 잠금 보유 트랜잭션이 DELETE→같은 ID/새 epoch INSERT→COMMIT한 뒤
대기 PUT은 401이었다. 이 제어 실험은 기존 비밀번호 해시를 재사용하고 새 epoch를 부여했다.
재생성 계정의 새 로그인 세션은 NOT_STARTED, 두 취향 테이블은 0행이었다. 이는
새 저장 경계가 계정 인스턴스에 묶인다는 통제 실험이며 자연 발생 동시 부하·모든 in-flight 취소·
운영 오류율을 입증하지 않는다. 이전에 없던 API의 404→200을 결함 감소로 세지도 않는다.

005를 004까지 적용한 기존 폐기 스키마에 수동 적용해 9개 기존 테이블의 전체 UTF-8 byte hash가
모두 같음을 확인했다. 회원 6·상품 80·주문 3·상세 4와 원래 자료를 보존했고 새 두 테이블은 0행이다.
기존 사용자/개발 볼륨을 사용하지 않았다. 최초 임시 MySQL bootstrap readiness 준비 시도는
테스트 환경 준비 실패로 정리하고 인증된 TCP readiness 뒤 새 환경에서 검증했다. 제품 결함이나
실패율의 분모에 섞지 않았다.

최종 `scripts/smoke.sh`도 동일 불변 jar/전용 MySQL에서 통과했다. 기존 주문·가격·쓰기 후 실패
rollback·삭제 이력·세션·상품 특성과 새 private_preferences 경로, 실행 문서까지 유지했다.
공개 필수 smoke의 합성 입력과 수동 HTTP의 23개 입력은 서로 다른 고정 검증 집합이며 실제
사용자 선호/추천 품질·클릭/전환 성과가 아니다. 실행은 [TESTING](../TESTING.md)의 폐기 환경·marker와
`./scripts/check.sh`, `PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh`를 따른다. PR 최신 CI·완료된
리뷰·병합/main CI는 별도 조정자 게이트다.

## 사례 비교·남은 범위

현재 README BE-CS02는 실제 기존 세션의 권한/계정 변경 오류 전후와 버전 경계를 다룬다. 이번
증가분의 private 상태·행 잠금 ABA 실증은 같은 수명 문제의 추가 설계 근거지만, 새 기능의 방어
설계를 기존 운영 결함 수정처럼 포장해 다른 사례를 교체할 근거는 아니다. 네 사례/순서를 유지하고
이번 실험은 향후 인증/업무 경계 보강 자료로 보관한다. 테스트 수/최신성으로 선정하지 않는다.

R01 UI·후속 추천 호출에서 soft/hard 전달·필수 조건 준수·fallback의 실제 연결은 남아 있다.
ETag/revision 충돌·전체 동시 부하·다른 업무의 진행 요청 취소는 이번에 완료하지 않았다. 상세
모델/평가/데이터 출처는 Recommendation 정본을 유지하며 Backend에 복제하지 않는다.
