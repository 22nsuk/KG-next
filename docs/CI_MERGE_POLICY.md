# CI 결과와 main 병합 조건

**워크플로 변경과 저장소 보호 설정은 별개다.**
`.github/rulesets/main-ci.json`은 검토·가져오기용 설정이며, 파일을 커밋하거나
PR을 병합하는 것만으로 GitHub의 보호 설정이 활성화되지는 않는다.
활성화는 아래의 검증 순서를 거친 저장소 관리자의 별도 작업이다.

## 하나의 필수 검사

main의 필수 상태 검사는 **`ci-required`** 하나로 유지한다. 기존 repository,
script, Linux/Windows Java, Java 17, desktop, analysis-provenance UI와
engine-process matrix의 필수 성공 판정은 바꾸지 않는다.

Fox Search Regression, B11 Speed Notice UI, Native Windows Engine Acceptance,
Acceptance integration(SGF UI·Linux CPU)은
기존 실행 본문을 가진 재사용 워크플로로 CI 안에서 호출한다. `ci-required`는
그 호출에도 `needs`를 연결하고 `always()`로 실행한다. 선택 판정이 성공하고
필요하다고 판정한 모든 검사 결과가 `success`여야 통과한다. 실패, 취소,
필요한 실행의 skip, 누락, 알 수 없는 결과는 통과시키지 않는다.

별도 수동 실행(`workflow_dispatch`)도 유지하지만, 그 결과를 PR CI의 실행
결과로 대신 사용하지 않는다. 수동 실행과 CI 호출은 concurrency group을
분리하여 같은 브랜치의 수동 실행이 병합 검사를 취소하지 않게 한다.
릴리스·배포·최종 패키지·유료 서비스·GPU 검증 범위는 확대하거나 축소하지 않는다.
특히 Native Windows 검사는 고정 소스에서 빌드한 CPU 엔진을 쓰며 모든 배포
카탈로그 엔진이나 GPU 패키지의 수용 검사를 대신하지 않는다.

## 선택과 생략

`repository-checks`가 전체 Git 이력을 받아 PR base/head의 merge-base부터
head까지를 NUL 구분 파일명으로 비교한다. API의 파일 수 제한에 의존하지 않고,
삭제된 경로와 이름 변경 전후 경로를 모두 검사한다. diff나 SHA 해석이 실패하면
선택 job이 실패한다. 실패를 문서 변경으로 간주하지 않는다.

PR 변경이 **루트 Markdown 및 `docs/`의 Markdown/reStructuredText뿐**일 때만
네 보조 검사를 생략한다. 그 외 코드·테스트·자원·엔진 카탈로그·빌드 도구·설정·
알 수 없는 경로의 변경은 네 검사를 모두 실행한다. 기존의 세밀한 파일 목록보다
실행량이 늘어나는 보수적 정책이다. 공유 GUI·테스트 도우미·빌드 의존성 변경을
놓치지 않고 파일별 의존성 목록의 유지 부담을 줄이기 위해 선택했다.

main push와 merge queue의 `merge_group`은 보조 검사도 모두 실행한다.
merge queue 이벤트 지원은 queue를 활성화하는 설정이 아니다.
기본 CI는 문서 PR에서도 실행한다. 생략 가능한 보조 검사 이름을 별도의 필수
검사로 등록하지 않는다. 경로 필터로 워크플로 자체를 실행하지 않으면 필수 검사가
보고되지 않아 관련 없는 PR까지 대기할 수 있기 때문이다.

## 저장소 보호 적용 순서

1. 수정 PR의 정확한 head/테스트 병합 커밋에서 `ci-required`와 선택된 실행들을
   확인한다. 이 변경은 워크플로 파일을 수정하므로 네 보조 검사가 모두 필요하다.
2. 승인된 병합 후 해당 main SHA의 push CI 성공도 확인한다.
3. 현재 repository ruleset/branch protection을 읽고 원래 설정을 보존한다.
   접근 오류를 보호 설정 부재로 해석하지 않는다. 이미 같은 이름의 규칙이 있으면
   무작정 새 규칙을 중복 생성하거나 기존 규칙을 덮어쓰지 않는다.
4. GitHub Settings → Rules → Rulesets에서
   [`main-ci.json`](../.github/rulesets/main-ci.json)을 가져온 뒤 내용을 확인하고 적용한다.
   대상은 `refs/heads/main`, enforcement는 `active`, bypass actor는 없다.
   PR을 요구하되 필수 승인 수는 0으로 유지한다. `ci-required`의 출처는
   GitHub Actions(`integration_id=15368`), strict 최신 base 검사는 활성화한다.
   이 App ID는 이 저장소의 기존 실제 `ci-required` check run에서 확인한 값이다.
5. 저장 후 규칙을 다시 읽어 실제 enforcement·대상·예외·필수 검사·strict 값을
   확인한다. PR 화면에서 필수 검사 누락/실패 및 오래된 base가 병합을 막는지 확인한다.
   설정 JSON 검증이나 녹색 CI만으로 이 단계를 완료했다고 보고하지 않는다.

규칙과 적용된 main 규칙은 다음 읽기 전용 명령으로 확인할 수 있다.

```sh
gh api repos/22nsuk/KG-next/rulesets
gh api repos/22nsuk/KG-next/rules/branches/main
```

이 제안은 승인 인원 증가, CODEOWNERS 변경, 서명, 선형 이력, release 배포 정책을
추가하지 않는다. 서버 측 규칙이 활성화되기 전에는 CI 실패를 자동으로 병합 차단하는
기능까지 완료된 것이 아니다.

## 검증과 변경 관리

```sh
python3 -m unittest discover -s scripts -p test_ci_acceptance.py -v
```

검사는 전체 diff, 삭제·이름 변경, 300개 초과 경로, 문서 전용 PR, 자원/엔진
카탈로그 변경, 잘못된 SHA, Git 실패, 누락된 선택 출력, 모든 실행 상태의 판정을
다룬다. 워크플로 연결과 ruleset 템플릿도 검사한다. 이 Python 검사의 성공은 실제
GitHub Actions 실행이나 서버 규칙 활성화의 증거가 아니다.

필수 검사 이름이나 수용 기준을 바꿀 때는 CI·ruleset·문서를 함께 검토한다.
회귀 검사 실패를 숨기기 위한 skip/neutral, 타임아웃 확대, 보호 해제는 하지 않는다.
회수가 필요하면 작동하는 `ci-required`와 승인된 검증 범위를 유지하며, 서버 설정의
변경은 원래 값과 이유를 기록한 별도 관리 작업으로 수행한다.

공식 동작 기준:
[필수 상태 검사](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks),
[ruleset 규칙](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets),
[검사 이름 규칙](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/troubleshooting-rules).
