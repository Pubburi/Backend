# DB02 읽기 전용 MySQL source snapshot

기준 main3d818625, 작업 codex/mysql-source-snapshot. 최신 사용자 목표 PostgreSQL18.6·
JPA(Hibernate)·Flyway Community/Psycopg3와 [전환 계약](../POSTGRESQL-MIGRATION.md)을 따른다.
이번 단위는 현재16업무표의 source export·typed manifest·오프라인 검증이며 **PG import/전체
복사/DB03 JPA API/relay 전환/cutover 완료가 아니다**. 기존 이관 전 API와 과거 완료 이력은 보존한다.

## 결정과 구현

- 독립 모듈만 Connector/J26.10.0을 추가해 Java17/현재8.4 source를 읽는다. root API POM/jar,
  mapper/data.sql/001–008, 기존 Flyway V1은 변경하지 않았다. 공식 지원/보안 속성은
  [source 계약](../SOURCE-SNAPSHOT.md)의 primary 문서를 따른다.
- numeric loopback/CID/label/publication·read-only grants·CA/SAN/TLS·작업 창 token/UUID/QUIESCED와
  운영자의 쓰기/DDL 중단 선언을 먼저 요구한다. read-only RR snapshot과 ordinary metadata lock을
  사용하며 LOCK/RELOAD/global 권한을 추가하지 않는다. 현재 source의 PK/컬럼/정밀도·FK/참조·
  index 의미를 검사하고 charset/collation도 보존한다. target ICU의 전면 동등성은 미검증이다.
- 7개 AUTO_INCREMENT는 비MVCC이므로 캐시 없이 전후 읽고 다르면 거절한다. MAX+1/ID reset을
  사용하지 않으며 마지막 fresh connection의 maintenance marker를 별도로 확인한다. 모든 writer
  통제/관리자 ABA 검출 기능은 아니므로 후속 import까지 실제 쓰기/DDL 중단이 필요하다.
- immutable payload의 DB 반환 원문·UUID/epoch·가격·nullable 삭제 참조·시간·NULL/0·known state,
  F32/F64 raw bits와 source-next를 표현한다. 정수는 문자열, boolean은 정확한 타입이다.
- 파일16MiB/셀4MiB/전체10,000행/JSON깊이64 상한과 streaming·private permissions를 사용한다.
  새로운 ignored 출력만 허용하고 accepted=false→최종 정합성 검사→atomic rename→완성 파일
  오프라인 검수→accepted=true다.
  실패 자료는 불승인 상태를 유지한다. offline validate는 반복 가능하며 같은 경로 export는 거절한다.
- 원본에는 사용자 식별자/password hash가 포함되므로 plaintext/개인자료로 다룬다. SHA256은
  암호화/서명/익명화가 아니다. Git/PR/CI 원본 업로드 금지·source integration XML도 제외한다.

## 실제 검증

조정자가 만든 **별도 copied MySQL8.4**에서 Java17/JDBC26.10.0을 사용했다. TLS는 자체 CA와
SAN127.0.0.1 VERIFY_IDENTITY, reader는 해당 복사 DB SELECT/SHOW VIEW만 보유한다.
제어 계정은 같은 DB의 고정 상품/회원 필드·marker·authored nullable column만 변경하고 finally
원값/스키마를 복원했다. 사용자 개발/원본 DB·다른 schema·container/global grant는 건드리지 않았다.

| 경계 | 실제 확인 |
|---|---|
| 최초/최종 export |16표/92컬럼·345행(회원6/상품80/주문3/상세4)·PK 순서·현재 타입/nullable/index/FK·source UUID와7고수위의 정상 추출/오프라인 검증 |
| read-only 권한 | reader UPDATE 실패, 별도 writer 계정은 exporter가 권한 검수에서 거절 |
| 같은 RR의 행 | SourceSession 시작 뒤 상품/회원 이름을 별도 TX로 함께 commit했어도 두 export 행은 이전 값; finally 원값 복원 |
| source-next 비MVCC | snapshot 뒤 authored 상품 INSERT+ROLLBACK가 counter를 올리면 최종 거절/accepted=false; counter는 뒤로 되돌리지 않음 |
| 작업 창 | 추출 중 marker ACTIVE면 fresh 최종 연결이 거절/accepted=false; fixture marker 복원 |
| PG 비호환 값/DDL | 상품명 NUL 추출 거절/accepted=false; authored nullable 컬럼 추가 시 schema 거절, 각각 원값/컬럼 복원 |
| 최종 행 보존 | 최초/최종16표의 canonical row SHA가 모두 동일; 원본 고수위는 rollback 할당을 반영해 더 높게 유지 |
| 사설 artifact | offline 반복 검증 통과, 같은 export directory 거절, hash/타입/추가 필드/불승인 receipt/공개 권한 거절 |

이는 **새 도구의 통제된 정합성 실험**이다. 과거 원본 유실 사고, 고객 부하/성능, PG 전체 데이터
복사 성공률로 표현하지 않는다. 일부 행의 개별 타입 검사와 source FK 검수가 target 전체 업무/
Unicode 제약의 성공을 대신하지 않는다.

별도로 실제 CI helper를 승인된 새 mysql:8.4/tmpfs/동적127포트 컨테이너 하나에서 실행했다.
서비스 생성→TCP/TLS 준비→현재 data.sql 복사본+최소 reader/control 생성→실 Source 통합과
wrapper export/validate 두 번/출력 재사용 거절이 통과했다. 기존 API 서비스 CA와 별개다.
마지막 소유 CID/label 확인 후 새 container·private key/CA·source snapshot/log를 finally 정리했다.
안전 요약 target/source-snapshot-report.json만 남기며 원본/비밀번호/개인 경로는 공개하지 않는다.

## 실패와 한계

최초 소스 컴파일의 괄호 오타는 DB 실행 전에 수정했다. 기본 sandbox의 기존 non-TLS peer 단위는
socket bind가 금지돼 실패했으며 허용된 로컬 소켓 환경에서 재실행해 통과했다. 이를 도구/서비스
데이터 손실이나 개선 성과로 세지 않는다. 원본 실제 통합과 별도 CI bootstrap은 모두 통과했으며
완성 파일을 accepted=true 전에 검사하도록 보강한 중간 실행에서는 메모리 LongNode와 파일
JSON 정수 노드의 표현 차이로 source_integer_required가 발생했다. 같은 직렬화 바이트를 검수해
엄격 정수 검사를 유지했고, 해당 원본 export는 accepted=false였다. 수정 후 새 별도 컨테이너에서
동일 CI helper 전체와 원본 reader CLI export/오프라인 두 번 검증이 통과했다. required Actions/
latest Codex/PR·mainCI는 조정자의 최종 게이트다.

원본 metadata는 비MVCC이고 중단 선언을 물리적으로 강제하지 않는다. cap 초과의 대형 source,
원천 증명 서명/암호화·보관 기한, target import/전체 FK·collation 충돌·금액/계정/queue 업무 재인수,
동일 artifact의 import 멱등성/실패 전체 rollback·sequence 예약 통합은 후속이다. source UUID/epoch/
ACK를 자동 reset하거나 기존 DB를 초기화하는 복구 명령은 추가하지 않았다.

## README 선정

네 사례의 실제 거래·세션·검색 상태/업무 영향보다 현재 export 단독 근거가 더 강하다고 보지
않아 기존 네 사례/순서를 보존했다. README에는 실행 도구와 상세 계약 링크만 추가하며 테스트
개수·회귀 목록/CI 집계·측정하지 않은 속도 개선을 넣지 않는다.

최종 기본 `./scripts/check.sh`는 Python3.12 도구69개·현재 API Java140개/패키징·독립 모듈
단위10개를 통과했다. DB없는 기본 경로의 PG통합4개/source통합1개 skip은 실제 DB 통과로
세지 않는다. 별도 source CI bootstrap은 Source단위5개와 실제 통합1개/CLI를 실행해 성공했다.
기존 PG모듈9개(단위5+실PG4)와 MySQL HTTP14·실engine의 required CI는 유지한다. API jar SHA256은
dcb73c35b4dbf148b05775992408b8d930977e84022fbabc878e68491ed22b81, 불변V1은
98d9f46a24872139650694eff0cb3b6584ce9652633a8f4bd9f48fab5b3e6078로 확인했다.
