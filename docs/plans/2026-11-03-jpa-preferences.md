# DB03 본인 명시취향 JPA 연결

기준 main `9033935`, 작업 `codex/jpa-preferences`다. 원래 UserPreferenceRestController와
UserPreferenceServiceImpl을 별도 제공자에 연결하고 실제 두 표의 entity/Repository·DTO projection을
사용한다. 기존 MySQL 저장 기능의 PostgreSQL 재인수이며 새 R01 완료로 중복 계산하지 않는다.
기본 MySQL·공유 서비스·root POM·V1/V2·Java17·OSIV off/validate는 유지한다.

## 구현 전 계약과 검증 경계

- GET/PUT `/api/users/me/preferences`만 추가한다. 관리자도 본인만 읽고 body/query의 소유 선택자를
  신뢰하지 않는다. 내부 ID/epoch·inferred profile을 공개하지 않는다.
- 활성 계정에 헤더만 없을 때 NOT_STARTED다. epoch가 맞는 사용자 LEFT JOIN/값 조회를 같은
  RR에 두며 만료는401, DB 오류는 오류로 전파한다. unanswered/null·known[]·실수0과 상태를 보존한다.
- 원래 서비스의 User full FOR UPDATE/잠금 뒤 epoch 비교·전체 교체 TX를 유지한다. 헤더 upsert와
  값 삭제/삽입이 하나의 변경이며 부분 실패는 이전 헤더/값으로 rollback한다. 동시 저장은 마지막
  전체 교체가 남고 삭제/재가입·자격 회전 뒤 구 요청을 새 계정에 쓰지 않는다.
- 필수/제외 범위를 저장할 뿐 추천/필터를 새 실행하지 않는다. 정규화·정렬·엄격 입력과
  soft 목표/hard 범위 분리·생략 unknown·skip/reset all-unknown을 원래 서비스에서 유지한다.
- header SELECT/INSERT와 아홉 값 컬럼 UPDATE만, value SELECT/INSERT/DELETE만 추가한다.
  header DELETE/user_id UPDATE·value UPDATE·DDL/TRIGGER/TRUNCATE·sequence 권한은 없다.
  계정 삭제 cascade와 주문/상품/producer/source UUID 불변을 확인한다.

조정자의 폐기 PG18.6/TLS 정상/실패 DB와 별도 최소 runtime/control 예약에서 새 자체 계정만
검증한다. browser DB는 조정자 전용이며 원본 복사 행을 수정/초기화하지 않는다. 실패는 정확한
자체 user_id의 header INSERT/UPDATE와 value DELETE/INSERT 뒤에 한정하고 finally DDL을 제거한다.
자체 동시 연결로 header/values 사이 commit의 RR·구 epoch 대기·두 전체 교체 원자성을 확인한다.
원문/자격은 ignored에만 보관하며 불변 queue는 직접 정리하지 않는다.

## 실제 결과와 남은 범위

Java17 단위3/패키징이 통과했고 DB 없는 통합/준비59 skip은 실제 성공으로 세지 않았다.
control 예약의 소유자/허용 DB를 확인한 뒤 두 control 키만 자식 환경에 전달했다. 실제 PG18.6/
TLS·별도 최소 runtime에서 전체58 통합(기존49·취향 정상5/실패4)과 단위3이 통과했다.
이미 준비한 fixture1 skip은 합산하지 않았다. 입력 오류 뒤 기존 SQL 상태, null/[]/0·soft/hard·
생략 unknown·skip/reset·같은 계정 두 세션과 타인/관리자 격리를 실제 HTTP/DB로 확인했다.

테스트 전용 statement gate에서 헤더 읽기 뒤 새 전체 교체가 commit되어도 같은 GET의 헤더/값은
이전 snapshot을 보존했다. User 잠금 직전 비밀번호 회전과 삭제/동일 ID 재가입을 각각 완료한 뒤
구 PUT401·새 값 불변/새 계정 NOT_STARTED를 확인했다. 다른 gate는 첫 요청의 사용자 잠금/
헤더 저장 뒤 두 번째 요청의 대기를 확인하고 마지막 전체 교체의 헤더/값만 남겼다. 통제한
0.2초 대기 확인은 정확성 실험이며 서비스 성능이 아니다.

failure DB의 정확한 자체 사용자에 header INSERT/UPDATE·value DELETE/두 번째 INSERT 뒤
예외를 넣어 이전 헤더/목록·계정/상품/producer/source UUID가 모두 보존되는지 확인했다.
실패 DDL은 finally 제거했고 제거 뒤 같은 요청은 성공했다. header DELETE/user_id UPDATE·
value UPDATE/TRUNCATE의 runtime 거절도 확인했다. 동결 JAR SHA256은
`50c3e1fde4a329dd7e5874aaea19f384add6735e422dd282bc78844b614072ca`다.

Java17/Python3.12 전체 `./scripts/check.sh`가 통과했다. 기본 Java140·도구 Python86·이관 단위18·
JPA 단위3을 확인했고 DB 없는 skip은 실제 DB 성공으로 세지 않았다. 재빌드한 실행 JAR의 SHA도 같았다.

독립적인 기존 MySQL 패키지와 같은 JPA 패키지는 고정79개 HTTP의 상태와 전체 JSON이 같았다.
정규화/시각 제외는 없었다. 오류23개400 뒤 상태 불변, null/known[]/0·soft15.25와 hard0–10 분리·
전체 교체/skip/reset·쉼표/줄바꿈을 포함한 원자 태그·관리자/타인/선택자 격리·삭제/재가입 epoch를
포함했다. 시나리오 SHA256은
`35e575c49f486591f9770cb55316566e4aaca682bb0c371bd87fbf65d5114418`다.
새 자체 두 계정만 작성/삭제했으며 원본 행을 변경하지 않았다.

같은 JAR의 packaged smoke는 실제 OpenAPI32경로/39작업과 명시취향 저장·반복/전체 교체·
skip/reset·재시작/재로그인·삭제/재가입의 새 NOT_STARTED를 확인했다. 기존 찜/후기/계정/주문/
상품 흐름과 잘못된 기동의 거절도 유지됐다. 소유 JVM6개는 모두 종료됐고 정상/실패 두 DB에서
자체 계정/상품/후기/찜/취향 헤더/값·실패 trigger/function 잔여는 각각0이었다.
불변 version/event/delivery는 정리 대상으로 삼지 않았다.

고정된 기존 React 산출물과 같은 JPA JAR의 선택된 실제 취향 폼도 통과했다. 로그인/프로필 링크·
unknown GET, 내부 줄바꿈을 포함한 원자 태그·known[]·0·soft80.25/hard0–10.5 PUT, reload/[]
재편집, 건너뛰기 취소의 draft 보존→확정 SKIPPED 전체 clear·NOT_STARTED reset을 확인했다.
다른 실제 계정의 private cache가 격리됐고390px 웹 가로 넘침·페이지 오류·예상 밖 API 오류는0,
응답 stub은0이었다. 검증 프로세스는 모두 종료됐다. 기존 폼을 새 영속 제공자에 연결한 증거이며
전체 R01/추천 caller·Psycopg3 relay·기본 PG 전환/DB03–06 완료로 확대하지 않는다.

README 네 사례와 비교하되 이번은 기존 계정 수명/저장 계약의 재인수다. 더 강한 독립 영향/원인
근거가 없다면 본문/순서를 보존한다. 새 API 연결이나 회귀 수를 과거 운영 결함/성능 개선으로 만들지 않는다.
11월22일 DB/React·12월31일 전체20개 MVP와 최신 CI/완료 Codex/미해결0 게이트를 유지한다.
