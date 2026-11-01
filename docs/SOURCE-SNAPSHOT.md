# MySQL 원본 스냅샷 추출 계약

DB02의 **읽기 전용 export와 오프라인 검증**이다. 현재16업무표를 하나의 InnoDB 행 snapshot에서
추출하며 PostgreSQL import·전체 복사·JPA API·cutover는 구현하지 않았다. 현재 API/MySQL001–008,
PostgreSQL Flyway V1과 원본 계정/상품/주문/relay 상태를 자동 변경하지 않는다.

## 실행과 소유권

Java17 독립 모듈만 빌드하고 [도구 환경 예시](../tools/postgresql-migration/.env.example)의 SOURCE
환경을 명시한다. 비밀번호/신뢰 저장소 암호는 환경에서만 읽고 명령 인수에 넣지 않는다.

~~~bash
./mvnw --batch-mode --no-transfer-progress -f tools/postgresql-migration/pom.xml verify
python3 scripts/mysql_source_snapshot.py export-source
python3 scripts/mysql_source_snapshot.py validate-source
~~~

export는 `*_ci` 복사 DB와 명시 CID·pubburi.validation label·Running·정확한 단일 numeric loopback
3306 publication을 요구한다. Java도 실제 DB명/8.4 버전·SSL cipher와 별도 marker를 검사한다.
원본 계정은 해당 DB의 SELECT/SHOW VIEW만 허용하고 SHOW GRANTS에서 추가/역할/전역 권한을
거절한다. 검증용 제어 계정/관리자 자격은 export 자식 프로세스에 전달하지 않는다.

JDBC는 이 독립 모듈에만 Connector/J **26.10.0**을 고정한다. 현재 API의 의존성은 바꾸지 않는다.
[공식26.10.0 release](https://dev.mysql.com/doc/relnotes/connector-j/en/news-26-10-0.html)와
[MySQL8.4+/Java8+ 지원](https://dev.mysql.com/doc/connector-j/en/connector-j-versions.html)을 확인했다.
Java17에서 실제 협상한 TLS를 검사하며 명시 PKCS12 공개 CA·`sslMode=VERIFY_IDENTITY`로 CA와
numeric IP SAN을 검증한다. 시스템 trust fallback·서버 공개키 자동 조회·LOCAL INFILE은 끈다.
[공식 TLS/신뢰 저장소 속성](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html)을 따른다.
CA private key를 원본에서 가져오거나 Git/아티팩트에 넣지 않는다.

## 작업 창과 일관성의 범위

운영자가 쓰기·relay·DDL을 중지한 복사본에서만 `PUBBURI_SOURCE_WRITES_STOPPED=1`과
`PUBBURI_SOURCE_DATETIME_ZONE=UTC`를 명시한다. 별도 control plane이 준비한
`pubburi_source_snapshot_guard(token,state,window_uuid)`의 state는 QUIESCED, token과 window는
이번 실행 값과 정확히 같아야 한다. 추출 전과 모든 행을 읽은 뒤 **별도 새 연결**에서 검사한다.
도구가 쓰기를 중지하거나 실제 전체 writer를 통제하는 기능은 아니다. 운영자의 중단 선언을
그대로 믿는 것만으로 모든 동시 변경/관리자 ABA를 탐지했다고 주장하지 않는다.

한 연결에서 REPEATABLE READ·READ ONLY·WITH CONSISTENT SNAPSHOT을 설정/재확인하고16표의
일반 읽기로 metadata lock을 유지한다. LOCK TABLES/RELOAD/global 권한을 요구하지 않는다.
PK/컬럼 타입·nullable·길이/정밀도·InnoDB·FK/delete rule·인덱스 키/NULL 순서의 정규화 계약을
대조하고 FK 데이터는 원본 MySQL의 실제 collation으로 검사한다. 현재 source의 문자 charset/
collation 이름도 표/컬럼별로 artifact에 보존한다. 이를 PostgreSQL ICU와 Unicode 전체가
동일하다는 증명으로 사용하지 않는다.

행 RR snapshot과 `INFORMATION_SCHEMA.TABLES.AUTO_INCREMENT`는 같은 MVCC snapshot이 아니다.
`information_schema_stats_expiry=0`으로 캐시 값을 사용하지 않고7표의 source-next를 전후 대조해
변경되면 추출을 거절한다. 이미 rollback된 INSERT도 고수위를 올릴 수 있으므로 MAX(id)+1로
대체하거나 원본 counter를 reset하지 않는다. 삭제 후 유지된 상품 version ID보다도 커야 한다.
[일관 snapshot/READ ONLY](https://dev.mysql.com/doc/refman/8.4/en/commit.html)와
[rollback되지 않는 AUTO_INCREMENT 할당](https://dev.mysql.com/doc/refman/8.4/en/innodb-auto-increment-handling.html)을 구분한다.
쓰기/DDL 중단과 같은 작업 창은 후속 import/검수까지 별도 유지해야 한다. 이 export만으로
온라인 무중단 이관이나 metadata의 원자 snapshot을 보장하지 않는다.

## 형식과 보존

`pubburi-mysql-source-v1`은 고정16표/92컬럼·PK 순서의 행 배열이다. 업무 컬럼명/타입 계약은
[table](../tools/postgresql-migration/src/main/resources/source-table-contract.tsv),
[PK](../tools/postgresql-migration/src/main/resources/source-primary-contract.tsv),
[FK](../tools/postgresql-migration/src/main/resources/source-foreign-contract.tsv),
[index](../tools/postgresql-migration/src/main/resources/source-index-contract.tsv)를 따른다.

- 정수/금액/ID/source-next는 canonical 10진 **문자열**이다. JS 정수/실수 변환으로 정밀도를 잃지 않는다.
- FLOAT/DOUBLE은 각각8/16자리 IEEE754 raw bit hex다. 기존 단정도·음수0을 보존하며 비유한 값은 거절한다.
- numeric(5,2)는 고정 scale2 문자열, BOOL은 정확한 JSON boolean이다. 숫자0/false/문자열/NULL을 서로 바꾸지 않는다.
- TIMESTAMP는 연결 UTC·canonical Instant 문자열이며 원본0/6자리 정밀도를 검사한다. zero date/변환 불가 시각을 보정하지 않는다.
- JSON은 DB가 반환한 **원문 텍스트를 문자열 셀**로 보관한다. 이 문자열 내부의 공백/키 순서를 재직렬화하지 않는다. MySQL 저장 전 입력이 아니라 DB 반환 원문이다.
- SQL NULL, JSON 원문 `null`, 빈 배열 원문 `[]`, known flag·빈 값 관계를 구분한다. ID/epoch/source UUID·nullable 삭제 참조/주문 snapshot도 같은 방식으로 보존한다.
- 문자 NUL·잘못된 surrogate, 고정 CHAR의 원문 길이 손실 가능성, 미지원 charset·schema/type 변화는 자동 정규화 대신 거절한다.

서버/업무 source UUID·작업 창·컬럼 collation·source-next·계약 hash와 표별 canonical row hash를
포함한다. 전체 행10,000개·파일16MiB·셀4MiB·JSON 깊이64로 범위를 제한한다. JDBC streaming과
bounded output을 사용하며 상한 초과는 부분 artifact만 남기고 실패한다. 큰 원본을 자동 분할해
일관성을 추정하지 않으며 해당 경우 별도 설계가 필요하다.

## 승인·보안·재사용

출력은 저장소 ignored `.pubburi` 안의 **새 디렉터리**여야 한다. mode0700/파일0600을 요구하고
기존 출력/심볼릭 링크를 채택·덮어쓰지 않는다. `receipt.json`은 accepted=false에서 시작한다.
행/고수위/최종 작업 창 검사·atomic rename 뒤 완성 파일을 같은 오프라인 검증기로 먼저
검수한 다음에만 accepted=true를 게시한다. 실패/부분 receipt는 후속 사용 불가이며 자동 삭제/재시도로 감추지 않는다.
동일 artifact의 validate는 DB 연결 없이 반복 가능하지만 export 재사용이나 import 멱등성을 뜻하지 않는다.

오프라인 검사는 바이트/표 hash·정확한 필드/타입·NULL·PK 중복·source ID/고수위와 계약을 검수한다.
모든 target FK/ICU 충돌·업무 제약 검증 또는 원천 인증을 대체하지 않는다. **SHA256은 무결성
검사이며 암호화/서명/익명화가 아니다.** 파일에는 계정 식별자·이름·password hash·주문 등이
포함되므로 원본 수준의 비공개 자료다. Git/PR/CI artifact/채팅/로그에 공개하지 않는다. 이관 전
보관 기간/백업 암호화·접근 통제는 별도로 정하고 후속 작업에서 공개 raw seed를 재생성하지 않는다.

실제 검증/준비 실패·완료 범위는 [작업 기록](plans/2026-11-02-mysql-source-snapshot.md)에 둔다.
