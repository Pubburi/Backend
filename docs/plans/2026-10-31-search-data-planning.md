# 검색 계획과 추천 문서 소유권 정리

- 승인된 검색 범위는 로컬 OpenSearch 상품 검색을 F02/R06/R02와 연결하는 것이다. Backend의 정본은 [SEARCH-PLAN.md](../SEARCH-PLAN.md)이며 S01–S05를 관리한다.
- Backend는 업무 상품 MySQL 원본·검색 API·색인 동기화/삭제·추천 서버 호출과 조건 재확인·장애 대체 검색을 담당한다.
- 데이터 수집·정제·특성·추천 모델 학습·평가 계획은 사용자 지시에 따라 [Recommendation 정본](https://github.com/Pubburi/Recommendation/blob/main/docs/DATA-AND-MODEL-PLAN.md)으로 이관한다. DAT01–DAT04·B0/B1/M1·모델 평가의 상세는 Backend에 중복 보관하지 않는다.
- 최초 통합 기획과 실사용자 없는 개발 기준의 변경 이력은 Backend PR #4·#5와 Git 이력에 보존된다. 현재 문서 소유권은 검색은 Backend, 추천 데이터/모델/평가는 Recommendation이다.
- 비상업 프로젝트·외부 사용자 없음·20개 필수 기능·기한은 유지한다. 실제 수집·엔진 설치·색인·모델 학습·평가 구현을 이번 문서 이관의 성과로 표시하지 않는다.
- 조정자가 문서 링크·작업 ID·범위/소유권·저장소 검사와 필수 CI·최신 Codex 리뷰를 확인한다. 실제 PR/병합 근거는 기획과 ignored 체크포인트에 기록한다.
