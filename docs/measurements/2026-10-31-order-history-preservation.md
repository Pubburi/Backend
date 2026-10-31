# 상품·회원 삭제 후 주문 이력 보존 정확성

## 대상·조건

- 기준 소스: `56a76d147229f31e492ca9d7fd4e339ae1c1d357`(BE01 가격 스냅샷 적용).
- 변경본은 이 기록을 포함하는 BE02 변경이다. 실제 실행한 불변 jar의 SHA-256은
  `12f68b4eae1f152a54b83c968bf86231d498ed716ae4b0488161040a36c8715a`다.
- 같은 로컬 호스트·Java 17·MySQL 8.4에서 기준/변경 각각 별도 폐기 DB에 같은 초기 시드를 적용했다.
  기존 사용자 DB나 볼륨은 초기화하지 않았다. 최종 jar는 빌드 뒤 복사해 실행 중 덮어쓰지 않았다.
- 상품 A=33,000원, B=72,600원으로 같은 구매자가 A×2, B×3, A×1+B×2 주문을 만든다.
  금액은 66,000원·217,800원·178,200원, 상세는 합계 4행이다.
- 관리자 HTTP로 상품 A를 삭제한 뒤 **그 동일 구매자를 삭제하는 순차 흐름**이다.
  같은 세 주문을 두 단계에서 관찰하므로 독립 표본 여섯 건으로 합산하지 않는다.
- 보존 조건은 주문 존재·상세 행/상품명/주종/이미지/단가/수량/합계·header 총액이다.
  삭제된 productId/userId와 회원 자유 입력 orderTable의 null 전환은 의도된 변화다.
- 외부 실사용자가 없는 로컬 데모의 정확성 재현이다. 지연/처리량·추천 품질·매출·운영 장애율 측정이 아니다.

## 원값

| 단계/지표 | 기준 BE01 코드 | BE02 변경 코드 |
|---|---|---|
| 최초 주문/상세 | 3주문 / 4상세 | 3주문 / 4상세 |
| 상품 A 삭제 뒤 상세 | 4→2행 | 4→4행 |
| 상품 A 삭제 뒤 상세 보존 위반 주문 | 2/3 | 0/3 |
| 상품 삭제 뒤 null productId 참조 | 0개(상세가 삭제됨) | 2개(상세 보존) |
| 이어 구매자 삭제 뒤 신규 주문 | 3→0개 | 3→3개 |
| 회원 삭제 뒤 주문 유실 | 3/3 | 0/3 |
| 회원 삭제 뒤 조회 가능한 확정 총액 | 주문이 없어 조회 불가 | 66,000 / 217,800 / 178,200원 |

상품 삭제 단계의 관측 위반 비율은 66.7%→0%(약 66.7%p 감소), 이어진 회원 삭제 단계의
주문 유실 비율은 100%→0%(100%p 감소)다. 이는 동일한 세 주문의 단계별 결과이며 두 비율을
더하거나 실서비스 장애 확률로 일반화하지 않는다. 기준 코드도 상품 삭제 뒤 header 총액은
남으므로 총액만 비교했다면 상세 소실을 놓쳤을 것이다.

변경본에서는 회원 삭제 후 userId/orderTable이 null이고 관리자 단건 조회가 가능했다.
같은 문자열 ID로 새 계정을 만든 뒤 해당 계정의 과거 주문 목록은 0개, 단건 접근은 403이었다.
이 결과는 삭제된 소유 참조 접근 제어이며 모든 기존 세션을 무효화했다는 뜻이 아니다.

## 재현

1. 기준/변경을 각각 전용 폐기 DB로 준비한다. 기준은 위 커밋의 data.sql, 변경은 새 data.sql을 쓴다.
   BE01 DB를 전환할 때는 001이 있는 상태에서 002를 적용한다. 기존 사용자 DB에서는 실행하지 않는다.
2. Java 17에서 `./mvnw --batch-mode --no-transfer-progress verify`로 만든 jar를 별도 파일로 복사하고
   `shasum -a 256`으로 실행 대상을 고정한 뒤 로컬 포트에서 실행한다.
3. [검증 안내](../TESTING.md)의 loopback *_ci DB·컨테이너 포트·run marker·명시 opt-in을 준비한다.
   아래 코드는 현재 스모크 도구를 사용하며 기준/변경 jar 모두 같은 입력과 관찰 경계를 사용한다.
   `PUBBURI_HISTORY_EXPECT`는 기준 실행에 before, 변경 실행에 after를 지정한다.

```bash
PYTHONPATH=scripts python3 - <<'PY'
import json
import os
import uuid
from api_smoke import ApiClient, promote_ci_fixture_admin, require_disposable_database

env = os.environ
require_disposable_database(env)
mode = env["PUBBURI_HISTORY_EXPECT"]
assert mode in {"before", "after"}
port = env["PUBBURI_SERVER_PORT"]
admin = ApiClient(port)
admin_id, admin_pass = "ci_admin_" + uuid.uuid4().hex, uuid.uuid4().hex
admin.request("POST", "/users", 201, {"id": admin_id, "name": "History admin", "pass": admin_pass})
promote_ci_fixture_admin(env, admin_id)
admin.request("POST", "/auth/login", 200, {"id": admin_id, "pass": admin_pass})
a, b = admin.request("GET", "/products?page=1&size=2", 200)["data"]["items"]
assert [a["price"], b["price"]] == [33000, 72600]
buyer = ApiClient(port)
uid, password = "ci_history_" + uuid.uuid4().hex, uuid.uuid4().hex
buyer.request("POST", "/users", 201, {"id": uid, "name": "History buyer", "pass": password})
buyer.request("POST", "/auth/login", 200, {"id": uid, "pass": password})
before = []
for lines in [[(a, 2)], [(b, 3)], [(a, 1), (b, 2)]]:
    before.append(buyer.request("POST", "/orders", 201, {
        "orderType": "TAKEOUT", "orderTable": "demo note",
        "details": [{"productId": p["id"], "quantity": q} for p, q in lines],
    })["data"])
assert [o["totalPrice"] for o in before] == [66000, 217800, 178200]
admin.request("DELETE", f"/admin/products/{a['id']}", 200)
after_product = [admin.request("GET", f"/orders/{o['oId']}", 200)["data"] for o in before]
fields = ("id", "name", "type", "img", "quantity", "unitPrice", "sumPrice")
project = lambda o: (o["totalPrice"], [tuple(d[k] for k in fields) for d in o["details"]])
violations = sum(project(old) != project(new) for old, new in zip(before, after_product))
admin.request("DELETE", f"/admin/users/{uid}", 200)
surviving = []
for old in before:
    if mode == "before":
        admin.request("GET", f"/orders/{old['oId']}", 404, error_code="NOT_FOUND")
    else:
        order = admin.request("GET", f"/orders/{old['oId']}", 200)["data"]
        assert order["userId"] is None and order["orderTable"] is None
        assert project(order) == project(old)
        surviving.append(order)
print(json.dumps({
    "product_history_violations": violations,
    "details_before": sum(len(o["details"]) for o in before),
    "details_after_product": sum(len(o["details"]) for o in after_product),
    "user_deletion_surviving_orders": len(surviving),
}))
PY
```

생성/삭제한 데이터는 폐기 DB에만 남긴다. 표준 CI 스모크의 별도 단일 상품/회원 fixture와
이 세 주문 전후 비교를 같은 표본으로 합산하지 않는다. 재가입 권한·관리자 페이지 보존은
[실제 스모크 코드](../../scripts/api_smoke.py)의 history_preservation_contract에서도 검증한다.

## 002 전환 검증과 실제 수정 과정

BE01 스키마의 기존 주문 3개·상세 4개·총액 59,100원을 002 적용 전후 보존했다.
fk_order_history_user와 fk_order_history_product 모두 SET NULL, 대상 컬럼 nullable을 확인했다.
새 초기 시드도 같은 history FK 이름을 사용한다.

처음에는 한 ALTER에서 기존 FK를 DROP하고 같은 이름으로 nullable/ADD를 수행해
**MySQL ERROR 1826(Duplicate foreign key constraint name)**가 발생했다. 실제 전환 결함으로
기록하고 성공/무효 결과로 숨기지 않았다. [MySQL 8.4 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)의
같은 ALTER FK add/drop 제한(INPLACE 지원, COPY 미지원)을 확인해 제거/추가를 분리하고
새 history 이름을 사용했다. 세부 실제 실행 알고리즘을 별도로 확정한 것은 아니다.
수정한 네 DDL로 실제 전환을 다시 실행해 위 보존 결과를 확인했다.

최초 1826 실패는 변경 SQL을 개발하며 발견한 결함이고, 위 기준 코드의 상품/회원 삭제
위반 분모에 추가하지 않는다. DDL 사이에는 FK가 없는 상태가 가능하므로 쓰기를 중단하고
백업/복구·상태 확인 후 재개해야 한다. [전환 절차](../ORDER-HISTORY-PRESERVATION.md)를 따른다.

## 남은 범위

- 이미 CASCADE로 사라진 과거 행은 복원하지 못한다.
- 회원 이름/비밀번호를 주문으로 복제하지 않으며 공식 회원 삭제 경로에서 자유 입력 메모도 지운다.
  직접 SQL 삭제는 이 서비스 정리를 거치지 않으므로 운영 삭제 수단으로 쓰지 않는다.
- BE03 기존 세션 재조회·권한 회수/동일 ID 재가입의 세션 인증과 전체 동시성·실패 복구는 별도 검증 과제다.
- 삭제 상태/상품 링크 비활성화의 화면 연결은 Frontend 소유다.
- 로컬 데모 세 주문의 정합성 결과이며 응답 시간·락 대기·대규모 운영 효과는 미측정이다.
