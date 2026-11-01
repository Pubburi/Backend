# S02 로컬 엔진·ID 버전 재생 증가분

기준 main de7f807, branch codex/local-search-index-replay. 기존 MySQL/API/세션 경로를 유지하면서
선택적 로컬 OpenSearch와 명시적 관리 도구를 구현했다. 정본은 [LOCAL-SEARCH](../LOCAL-SEARCH.md)다.
업무 outbox/자동 동기화·공개 검색·재색인 세대 전환·S02 전체 완료와 구분한다.

## 선택과 구현

- 공식 release3.9.0과 Docker Hub 공식 multi-platform digest를 확인해 base를 고정했다.
  nori3.9.0 descriptor와 실제 plugin/root API를 검사했다. ARM64 실제 실행을 확인했으며 CI는
  같은 multi-platform digest의 runner 이미지를 빌드/실행한다. Java17 앱과 엔진의 bundled JDK는 분리한다.
- public API/프레임워크 SDK 대신 표준 라이브러리 관리 CLI와 strict schema를 선택했다.
  sourceVersion 생산자는 별도이며 사용자/추천 원천 QID를 업무 ID로 자동 매핑하지 않는다.
- external strict version·실제 GET 정본의 중복/오래된 버전/동일 현재 버전 충돌 분류·지속 tombstone을
  구현했다. 같은 버전 overwrite와 물리 DELETE의 짧은 버전 보존을 대안으로 비교했다.
- 소유 metadata/자료 성격·정확 schema/analyzer·단일 filtered/write alias와 require_alias를 확인한다.
  임의 index 삭제/alias retarget·외부 origin·무제한 본문/자동 retry는 제공하지 않는다.
- 실제/fixture 입력 성격과 null/[]·0을 보존하고 모든 문서를 IO 전에 검증한다. 배치 중간 실패와
  최종 receipt 쓰기 실패도 completed=false이며, 기존 출력 디렉터리를 보존한다.

## 검증과 실제 발견

check.sh의 Java17 Maven133개·Python 도구37개·저장소/문법·패키징이 통과했다. Python의 소유
임시 loopback HTTP는 2MiB의 유효 JSON+공백 수용·초과 거절, redirect 미추적과 끝나지 않는
부분 body의 전체 deadline을 확인한다. 직접 작성한 문서/가짜 HTTP는 strict input·버전 정본 검증·
소유 schema/alias와 부분/최종 receipt 실패를 확인한다. 실제 서비스 지연/오류율 실험은 아니다.

최초 실제 init은 OpenSearch가 기본 `_source.enabled=true`를 mapping 응답에서 생략해
owned_strict_mapping_required로 실패했다. 응답에서 이 기본값만 의미상 정규화했다. disabled
_source·includes/excludes·바뀐 dynamic/properties/자료 성격과 alias는 여전히 거절하며 별도 회귀로
검증했다. 초기 failed receipt는 보존했고 같은 소유 schema를 새 receipt 경로로 재검증했다.
이 개발 중 실패를 과거 운영 장애/성능 개선으로 포장하지 않는다.

최종 자체 검토에서는 Python의 False==0 동등성이 metadata의 JSON bool/int 차이를 숨기는
경계를 발견했다. 수정 전 회귀에서 mapping/alias의0과 ack의false를 거절하지 못함을 확인하고
metadata는 JSON 타입을 보존해 비교하며 ack 실패 수는 정확한 int0만 허용하도록 보강했다.
alias fixture도 기대 정의와 공유하던 참조를 deepcopy로 분리했다. 보강 뒤 전체 Python38개와
같은 실제 엔진 lifecycle을 다시 통과했고 Java/앱 jar는 변경하지 않았다.

최종 실제 검증은 공식 base/nori image, 512MiB heap·1.5GiB 컨테이너 상한, 새 전용 volume과
동적 loopback 포트에서 다음을 통과했다. 설정 상한은 실측 사용량이 아니다.

1. strict index/alias 최초/반복 init, 현재 ID 문서 적용·재전달 중복, 더 높은 버전과 오래된 입력.
2. 같은 현재 버전의 다른 내용 거절·저장값 불변, 두 제어 동시 요청의 한 적용/한 충돌.
3. null/[] 보존, unknown 속성/숫자 문자열의 엔진400, nori의 한국어 토큰 생성.
4. 삭제 표식의 원문 필드 제거·alias 검색 제외·GET-by-ID 반환 차이.
5. volume 유지 재시작 뒤 삭제 버전 보존과 오래된 갱신의 부활 차단.
6. 새 색인의 실제 CLI 이벤트 이력 재생, 반복 시 적용0/중복2/오래된2와 complete receipt.
7. 정지 엔진에서 exit2와 incomplete receipt. 소유 label을 확인한 컨테이너/volume 정리.

최종 관리 client SHA-256은 `ef74e1a9694f66bb8c73f52dd3ec08303735b9e1d920ba2e70a6f60bc688aa9a`이며
실제 report의 hash와 일치했다. 초기 독립 probe와 세 전체 lifecycle 실행의 컨테이너/volume을
모두 정리했고, 같은 label의 잔여 자원0을 확인했다. ID/포트/PID·원문 로그·가상 입력은 ignored에
보존하며 공개 artifact에는 가상 검증의 집계/버전·client hash만 둔다. 원본 업무/추천 데이터는 사용하지 않았다.

앱 jar SHA-256: `f5313650faf31d80800786fcf3d20e439fdb5aadc200fbdc5333ebc0c348e9e8`.
조정자가 같은 jar·전용 MySQL에서 기존 실제 HTTP13범주도 통과했다. 세션/권한·주문/가격/rollback·
삭제/익명화·재가입·필터/오프셋·특성/취향/찜·실행 OpenAPI·B0 fallback을 유지했다.
이 검증을 새 검색 엔진의 품질/성능이나 전체 업무 동기화 완료로 해석하지 않는다.
필수 backend-ci에 pinned image build와 같은 소유 lifecycle smoke를 추가하고 보고서는7일 보존한다.

## 사례 비교와 다음 경계

이번 실제 엔진의 재전달/역전·동시 충돌·삭제/재시작·부분 실패는 유효한 분산 상태 경계다.
하지만 업무 변경과 원자적으로 발생하는 버전/outbox, 실제 색인 장애의 고객 영향은 아직 연결하지
않았다. 기존 README의 권한 회수·거래 금액/이력·조회/입력 정규화보다 더 강한 실제 업무 개선으로
과장해 교체하지 않고 네 사례와 순서를 유지한다. 같은 bootstrap 소재를 별도 사례로 늘리지 않는다.

후속 S02는 업무 변경과 원자적인 버전/outbox·재처리, 전체 원본/삭제 watermark와 재색인 중 변경·
atomic alias cutover를 구현한다. S03 이후에 현재 MySQL 조건을 보존하는 공개 검색/fallback·한국어/
동의어/오타·품질/지연을 검증한다. 기본 tokenizer 동작을 초성/오타·검색 품질 완료로 표시하지 않는다.
최신 CI·완료 Codex·미해결 지적 없음·병합/main CI·브랜치 정리는 조정자 게이트다.
