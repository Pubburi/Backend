# README 사례 근거

대표 사례의 재현 조건과 검증 근거를 관리한다.

| 사례 | 확인할 근거 | 해석 경계 |
|---|---|---|
| BE-CS07 미확정 카트 요청의 중복 적용·원자 주문 | [계약](CART.md)·[실제 처리](../src/main/java/com/pubburi/rehearsal/CartService.java)·[HTTP/동시성](../src/test/java/com/pubburi/rehearsal/JpaCartIntegrationTests.java)·[실패 rollback](../src/test/java/com/pubburi/rehearsal/JpaCartFailureIntegrationTests.java)·[404 뒤 지연 적용 재현/선택](https://github.com/Pubburi/Backend/discussions/71) | 개발 중 통제한 요청 경쟁·소유 PG18.6 실제 HTTP/TX·복구 결과이며 과거 운영 장애·실사용자 오류율·성능 개선이 아님 |

브라우저 fixture에서 수량 2개가 4개가 되는 상황을 재현했다. 실제 PostgreSQL의 잠금, 주문 트랜잭션, 롤백과 복구는 별도로 확인했다. [이전 대표 사례와 근거](https://github.com/Pubburi/Backend/blob/9c7b545f00470d0de6525f3424d5e2570bc37d7f/docs/case-study-register.md) · [소스 출처](PROVENANCE.md)
