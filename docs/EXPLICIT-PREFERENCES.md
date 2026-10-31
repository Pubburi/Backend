# 본인 전용 명시취향 저장 계약

C03/BE04/R01의 **명시 입력 저장·조회 증가분**이다. 추천 점수·모델 학습·행동 추정·이벤트 축적을
구현하지 않는다. R01 설문 화면과 추천 호출/필터 실행은 후속이므로 전체 R01/BE04 완료가 아니다.

## 경로와 소유권

GET/PUT `/api/users/me/preferences`는 현재 로그인한 계정만 대상으로 한다. 관리자도 자기 데이터만
읽고 바꾸며 타인 취향용 경로는 없다. userId/authEpoch를 요청의 소유 선택자로 받지 않고 응답에도
노출하지 않는다. 같은 이름의 본문/쿼리 값은 소유 선택에 영향을 주지 않는다. 기존 관리자 회원
목록/타인 profile 응답에는 취향을 추가하지 않는다.

JSON ApiResponse의 `data/message/error`를 유지한다. GET과 유효한 PUT은 비인증/만료 401,
잘못된 JSON/필드/상태·역전 범위는 400, 정규화 후 중복/알 수 없는 주종은 400 BAD_REQUEST다.
지원하지 않는 Content-Type은 415, Accept는 406 JSON 오류다. DB 실패를 NOT_STARTED나 401로
위장하지 않고 기존 내부 오류 경로로 전파한다.

```json
{
  "surveyStatus": "NOT_STARTED",
  "softPreferences": {
    "categories": null,
    "tasteTags": null,
    "aromaTags": null,
    "targetAbvPercent": null
  },
  "hardConstraints": {
    "minPriceKrw": null,
    "maxPriceKrw": null,
    "minAbvPercent": null,
    "maxAbvPercent": null
  }
}
```

이는 활성 계정에 저장 행이 없을 때의 data 예시다. 이미 삭제됐거나 epoch가 달라진 계정은 이
기본값을 받지 않고 401이다. 응답에는 항상 세 루트 필드와 각 그룹의 네 필드가 있다. [OpenAPI 3.1](openapi/explicit-preferences.json)을 따른다.

## 상태·unknown·전체 교체

| 상태 | 의미·허용 값 |
|---|---|
| NOT_STARTED | 아직 입력하지 않았거나 명시적으로 초기화. 모든 선호/범위가 null |
| SKIPPED | 설문을 명시적으로 건너뜀. 모든 선호/범위가 null |
| COMPLETED | 입력을 마침. 일부/전체 unknown도 허용. 빈 배열은 명시적으로 선호하지 않음 |

PUT은 surveyStatus가 필수인 전체 교체다. 생략/null 그룹·필드는 unknown이며 이전 값을 유지하지
않는다. `{"surveyStatus":"SKIPPED"}` 또는 NOT_STARTED로 기존 값을 지울 수 있다. 건너뛰기/
초기화에 []나 숫자 0을 섞으면 거부한다. []는 명시 입력이므로 null과 다르며 0도 실제 경계 값이다.
동시에 저장하면 계정 행 잠금 순서로 마지막 전체 교체가 남는다. ETag/revision 충돌 검사는 미구현이다.

## soft와 hard의 의미

| 그룹·필드 | 검증·소비 의미 |
|---|---|
| softPreferences.categories | null/최대 8개. 기존 탁주·약 · 청주·과실주·증류주·위스키·양주·맥주·사케. 선호 순위 입력이지 필수 주종 필터가 아님 |
| softPreferences.tasteTags/aromaTags | null/최대 20개·각 40자. trim/Locale.ROOT 소문자/정렬, null 항목·공백·정규화 중복 거부 |
| softPreferences.targetAbvPercent | null/JSON 숫자 0–100, 최대 소수 2자리. 가까운 값을 선호하는 soft 목표 |
| hardConstraints.min/maxPriceKrw | null/JSON 정수 0–2,147,483,647. 포함 범위이며 하한≤상한 |
| hardConstraints.min/maxAbvPercent | null/JSON 숫자 0–100, 최대 소수 2자리. 포함 범위이며 하한≤상한 |

주종은 앞뒤 공백만 제거하고 기존 라벨을 정확히 사용한다. 동의어·번역·추정 주종을 만들지 않는다.
숫자/boolean을 문자열 태그로, 문자열/boolean을 수치로 변환하지 않는다. 가격 소수·ABV 범위 초과/
소수 2자리 초과도 거부해 DB에서 반올림·잘림이 발생하지 않게 한다.

soft 목표가 hard 범위 밖이어도 저장할 수 있다. 예를 들어 target 80, 허용 0–10이면 80을 맞추려고
상한을 바꾸면 안 된다. 가격 0–0은 unknown이 아니므로 후보가 없더라도 이후 소비자가 범위를
완화해서는 안 된다. **이번 API는 이를 저장할 뿐 추천/필터를 실행하지 않는다.** 향후 Backend
호출 어댑터는 soft categories/target을 Recommendation의 profile에, 명시한 hard 범위를 constraints에
연결해야 한다. soft 주종을 B0 constraints.categories로 자동 승격하지 않는다. 모델/평가 정본은
[Recommendation 계획](https://github.com/Pubburi/Recommendation/blob/main/docs/DATA-AND-MODEL-PLAN.md)이 소유한다.

## 계정 수명·트랜잭션

조회는 `t_user.id + auth_epoch`로 활성 계정을 제한한 LEFT JOIN을 사용한다. 첫 행이 없어지면
인증 만료이며, 살아 있는 사용자에 취향 행만 없는 경우에만 NOT_STARTED다. 헤더/값 모두 같은
소유 epoch를 사용하고 read-only REPEATABLE READ 트랜잭션에서 조립해 새 계정의 값을 섞지 않는다.

쓰기는 사용자 FOR UPDATE → 관측 epoch와 현재 epoch 비교 → 헤더 upsert → 값 교체를 같은
트랜잭션에 둔다. Guard를 통과한 뒤 삭제/같은 ID 재가입·자격 회전이 먼저 완료되면 새 계정에
오래된 입력을 쓰지 않고 401을 반환한다. 값 삽입 실패는 예외로 rollback한다. 모든 기존 업무의
in-flight 요청을 취소하거나 전체 동시 부하를 검증한 결과는 아니다.

회원 삭제는 새 헤더와 값의 FK CASCADE로 비공개 취향을 제거한다. 같은 문자열 ID로 재가입해도
NOT_STARTED이고 이전 쿠키는 BE03에서 거부한다. 타인·관리자에게 취향을 복제하거나 공개하지 않는다.
설명용 합성 선언은 실제 사람의 선호·추천 품질·CTR/구매 성과로 사용하지 않는다.

## 수동 005 전환·검증

새 폐기 DB는 data.sql, 기존 DB는 001→004 적용 상태에서 [005](migrations/005-explicit-user-preferences.sql)를
수동 적용한다. 백업/복원·쓰기 중단, t_user.id와 DB 기본 charset/collation 일치, 기존 9개 테이블
행/digest 보존을 먼저 확인한다. 두 새 테이블만 만들고 기존 행·인증값을 변경하거나 선호를 채우지 않는다.

DDL은 암시적 commit이며 전체 재실행은 첫 table-exists 오류에서 중단한다. 부분 실패는 테이블/
제약 상태를 검토해 복구·남은 단계를 실행하고 --force/data.sql 재실행/앱 시작 시 자동 전환을 하지 않는다.
동일 collation이 아니면 기존 사용자 컬럼을 임의 변경하지 말고 새 테이블 정의를 검토한다.

[TESTING](TESTING.md)의 전용 폐기 DB·컨테이너·marker에서 두 계정/관리자 본인 격리·상태/null/[]/0·
역전/엄격 타입·삭제/재가입과 문서를 검증한다. 새 권한/저장 증가분이며 성능 개선율을 주장하지 않는다.
