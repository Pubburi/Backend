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

독립 정적 검토는 두 P2를 추가로 지적했고 수정 전에 재현했다. 실제 HTTPResponse의 선언12B/
수신2B인 완결 JSON EOF가 성공하던 조건, 가짜 Docker 경계에서 로그 조회/파일쓰기 실패가
컨테이너/볼륨 정리를 건너뛰던 두 조건의 회귀가 실패했다. 수신 길이 일치 검사와 진단/정리
분리를 적용했다. 검증 본문이 성공한 뒤에도 로그/삭제 실패는 실패 상태이며, 소유 CID/볼륨 정리·
비소유 보호·삭제 실패 보류를 확인했다. 이후 전체 Python43개와 최종 실제 엔진 lifecycle이
통과했다. 불완전 응답은 부분 적용 receipt도 incomplete로 남는다. 이는 새 도구의 개발 중
검수/수정 근거이며 기존 서비스의 실제 truncation/자원 누출 사고를 주장하지 않는다.

PR21의 native Codex는 이 검사들에 없던 헤더 수신 기한 문제를 지적했다. 기준523edf0/Python3.9의 실제
loopback HTTPResponse에 헤더5개를0.08초 간격으로 보내고 기한0.15초를 지정했다. 기존 socket
timeout은 각 읽기의 idle 시간이라 헤더가 계속 들어오면 전체 예산을 넘겼다. 동일 회귀의 수정 전
한 실행은0.430초 후에야 실패했다. 이전 body deadline 통과를 모든 IO의 기한 보장으로 해석한
범위를 바로잡았다. 이는 개발 중 관리 도구의 제어 실패이며 기존 사용자 장애나 지연 성능 측정이 아니다.

요청별 소유 connection을 추가했다. 숫자 loopback의 connect는 nonblocking+남은 예산 select로,
쓰기/헤더/본문은 절대 기한에 소유 socket을 shutdown하는 watchdog으로 제한한다. HTTP의
Connection: close 이후에도 shutdown할 수 있도록 같은 연결의 dup descriptor를 소유한다.
finally에서 watchdog을 중지/join하고 연결과 취소용 descriptor를 모두 닫는다. 전역 signal은
동시 요청에 간섭하므로 쓰지 않고, timeout만 반환한 채 요청 future를 남기는 대안도 제외했다.

같은 Python3.9 헤더 회귀의 최종 수정 후 한 실행은0.160초에 engine_request_deadline으로 종료했고 헤더 전송
완료 전이었다. 반환 시 소유 watchdog/열린 socket은0이었다. 테스트의0.32초 허용 경계는0.40초
헤더 완료 전 취소를 확인하기 위한 스케줄링 여유이며 정확한 실시간 실행 보장/성능 개선율이 아니다.
실제 읽지 않는 peer에 막힌4MiB 요청 body와 Connection: close 본문, 제어된 connect 대기,
기한이 다른 두 동시 요청의 격리와 정상/실패 뒤 소유 자원 정리도 확인했다. 기존 short
Content-Length·정상 chunked/2MiB 상한·버전 충돌과 incomplete receipt 경계는 유지한다.

추가 정적 검토는 정상 Connection: close의 완결 본문 뒤 원 FD를 다시 조작할 가능성을 지적했다.
보강 전 복사본의 실제 정상 CL2/JSON 응답은 Python3.9에서 성공했지만, CI와 같은 Python3.12.14에서
다음 settimeout이 EBADF가 되어 engine_transport_failed로 실패했다. 같은 정상 chunked close는
보강 전에도 성공했다. read1 뒤 응답이 이미 닫혔으면 즉시 반복을 끝내도록 보강했고, 정확 CL/
chunked close 성공과 미완료 close의 기한 실패를 구분했다. 최종 전체 Python49개를3.9와3.12.14
모두 통과했다. 원문 네이티브 지적의 답글/실제 Resolve와 새 head CI/Codex는 조정자 게이트로 남겼다.

최종 실제 검증은 공식 base/nori image, 512MiB heap·1.5GiB 컨테이너 상한, 새 전용 volume과
동적 loopback 포트에서 다음을 통과했다. 설정 상한은 실측 사용량이 아니다.

1. strict index/alias 최초/반복 init, 현재 ID 문서 적용·재전달 중복, 더 높은 버전과 오래된 입력.
2. 같은 현재 버전의 다른 내용 거절·저장값 불변, 두 제어 동시 요청의 한 적용/한 충돌.
3. null/[] 보존, unknown 속성/숫자 문자열의 엔진400, nori의 한국어 토큰 생성.
4. 삭제 표식의 원문 필드 제거·alias 검색 제외·GET-by-ID 반환 차이.
5. volume 유지 재시작 뒤 삭제 버전 보존과 오래된 갱신의 부활 차단.
6. 새 색인의 실제 CLI 이벤트 이력 재생, 반복 시 적용0/중복2/오래된2와 complete receipt.
7. 정지 엔진에서 exit2와 incomplete receipt. 소유 label을 확인한 컨테이너/volume 정리.

최종 관리 client SHA-256은 `e97f57c79fe385c9924abb627b2e67690b6d48bf52a570f3ca83b0e9d236d86a`이며
실제 report의 hash와 일치했다. 초기 독립 probe와 여섯 전체 lifecycle 실행의 컨테이너/volume을
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
