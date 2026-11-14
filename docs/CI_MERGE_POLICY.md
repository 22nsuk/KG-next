# CI 결과와 main 병합 조건

**워크플로 변경과 저장소 보호 설정은 별개다.**
`.github/rulesets/main-ci.json`은 검토·가져오기용 설정이며, 파일을 커밋하거나
PR을 병합하는 것만으로 GitHub의 보호 설정이 활성화되지는 않는다.
활성화는 아래의 검증 순서를 거친 저장소 관리자의 별도 작업이다.

## 하나의 필수 검사

main의 필수 상태 검사는 **`ci-required`** 하나로 유지한다. 모든 PR에서
`repository-checks`의 성공을 요구한다. 문서 전용으로 판정된 PR을 제외하면
script, Linux/Windows Java, Java 17, desktop, analysis-provenance UI와
engine-process matrix도 모두 성공해야 한다.

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

### 기본 검사의 실행 횟수와 책임

- `native-clipboard-macos`는 실제 AppKit 클립보드 변경 감지와 별도 프로세스의
  복사를 검사한다. `SgfCopyDesktopTest`는 디스플레이를 필수로 요구하며,
  `ClipboardCopyTest`와 함께 실패 시 `ci-required`를 통과시키지 않는다.

- 데스크톱 전체 검사는 한 번 실행하고, `desktop-races`의 48개 사례를 두 번 더
  실행한다. 추가 반복은 시작 시 EDT 소유권, 저장·종료, 분석 창 수명, 트리 게시,
  저장 목록 worker, 해설 요청 소유권, HumanSL의 늦은 완료에 집중한다.
  이 사례들은 전체 검사에도 포함되어 총 세 번 실행된다. 나머지 데스크톱 사례의
  실행 빈도는 세 번에서 한 번으로 줄어들므로 그 범위의 간헐적 오류 탐지 기회도 줄어든다.
- 검색 탐색은 native/custom 메뉴를 각각 한 번 실행한다. 외부의 다섯 번 반복만
  제거하며, 같은 세션에서 취소·재열기와 상태 보존을 확인하는 내부 반복은 유지한다.
- 엔진 프로세스 smoke는 Linux/Windows `engine-process` 검사에서 각각 실행한다.
  동일 사례를 desktop에서 추가 실행하지 않는다. 두 플랫폼의 23개 필수 사례 판정은 유지한다.
- Windows 자격 증명 검사는 전체 Maven `verify`에 포함한다. 별도 선행 실행은
  제거하되, 실제 Windows 호스트에서는 profile과 무관하게
  `PlatformCredentialStoreTest.windowsDpapiRoundTripsAcrossRealNativeCalls`의
  성공을 요구한다. 필수 사례의 누락·skip·실패·오류는 Java 검사를 실패시킨다.

전체 desktop 보고서와 추가 반복 보고서는 서로 다른 디렉터리에서 수집한다.
CI는 성공한 각 반복의 XML·probe를 `target/desktop-smoke/races-1/`, `races-2/`에
보존하며, 실패한 반복은 `races/`에 남긴다. 각 호출의 요약도 별도로 보존한다.
필수 사례의 성공 여부와 각 반복의 실패 전파는 기존과 같이 검사한다.

자동 저장 중단 검사의 28개 사례와 실제 파일·재시작 검증은 유지한다.
JDI 감시는 클래스 전체의 메서드 진입 이벤트 대신 지정 메서드의 첫 bytecode에
중단점을 설치한다. 프로세스를 강제 종료하는 `vm.exit`와 cleanup 실패 주입은
유지하며, 목표 메서드에 도달하지 못하거나 먼저 종료되면 검사가 실패한다.

### 문서 전용 PR과 전체 검사의 선택

`repository-checks`가 전체 Git 이력을 받아 PR base/head의 merge-base부터
head까지를 NUL 구분 파일명으로 비교한다. API의 파일 수 제한에 의존하지 않고,
삭제된 경로와 이름 변경 전후 경로를 모두 검사한다. diff나 SHA 해석이 실패하면
repository job이 실패한다. 실패를 문서 변경으로 간주하지 않는다.

PR 변경이 **루트 Markdown 및 `docs/`의 Markdown/reStructuredText뿐**일 때만
`repository-checks`와 `ci-required`를 실행한다. repository 검사는 줄바꿈·로컬 링크·
엔진/모델 메타데이터와 CI 선택 정책을 검사하고, 문서를 실제로 읽는 다음 기존
Python 검사도 실행한다. 이 네 모듈은 portable scripts 그룹에서 repository 그룹으로
옮겨 전체 CI에서도 한 번만 실행한다.

- `test_validate_kg_metadata.py`: 메타데이터, 다국어 README, 패키지·지원 링크
- `test_windows_download_guidance.py`: 하드웨어별 Windows 다운로드 안내
- `test_validate_release_workflow_identity.py`: 릴리스 workflow와 체크리스트의 식별 정보
- `test_macos_signing_security.py`: macOS 서명 실패 처리와 문서의 일치

코드·테스트·자원·엔진 카탈로그·빌드 도구·설정·알 수 없는 경로가 하나라도 바뀌면
기존 전체 검사를 실행한다. main push와 merge queue의 `merge_group`도 항상 전체
검사를 실행한다. merge queue 이벤트 지원은 queue를 활성화하는 설정이 아니다.
문서 PR에서는 native Windows·Java·desktop 실행을 생략하므로 플랫폼 검증을
문서 PR마다 반복하지 않으며, 문서 소비 검사와 병합 후 main 전체 검사가 그 범위를 맡는다.

`ci_acceptance.py`가 모든 실행 job의 결과를 한 곳에서 판정한다. repository 검사가
성공하고 전체 실행 필요 출력(`acceptance_required`)이 정확히 `false`일 때만
선택 job의 `skipped`를 허용한다.
문서 PR도 실패·취소·결과 누락·알 수 없는 결과는 거부하며, dependency 목록이 정책과
달라도 실패한다. 선택 출력이 없거나 잘못되면 생략을 허용하지 않는다.
`ci-required`는 `always()`와 모든 job에 대한 `needs`를 유지한다.
생략 가능한 job을 별도의 필수 검사로 등록하거나 workflow 자체에 경로 필터를
걸지 않는다. 필수 검사 결과가 보고되지 않아 PR이 계속 대기하는 상황을 피하기 위해서다.

### 캐시와 desktop 증거 보관

Native Windows Engine Acceptance에도 기존 Java job과 같은 Maven 의존성 캐시를
사용한다. 별도로 고정 native CPU 엔진과 SDK의 빌드 산출물을 캐시한다.
소스 저장소·SHA, runner 이미지·아키텍처, MSVC·Windows SDK와 실제 빌드 도구의
SHA-256, SDK 경로·환경, 빌드 스크립트·의존성 lock·workflow의 옵션을 키에 반영한다.
정확한 키가 일치할 때만 컴파일을 생략하고, 키가 다르거나 복원에 실패하면 깨끗이
다시 빌드한다. 다른 키를 대안으로 재사용하는 `restore-keys`는 설정하지 않는다.

캐시에는 SDK prefix, 엔진 실행 파일, 빌드 receipt·로그·CMake 설정·라이선스만 넣는다.
고정 소스 checkout 검증은 항상 실행하고, 캐시 적중 시에도 SDK 전체 파일·lock과
실행 파일의 해시를 확인하는 기존 패키지 감사, PE 의존성 검사, 개발용 PATH를 제거한
엔진 실행을 다시 수행한다. 감사 오류는 검사를 실패시킨다.
`MoveFocusNativeAcceptanceIT`의 1개 사례와 `EngineGameParametersAcceptanceIT`의
2개 사례도 매번 실제 Windows 앱·엔진으로 실행하며 누락·skip·실패를 허용하지 않는다.
캐시는 작업 전체가 성공한 뒤에만 저장한다. 패키지 감사와 인수 결과는 캐시하지 않는다.

캐시 적중 시 `source-build.json`과 빌드 로그는 원래 빌드의 증빙이며,
`source-package.json`과 인수 검사 보고서는 현재 실행의 증빙이다.
`native-engine-cache-inputs.json`과 Actions의 복원·빌드·감사 단계 시간을 함께 확인하여
새 키의 첫 빌드와 같은 키의 재사용 비용을 비교한다. 실제 절감 시간은 runner와 캐시
전송 비용에 따라 달라지며, 캐시 도입 전 빌드 시간만으로 절감량을 단정하지 않는다.
전체 Java 검증의 JaCoCo 산출물과 CPU archive 전달용 artifact는 그대로 유지한다.

성공한 desktop artifact는 7일 보관한다. 최초 전체 검사의 이미지는 모두 유지하고,
추가 두 반복의 `races-1/**/*.png`, `races-1/**/*.bmp`, `races-2/**/*.png`,
`races-2/**/*.bmp`만 업로드에서 제외한다. 테스트 중 이미지 생성·읽기 검증과
각 반복의 XML·텍스트·SGF·로그·result·lifecycle 증거는 그대로 유지한다.
실패·취소 시에는 진행 중인 `races/`를 포함한 전체 desktop 증거를 14일 보관하도록
업로드를 시도한다. artifact 이름과 반복별 경로도 유지한다.
작은 local CI JSON/Markdown 요약은 성공 여부와 무관하게 14일 보관하며, 실행 SHA·
명령·결과·JUnit 집계로 해당 실행을 식별한다.

### Dependabot 업데이트 묶음

기존 `.github/dependabot.yml`의 Maven·GitHub Actions 주간 검사와 생태계별
버전 업데이트 PR 상한 5개를 유지한다. 월요일 09:00 UTC(한국 시간 18:00)에
일반 minor/patch 버전 업데이트를 생태계별 한 묶음으로 제안한다.
major 업데이트는 개별 PR로 남기며, 이 그룹 설정은 보안 업데이트에 적용하지 않는다.
자동 병합이나 기존 CI 생략은 추가하지 않는다.

`me.friwi:jcefmaven`은 일반 Maven 묶음에서 제외하고 개별 제안을 유지한다.
JCEF는 POM뿐 아니라 네이티브 번들·릴리스 핀·체크섬을 함께 갱신해야 하므로,
다른 의존성 업데이트와 분리해 [기존 업그레이드 절차](DEPENDENCY_UPGRADE.md)를 따른다.
KataGo 소스 SHA와 사용자 정의 SDK JSON·번들 체크섬은 이 설정의 자동 갱신 대상이 아니다.

포크에서는 설정 파일 존재와 실제 Dependabot 활성화가 별개다.
관리자는 GitHub의 Dependabot version updates 활성화 상태와 최근 업데이트 작업을
확인해야 한다. 설정 PR만으로 서비스 활성화나 업데이트 PR 생성을 확인했다고 간주하지 않는다.
공식 기준: [그룹 옵션](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference#groups),
[포크의 버전 업데이트 활성화](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/secure-your-dependencies/configure-version-updates#enabling-version-updates-on-forks).

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

## Linux 화면 검사 준비

엔진 프로세스·데스크톱·분석 UI·Fox·SGF 인수 검사의 Ubuntu 화면 준비 단계는
기존 미러 helper의 `--install` 경로를 사용한다.
패키지 다운로드 한 번에 120초를 허용하고, APT 오류(100)나 다운로드 시간 초과(124)면
runner에 이미 등록된 기본 미러 우선순위로 한 번 더 시도한다. 미러 주소·서명 검사와
기존 다운로드 캐시는 유지한다. 취소·강제 종료·알 수 없는 실행 오류는 재시도하지 않는다.
두 다운로드가 모두 실패하면 설치와 테스트를 진행하지 않고 해당 검사를 실패시킨다.

시간 제한은 [`apt-get --download-only`](https://manpages.debian.org/testing/apt/apt-get.8.en.html)
단계에만 적용한다. 다운로드가 완료된 뒤 `--no-download`로 설치하여 dpkg 실행 중
다운로드 예산 때문에 강제 종료하지 않는다. 작업 전체와 테스트 단계의 시간 제한은
유지하며, helper에 옵션을 전달하지 않는 기존 호출은 미러 우선순위만 설정한다.

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
