# 로컬 상품 색인과 명시 이벤트 재생

현재 S02 증가분은 **로컬 엔진·관리 CLI·버전 있는 ID 문서와 삭제 표식**이다.
MySQL outbox/자동 동기화, 재색인 중 변경 재생·alias 세대 전환과 공개 검색 전환은 미구현이다.
`GET /api/products`는 기존 MySQL 필터를 사용하고 엔진이 없어도 앱은 그대로 기동한다.

## 고정 버전과 기동

[공식 3.9.0 release](https://github.com/opensearch-project/OpenSearch/releases/tag/3.9.0)를 확인하고
공식 이미지의 multi-platform digest를 Dockerfile에 고정했다.
`opensearchproject/opensearch:3.9.0@sha256:3b55f6b6249cfdb92b52ee59adc8a266203ba9ef9f5d82544fad6f1af2f3609e`다.
analysis-nori를 같은 3.9.0으로 설치하고 descriptor/실제 plugin API에서 버전을 검사한다.
[공식 플러그인 관리](https://docs.opensearch.org/latest/install-and-configure/plugins/)의 설치·호환 규칙을 따른다.

~~~bash
docker compose -f compose.search.yml up -d --build
docker compose -f compose.search.yml ps
docker compose -f compose.search.yml stop
~~~

별도 Compose 프로젝트/볼륨이며 기본 MySQL Compose나 local-stack 기동에 추가되지 않는다.
기본 연결은 `http://127.0.0.1:9200`, 포트 변경은 `PUBBURI_SEARCH_PORT`로 지정한다.
512MiB heap·1.5GiB 컨테이너 메모리·1.5 CPU는 설정 상한이며 실측 사용량/성능 수치가 아니다.
[공식 Docker 가이드](https://docs.opensearch.org/latest/install-and-configure/install-opensearch/docker/)에
따라 single-node 개발 구성을 사용한다. security/demo 설정을 비활성화하고 REST를 loopback에만
게시하므로 키가 필요 없다. 공개 배포용 보안 구성이 아니며 임의 host/기존 볼륨을 자동 수정하지 않는다.
Docker Desktop의 메모리/호스트 설정은 공식 가이드를 확인하고 기존 DB 볼륨을 초기화하지 않는다.

## 관리 대상과 입력

`scripts/search_index.py`는 표준 라이브러리만 사용하며 `init`·`replay`만 제공한다. 임의 index 삭제나
alias 재지정 명령은 없다. 명시한 numeric loopback HTTP origin과
`pubburi-products-v1[-소문자/숫자 suffix]` 범위만 허용한다. alias는 `<index>-visible`로 고정 파생한다.
엔진/nori 버전·소유 `_meta`·전체 strict mapping·분석기·단일 filtered/write alias를 확인하고 사용한다.
기존의 다른 schema/자료 성격을 조용히 채택하지 않는다.

입력은 schemaVersion1, dataKind, 최대 1,000 events의 JSON이며 원문 상한은4MiB다. dataKind는
`authored_fixture` 또는 `backend_product_events`로 명시하고 색인 `_meta`와 같아야 한다. 후자는
생산자의 선언이며 CLI가 실제 MySQL 행/원자적 버전 발급을 증명하는 것은 아니다. 아직 outbox
생산자가 없고 이번 실제 검증은 직접 작성한 가상 입력만 사용했다. 원본 이벤트와 receipt는 ignored에 둔다.

~~~json
{
  "schemaVersion": 1,
  "dataKind": "authored_fixture",
  "events": [{
    "productId": 1, "sourceVersion": 1, "deleted": false,
    "document": {"name": "가상 검증 상품", "type": "탁주", "priceKrw": 1000,
      "abvPercent": 5.555, "orderCount": 0, "tasteTags": null, "aromaTags": []}
  }]
}
~~~

productId는 양의 signed32-bit 정수, sourceVersion은 양의 signed64-bit 정수다. 삭제 이벤트도
같은 ID·새 버전을 가지며 deleted=true, document=null이다. 그 외 필드/문자열 숫자·bool 숫자는
거절한다. 상품명/주종·현재 가격/도수/주문 수와 F03의 명시 태그만 투영한다. unknown null과
검수[]를 known boolean과 함께 보존한다. 태그는 F03처럼 trim/lowercase된 고유 문자열을 받고
순서만 정렬한다. 맛/향·가격을 추정하거나 이미지/개인정보·원천 본문을 수집하지 않는다.
태그는 종류별20개/각40 UTF-16 단위, 이름100·주종20 단위로 제한한다. 가격/주문 수는
0..signed32-bit 정수, 도수는0..100의 유한 수치이며 기존5.555 같은 값을 두 자리로 반올림하지 않는다.

~~~bash
python3 scripts/search_index.py init --endpoint http://127.0.0.1:9200 \
  --index pubburi-products-v1-demo --data-kind authored_fixture \
  --output-dir .pubburi/search-init-unique
python3 scripts/search_index.py replay --endpoint http://127.0.0.1:9200 \
  --index pubburi-products-v1-demo --data-kind authored_fixture \
  --input .pubburi/search-events.json --output-dir .pubburi/search-replay-unique
~~~

## 버전·삭제·실패 경계

[external version](https://docs.opensearch.org/latest/api-reference/document-apis/index-document/)을
사용해 현재보다 높은 버전만 쓴다. 409에서는 실제 저장 문서·버전·ID/형을 다시 확인한다. 현재가
더 높으면 stale_ignored, 같은 버전/정규 투영이 같으면 duplicate, 같은 현재 버전의 내용이 다르면
충돌로 실패한다. external_gte로 같은 버전의 다른 내용을 덮지 않으며 409 조회는 mutation 재시도가 아니다.
이미 더 높은 버전으로 진행된 뒤의 과거 충돌 이력까지 복원하는 기능은 없다.

삭제는 물리 DELETE 대신 ID/버전/deleted만 남기는 tombstone PUT이다. 원래 상품 필드는 제거한다.
[물리 삭제 버전 보존](https://docs.opensearch.org/latest/api-reference/document-apis/delete-document/)은
gc_deletes 기본60초에 제한되므로, 삭제 표식을 유지해 오래된 갱신의 재생이 문서를 부활시키지
않게 한다. tombstone을 지우거나 색인을 재생성하면 이 장벽도 사라진다. 완전한 이벤트 이력/
삭제와 high-watermark를 보존하는 실제 outbox·재색인/정리 정책은 후속이다. 활성 상품만 복사해
완전한 이력이 복구됐다고 주장하지 않는다.

filtered alias는 deleted=false의 **검색**을 제공하며 보안/권한 경계가 아니다.
[alias 동작](https://docs.opensearch.org/latest/im-plugin/index-alias/)과 실제 테스트처럼 GET-by-ID는
필터를 우회해 tombstone을 반환할 수 있다. 향후 공개 어댑터는 deleted와 최신 MySQL/필수조건을
직접 확인해야 한다. 관리 작업 동안 같은 index/alias를 다른 작업자가 변경하지 않는 소유권이 필요하다.
쓰기는 require_alias=true로 지정해 alias 소실 시 의도치 않은 물리 색인이 자동 생성되지 않게 한다.

HTTP는 연결 최대2초·응답 전체 본문 완료 최대5초·누적2MiB 상한과 무자동재시도/redirect 미추적을
사용한다. 이는 개별 요청의 예산이며 배치 전체 시간이나 프로세스 메모리 측정값이 아니다.
모든 입력을 IO 전에 검증해도 중간 엔진 장애/응답 유실은 이미 적용한 문서를 rollback하지 못한다.
receipt는 completed=false와 확인된 적용/중복/오래된 이벤트 수를 남기고 실패한다. 응답을 확인하지
못한 실제 쓰기는 이 수에 포함되지 않을 수 있다. 원인을 확인한 뒤 같은 입력을 새 receipt 경로로
재생해 복구한다. 디렉터리를 덮지 않고 모든 단계가 끝나야 completed=true를 기록한다.
파일시스템 오류로 receipt가 없거나 일부만 남은 경우도 완료로 취급하지 않는다.

실제 검증·초기 실패/수정·제한은 [S02 작업 기록](plans/2026-11-01-local-search-replay.md), 실행은
[TESTING](TESTING.md)을 따른다. 현재 검색 품질·응답 속도·서비스 사용자 성과는 측정하지 않았다.
