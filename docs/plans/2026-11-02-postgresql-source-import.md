# DB02 승인 snapshot의 PostgreSQL 원자 복사

기준 mainf30e32d, 작업 codex/postgresql-source-import. [전환 계약](../POSTGRESQL-MIGRATION.md)의
PostgreSQL18.6/JPA(Hibernate)/Flyway Community·Psycopg3 목표를 따른다. 이번 단위는 실제
source artifact→빈 PG의16업무표 전체 복사/검수·7ID 예약/영구 수령증이다. DB03 API/JPA,
DB04 전체 거래 재인수, DB05 relay 전환, DB06 cutover/역이관은 미완료다.

## 선택·구현

- [복사 계약](../SOURCE-IMPORT.md)을 정본으로 둔다. source를 다시 조회/변경하지 않고 검수한
  파일 바이트를 한 번 잡는다. 승인 SHA/source/window와 source 형식/무결성은 별도 조건이다.
  SHA/수령증은 서명/암호화·현재 writer 중단을 증명하지 않는다.
- 기존 API Java/POM/MySQL001–008·data.sql과 Flyway V1은 바꾸지 않았다. V2는 업무16표와
  분리한 singleton import metadata이며 source/target UUID·ACK를 초기화하지 않는다.
- 대상의 실제 catalog/type/null/정밀도·PK/FK/parent schema·index·identity/sequence/C/ICU를
  검사한다. 빈 대상/수령증·모든 행 INSERT/PK tuple별 전행 대조·예약/수령증을 한 TX에 둔다.
- MySQL source의 PK 순서와 PG ICU의 정렬 순서를 동일하다고 가정하지 않는다. 각 tuple의
  정확한 원문/타입 셀을 대조하고 PK/FK 충돌은 병합/정규화 대신 전체 실패로 처리한다.
- sequence는 독립 잠금을 먼저 얻어 bare nextval과 검사/RESTART가 겹치지 않게 한다.
  transactional ALTER RESTART를 쓰고 현재 next가 source보다 크면 뒤로 옮기지 않고 거절한다.
- commit 시도/ACK 확인을 resource scope 바깥까지 추적한다. ACK 유실/부모 timeout과 ACK 뒤
  close 실패를 구분하고 자동 재실행 없이 READ ONLY verify-import로 현재 수령증/전행/예약을
  확인한다. 실제 ACK 유실을 실험했다고 쓰지 않는다.

## 기본 copied source — 345행

조정자가 소유한 새 MySQL8.4 TLS 복사본의 literal SELECT/SHOW VIEW reader를 사용했다.
원본 사용자/개발 DB·실제 서비스 API는 접근하지 않았다. source export는 읽기 전용이며 source
345행(회원6·상품80·주문3·상세4 등16표)을 그대로 보존했다. source와 별도 PG18.6 TLS/비superuser
검증 DB에서 수행했다. 사설 artifact/SQL 원문·자격/개인 경로는 공개하지 않는다.

| 실제 경계 | 결과 |
|---|---|
| fresh import |16표345행을 전부 바인딩·재조회해 모든 PK tuple/셀 일치,7개 source-next·is_called=false·수령증 source/server/window/SHA/행수 일치 |
| 동일 명령 재실행 | 기존 행/수령증으로 거절, 전체 값/sequence 상태 불변 |
| 중간 INSERT 실패 | 첫 상품 이후 trigger 오류, 앞선 회원/상품 쓰기까지 모든16표0행·수령증0·sequence 원값 rollback |
| 7예약 뒤 수령증 실패 | receipt trigger 오류, 모든 업무행/수령증과7sequence last_value/is_called 전체 원값 rollback |
| 복구 | 직접 만든 trigger/function을 제거한 같은 빈 DB·같은 파일의 실제 Python CLI import 성공 |
| collation 드리프트 | 예정 ICU인 상품 name을 C로 바꾼 대상 거절·원상 복원, 값/예약 불변 |
| FK namespace 드리프트 | 같은 이름의 authored 다른 schema parent로 바꾼 FK 거절·원상 복원, 값/예약 불변 |
| typed 충돌 파일 | authored 대소문자 ICU PK 충돌23505·missing FK23503에서 전체 rollback, 자동 병합/정규화 없음 |
| 기존 데이터 | 직접 만든 고정 기존 상품1행을 보존한 채 import 거절, sequence 불변 |
| 읽기 전용 대조 | 같은 승인 파일의 verify-import가 현재345행/수령증/예약을 대조, mutation flag 없이 성공 |

충돌 typed 파일은 exporter가 실제 MySQL에서 추출한 유효 두 계정이라고 주장하지 않는다.
오프라인 타입 검수만으로 collation/FK 정답을 인증할 수 없다는 target 실패 경계용 허구 자료다.
이 표의 숫자는 새 도구의 통제된 정합성 조건이며 서비스 오류율/성능·과거 유실 사고 개선이 아니다.

## 별도 authored 타입 source — 353행

기본345행 source를 바꾸지 않고 조정자가 같은 owned MySQL의 **별도 DB**에 seed를 복사한 뒤
검수한 허구 SQL만 실행했다. token/window/literal reader·artifact·PG target도 각각 분리했다.
추가8행/상태는 [fixture SQL](../../tools/postgresql-migration/src/test/resources/source-import-types.sql)이다.

- 16표353행 전체 PK tuple/셀과 source UUID/epoch·원문·7예약을 대조했다. READ ONLY verify도 성공했다.
- numeric15.25 soft target와 hard10.00·0.00/가격0, unknown taste/known aroma·검수 태그/빈 관계,
  취향/찜 microseconds를 importer 경로로 보존했다.
- userId/productId가 NULL인 추가 주문/상세의15,000원·단가7,500원·합계를 보존했다.
- IN_FLIGHT claim/lease·DELIVERED/ACK microseconds·attempt/error·기존 source_id를 유지한
  허구 binding/fingerprint/identity JSON 원문을 보존했다. 실제 엔진 binding/서비스 API 승인이나
  사용자 취향/제품 사실·매출 성과로 표시하지 않는다.

기본345행과 authored353행은 서로 다른 입력/target이며 합쳐698행의 운영 규모로 쓰지 않는다.

## CI와 실제 재현

기존 PostgreSQL 통합4개(기존 단위5와 합쳐9범위)의 타입/거래·30index·identity 실패/복구는
유지했고 V1+V2의 fresh migration 기대 수만2개/표18개로 갱신했다. 새 import3통합/typed1은
각각 별도4DB를 쓰며 기존 PG3DB를 초기화하지 않는다.

새 owned PG/tmpfs/동적127/TLS bootstrap에서7DB/비superuser/marker를 준비한 뒤 기존 PG와
새 source CI helper를 실제 실행했다. source helper는 별도 owned MySQL/tmpfs/동적127/TLS에서
기존 source 회귀/CLI·기본/typed artifact·PG import4조건을 모두 통과했다. 자기 두 container/
private CA·snapshot/log만 finally 정리했고 조정자 source/PG 원본은 보존했다.

첫 자체 하네스는 기존 fresh와 import_fresh DB명을 중복 지정해 preflight가 거절했다. 업무 데이터
실행 전에 자기 container/private 자료를 정리한 뒤 새 이름의 새 container에서 검증했다.
애플리케이션 이관 실패나 성능 결과로 집계하지 않는다. 이 외 개발 중 카탈로그 collation/
parent namespace와 commit 뒤 close 경계는 검수에서 보강했고 회귀에 포함했다.

최신 기본 check는 Python3.12 도구72개·API Java140개/패키징·독립 단위18개를 통과했다.
기본 경로 실DB9개 skip은 DB 성공으로 세지 않고, 위 실제 실행/required CI와 구분한다.
commit/close 정상·사전 실패·ACK 미확인·ACK 뒤 close 분류4개와 Python timeout은 단위 주입이며
실 네트워크 ACK 유실·분산 crash 검증은 아직 아니다. 원본/추가 typed XML·원문 로그는 공개
artifact에서 제외하고 안전 요약만 보존한다. Actions/latest Codex/PR·mainCI는 최종 게이트다.

API jar SHA256은 dcb73c35b4dbf148b05775992408b8d930977e84022fbabc878e68491ed22b81,
불변V1은98d9f46a24872139650694eff0cb3b6584ce9652633a8f4bd9f48fab5b3e6078로 확인했다.

## 한계·README 비교

실제 원본 규모·UTF8/문자열 질의 전면 동등성·온라인 변경 추적, source 원천 서명/파일 암호화·
보관 기한, 실제 네트워크 ACK 유실/서버 crash·cutover 뒤 역이관은 후속이다. 도구는 중단 창과
cap 이내 artifact의 빈 대상 복사만 제공하며 writer/직접 관리자 변경을 모두 통제하지 않는다.

기존 BE-CS03은 실제 HTTP·폐기 DB의 정규화/쓰기 후 실패 보강도 있는 유효 사례다. 이를
인정하면서 두 저장소의 typed 값/ID 수명·전행 검수·atomic 예약/수령증·commit/close 판정·
무덮어쓰기 설계의 직접 원인/대안/구현 근거가 더 깊어 해당 슬롯만 BE-CS06으로 교체했다.
나머지3개/순서·총4개를 보존하고 README에 회귀 수/목록·CI 집계·미측정 성능은 넣지 않았다.
