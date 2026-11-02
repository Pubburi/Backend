<div align="center">
  <img src="docs/brand/pubburi.webp" alt="주점부리" width="96" />
  <h1>주점부리 · Backend</h1>
  <p><strong>취향에 맞는 한 잔을 발견하고 기록하는 주류 큐레이션</strong></p>
  <p>회원·상품·후기·데모 주문을 제공하는 Spring Boot API</p>
  <a href="https://github.com/Pubburi/Backend/actions/workflows/ci.yml"><img src="https://github.com/Pubburi/Backend/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Java-17-437291" alt="Java 17" /> <img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F" alt="Spring Boot 3.5" /> <img src="https://img.shields.io/badge/MySQL-8.4-4479A1" alt="MySQL 8.4" />
</div>

<br />

## 📄 목차

- [프로젝트 개요](#overview)
- [현재 기능과 범위](#features)
- [기술 스택](#technology)
- [시스템 구조](#architecture)
- [문제 해결](#case-studies)
- [로컬 실행](#development)
- [검증과 협업](#verification)
- [개발 계획](#roadmap)

<br />

<a id="overview"></a>

## ✍ 프로젝트 개요

주류 탐색·후기·데모 주문과 관리자 기능을 제공하는 공통 API입니다. Java 17·Spring Boot·MyBatis·MySQL로 구성하며 세션 인증을 사용합니다.

<a id="features"></a>

## 🥃 현재 기능과 범위

| 영역 | 현재 제공 기능 |
|---|---|
| 회원 | 회원가입, 세션 로그인·로그아웃, 현재 계정·역할 기반 접근 제어, 삭제·비밀번호 변경 세션 회수 |
| 명시취향 | 본인 전용 조회·전체 교체 API, 설문 상태·미입력/빈 선호·soft 목표/hard 범위 구분 |
| 계정 찜 | 본인 전용 저장·해제·단건 상태·페이지 API, 반복 요청 멱등성·현재 상품 정보·삭제/재가입 격리 |
| 기본 주류 목록 | 비개인 ID순 B0 연결, 전체 응답 대기·크기 제한, 현재 DB 재조회와 장애 대체 |
| 상품 | 목록·이름/주종 검색, 가격·도수·명시 맛/향 AND 필터·정렬·페이지 이동, 상세·인기 상품 |
| 상품 특성 | 별도 조회·관리자 전체 교체 API, 용량·설명·맛/향 태그·출처, 미확인/검수된 빈 태그 구분 |
| 색인 관리 | 업무 트랜잭션의 상품 버전·변경 기록과 전송 예약, 별도 실행 relay의 claim/ack·재전달, 선택적 로컬 OpenSearch·nori와 삭제 표식. 공개 검색 전환은 후속 |
| 후기 | 상품별 평점·후기 조회와 작성·수정·삭제 |
| 데모 주문 | 주문 생성·내역·상세, 주문 시점 상품/가격 스냅샷·총액, 상품·회원 삭제 후 이력 보존, 관리자 완료 처리·스탬프 |
| 관리자 | 상품·회원·후기·매장·주문 관리 |

현재 인기 상품은 누적 주문 수 정렬이며 기본 추천 목록은 별도 ID순입니다. 개인화 추천은 후속 작업입니다.
API와 응답 계약은 [API Map](docs/API-MAP.md)을 참고하세요.

<a id="technology"></a>

## ⚙️ 기술 스택

| 영역 | 기술 |
|---|---|
| 언어·런타임 | Java 17 |
| 서버 | Spring Boot 3.5.0, Tomcat 10.1.61, Bean Validation |
| JSON | Jackson BOM 2.21.8 · 문자셋/엄격 입력 계약 |
| 현재 API 저장 | MyBatis 3.0.3, MySQL 8.4 · 이관 전 기준 |
| 확정 저장 전환 | PostgreSQL 18.6 · Spring Data JPA(Hibernate) · Flyway Community |
| 별도 JPA 제공자 | Boot 3.5.16 · Spring Data JPA 3.5.13 · Hibernate 6.6.53.Final · 세션/매장·상품 읽기 범위 |
| 로컬 색인 | OpenSearch 3.9.0 · analysis-nori 3.9.0 · 명시 관리 CLI |
| API 문서 | springdoc 2.8.9 · OpenAPI 3.1 · Swagger UI |
| 빌드·테스트 | Maven Wrapper 3.9.9, JUnit 5 |
| CI | GitHub Actions · backend-ci |

PostgreSQL의 독립 Flyway 스키마·설치/검증 도구와 [읽기 전용 원본 추출 도구](docs/SOURCE-SNAPSHOT.md), [승인 자료의 빈 대상 복사·검증 도구](docs/SOURCE-IMPORT.md)를 제공합니다. 전체 데이터 이관과 JPA API 전환은
[전환 계약](docs/POSTGRESQL-MIGRATION.md)을 따르며, 현재 API 실행 경로와 과거 검증을 보존합니다.
별도 [세션·매장 JPA 제공자](docs/JPA-REHEARSAL.md)는 복사한 PostgreSQL에서 기존 계약으로
로그인과 매장 CRUD·상품 목록/인기/상세·특성 읽기를 실행합니다. 상품 쓰기/outbox·다른 업무 API와
기본 로컬 스택의 전환은 후속입니다.

<a id="architecture"></a>

## 🏛️ 시스템 구조

~~~mermaid
flowchart LR
    Web["웹 클라이언트"] --> API["/api · 세션 인증"]
    API --> Service["서비스 · 트랜잭션"]
    Service --> Mapper["MyBatis"]
    Mapper --> DB[("MySQL")]
    Service -->|B0/대체| Recommendation["추천 서비스"]
    Events["명시 이벤트 파일"] --> CLI["색인 관리 CLI"]
    CLI --> Search["로컬 OpenSearch · nori"]
~~~

로컬 색인 기동·재생과 미완료 범위는 [실행 안내](docs/LOCAL-SEARCH.md)를 따릅니다. 현재 상품 검색은 MySQL을 사용합니다.

<a id="case-studies"></a>

## 🧩 문제 해결

업무와 검색의 상태 경계·입력 정규화·세션의 계정 수명·주문 이력 보존에서 내린 판단을 정리했습니다.
아래 결과는 현재 구현의 동작 범위이며, 응답 시간·처리량의 전후 개선 수치는 아직 측정하지 않았습니다.

### 1. 상품 변경 기록의 원자성부터 검색 엔진 재전달까지 연결하기

#### 문제

검색 색인을 준비하면서 상품만 바뀌고 전달할 기록은 없는 틈, 삭제 뒤 오래된 갱신이 상품을 부활시키는 조건을 다뤄야 했습니다. 과거 운영 장애를 주장하는 사례가 아니라, 실제 업무 쓰기에 재생 가능한 변경 기록을 연결한 설계 사례입니다.

#### 원인

현재 구성에서 MySQL commit과 별도 엔진 HTTP 전송은 같은 DB 트랜잭션이 아닙니다. 상품 행과 함께 버전까지 삭제하면 뒤늦은 입력의 순서를 판단할 기준도 잃습니다. 전역 auto-increment ID 역시 서로 다른 상품의 commit 순서를 뜻하지 않습니다.

#### 해결

생성·수정·특성 교체·주문 수 증가·삭제를 기존 업무 트랜잭션 안의 MANDATORY producer에 연결했습니다. 현재 상품·특성을 잠금 조회한 뒤 영속 상품별 버전·불변 투영·PENDING 예약을 함께 기록합니다. 별도 relay는 짧은 DB claim을 commit한 뒤 엔진에 보내고, DB 시각 lease와 token이 유효할 때만 ACK합니다. 삭제는 영속 tombstone으로 남기며 엔진 전송 중 업무 잠금을 유지하지 않습니다.

#### 결과

상품 변경과 전송 예약은 함께 commit·rollback되고, 명시 실행한 relay는 중단 뒤 남은 미전달 작업을 다시 처리합니다. 중복·순서 역전은 상품별 버전과 엔진 정본으로 판정하며, source·target 식별이 달라지면 ACK하지 않습니다. 이 동작을 공개 검색 전환이나 색인 유실의 자동 복구로 확대하지 않습니다. [생산 근거](docs/plans/2026-11-01-product-search-outbox.md)와 [relay의 상세 근거·한계](docs/plans/2026-11-01-durable-search-relay.md)는 별도 기록에 보존했습니다.

#### 대체안/회고

afterCommit 전달은 기록 유실 틈을 남기고, 업무 안의 엔진 전송은 장애와 잠금 시간을 결합하므로 선택하지 않았습니다. 전송은 at-least-once이며 중복은 버전/정본으로 판정합니다. source·물리 색인 UUID를 묶어 다른 target을 조용히 채택하지 않지만 DB와 엔진을 분산 트랜잭션으로 묶는 것은 아닙니다. 추가 조회·DB 쓰기·보관·재전달 비용이 있고 지연은 미측정입니다. 재색인/유실 복구·정리 정책과 공개 검색은 후속이며 event ID를 완료 watermark로 쓰지 않습니다.

#### 코드

~~~java
@Override
@Transactional
public int removeProduct(int id) {
	if (id <= 0) {
		return 0;
	}
	if (productDao.selectByIdForUpdate(id) == null) return 0;
	int changed = productDao.delete(id);
	if (changed > 0) searchOutbox.recordDeleted(id);
	return changed;
}
~~~

~~~java
private void append(int productId, boolean deleted, Map<String, Object> projection) {
    if (productId <= 0) throw new IllegalArgumentException("Positive product ID required");
    outbox.ensureVersion(productId);
    if (outbox.advanceVersion(productId) != 1)
        throw new IllegalStateException("Search source version cannot advance");
    Long version = outbox.selectVersionForUpdate(productId);
    if (version == null || version <= 0) throw new IllegalStateException("Search source version is invalid");
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("productId", productId);
    event.put("sourceVersion", version);
    event.put("deleted", deleted);
    event.put("document", projection);
    try {
        if (outbox.append(productId, version, deleted, json.writeValueAsString(event)) != 1)
            throw new IllegalStateException("Search outbox event could not be recorded");
        if (outbox.schedule(productId, version) != 1)
            throw new IllegalStateException("Search delivery could not be scheduled");
    } catch (JsonProcessingException error) {
        throw new IllegalStateException("Search projection cannot be serialized", error);
    }
}
~~~

~~~python
result = self.engine.apply(claim_document(claim))
if result not in ("applied", "duplicate", "stale_ignored"):
    raise SearchError("relay_write_outcome_invalid")
self.engine.ready()
if target_identity(self.engine, self.source_id) != before:
    raise SearchError("relay_target_changed_during_delivery")
accepted = self.store.ack(claim)
return {"status": "delivered" if accepted else "lease_lost", "outcome": result}
~~~

~~~sql
UPDATE t_product_search_delivery
SET status='DELIVERED', delivered_at=NOW(6),
    claim_token=NULL, lease_until=NULL, last_error=NULL
WHERE event_id=%s AND status='IN_FLIGHT'
  AND claim_token=%s AND lease_until>NOW(6)
~~~

[업무 쓰기와 트랜잭션](src/main/java/com/pubburi/pub/model/service/ProductServiceImpl.java) · [현재 투영·필수 트랜잭션·버전 기록](src/main/java/com/pubburi/pub/model/service/ProductSearchOutbox.java) · [버전과 outbox SQL](src/main/resources/mappers/productSearchOutbox.xml) · [claim·target 확인·조건부 ACK](scripts/search_relay.py) · [전송·운영 경계](docs/SEARCH-RELAY.md)

### 2. 같은 ID 재가입과 권한 변경에서 기존 세션을 구분하기

#### 문제

관리자 권한을 내리거나 계정을 삭제해도 기존 쿠키는 계속 인증됐습니다. 삭제 중 사용하지 않은 쿠키는 같은 ID로 재가입한 새 계정의 프로필에도 접근했습니다.

#### 원인

Guard가 로그인 때의 역할·이름·스탬프를 신뢰했습니다. ID만 DB에서 재확인해도 삭제 후 같은 문자열 ID로 만든 계정은 구분하지 못합니다. 본인 정보 수정 뒤 계정을 다시 읽어 세션을 갱신하는 방법도 다른 관리자가 그 사이 바꾼 자격을 구세션에 부여할 수 있었습니다.

#### 해결

가입마다 내부 `auth_epoch`를 만들고 명시적 비밀번호 변경 때 회전합니다. 보호 요청은 ID와 epoch가 모두 일치하는 현재 계정을 조회해 역할·표시 정보를 사용합니다. 본인 비밀번호 수정은 잠금 안에서 캡처한 변경 전·후 버전만 반환하고, 관측한 세션이 변경 전 버전과 같을 때만 그 수정이 만든 버전으로 갱신합니다. 일반 정보 수정은 세션 버전을 갱신하지 않습니다.

#### 결과

동일 조건의 로컬 MySQL 8.4·실제 HTTP에서 권한 강등 후 관리자 API 허용, 삭제 후 인증, 같은 ID 재가입 후 구쿠키 접근, 비밀번호 변경 후 구세션 인증이 **각각 1/1→0/1**로 바뀌었습니다. 강등은 403, 나머지는 401이며 새 계정 로그인은 유지합니다. 별도 이름/스탬프 시나리오의 오래된 표시도 1/1→0/1입니다. 서로 다른 각 1회 시나리오의 정확성 결과로, 실사용자 오류율·성능 수치는 아닙니다. [원값·조건·전환 근거](docs/measurements/2026-11-01-session-authority.md)를 분리해 기록했습니다.

#### 대체안/회고

서버의 모든 세션을 찾아 지우는 방법은 계정별 세션 색인과 수명 관리가 필요합니다. 현재는 계정 행의 버전과 요청 시점 DB 확인을 선택했습니다. 대신 Guard마다 DB 읽기 비용이 들고 이미 인증 검사를 통과한 요청 전체를 취소하지는 않습니다. DB 실패에는 저장된 권한으로 허용하거나 세션을 지우지 않습니다. 분산 세션·전체 동시성·지연 측정은 남아 있으며, 해시 형식만 바꾸는 재해시는 자격 회전과 구분합니다.

#### 코드

~~~java
public SessionUser currentUser(HttpSession session) {
	Object value = value(session);
	if (!(value instanceof SessionUser observed)) {
		return null;
	}
	if (observed.id() == null || observed.authEpoch() == null || observed.authEpoch().isBlank()) {
		clearIfCurrent(session, observed);
		return null;
	}
	// DB 예외는 전파한다. 저장된 역할로 대신 허용하거나 세션을 지우지 않는다.
	User current = userDao.selectSessionIdentity(observed.id(), observed.authEpoch());
	if (!sameIdentity(value(session), observed)) {
		return null;
	}
	if (current == null) {
		clearIfCurrent(session, observed);
		return null;
	}
	return SessionUser.from(current);
}
~~~

~~~java
public void refreshAfterCredentialChange(HttpSession session, SessionUser observed, UserUpdateResult updated) {
	if (!updated.credentialsChanged()) {
		return;
	}
	try {
		synchronized (session) {
			if (sameIdentity(value(session), observed)) {
				if (!java.util.Objects.equals(updated.id(), observed.id())
						|| !java.util.Objects.equals(updated.previousAuthEpoch(), observed.authEpoch())) {
					session.removeAttribute(SESSION_USER);
				} else {
					session.setAttribute(SESSION_USER, new SessionUser(observed.id(), observed.name(),
							observed.stamps(), observed.role(), updated.authEpoch()));
				}
			}
		}
	} catch (IllegalStateException invalidated) {
		// 동시 로그아웃으로 제거된 신원을 복원하지 않는다.
	}
}
~~~

[현재 계정 확인·조건부 세션 갱신](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/controller/auth/AccessGuard.java) · [잠금 안의 버전 캡처](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java) · [ID·epoch 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/user.xml) · [계약·전환](docs/SESSION-AUTHORITY.md)

### 3. 저장소 전환에서 업무 값·ID 수명과 복사 결과를 함께 확정하기

#### 문제

PostgreSQL 전환을 준비하며 ID·계정 수명·과거 금액·검색 전달 상태를 유지하는 복사 경로가 필요했습니다. 중간 실패를 부분 복사와 구분하고, 결과를 모르는 실행을 다시 덮어쓰지 않는 기준도 필요했습니다. 과거 운영 유실 사고를 수정한 사례가 아니라 실제 이관 도구의 설계·구현입니다.

#### 원인

남아 있는 행의 최댓값과 원본 ID의 다음 값은 다릅니다. JSON 재직렬화·실수의 소수 변환·서로 다른 collation의 정렬 순서도 원래 값을 바꿀 수 있습니다. sequence의 nextval은 행 rollback과 다르게 움직이고 COMMIT 응답/연결 종료 실패만으로 결과를 단정할 수도 없습니다.

#### 해결

승인 파일의 바이트·타입·source/window를 한 번 검수하고 빈 대상만 허용합니다. 실제 catalog와 PK tuple별 전체 값을 대조하며, 업무 행·transactional sequence 예약·영구 수령증을 같은 트랜잭션에 둡니다. sequence 자체의 잠금도 먼저 얻고 commit 시도·확인과 연결 종료를 분리합니다.

#### 결과

승인된 snapshot의 명시 ID·epoch·주문 금액·원문 JSON·전달 상태를 유지한 복사와 읽기 전용 대조를 제공합니다. 기존 데이터를 병합하거나 충돌을 정규화하지 않으며, 결과 미확인/연결 종료 실패도 성공·rollback과 구분합니다. 실제 확인 범위와 한계는 [작업 기록](docs/plans/2026-11-02-postgresql-source-import.md)에 남겼습니다.

#### 대체안/회고

표별 복사나 충돌 시 건너뛰기는 운영 복구를 단순하게 보이게 하지만 부분 상태를 성공으로 오인할 수 있어 빈 대상의 단일 트랜잭션을 선택했습니다. 대신 작업 중단·테이블 잠금과 제한된 파일 규모가 필요합니다. 파일은 원본 수준의 비공개 자료이며 hash는 암호화가 아닙니다. JPA API·relay 전환과 서비스 cutover는 별도 단계입니다.

#### 코드

~~~java
    static SourceArtifact.Report run(Map<String,String> env) throws Exception {
        // Read/validate once. No second file read after approved digest/identity checks.
        var source=input(env);var settings=PostgresSchemaTool.Settings.from(env);
        PostgresSchemaTool.guard(settings);PostgresSchemaTool.configuration(settings,PostgresSchemaTool.LOCATION).load().validate();
        return ImportTransaction.run(settings.connect(),connection -> {
                PostgresSchemaTool.guard(connection,settings);
                try(var marker=connection.prepareStatement("SELECT token FROM public.pubburi_pg_migration_guard WHERE token=?")) {
                    marker.setString(1,settings.token());try(var r=marker.executeQuery()){if(!r.next())throw new IllegalArgumentException("postgresql_import_guard_changed");}
                }
                try(var statement=connection.createStatement()) {
                    String names=String.join(",",SourceContract.TABLES.stream().map(t->"pubburi."+t.name()).toList());
                    statement.execute("LOCK TABLE "+names+",pubburi.migration_source_receipt IN ACCESS EXCLUSIVE MODE");
                    TargetSchemaContract.verify(connection);
                    for(var table:SourceContract.TABLES)try(var rows=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi."+table.name()+")")) {
                        rows.next();if(rows.getBoolean(1))throw new IllegalArgumentException("postgresql_import_empty_target_required");
                    }
                    try(var rows=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pubburi.migration_source_receipt)")) {rows.next();if(rows.getBoolean(1))throw new IllegalArgumentException("postgresql_import_already_recorded");}
                }
                for(int t=0;t<SourceContract.TABLES.size();t++) insert(connection,SourceContract.TABLES.get(t),source.data().path("tables").get(t));
                compare(connection,source);reserve(connection,source.data().path("sourceNext"));
                try(var receipt=connection.prepareStatement("INSERT INTO pubburi.migration_source_receipt(singleton_id,artifact_sha256,source_id,source_server,window_id,row_count) VALUES(1,?,?::uuid,?::uuid,?::uuid,?)")) {
                    receipt.setString(1,source.sha256());receipt.setString(2,source.data().path("sourceId").textValue());receipt.setString(3,source.data().path("sourceServer").textValue());
                    receipt.setString(4,source.data().path("window").textValue());receipt.setLong(5,source.report().rows());if(receipt.executeUpdate()!=1)throw new IllegalArgumentException("postgresql_import_receipt_failed");
                }
                PostgresSchemaTool.guard(connection,settings);return source.report();
        });
    }
~~~

~~~java
    static <T> T run(Connection connection,Work<T> work) throws Exception {
        boolean attempted=false,confirmed=false;
        try(connection) {
            connection.setAutoCommit(false);
            try {
                T value=work.execute(connection);attempted=true;connection.commit();confirmed=true;return value;
            } catch(Exception error) {
                try{connection.rollback();}catch(SQLException ignored){ }
                throw error;
            }
        } catch(Exception error) {
            if(confirmed)throw new OutcomeException("postgresql_import_committed_close_failed",error);
            if(attempted)throw new OutcomeException("postgresql_import_commit_status_unknown",error);
            throw error;
        }
    }
~~~

[복사·PK tuple 대조·sequence 잠금](tools/postgresql-migration/src/main/java/com/pubburi/migration/SourceImportTool.java) · [commit/자원 종료 분류](tools/postgresql-migration/src/main/java/com/pubburi/migration/ImportTransaction.java) · [typed bind](tools/postgresql-migration/src/main/java/com/pubburi/migration/TargetValues.java) · [복사 계약](docs/SOURCE-IMPORT.md)

### 4. 가격 변경·참조 삭제에서 주문 이력을 분리하기

#### 문제

주문이 끝난 뒤 상품 가격을 바꾸면 과거 금액이 달라졌고, 상품·회원 삭제는 상세나 주문 자체를 지웠습니다. 활성 상품/계정의 수명이 이미 만들어진 주문 기록까지 바꾸는 문제였습니다.

#### 원인

가격은 현재 상품 JOIN에, 행 생존은 상품·회원 FK의 CASCADE에 묶여 있었습니다. 가격 스냅샷만 추가해도 FK가 상세 행을 지우면 표현/수량이 사라집니다. 계정을 계속 남기는 방식은 이름·자격정보 보관과 모든 조회의 삭제 상태 처리를 요구합니다.

#### 해결

트랜잭션 안에서 상품을 FOR UPDATE로 잠금 조회하고 서버의 상품명·단가·수량별 합계와 주문 총액을 저장합니다. 이후 단건/목록 조회는 저장한 스냅샷을 읽습니다. 새 주문은 ORDER_SNAPSHOT, 과거 단가를 복구할 수 없어 전환 시 고정한 추정값은 LEGACY_ESTIMATE로 구분합니다. 상품·회원 FK는 nullable/SET NULL로 바꿔 참조만 끊고 주문을 보존합니다. 회원 삭제는 회원 잠금→자유 입력 메모 정리→계정 삭제 순서로 처리하며 null 소유자 주문은 관리자만 조회합니다.

#### 결과

가격 변경 실험의 금액 불변 위반은 **3/3 → 0/3**이었습니다. 별도로 같은 초기 데이터의 세 주문에 상품 삭제→회원 삭제를 적용하자 상세 보존 위반은 **2/3 → 0/3**, 회원 삭제 뒤 주문 유실은 **3/3 → 0/3**으로 바뀌었습니다. 삭제 후에도 66,000/217,800/178,200원의 금액과 상세가 남고 참조는 null이 됩니다. 이는 로컬 데모의 정확성 결과이며 실사용자 장애율·응답 성능 수치가 아닙니다. [가격 원값](docs/measurements/2026-10-31-order-price-invariance.md) · [삭제 흐름의 분모·원값·재현](docs/measurements/2026-10-31-order-history-preservation.md)을 구분해 기록했습니다.

#### 대체안/회고

주문 스냅샷과 nullable FK로 업무 기록을 활성 참조에서 분리하고 회원 이름/비밀번호를 복제하지 않았습니다. 과거 가격은 추정임을 명시합니다. FK 전환에서 실제 MySQL 1826 오류를 만나 동일 ALTER의 제거/추가를 분리했고, 쓰기 중단·백업·중간 상태 확인이 필요해졌습니다. 상품별 가격 SELECT와 회원 삭제 잠금의 비용은 미측정이며 전체 동시성 검증이 남아 있습니다. 삭제 계정 세션의 회수는 두 번째 사례의 별도 인증 경계로 처리합니다. 이미 삭제된 행을 복원한 결과로 확대하지 않습니다.

#### 코드

서비스의 트랜잭션 안에서 가격을 캡처하고 저장하는 부분입니다.

~~~java
        int totalQuantity = 0;
        int totalPrice = 0;
        for (OrderDetail detail : normalizedDetails) {
            detail.setOrderId(order.getoId());
            Product product = productDao.selectByIdForUpdate(detail.getProductId());
            if (product == null) {
                throw new IllegalArgumentException("Product not found: " + detail.getProductId());
            }
            // Capture server prices under a row lock before any amount-dependent write.
            if (product.getPrice() < 0) {
                throw new IllegalArgumentException("Invalid product price: " + detail.getProductId());
            }
            detail.setProductName(product.getName());
            detail.setProductType(product.getType());
            detail.setProductImg(product.getImg());
            detail.setUnitPrice(product.getPrice());
            try {
                detail.setSumPrice(Math.multiplyExact(product.getPrice(), detail.getQuantity()));
                totalPrice = Math.addExact(totalPrice, detail.getSumPrice());
                totalQuantity = Math.addExact(totalQuantity, detail.getQuantity());
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Order amount or quantity is out of range", ex);
            }
            if (productDao.incrementOrderCount(detail.getProductId(), detail.getQuantity()) <= 0) {
                throw new IllegalArgumentException("Product not found: " + detail.getProductId());
            }
        }
        if (orderDetailDao.insertAll(normalizedDetails) != normalizedDetails.size()) {
            throw new IllegalStateException("Order details could not be created");
        }
        if (orderDao.updateTotalPrice(order.getoId(), totalPrice) != 1) {
            throw new IllegalStateException("Order total could not be captured");
        }
        order.setTotalPrice(totalPrice);
~~~

조회는 현재 상품 가격 대신 저장된 상세를 사용합니다.

~~~sql
SELECT d.d_id AS id, d.order_id AS orderId, d.product_id AS productId,
       d.quantity, d.product_name AS name, d.product_type AS type,
       d.unit_price AS unitPrice, d.sum_price AS sumPrice,
       d.product_img AS img
FROM t_order_detail d
WHERE d.order_id = #{orderId}
ORDER BY d.d_id ASC
~~~

참조 삭제와 회원 자유 입력 정리는 다음 경계로 처리합니다.

~~~sql
ALTER TABLE t_order DROP FOREIGN KEY fk_order_user;
ALTER TABLE t_order
    MODIFY COLUMN user_id VARCHAR(100) NULL,
    ADD CONSTRAINT fk_order_history_user FOREIGN KEY (user_id)
        REFERENCES t_user(id) ON DELETE SET NULL;

ALTER TABLE t_order_detail DROP FOREIGN KEY fk_order_detail_product;
ALTER TABLE t_order_detail
    MODIFY COLUMN product_id INT NULL,
    ADD CONSTRAINT fk_order_history_product FOREIGN KEY (product_id)
        REFERENCES t_product(id) ON DELETE SET NULL;
~~~

~~~java
@Override
@Transactional
public int removeUser(String id) {
    if (isBlank(id) || userDao.selectByIdForUpdate(id) == null) {
        return 0;
    }
    // Block new order FK references until notes are cleared and the user is deleted.
    userDao.clearOrderNotes(id);
    if (userDao.delete(id) != 1) {
        throw new IllegalStateException("User could not be deleted");
    }
    return 1;
}
~~~

[가격 캡처·저장 경계](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/OrderServiceImpl.java) · [상품 잠금 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/product.xml) · [저장된 가격 조회](https://github.com/Pubburi/Backend/blob/main/src/main/resources/mappers/order.xml) · [회원 삭제 트랜잭션](https://github.com/Pubburi/Backend/blob/main/src/main/java/com/pubburi/pub/model/service/UserServiceImpl.java) · [삭제 정책·FK 전환](docs/ORDER-HISTORY-PRESERVATION.md)

<a id="development"></a>

## 🚀 로컬 실행

Java 17과 Docker Compose가 필요합니다. 레포 루트에서 실행합니다.
기존 DB는 [주문 가격 스냅샷 전환](docs/ORDER-PRICE-SNAPSHOT.md)부터 필요한 선행 전환과
[007 검색 변경 기록 전환](docs/SEARCH-OUTBOX.md)과 [008 전송 예약 전환](docs/SEARCH-RELAY.md)을
순서대로 확인하세요. 초기화 SQL을 다시 실행하지 않습니다.

웹·추천 서버까지 함께 실행하려면 [로컬 통합 실행](docs/LOCAL-DEVELOPMENT.md)을 따릅니다. AWS 계정이나 외부 AI 키 없이 사용할 수 있습니다.

~~~bash
./scripts/local-stack.sh init
./scripts/local-stack.sh install
./scripts/local-stack.sh up
~~~

백엔드만 개별 실행할 때는 아래 설정을 사용합니다.

macOS에서 여러 JDK를 사용하는 경우 먼저 export JAVA_HOME=$(/usr/libexec/java_home -v 17)로 Java 17을 선택합니다.

~~~bash
cp .env.example .env
# .env의 빈 DB 항목을 본인 로컬 값으로 채웁니다.
docker compose up -d db
./scripts/dev.sh
~~~

- 기본 서버: http://localhost:9987
- 상품 API: http://localhost:9987/api/products
- Swagger UI: http://localhost:9987/docs
- 필요한 환경변수: PUBBURI_DB_NAME, PUBBURI_DB_USER, PUBBURI_DB_PASSWORD, PUBBURI_DB_ROOT_PASSWORD, PUBBURI_DB_URL.
- PUBBURI_DB_URL에는 로컬 DB와 포트에 맞는 JDBC 접속 문자열을 설정합니다. 비밀번호와 접속 정보는 커밋하지 않습니다.
- 선택 항목: PUBBURI_DB_PORT(기본 3306), PUBBURI_SERVER_PORT(기본 9987), PUBBURI_LOG_LEVEL(기본 INFO).
- SQL은 빈 볼륨에서 처음 DB를 만들 때만 초기화됩니다. 기존 볼륨은 자동으로 다시 초기화하지 않습니다.

종료할 때는 docker compose down을 사용합니다. 일반 종료에서 DB 볼륨은 보존됩니다.

<a id="verification"></a>

## ✅ 검증과 협업

~~~bash
./scripts/check.sh
~~~

검증 명령과 폐기용 DB의 HTTP 검사 절차는 [테스트·CI 안내](docs/TESTING.md), 실행 환경은 [로컬 개발 문서](docs/LOCAL-DEVELOPMENT.md)를 따릅니다. 실행 결과와 회귀 근거는 작업 계획·PR에서 관리합니다.

main은 PR·backend-ci 성공·최신 main 반영·대화 해결을 요구합니다. 필수 승인은 0명이고 관리자에게도 적용합니다. Squash merge 뒤 작업 브랜치를 정리합니다.

자동화 PR은 최신 커밋의 Codex 리뷰와 관련 검증까지 통과해야 병합합니다. [개발·운영 기준](docs/DELIVERY-PLAN.md)을 함께 적용합니다.

<a id="roadmap"></a>

## 🗺️ 개발 계획

기한은 **2027년 2월 5일**이며 P1·P2 기능 모두 필수 목표입니다. 승인된 기획과 [백엔드 작업·일정](docs/DELIVERY-PLAN.md)을 기준으로 진행합니다.

- [ ] 상품 맛·향 특성과 출처 관리
- [ ] 취향·찜·추천 피드백·이벤트 API
- [ ] 추천 서비스 요청·응답과 실패 시 대체 추천
- [ ] 주문 당시 단가·상품 삭제 이력 보존 등 기존 정합성 개선
- [ ] 실제 DB 통합 테스트와 후속 배포 설계
