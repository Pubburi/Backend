# BE07 Jackson Reader 필드명 제한 패치

기준 main `6adac1a`, 작업 codex/json-parser-security다. [공식 영향 범위·버전·지원 수명](../DEPENDENCY-SECURITY.md)을
확인해 이번 변경을 Jackson Reader 필드명 누적 제한으로 좁혔다. 이미 수정한 Tomcat/multipart,
Java 17·Boot/Framework·crypto·MyBatis/SQL·springdoc/라우팅과 기존 입력 계약은 유지한다.

## 재현·선택

공식 GHSA-649p-m576-vr99의 문자 입력 경로를 소량으로 재현했다. 모든 Reader는 매 read 최대
128자를 반환한다. 초기 maxNameLength64/키2,048 결과는 fresh 버퍼에서 얻은 축소 fixture다.
이후 CI에서 발견한 재사용 조건을 추가해 기본 50,000자 상한의 주 회귀를 검증했다. 제품 기본값은 유지한다.

| 조건 | Jackson 2.19.0 | BOM 2.21.8 |
|---|---|---|
| fresh 버퍼·상한64·키2,048 | 예외 전 2,054자 소비 | 예외 전 256자 소비 |
| 기본 shared pool primed·상한64·키2,048 | 예외 전 2,054자 소비 | 예외 전 2,054자 소비 |
| 같은 primed pool·기본상한50,000·키140,000 | 예외 전 140,006자 소비 | 예외 전 76,032자 소비 |
| 문서 길이10B/정상10B | 통과 | 통과 |
| 문서 길이10B/초과13B | 이미 거부 | 계속 거부 |

기준의 긴 이름은 최종적으로도 거부됐다. 결함은 필드명을 끝까지 모은 뒤 검사하는 시점이며,
새 버전은 segment가 다시 성장하기 전에 제한을 검사한다. 큰 기존 버퍼에 들어가는 작은 키까지
제한값에서 즉시 중단한다고 보장하지 않는다. 소량 fixture의 소비 수를 전 HTTP 요청의 수신
바이트·힙 메모리·처리 시간·전체 DoS 차단률로 바꾸지 않는다. 문서 길이 공지의 작은 blocking
샘플은 기존에도 차단됐으므로 그 우회를 고쳤다는 성과로 집계하지 않는다.
패치 후 별도 짧은 정상 Reader도 통과했다. 그 축소 Reader의 정상 대조는 패치 후에 추가했으며
실행하지 않은 기준 버전의 같은 테스트 결과를 만들지 않는다.

최초 Reader 수정은 2.21.6이며 2026-10-17 공개된 2.21.8 LTS BOM을 선택했다. crypto만 쓰는
구성을 전체 Security 필터/WebAuthn으로 오인하거나, default typing을 쓰지 않는데 다형성 RCE를
실행하지 않는다. Boot 전체/major 교체는 인과·롤백 범위가 커 별도 지원 수명 과제로 남겼다.
새 문서1MiB 제한은 계약 변경과 무관한 하드닝을 섞지 않도록 추가하지 않았다.

## 실제 의존성·검증

Jackson BOM 속성 하나로 바뀐 실제 runtime/jar는 core/databind·jdk8/jsr310·parameter-names·yaml의
2.21.8 여섯 개와 annotations 2.21이다. 그 밖의 runtime 버전은 비교 결과 같았다. 단일 모듈만
강제로 올리거나 annotations까지 존재하지 않는 동일 patch 번호로 맞추지 않았다.

원인 보강 뒤 Java 17 Maven verify 101개·패키징과 Python 도구/안전 게이트 28개·저장소/문법/diff 검사를
통과했다. 작은 Reader의 소비 숫자와 정상/거부는 실제 라이브러리 회귀이고, MockMvc는 세 charset의
정상 Join DTO와 60,000자 필드명의 400·원문 비노출·컨트롤러 호출0을 확인했다. 실제 업무 DB 쓰기
검증이나 운영 메모리 성능으로 표현하지 않는다. 전체 MVC 문서·Swagger와 기존 strict DTO도 유지했다.

재현 명령은 `./mvnw --batch-mode --no-transfer-progress -Dtest=JsonNameConstraintTests test`다.
최종 테스트를 `-Djackson-bom.version=2.19.0`로 실행하면 fresh 작은 키/primed 기본 상한의 두
회귀가 각각 소비2,054/140,006으로 실패한다. 2.21.8에서는 해당 두 조건이256/76,032로 통과한다.
작은 primed 키의2054 전체 소비도 명시적으로 확인해 기존 버퍼 재사용의 의미를 숨기지 않는다.
`JsonDocumentConstraintTests`는 기존에도 통과한 문서 경계를 유지하는 대조다.
runtime tree는 고정 maven-dependency-plugin:3.8.1 tree -Dscope=runtime과 BOOT-INF/lib를 함께 확인했다.

## 최초 CI 실패와 원인 확인

[최초 PR CI](https://github.com/Pubburi/Backend/actions/runs/41262574331)는 100개 중 작은 Reader
회귀 하나가 소비2,054자로 실패했다. 승인/무조건 재시도로 처리하지 않았다. CI XML은 Temurin
17.0.21과 의도한 Jackson core2.21.8/annotations2.21 등 일곱 모듈을 확인했다. 로컬도 같은
Temurin17.0.21이며 공식 Maven Central의 새 core jar와 기존 캐시 SHA-256은 모두
`604ef7765013daf5cde192e40bc4c8412c4ca93f4aaf7bf8e9a62fad1e1026bb`였다. 공식 SHA-1도 일치했다.
CI core jar 자체의 checksum을 기존 테스트 아티팩트가 제공한 것은 아니므로 버전 경로 확인과
로컬/공식 artifact 검증을 구분한다.

공식 소스는 누적 segment가 가득 찰 때 validateNameLength를 호출한다. 기본 pool에서 60,000자
정상 문자열을 먼저 읽으면 큰 TextBuffer가 돌아오고, 뒤의 작은 키는 추가 성장 없이 전부 들어간다.
이를 공식 fresh jar·기본 shared pool에서 직접 재현해 CI 결과와 일치시켰다. 사설65,536자 재사용
buffer 대조도 같은 특성을 보였다. 손상된 캐시나 누락된 POM 적용으로 결론 내리지 않았다.

검사를 새 buffer로만 격리해 실패를 숨기는 대신 주 회귀는 새 스레드 안에서 실제 기본 ThreadLocal
pool을 의도적으로 prime한다. 같은 수명 안에서 작은 키의 전체 소비와 기본 상한/더 큰 키의 추가
성장 차단을 둘 다 확인한다. 새 스레드는 외부 테스트의 이전 buffer 크기를 제거하기 위한 수명
경계이고, 그 안의 실제 재사용은 그대로 검증한다. 서비스의 pool/설정·오류 정책은 바꾸지 않았다.

최종 불변 jar SHA-256: `2b868211d132971d77b947622bfb1aff0d24d92357a91151fa96e3497b499410`.
조정자가 같은 jar/전용 MySQL의 실제 HTTP smoke 열 범주를 통과했다. 새 charset 경로와 기존
응답/문서·multipart·세션/권한·주문 가격/rollback·삭제 이력·계정 epoch·특성·취향·찜을 유지했다.
H7의 기준 불변 jar `0e1933a2daef4d0aa5549d27114e6ec5419bc958cf898f761c83a665ab76cd9f`에서도
같은 별도 charset 검사를 실행했다.

| 실제 HTTP 대조 조건 | 기준 Jackson 2.19.0 | 패치 BOM 2.21.8 |
|---|---|---|
| UTF-8/ISO-8859-1/UTF-16 정상 로그인 | 모두 200 | 모두 200 |
| 같은 세 charset의 60,000자 초과 키 | 모두 고정 VALIDATION_FAILED 400 | 모두 같은 400 |
| 초과 입력 뒤 기존 로그인 세션 | 유지 | 유지 |

이는 HTTP 상태/세션 호환 대조이며 그 결과가 바뀌어 개선됐다는 주장은 하지 않는다. 직접
Reader의 2,054→256자 소비만 같은 fixture의 조기 중단 근거다. 기존/공유 DB·포트·AWS는
작업자가 사용하지 않았다. PR/병합 CI는 이 로컬 실행 결과와 별도다.

## 사례 비교·잔여 범위

현재 README의 인증/거래/조회 네 사례보다 더 강한 독립 문제 해결이라고 평가할 근거는 없어
사례/순서를 유지했다. JSON 기술 버전만 갱신하고 자세한 실험·한계는 이 기록에 둔다.

공식 Boot3.5 OSS 종료·Framework/BOM 및 나머지 전이 라이브러리의 조건/지원 수명 검토는
계속 필요하다. 이번에는 실제 Reader 경계를 수정했고 전체 BE07/P0 완료·취약점0·실운영 침해
해결로 기록하지 않는다. 최신 head CI·완료 Codex 리뷰·미해결 지적 없음과 main CI·브랜치 정리는
조정자 게이트다.
