# 세션과 현재 계정·권한의 연결

로그인 시 저장한 SessionUser의 role/name/stamps를 현재 권한으로 신뢰하지 않는다. 보호 요청의
AccessGuard는 세션의 id+authEpoch로 DB 계정을 조회하고 최신 role/name/stamps를 반환한다.
auth_epoch는 계정 생성마다 새로 만드는 내부 UUID이며 공개 UserResponse 필드에 추가하지 않는다.

| 변화 | 다음 인증 검사에서의 동작 |
|---|---|
| ADMIN→USER | 기존 세션도 관리자 API 403, auth/me는 USER |
| 계정 삭제 | 기존 세션 401, 그 세션의 이전 신원 제거 |
| 같은 문자열 ID 재가입 | 다른 auth_epoch이므로 삭제 중 조회하지 않은 구세션도 401, 새 로그인은 가능 |
| 명시적 비밀번호 변경 | auth_epoch 회전으로 다른 기존 세션 401, 새 비밀번호 로그인 가능 |
| 관리자 본인 비밀번호 수정 | 관측 세션과 잠금 중 변경 전 epoch가 일치할 때 해당 수정의 실제 epoch만 채택 |
| 이름/스탬프 수정 | epoch를 보존하고 auth/me는 DB 최신 값 반환 |
| DB 조회 실패 | 예외 전파·보호 동작 거부. 저장된 권한으로 허용하거나 세션을 지워 비로그인으로 위장하지 않음 |

401은 유효한 계정/자격 연결이 없는 경우, 403은 현재 인증된 계정의 권한이 부족한 경우다.
삭제된 회원 주문은 BE02에서 소유자 null로 보존되므로 관리자만 읽는다. 그 주문을 새 재가입
계정에 연결하지 않는다. 삭제 계정의 옛 세션이 받는 상태는 이제 401이며 활성 타인 계정은 403이다.

## 상태 갱신과 경합 경계

권한 조회는 HttpSession snapshot을 덮어쓰지 않는다. DB 조회 뒤에도 현재 세션의 id/epoch가
요청 시작 신원과 같은지 확인해 조회 중 로그아웃·다른 계정 로그인을 이전 snapshot으로 복원하지 않는다.
일반 정보 수정은 세션 epoch를 바꾸지 않는다. 로그인/본인 비밀번호 수정의 세션 쓰기와 조건부 제거는 같은 세션 모니터로 직렬화한다. DB 예외에는 이
정리 경로를 실행하지 않는다. 이는 로컬 단일 애플리케이션 세션의 경계이며 분산 세션 저장소를 구현한 것은 아니다.

본인 비밀번호 수정은 서비스가 잠금 안에서 캡처한 UserUpdateResult(id·변경 전 epoch·실제 적용 epoch·
비밀번호 변경 여부)만 사용한다. 컨트롤러가 수정 뒤 계정을 재조회해 그때의 epoch를 채택하지 않는다.
관측 epoch와 잠금 이전 epoch가 다르면 이미 회수된 세션을 갱신하지 않고 제거한다. 서비스 커밋 뒤
다른 관리자가 다시 회전한 경우에도 이 수정이 만든 epoch만 넣으므로 다음 검사는 새 회전을 거부한다.

관리자 수정의 생략 필드는 컨트롤러에서 과거 조회 값으로 채우지 않는다. UserUpdate의 nullable
필드를 서비스 FOR UPDATE 뒤 현재 행으로 해결해 생략한 role/password/epoch/stamps가 오래된
fallback으로 되돌아가지 않게 한다. 명시한 비밀번호만 해시·epoch를 바꾸고 프로필/역할 수정은 epoch를 유지한다.

SHA-256→BCrypt의 동일 비밀번호 재해시는 자격 변경과 구분한다. 기존 hash+auth_epoch가 그대로일
때만 조건부 UPDATE해 동시 비밀번호 초기화/재가입을 덮어쓰지 않는다. 경합으로 갱신하지 못하면
FOR UPDATE의 최신 행에서 입력 비밀번호를 다시 검증한다. 일반 consistent read는 같은 트랜잭션의
이전 read view를 읽을 수 있으므로 이 재검증에 사용하지 않았다. [MySQL 잠금 읽기](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)

권한 검사는 각 Guard 호출 시점의 DB 상태를 확인한다. 이미 검사를 통과해 실행 중인 모든 작업을
취소하거나 전체 요청을 계정 삭제와 원자적으로 묶지는 않는다. 그 동시성 경계는 BE06/C04의 후속 검증이다.
Guard 호출마다 DB 읽기 비용이 추가되며 지연/처리량 개선으로 주장하지 않는다.

## 기존 DB 전환

새 폐기 DB는 현재 data.sql을 사용한다. 기존 DB는 001→002가 적용된 상태에서
[003-session-auth-epoch.sql](migrations/003-session-auth-epoch.sql)을 수동 적용한다.

1. 백업과 복원/전환을 별도 폐기 복제 DB에서 검증하고 모든 구버전 애플리케이션 쓰기를 중단한다.
2. 기존 사용자/주문/상세 개수와 사용자 id/name/password_hash/role/stamps가 보존되는지 내부에서
   비교한다. 비밀번호 해시·실제 epoch 값·쿠키·DB 설정을 공개 로그/보고서에 내보내지 않는다.
3. auth_epoch가 없는 스키마에 ADD nullable→NULL 행에 UUID 채움→NOT NULL 순서로 실행한다.
   기존 DB에 data.sql을 재실행하거나 mysql --force로 오류를 무시하지 않는다.
4. 아래 개수/NULL/길이와 컬럼 NOT NULL을 확인한 뒤 새 서버를 시작한다. 새 API를 먼저 실행하면
   필요한 컬럼이 없으므로 인증할 수 없다. epoch가 없던 기존 세션은 재로그인해야 한다.
5. MySQL DDL은 암시적으로 커밋하므로 파일 전체가 자동 롤백되지 않는다. 부분 실패 때 현재 컬럼/
   NULL 상태를 확인하고 백업 복구 또는 검토한 단계부터 재개한다. 전체 재실행은 첫 ADD 중복 컬럼에서 중단된다.

```sql
SELECT COUNT(*) AS users,
       COUNT(DISTINCT auth_epoch) AS distinct_epochs,
       SUM(auth_epoch IS NULL OR CHAR_LENGTH(auth_epoch) <> 36) AS invalid_epochs
FROM t_user;
SELECT IS_NULLABLE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'auth_epoch';
```

003은 기존 비밀번호/개인정보를 변경하지 않고 내부 계정 식별자를 추가한다. 새 가입/명시적 비밀번호
변경은 애플리케이션이 UUID를 생성하고 초기 데모 시드는 MySQL UUID()를 사용한다. 기존 데이터/
세션을 자동 전환하지 않으며 AWS·외부 인증 서비스·실제 결제 연동도 추가하지 않는다.
