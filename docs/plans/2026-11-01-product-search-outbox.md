# S02 업무 변경의 원자적 버전·outbox 생산

기준 main23620ceb, branch codex/product-search-outbox. [선행 로컬 엔진](2026-11-01-local-search-replay.md)의
다음 단계로, 승인된 업무 쓰기와 검색 변경 기록을 동일 트랜잭션으로 묶는다. 새 기능 증가분이며
기존 운영 장애/성능 개선으로 표현하지 않는다. 정본은 [생산 계약](../SEARCH-OUTBOX.md)이다.

## 구현·선택

- 생성·기본 수정·특성 교체·주문 수 증가·삭제 후 MANDATORY producer를 호출한다. counter와
  immutable projection/event는 업무 TX에 참여하며 엔진 장애/키/외부 API를 요구하지 않는다.
- 상품/특성의 current locking read 뒤 영속 카운터를 증가시키고 UNIQUE 상품/버전 이벤트를 쓴다.
  삭제 뒤 카운터/이력을 보존하고 tombstone은 상품 원문을 포함하지 않는다.
- 007은 모든 쓰기를 중단하고 새2표 DDL 뒤 현재 상태 v1/backfill만 수행한다. 기존 표를 바꾸거나
  지난 삭제를 복원하지 않는다. DDL/DML 실패와 재실행 방침을 계약에 구분했다.
- 원격 전송까지 업무 TX에 넣으면 엔진 장애가 주문/상품 잠금을 늘릴 수 있어 제외했다. 별도 TX/
  afterCommit 전달은 업무만 성공하고 기록이 없는 틈이 생기므로 선택하지 않았다.
- 전역 autoID/최댓값을 commit watermark로 사용하지 않는다. dispatcher의 claim/ack·실패 재처리,
  alias 세대 전환·공개 검색은 후속이며 이 단계로 S02 전체를 완료 처리하지 않는다.

## 검증 상태

Java17 Maven139개·패키징, Python3.12 도구52개와 repository/diff 검사를 통과했다. 단위는 투영
필드·unknown/[]·태그 정렬·영속 버전/상한/실패 거절·상품 쓰기 뒤 생산 경계를 확인한다.
최초 sandbox 내부 Maven은 Mockito/JDK self-attach 제한으로 실패했고 허용된 동일 Java17의
sandbox 밖 재실행으로 통과했다. 이 환경 실패를 제품 결함/개선 수치에 넣지 않는다.

필수 HTTP에는 전용 CI 표식 DB에서 생성v1→특성v2→기본수정v3→주문수v4→삭제v5와
불변 이전 이벤트/CLI 입력 호환을 추가했다. productID 한정 outbox INSERT 실패 trigger로
상품·특성·버전/이력 rollback, 첫 상품 변경 후 두 번째 없는 상품으로 실패하는 주문의 rollback을
확인한다. 본문/키·원문 DB 로그를 공개 artifact로 내보내지 않는다.

실제 MySQL 8.4 전환은 기존12업무표와 CIguard의 전체 행/UTF-8 해시를 보존했다. 회원6·상품80·
주문3·상세4는 그대로이며, 전환 전에 명시한 허구 검증용 특성 header2/tag2도 보존했다.
현재80개에 v1/event80개를 기록하고 unknown/[]·태그 집합을 strict CLI 입력으로 확인했다.
반복007은 기존 표 오류로 거절하고 전체 상태가 같았으며, counter INSERT 뒤 outbox 실패를
주입한 DML 트랜잭션은 counter까지 rollback했다. DDL 자체의 implicit commit과 구분한다.

같은 불변 jar의 실제 HTTP14범주는 기존 스키마 전환 후와 빈 DB의 현재 data.sql 새 설치에서
모두 통과했다. 처음 새 검사가 기존 상품 PUT의 data=true를 DTO로 오해해 실패한 것은 검사
가정 오류였다. boolean 계약을 명시 확인한 뒤 GET으로 원값을 비교했고 API는 바꾸지 않았다.
별도 private harness의 결과 출력 변수·TIME_WAIT 포트 검사·MySQL CLI 문자셋 실수도 실제
제품 결함/성능 결과와 분리해 기록하고 소유 프로세스를 정리했다.

별도 제어 실험은 독립2세션의 같은 상품 수정2건에서 v1/2/3의 유일 순서와 최종 값을 확인했다.
삭제 outbox 실패는 상품/태그/기존 주문 참조를 복원했고, 주문 outbox 실패는 헤더·주문수·스탬프/
이력을 모두 rollback했다. 버전 상한·중복 상품/버전 거절 뒤 원값도 보존했다. 삭제v6 뒤 명시적으로
같은 ID를 재생성하고 다음 서비스 PUT을 했을 때 v7로 이어졌다. 이 재생성은 제어SQL+서비스의
카운터 검증이며 직접SQL 자체를 자동 수집하는 기능은 아니다.

backfill SQL JSON과 Java 응답의 도수80개는 동일 IEEE FLOAT 비트였다. 소수 문자열 표기만 다른
2개가 있었으며 데이터나 필수조건을 두 자리로 반올림하지 않았다. SQL의 태그 배열 순서에는
의존하지 않고 strict CLI의 태그 정렬로 집합을 비교한다. 저장 이벤트는 현재 값으로 재구성하지 않는다.

실제 API에서 발생한 가상 상품의5개 이벤트를 명시 batch로 고정해 같은 strict CLI에 전달했다.
별도3.9.0/nori 엔진에서 모두 적용·삭제표식·old v1 거절과 volume 재시작 뒤 유지, 정상 재생/
중복·정지 엔진 incomplete를 통과했다. raw batch/receipt·CID/port/PID는 ignored에만 보관한다.
CI도 HTTP가 만든 이 private fixture를 다음 엔진 단계에 전달하며 공개 artifact에는 집계만 둔다.
이는 자동 relay/실제 사용자의 검색 인덱스 배포가 아니다.

현재 앱 jar SHA-256은 `c311c255434c558dc0254b0016e3eab96a06cccecedf7bd207bab6944c45af17`,
007 SHA-256은 `598c204652b1f5675b5f4421c4e183c4f5ae0941c853a92bcc750c1763d3c631`이다.

## 사례 비교·인계

초기에는 로컬 관리 도구의 연장으로 보수적으로 기존 네 사례를 유지하려 했다. 실제 업무TX의
실패/동시 순서·비파괴 전환·API→CLI→별도 엔진 재생까지 근거를 확보한 뒤 다시 비교했다.
BE-CS01의 주문 상세 조립은 기존 가짜 DAO 중심이며 전후 실측이 없었다. 이번은 원인/대안·
구현 기여·직접 재현한 경계가 더 강해 1번만 BE-CS05로 교체하고 나머지3개와 총4개를 유지했다.
신규 설계임을 명시하며 과거 운영 사고·자동 relay·속도 개선으로 포장하지 않는다.
최신 CI·Codex·관련 검증·병합/main CI와 브랜치 정리는 조정자 게이트다.
