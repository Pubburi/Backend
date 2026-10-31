# 주문 가격 불변 정확성 측정

## 측정 대상과 조건

- 기준 소스: `a563c3693ea10452ced7c3c3eff4275badac3439`.
- 변경본: 이 기록을 포함하는 BE01 변경의 패키징 jar. 실제 실행 파일 SHA-256:
  `cdbd64ff44b42045ff5239a4cbbb598d6891efe3010302993e06c5c8e81a207f`.
- 실행: 같은 로컬 호스트의 Java 17·MySQL 8.4, 기준/변경 각각 별도 폐기 DB에 같은 초기 `data.sql` 적용.
  기존 개발 DB·볼륨은 사용/초기화하지 않았다. 서버는 각 jar의 독립 프로세스로 실행했다.
- 입력: 같은 상품 정렬의 첫 두 상품 A(33,000원), B(72,600원)를 한 번 선택하고
  A×2, B×3, A×1+B×2 주문을 HTTP로 생성했다. 회원은 새 테스트 계정이며 외부 실사용자는 없다.
- 조작: 두 상품의 단가를 각각 1,000원 올린 뒤 생성된 주문을 HTTP 단건/목록으로 다시 조회했다.
- 경계: 생성 시 상세 합계와 가격 변경 후 조회 금액의 차이, 금액 불변 위반 주문 수/생성한 주문 수.
  변경본은 저장된 헤더 총액과 상세 합계 일치도 확인했다. 카드 청구·판매 성과·추천 품질 측정이 아니다.
- 이 실험은 지연/처리량 측정이 아니므로 워밍업·캐시·CPU 부하로부터 성능 향상을 추론하지 않는다.
  세 주문 시나리오의 기능 재현이며 운영 장애율을 추정할 표본이 아니다.

## 원값과 결과

| 주문 입력 | 생성 시 금액 | 기준 코드: 가격 변경 후 | 기준 차이 | 변경 코드: 가격 변경 후 | 변경 차이 |
|---|---|---|---|---|---|
| A×2 | 66,000원 | 68,000원 | +2,000원 | 66,000원 | 0원 |
| B×3 | 217,800원 | 220,800원 | +3,000원 | 217,800원 | 0원 |
| A×1+B×2 | 178,200원 | 181,200원 | +3,000원 | 178,200원 | 0원 |

- 불변 위반 주문: **3/3 → 0/3**. 이 시나리오의 위반 비율 100% → 0%, 절대 차이 **100%p 감소**.
- 관측된 주문별 최대 금액 차이: 3,000원 → 0원.
- 변경본의 신규 주문 단건 응답은 가격 변경 전후 완전히 같았고 목록 응답도 같은 주문/상세와 일치했다.
  저장된 헤더 총액은 라인 합계와 일치했다.
- 이는 **금액 정합성 수정**이다. 응답 시간·처리량·운영 매출 손실 개선으로 표시하지 않는다.

## 재현 순서와 계산 코드

기준/변경 각각 새로운 폐기 MySQL 8.4에 해당 버전의 `data.sql`을 적용하고 Java 17에서
`./mvnw --batch-mode --no-transfer-progress verify`로 만든 jar를 기동한다.
빌드 완료 후 실행 파일을 별도 불변 파일로 복사하고 `shasum -a 256`으로 대상을 기록한다.
실행 중인 jar를 다시 빌드하거나 덮어쓰지 않는다.

[테스트 안내](../TESTING.md)의 loopback JDBC URL·`*_ci` 스키마·컨테이너 공개 포트·run marker와
명시적 변이 opt-in을 먼저 준비한다. 다음은 실제 측정과 같은 주문 입력/변경/금액 계산을 재현하는 코드다.
안전 게이트와 이름 인코딩은 [스모크 도구](../../scripts/api_smoke.py)를 사용한다.
기준 jar를 검증할 때도 현재 스모크 도구를 별도로 사용하며, 기준/변경 DB를 섞지 않는다.

```bash
PYTHONPATH=scripts python3 - <<'PY'
import json
import os
import uuid
from api_smoke import ApiClient, change_ci_product, require_disposable_database

env = os.environ
require_disposable_database(env)
client = ApiClient(env["PUBBURI_SERVER_PORT"])
products = client.request("GET", "/products?page=1&size=2", 200)["data"]["items"]
a, b = products
assert [a["price"], b["price"]] == [33000, 72600], "Initial fixture changed"
uid, password = "price_" + uuid.uuid4().hex, uuid.uuid4().hex
client.request("POST", "/users", 201, {"id": uid, "name": "Price check", "pass": password})
client.request("POST", "/auth/login", 200, {"id": uid, "pass": password})
before = []
for lines in [[(a, 2)], [(b, 3)], [(a, 1), (b, 2)]]:
    before.append(client.request("POST", "/orders", 201, {
        "orderType": "TAKEOUT",
        "details": [{"productId": p["id"], "quantity": q} for p, q in lines],
    })["data"])
amount = lambda order: sum(line["sumPrice"] for line in order["details"])
assert [amount(order) for order in before] == [66000, 217800, 178200]
try:
    for product in products:
        change_ci_product(env, product["id"], product["price"] + 1000, product["name"])
    after = [client.request("GET", f"/orders/{order['oId']}", 200)["data"] for order in before]
    print(json.dumps([{
        "before_krw": amount(old), "after_krw": amount(new),
        "delta_krw": amount(new) - amount(old),
    } for old, new in zip(before, after)]))
finally:
    for product in products:
        change_ci_product(env, product["id"], product["price"], product["name"])
PY
```

이 코드는 새 주문을 남기므로 테스트 뒤 그 폐기 환경만 제거한다. 기존 개발 DB에서 실행하지 않는다.
별도로 필수 CI 스모크는 같은 안전 게이트 아래 상품 가격 **+123원과 이름 변경**을 사용해 단건/목록
불변을 검사한다. 위 전후 비교의 +1,000원 실험과 CI의 회귀 조건을 같은 측정으로 합산하지 않는다.

## 이전 스키마 전환

기준 DB의 주문 6개·상세 8개에 [전환 SQL](../migrations/001-order-price-snapshot.sql)을 적용했다.
주문/상세 개수를 보존했고 기존 주문 모두 `LEGACY_ESTIMATE`로 표시하며 헤더/라인 합계가 일치했다.
전체 파일을 다시 실행하면 중복 컬럼 오류로 중단됐고 행 수/총액은 추가로 바뀌지 않았다.
이는 자동 재실행이 가능한 멱등 마이그레이션이라는 뜻이 아니다. MySQL DDL의 암시 커밋·쓰기 중단·백업·
strict mode·범위 사전 검사는 [전환 안내](../ORDER-PRICE-SNAPSHOT.md)를 따른다.

## 무효 시도와 한계

- 처음 변경본을 실행하던 중 같은 target jar가 재빌드돼 `NoClassDefFoundError`가 발생한 시도는
  실행 대상이 고정되지 않아 **무효**로 제외했다. 제품 결함 발생 수/개선 수치에 포함하지 않았다.
  위 결과는 빌드 완료 후 별도 복사·SHA 확인한 최종 불변 jar의 재측정만 사용한다.
- 과거 단가가 없던 주문의 추정값을 실제 구매 당시 금액이라고 복원하지 않았다.
- BE02 상품/회원 삭제 이력 보존, 모든 동시/중복 주문, Frontend의 가격 출처 표시 UI는 미완료다.
  서버가 `priceBasis`를 제공하는 것과 화면이 그 뜻을 보여주는 것은 별도 완료 조건이다.
- 서로 다른 상품마다 잠금 가격 SELECT가 추가된다. 이번에는 금액 불변을 우선했으며
  DB 왕복/락 대기/지연의 성능 효과는 측정하지 않았다.
