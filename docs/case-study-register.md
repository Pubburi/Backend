# README 사례 근거

README의 현재 네 사례와 순서를 유지한다. 상세 문제·원인·구현·결과는 README, 원값은 아래
독립 근거가 정본이다. 실행별 '유지' 판단과 완료 일지는 Git/PR 이력에서 확인한다.

| 사례 | 확인할 근거 | 해석 경계 |
|---|---|---|
| BE-CS05 업무 원자 생산·relay 상태 | [업무 생산](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-product-search-outbox.md)·[전달 검증](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-durable-search-relay.md) | 제어한 TX/재전달 실험이며 과거 운영 유실·자동 복구 SLA/성능 성과가 아님 |
| BE-CS02 현재 권한·세션 epoch | [실제 HTTP/DB 원값](measurements/2026-11-01-session-authority.md) | 고정 시나리오의 정확성 결과이며 실사용자 오류율·전체 동시성 결과가 아님 |
| BE-CS06 전환 뒤 쓰기·ID를 보존한 새 대상 복구 | [현재 복구/독립 SQL·실제 웹 인수](https://github.com/Pubburi/Backend/pull/55)·[이전 import 근거](https://github.com/Pubburi/Backend/blob/3748f25718e13a2e76012d1a86c5a9fd36b75fe6/docs/plans/2026-11-02-postgresql-source-import.md) | 소유 PG18.6의 stopped-writer 복구·19표/7sequence·JPA/웹 확인이며 과거 운영 유실·PITR·기본 DB cutover·성능 성과가 아님 |
| BE-CS04 가격·삭제 뒤 주문 이력 | [가격 원값](measurements/2026-10-31-order-price-invariance.md)·[삭제 원값](measurements/2026-10-31-order-history-preservation.md) | 서로 다른 기준/분모의 로컬 정확성 실험이며 삭제된 과거 행을 복구한 결과가 아님 |

사례3은 이관 시점의 복사 확정에서 전환 뒤 실제 업무 쓰기·원문·claim/ACK·소비 ID의 복구로 보강했다. 기존 사례가 다루지 못한 현재 상태와 최소 권한의 숨겨진 기존 세션 경계를 재현했고, 복구 후 독립 SQL·JPA·production 웹까지 확인해 같은 슬롯을 교체했다. 나머지 세 사례와 순서는 유지하며 행/검사 수를 서비스 성능으로 쓰지 않는다.

새 후보는 독립 업무 영향·원인/대안·구현 기여·재현 근거가 더 강할 때 교체한다. 신규성·코드량·
테스트 수·기술 연결만으로 교체하지 않는다. 전후 측정 없는 성능 수치나 동일 문제의 슬롯 분할을
추가하지 않는다. 원래 소스의 [출처 기록](PROVENANCE.md)은 그대로 보존한다.
