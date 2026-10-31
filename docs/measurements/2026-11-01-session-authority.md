# BE03 계정 수명·권한과 기존 세션의 정확성 비교

## 비교 대상과 측정 경계

2026-11-01에 JDK 17·MySQL 8.4와 실제 jar HTTP를 사용했다. 외부 사용자는 없고 모든 계정은
이 실험에서 생성한 fixture다. 속도·처리량·실사용자 사고율을 측정하지 않았다.

| 대상 | 식별자 |
|---|---|
| 기준 소스 | `0a3ddeec04600b94f57d3a77da53f8d507c7f68a` |
| 기준 불변 jar SHA-256 | `12f68b4eae1f152a54b83c968bf86231d498ed716ae4b0488161040a36c8715a` |
| 최종 변경 jar SHA-256 | `b4c0351e9f6fba1dab654f0b0f84c6c69c2f444aed5615e4320b3f4c09141f07` |
| 수동 003 SQL SHA-256 | `881c80bb2231b9d672b63e78b04bb901a40b827615232d6ca69502bb0da6d400` |

기준과 변경의 최종 비교는 모두 MySQL 기본 `utf8mb4_0900_ai_ci`, 같은 초기 시드의 업무 데이터,
같은 논리적 계정·요청 순서로 실행했다. 각각 별도
폐기 스키마와 새 서버/독립 cookie jar를 사용하고 서버 포트는 loopback의 빈 포트를 할당했다.
변경 DB에 필요한 `auth_epoch` 컬럼·값은 의도된 스키마 차이다. 기존 개발 DB/볼륨은 사용하지
않았다. 생성 ID·비밀번호·쿠키·epoch·DB 인증값·서버 원문 로그는 공개 근거에서 제외한다.

관측 단위는 아래의 **서로 다른 다섯 고정 시나리오, 각각 N=1**이다. 서로 다른 사건을 한데 묶어
서비스 오류율 5/5→0/5나 사람의 만족도·보안 사고 감소율로 표현하지 않는다. 시간 측정의 시작/끝,
부하·워밍업·캐시·p95 등은 이 정확성 실험의 지표가 아니며 성능 개선율도 계산하지 않았다.

## 원값

각 요청은 ApiResponse의 `data/message/error` 구조를 확인했다. 표의 실패는 지정한 상태/표시
불변식 위반 수이며, 이름/스탬프의 오래된 표시는 접근 권한 위반과 구분한다.

| 독립 경계·관측 요청 | 기준 원값 | 최종 원값 | 해당 조건의 위반 n/N |
|---|---|---|---|
| ADMIN을 USER로 수정한 뒤 기존 쿠키 `GET /api/admin/users?page=1&size=1` | HTTP 200 | HTTP 403 | 무단 관리자 허용 1/1→0/1 |
| 위 계정의 `GET /api/auth/me` | role=ADMIN | role=USER | 같은 강등 시나리오의 보조 관측 |
| 회원 삭제 뒤 기존 쿠키 `GET /api/auth/me` | HTTP 200 | HTTP 401 | 삭제 계정 인증 1/1→0/1 |
| 삭제 중 조회하지 않은 별도 쿠키로 동일 ID 재가입 뒤 `GET /api/users/{id}/profile` | HTTP 200 | HTTP 401 | 새 계정에 구쿠키 연결 1/1→0/1 |
| 관리자 비밀번호 수정 뒤 대상 구쿠키 `GET /api/auth/me` | HTTP 200 | HTTP 401 | 회수 대상 세션 허용 1/1→0/1 |
| 이름=`Updated fixture`, stamps=13으로 수정 뒤 기존 쿠키 `GET /api/auth/me` | 이름·스탬프 둘 다 이전 값 | 이름·스탬프 둘 다 수정 값 | 오래된 표시 1/1→0/1 |

삭제 직후 조회하는 쿠키와 동일 ID 재가입까지 보관하는 dormant 쿠키를 별도로 로그인했다.
dormant 쿠키는 삭제와 재가입 사이 어떤 인증 요청도 하지 않았다. 따라서 삭제 직후 한 쿠키의
세션 속성만 제거하는 구현으로 재가입 문제를 해결했다고 결론 내리지 않는다.

유지돼야 할 대조 조건도 확인했다. 같은 ID로 만든 새 계정의 새 비밀번호 로그인은 기준/변경 모두
200이었다. 명시적 비밀번호 변경 뒤 구 비밀번호 로그인은 둘 다 401, 새 비밀번호는 둘 다 200이었다.
이 로그인 조건을 이번에 새로 개선한 성과로 세지 않는다. 관측한 공개 사용자 응답에 epoch와
비밀번호/hash 필드가 없는 것도 기준/변경 모두 유지됐다.

## 원인·선택·핵심 구현

기존 Guard는 SessionUser snapshot의 역할·표시를 반환했다. 현재 ID만 조회하면 강등/삭제는
판단할 수 있어도 같은 문자열 ID의 재생성은 구분하지 못한다. 서버 전체 세션을 찾아 삭제하는
대신 계정 생성과 명시적 자격 변경의 내부 버전을 DB에 두고 매 Guard 호출에서 확인했다.

```sql
SELECT id, name, role, stamps, auth_epoch FROM t_user
WHERE id = #{id} AND auth_epoch = #{authEpoch}
```

[AccessGuard](../../src/main/java/com/pubburi/pub/controller/auth/AccessGuard.java)는 DB 결과로 최신
역할을 검사하고, 조회 뒤에도 HttpSession 신원이 요청 시작 때와 같은지 확인한다. DB 오류는
전파하며 snapshot으로 허용하지도, 정상 세션을 지워 401로 바꾸지도 않는다. 최신 표시를 반환할 때
세션 snapshot을 다시 쓰지 않아 늦게 끝난 조회가 새 로그인/로그아웃을 복구하지 않게 한다.

[UserServiceImpl](../../src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java)의 관리자 수정은
잠금 뒤 nullable patch를 현재 행과 합친다. 본인 비밀번호 변경의 결과는 잠금 안에서 캡처한
`UserUpdateResult(id, previousAuthEpoch, authEpoch, credentialsChanged)`다. 컨트롤러는 수정 뒤
계정을 다시 읽지 않고, 관측 세션 버전과 실제 변경 전 버전이 같을 때에만 그 수정이 만든 버전을
채택한다. 일반 이름/스탬프/역할 수정은 epoch를 갱신하지 않는다.

SHA-256→BCrypt 재해시는 같은 비밀번호 표현의 전환이므로 epoch를 회전하지 않는다. hash와
epoch가 일치할 때만 갱신하고 경합 시 잠금 읽기로 현재 비밀번호를 다시 확인한다. 같은 트랜잭션의
이전 snapshot을 다시 읽는 consistent read 대신 최신 값을 읽는 잠금 조회를 선택했다.
[MySQL 8.4 잠금 읽기 설명](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)을 근거로 삼았다.

## 재현 순서와 공개 실행 경로

원값 비교는 두 불변 jar에 대해 아래 순서를 각각 수행했다. fixture 관리자는 **전용 폐기 DB의
새로 만든 계정만** role=ADMIN으로 변경한 뒤 로그인했다. 기존 관리자·실사용자 계정은 사용하지 않았다.
로그인 상태가 필요한 각 주체는 별도 cookie jar를 유지한다.

1. `POST /api/users`로 실행 관리자와 대상 관리자 가입 → fixture 승격 → 각각 로그인.
   실행 관리자가 `PUT /api/admin/users/{target}`에 `{"role":"USER"}` 전송 → 대상 쿠키로 관리자 목록·me 조회.
2. 일반 회원 가입 → 같은 회원으로 쿠키 두 개 로그인 → 관리자 DELETE.
   첫 쿠키로 me 조회 → 같은 ID·새 비밀번호로 가입 → 아직 사용하지 않은 둘째 쿠키로 profile 조회 → 새 로그인 확인.
3. 다른 일반 회원 가입·로그인 → 관리자 PUT에 새 `pass` 전송 → 구쿠키 me → 구/새 비밀번호 로그인 확인.
4. 또 다른 일반 회원 가입·로그인 → 관리자 PUT에 `{"name":"Updated fixture","stamps":13}` 전송 → 기존 쿠키 me 확인.
5. 상태·역할·두 표시값·민감 필드 존재 여부만 보고서에 남긴다. 계정별 임의 값은 비교의 정답으로 사용하지 않는다.

최종 구현에는 [공개 HTTP 회귀 코드](../../scripts/api_smoke.py)의 `session_authority_contract`로
같은 실패 경계를 유지했다. 이 경로는 실행 전에 loopback·`*_ci`·컨테이너 포트·run marker를
모두 검증한다. 최종 CI 경로의 프로필 fixture 값은 `Updated session`/17이며 원값 비교의
`Updated fixture`/13과 혼동하지 않는다. CI는 이 외에도 본인 관리자 비밀번호 수정 후 현재 세션
관리자 API 200과 다른 기존 세션 401을 확인한다. 이는 별도 positive 회귀이며 위 다섯 표본에 합산하지 않는다.

전용 컨테이너/스키마/marker 준비는 [CI workflow](../../.github/workflows/ci.yml)의
`Initialize disposable database`와 [TESTING](../TESTING.md)을 따른다. 실행 예시는 Backend 루트 기준이다.

```sh
./mvnw --batch-mode --no-transfer-progress verify
# 폐기 DB·빈 서버 포트·컨테이너·run marker 환경을 확인한 뒤 실행한다.
# PUBBURI_DB_URL/USER/PASSWORD/NAME, PUBBURI_SERVER_PORT,
# PUBBURI_SMOKE_CONTAINER_ID/RUN_TOKEN은 같은 전용 환경을 가리켜야 한다.
PUBBURI_SMOKE_MUTATING=1 ./scripts/smoke.sh
```

실제 최종 jar의 표준 smoke는 CI와 같은 MySQL 기본 `utf8mb4_0900_ai_ci` 새 스키마에서 통과했다.
세션뿐 아니라 기존 HTTP envelope·권한·입력 검증, 주문 가격·쓰기 후 실패 rollback, 삭제 참조·
익명화 이력도 함께 확인했다. 공개 보고서는 `target/api-smoke-report.json`에 단계와 성공 여부를
담고 민감 원문은 담지 않는다. 이 실행 결과와 PR/main의 GitHub Actions 성공 여부는 별도 게이트다.

## 기존 스키마 전환

001·002 적용 상태의 별도 폐기 복제 스키마에 [003](../migrations/003-session-auth-epoch.sql)을
수동 적용했다. 기존 회원 6·주문 3·상세 4개를 보존했고 사용자 id/name/password_hash/role/stamps는
내부 바이트 비교에서 모두 같았다. epoch 6개가 서로 다르고 NULL 0·컬럼 NOT NULL을 확인했다.
hash/epoch 원문은 결과표에 공개하지 않았다.

기존 서버 쓰기를 중단하고 백업/복구와 현재 컬럼 상태를 확인해야 한다. 파일은 한 번 적용하는
DDL이며 전체 재실행은 첫 ADD 중복 컬럼에서 중단된다. DDL 암시적 커밋으로 전체 자동 rollback이
보장되지 않으므로 `--force`·기존 DB에 data.sql 재실행·앱 시작 시 자동 전환을 사용하지 않는다.
003으로 새 내부 버전을 부여한 뒤 구세션은 재로그인하며 비밀번호/업무 이력을 초기화하지 않는다.

## 중간 검증과 한계

- 첫 변경 jar `1efd04fa72b38b0ba3e88e829f62ce90c01d94a8c4c09a1ace56e00621895a9f`는 다섯 순차
  시나리오를 통과했지만 소스 검토에서 본인 수정의 세션 재발급 경합을 발견했다. 잠금 전에 다른
  관리자가 회전한 경우와 커밋 뒤 회전한 경우를 구분하지 못해 회수된 세션에 후속 epoch를 채택할 수
  있었다. 불변 변경 전/후 결과·동일 버전 검사·일반 수정의 epoch 미갱신으로 고쳤다. 변경 전 회전,
  커밋 뒤 회전, 사후 재조회 금지는 제어한 가짜 DAO 인터리빙으로 검증했다. 실제 DB 동시 부하 결과는 아니다.
- 표의 최종 after와 표준 smoke는 이 수정이 들어간 **b4c035…** 불변 복사본으로 다시 실행했다.
  중간 jar 통과를 최종 결과로 사용하지 않았다.
- 첫 표준 smoke 시도는 실험자가 새 DB를 `utf8mb4_unicode_ci`로 만들어 fixture 승격 SQL의
  문자열 collation과 충돌하는 MySQL ERROR 1267로 실패했다. CI 기본 조건과 다른 검증 환경 실패로
  기록하고 default0900 전용 DB를 새로 준비했다. BE03 세션 결함 수나 성공 횟수에 섞지 않았고
  오류 무시·재시도만으로 성공 처리하지 않았다. 다른 collation fixture 지원은 별도 범위다.
  기준 jar의 다섯 경계도 default0900 새 스키마/원본 시드로 다시 실행해 이전 관측과 같은 원값을
  확인했다. 위 표는 이 최종 기준과 최종 변경 결과만 사용하며 전후 collation 차이를 제거했다.
- Guard 호출마다 DB 읽기, 수정에는 행 잠금 비용이 있다. 지연·처리량·메모리는 미측정이며 개선을
  주장하지 않는다. 각 Guard의 확인 시점 이후 이미 실행 중인 모든 요청을 취소하거나 계정 삭제와
  전체 업무를 원자적으로 묶지 않는다. 분산 세션 저장소·전체 동시성/부하·BE06/C04는 남아 있다.
- 고정 시나리오의 회수·표시 정합성 증거다. 외부 사용자 만족도·실제 보안 사고율·운영 신뢰성 전체를
  입증하지 않으며 PG·AWS·모바일·새 인증 제품의 구현으로 범위를 넓히지 않는다.
