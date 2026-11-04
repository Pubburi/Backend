# README 사례 근거

README의 현재 네 사례와 순서를 유지한다. 상세 문제·원인·구현·결과는 README, 원값은 아래
독립 근거가 정본이다. 실행별 '유지' 판단과 완료 일지는 Git/PR 이력에서 확인한다.

| 사례 | 확인할 근거 | 해석 경계 |
|---|---|---|
| BE-CS05 업무 원자 생산·relay 상태 | [업무 생산](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-product-search-outbox.md)·[전달 검증](https://github.com/Pubburi/Backend/blob/53c15c06327f4518b9c269ac95ccdd68b3f05c81/docs/plans/2026-11-01-durable-search-relay.md) | 제어한 TX/재전달 실험이며 과거 운영 유실·자동 복구 SLA/성능 성과가 아님 |
| BE-CS02 현재 권한·세션 epoch | [실제 HTTP/DB 원값](measurements/2026-11-01-session-authority.md) | 고정 시나리오의 정확성 결과이며 실사용자 오류율·전체 동시성 결과가 아님 |
| BE-CS06 값·ID 수명/복사 결과 확정 | [당시 import/전행 검증](https://github.com/Pubburi/Backend/blob/3748f25718e13a2e76012d1a86c5a9fd36b75fe6/docs/plans/2026-11-02-postgresql-source-import.md) | 실제 PG 복사/실패 복구와 단위 ACK/close 주입을 구분하며 전체 서비스 cutover가 아님 |
| BE-CS04 가격·삭제 뒤 주문 이력 | [가격 원값](measurements/2026-10-31-order-price-invariance.md)·[삭제 원값](measurements/2026-10-31-order-history-preservation.md) | 서로 다른 기준/분모의 로컬 정확성 실험이며 삭제된 과거 행을 복구한 결과가 아님 |

새 후보는 독립 업무 영향·원인/대안·구현 기여·재현 근거가 더 강할 때 교체한다. 신규성·코드량·
테스트 수·기술 연결만으로 교체하지 않는다. 전후 측정 없는 성능 수치나 동일 문제의 슬롯 분할을
추가하지 않는다. 원래 소스의 [출처 기록](PROVENANCE.md)은 그대로 보존한다.
