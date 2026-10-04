# KG-next

**KataGo를 활용한 한국어 중심의 바둑 복기·분석 프로그램입니다.**

[LizzieYzy Next](https://github.com/wimi321/lizzieyzy-next)를 기반으로 분석 제한의 탐색 재사용, 한국어 표현과 글꼴, 제품 이름 및 모델 설정을 개선하는 포크입니다. 원본의 저작권과 GPL 라이선스를 유지합니다.

[릴리스](https://github.com/22nsuk/KG-next/releases) · [문제 보고](https://github.com/22nsuk/KG-next/issues) · [English](README_EN.md) · [中文](README_ZH_CN.md) · [라이선스](LICENSE.txt)

> **배포와 소스를 구분해 주세요.** 이 저장소의 변경을 원본 프로젝트에서 이미 배포한 설치 파일에 적용된 기능으로 간주하면 안 됩니다. KG-next 설치 파일은 이 저장소의 릴리스에서 확인하고, 아직 없으면 아래 절차로 소스를 빌드하세요. 원본 앱의 자동 업데이트로 포크를 덮어쓰지 않도록 앱의 업데이트 메뉴는 KG-next 릴리스 페이지를 안내합니다.

## 주요 기능

기보를 열어 승률·우세 집수·후보 수·변화도·영역 점유를 확인하고, 전체 기보의 빠른 분석과 정밀 분석을 수행합니다. 기보 편집·저장, 독립 바둑판과 변화도 비교, 엔진 대국, 원본에서 제공하던 기보 수집 기능을 유지합니다. 외부 기보 서비스의 로그인·수집 동작은 서비스 상황과 실행 환경에 영향을 받습니다.

### 분석 허용·제외와 탐색 트리 유지

새 설정의 허용·제외 범위는 **첫 수만 제한**입니다. 기존 설정에서 **전체 수순에 제한 적용**을 선택했다면 그 값을 보존하므로, 탐색을 재사용하려면 제한 도구의 범위를 **첫 수에만 제한 적용**으로 바꾸세요.

바둑판을 우클릭하면 **이 수만 분석 / 분석 허용 수 추가 / 분석 허용 수 해제 / 이 수 분석 제외 / 분석 제한 모두 해제**를 사용할 수 있습니다.

| 엔진·작업 조건 | 동작 |
|---|---|
| **KG-next 패치 엔진**, 같은 국면, 첫 수만 제한 | 기존 하위 탐색을 남긴 채 허용·제외 목록을 바꿉니다. 제외한 수를 다시 허용하면 그 수의 기존 탐색도 재사용합니다. |
| 이후 수순까지 제한하거나, 그런 제한을 해제 | 하위 국면의 평가 조건이 달라지므로 탐색을 초기화합니다. |
| 확장 기능이 없는 일반 KataGo·다른 엔진 | 기존 프로토콜을 사용합니다. 엔진 내부의 제한 변경에 따른 재탐색은 유지됩니다. |
| 어느 엔진이든 다른 국면에 저장된 분석 | 현재 국면의 제한 변경만으로 기보 전체의 분석을 초기화하지 않습니다. |

Windows CUDA12·CUDA13 패키지는 [22nsuk/KataGo](https://github.com/22nsuk/KataGo)의 탐색 재사용 확장을 포함한 엔진을 사용합니다. 다른 백엔드와 사용자가 직접 지정한 엔진의 지원 여부는 실제 기능 협상으로 확인합니다. [엔진 빌드·검증 절차](engine/README.md)와 우클릭 메뉴의 도움말에서 현재 엔진의 지원 여부를 확인할 수 있습니다.

엔진은 `list_commands`에서 `kg-reuse-root-tree`를 제공하고 `kata-analyze ... reuseRootTree true`를 처리해야 합니다. 이름이나 버전 문자열만 보고 지원한다고 판단하지 않습니다. 이 기능은 표시에 이전 방문 수를 덧붙이는 방식이나, 강제 제외를 약한 집중 탐색으로 대체하는 방식이 아닙니다. 필터를 바꾸면 유효 후보와 루트의 집수·승률은 달라질 수 있습니다.

## KataGo 실행 파일과 모델

실행 파일은 탐색을 수행하는 **엔진**, `.bin` 또는 `.bin.gz`는 평가에 사용하는 **신경망 모델**입니다. 서로 다른 항목입니다.

| 항목 | 기준값 |
|---|---|
| 기본 엔진 소스 버전 | **KataGo v1.18.2** |
| 기존 14개 자산의 고정 엔진 소스 커밋 | `47aadc08518b3e121f22539796c911002f699584` |
| Windows CUDA12·CUDA13 엔진 소스 | [22nsuk/KataGo](https://github.com/22nsuk/KataGo), 자산별 커밋·릴리스·해시는 [자산 목록](src/main/resources/katago-assets.json)에 고정 |
| 지정 모델의 압축 전 파일명 | **`kata1-tf3-b11c768-s11003M-d5973M-7gres.bin`** |
| 공식 다운로드·패키지 형식 | `kata1-tf3-b11c768-s11003M-d5973M-7gres.bin.gz` |
| 압축 파일 크기 | `262039869` 바이트(약 250 MiB) |
| 압축 파일 SHA-256 | `93bdb63a3bfae4a70db0cb5265287495ecfc10b1ba1cc6814feeba1cdf055871` |
| 압축 해제 후 크기 | `281905904` 바이트 |
| 압축 해제 후 SHA-256 | `0a2042694a8f956e15fb7d8e6a310b2441c63aad3e6bc2c092e65c9d2ee7f064` |

다운로드·패키징의 기준은 [자산 목록](src/main/resources/katago-assets.json)입니다. 공식 `.bin.gz`를 받아 압축 전 내용까지 확인한 [검증 기록](docs/KG_MODEL_VERIFICATION.json)을 포함합니다. KataGo는 gzip 모델을 직접 읽으므로 불필요한 압축 해제는 하지 않습니다. 패키지 내부의 `weights/default.bin.gz`는 기존 실행 설정과의 호환을 위한 별칭이며, 다른 모델을 뜻하지 않습니다.

Windows CUDA12·CUDA13 두 자산만 검증한 포크 엔진 릴리스로 연결합니다. 나머지 14개 자산의 기존 출처와 해시는 유지합니다. **앱 업데이트, 엔진 교체, 모델 교체는 별도 작업**이며 사용자가 직접 지정한 엔진과 모델을 자동으로 덮어쓰지 않습니다.

Windows NVIDIA의 기본 패키지는 **CUDA 12.8 / cuDNN 9.8.0.87**이며 **CUDA 13.2 / cuDNN 9.24.0.43** 선택 패키지도 제공합니다. 프로필별 필수 DLL·manifest와 실제 추론 호환성을 검사하며 CUDA12와 CUDA13을 혼용하지 않습니다. [런타임 및 패키징 안내](engine/README.md)를 참고하세요.

GPU 백엔드는 장치·드라이버·런타임에 맞춰 선택하세요. 같은 모델을 사용해 응답성과 전체 기보 처리량을 직접 비교하는 것이 좋습니다. 이전 모델의 RTX 3070 측정값을 새 모델의 성능으로 재사용하지 않습니다.

## 실행과 설정

### 릴리스 패키지

KG-next 릴리스에서 운영체제와 백엔드에 맞는 패키지를 선택합니다. 엔진이 없는 패키지나 직접 빌드한 앱은 엔진 실행 파일, 모델, 설정 파일을 별도로 지정해야 합니다. 모든 패키지가 KG-next 패치 엔진을 포함한다고 가정하지 마세요.

Windows 설치 제품명은 `KG-next`이며 NVIDIA·OpenCL 등 변형에는 백엔드 이름이 붙습니다. 원본 제품을 업그레이드 대상으로 오인하지 않도록 설치 식별자를 분리합니다. macOS 앱 이름도 `KG-next.app`입니다. 내부 Java 패키지명과 기존 SGF 분석 속성은 기보 호환성을 위해 유지합니다.

### 소스 빌드

JDK 17 이상과 Maven이 필요합니다. 저장소의 CI는 JDK 21 환경도 사용합니다.

```sh
git clone https://github.com/22nsuk/KG-next.git
cd KG-next
mvn -B verify
java -jar target/lizzie-yzy2.5.3-shaded.jar
```

JAR의 기존 파일명과 `featurecat.lizzie` 진입점은 빌드·플러그인 호환을 위해 유지하며, 실제 앱 표시 이름은 KG-next입니다. 운영체제 패키징은 `scripts/package_windows_exe.sh`, `scripts/package_macos_dmg.sh` 및 [개발 문서](docs/DEVELOPMENT.md)를 참고하세요. 원본의 릴리스 자격 증명·서명 키가 포크에 자동으로 이전되지는 않습니다.

### 한국어와 글꼴

언어 설정에서 한국어를 선택합니다. 기본 UI 글꼴은 설치된 한글 지원 글꼴 중 맑은 고딕, Apple SD Gothic Neo, Noto Sans CJK KR / Noto Sans KR, Source Han Sans K, 나눔고딕 등을 순서대로 확인합니다. Windows 전용 글꼴이 없는 운영체제에서도 다른 글꼴로 대체하며, 사용자가 지정한 한글 표시 가능한 글꼴은 유지합니다.

운영체제에 한글 글꼴이 전혀 없으면 앱이 없는 글꼴을 생성할 수는 없습니다. 운영체제의 글꼴 관리 기능으로 한글 글꼴을 설치한 뒤 앱을 다시 실행하세요. 이 변경은 외부 글꼴을 무단 배포하거나 자동 다운로드하지 않습니다.

## 검증

```sh
# Java 단위·통합 검사 및 패키지 빌드
mvn -B -Djava.awt.headless=true verify

# 텍스트 줄바꿈과 모델·README 일치 검사
python3 scripts/check_line_endings.py
python3 scripts/validate_kg_metadata.py

# 실제 엔진의 첫 수 제한 재사용 회귀 검사
python3 /path/to/KataGo/python/probe_kg_root_reuse.py --engine /path/to/katago \
  --model /path/to/test-model.bin.gz --output /tmp/kg-root-reuse-proof
```

Java의 헤드리스 테스트, 실제 CPU 엔진 테스트, Windows GPU 테스트, 설치 패키지 테스트는 서로 다른 검증입니다. 특히 CPU에서 탐색을 보존했다고 해서 Windows CUDA/TensorRT 실행·성능까지 검증한 것은 아닙니다. 자세한 변경·검증·남은 조건은 PR과 [엔진 문서](engine/README.md)에 기록합니다.

## 문서와 원본

[개발](docs/DEVELOPMENT.md) · [배포 체크리스트](docs/RELEASE_CHECKLIST.md) · [변경 이력](CHANGELOG.md) · [기여](CONTRIBUTING.md)

다른 언어의 README와 원본의 이전 검증 기록은 기능 참고 자료입니다. KG-next의 현재 모델·엔진 조건 및 배포 상태는 이 기본 문서와 자산 목록을 기준으로 합니다.

KG-next는 [LizzieYzy Next](https://github.com/wimi321/lizzieyzy-next), LizzieYzy 및 Lizzie의 작업을 이어갑니다. 바둑 엔진은 [KataGo](https://github.com/lightvector/KataGo)입니다. 원본 저작권 고지와 `LICENSE.txt`를 보존하며, 엔진 패치의 원본 라이선스도 [engine/README.md](engine/README.md)에 명시합니다.
