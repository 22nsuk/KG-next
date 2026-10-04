# KG-next 엔진 연동

탐색 트리·GTP 확장·엔진 빌드·네이티브 회귀 검사는 [22nsuk/KataGo](https://github.com/22nsuk/KataGo)에서 관리합니다. 이 GUI 저장소는 엔진 소스 패치를 중복 보관하지 않습니다.

탐색 재사용 변경은 [엔진 PR #1](https://github.com/22nsuk/KataGo/pull/1)에 네이티브 테스트와 실제 프로세스 회귀 검사와 함께 통합했습니다. CUDA 번들의 실제 빌드 커밋과 바이너리 해시는 [자산 목록](../src/main/resources/katago-assets.json)의 `windows-nvidia`·`windows-nvidia-cuda13` 항목이 기준입니다.

## 탐색 재사용

KataGo 저장소의 `docs/KG_ROOT_REUSE.md`와 `python/probe_kg_root_reuse.py`가 동작 및 검증의 기준입니다. 해당 기능을 포함한 엔진을 빌드하고 KG-next의 사용자 지정 엔진으로 선택하세요. Windows CUDA 13.2 빌드는 엔진 저장소의 `docs/KG_CUDA13_WINDOWS.md`를 따릅니다.

GUI는 실제 엔진이 GTP `list_commands`에 `kg-reuse-root-tree`를 광고할 때만 `kata-analyze ... reuseRootTree true`를 보냅니다. 동일 국면의 첫 수 허용·제외를 바꿀 때 하위 노드를 보존하고, 제외 후보는 선택·출력·집계에서 제외합니다. 다시 허용한 후보는 이전 탐색을 사용할 수 있습니다. 이후 수순까지 적용하는 제한의 변경은 하위 평가 조건이 달라지므로 초기화합니다.

국면·규칙·덤·모델 변경 및 명시적 캐시 초기화는 엔진의 무효화 규칙을 따릅니다. 누적 루트 방문 수와 현재 허용된 후보 방문 수의 합은 다를 수 있습니다. GUI가 과거 수치를 새 분석에 더하는 방식이 아닙니다.

## NVIDIA 런타임

- 기본 `windows-nvidia`: CUDA 12.8.0 / cuDNN 9.8.0.87, 저장된 런타임 프로필 이름 `cuda12.8-cudnn9`를 유지합니다.
- 추가 `windows-nvidia-cuda13`: CUDA 13.2.2 / cuDNN 9.24.0.43 CUDA13, 런타임 프로필 `cuda13.2-cudnn9.24`.

두 CUDA 번들은 `22nsuk/KataGo`의 같은 고정 소스 커밋에서 빌드하며 탐색 재사용 확장을 포함합니다. 각 자산에는 소스 저장소·커밋, 커밋 앞 12자리로 고정한 `kg-next-<SHA12>` 릴리스, 압축 파일·실행 파일·`source-release.json` 해시를 기록합니다. 다운로드와 패키징은 자산별 출처를 사용하고, 다른 14개 엔진 자산의 기존 출처를 유지합니다. TensorRT 패키지의 HumanSL 보조 실행 파일은 이 기본 CUDA12 실행 파일로 복사하고 그 해시에 연결합니다.

CUDA13 런타임의 `cudart`, cuBLAS, NVRTC, nvJitLink는 CUDA13 세트를 사용하고 cuDNN은 CUDA13용 9.24.0.43의 전체 DLL 세트를 함께 준비합니다. CUDA12 DLL을 이름만 바꾸거나 CUDA12의 manifest를 재사용하지 않습니다. 두 포크 엔진은 zlib 1.3.1을 정적으로 링크합니다. GUI는 신뢰한 자산의 실행 파일과 출처 매니페스트가 모두 일치할 때 동적 zlib 파일 검사를 생략합니다.

다운로드 자산·실행 파일 해시 검증과 실제 GPU 추론 검증은 서로 다른 검사입니다. GUI는 프로필별 필수 파일·manifest를 검사한 뒤 드라이버 및 추론 호환성을 검사합니다. 기존 CUDA12의 성공 표시는 CUDA13 검사 결과로 재사용하지 않습니다.

## 선택 CUDA13 패키지 준비

실제 엔진 릴리스 파일을 자산 목록에 반영할 때는 다음 명령으로 두 압축 파일을 검증하고 새 목록을 생성합니다. 두 파일의 소스·프로필·의존성·모든 파일 해시가 통과해야 출력합니다. 생성 결과를 검토한 뒤 `src/main/resources/katago-assets.json`을 교체합니다.

```sh
python3 scripts/pin_custom_cuda_assets.py \
  --cuda12 /path/to/katago-source-<SHA12>-windows-nvidia.zip \
  --cuda13 /path/to/katago-source-<SHA12>-windows-nvidia-cuda13.zip \
  --source-commit <FULL_SHA> --tag kg-next-<SHA12> \
  --output work/reviewed-custom-cuda-catalog.json
```

```sh
PREPARE_WINDOWS_CUDA13=true ./scripts/prepare_bundled_katago.sh
WINDOWS_BUILD_CUDA13=true ./scripts/package_windows_exe.sh YYYY-MM-DD 1.0.0 target/lizzie-yzy2.5.3-shaded.jar next-YYYY-MM-DD.1
```

일반 Windows 패키징 도구와 JDK가 필요합니다. 준비와 패키징 모두 옵션을 켜야 합니다. 생성되는 `windows64.nvidia.cuda13.portable.zip`은 별도 제품 폴더 `KG-next NVIDIA CUDA13`을 사용합니다. 기존 사용자의 엔진 폴더를 자동 교체하는 절차가 아닙니다.

## CUDA12·CUDA13 한 폴더에 설치

두 버전을 함께 사용하려면 `scripts/assemble_windows_dual_cuda.py`로 하나의 portable 설치를 구성할 수 있습니다. 기존의 검증된 Windows app-image에서 GUI 실행 파일·Java 런타임·JCEF·readboard·기본 모델을 재사용하고, 최신 GUI JAR과 같은 소스 커밋의 CUDA12·CUDA13 엔진을 추가합니다. Python 3.10 이상이 필요하며 다운로드·엔진 빌드·JRE 재생성은 수행하지 않습니다.

아래 예시는 이미 `prepare_katago_source_assets.py`로 검증·추출한 엔진 폴더와 `prepare_bundled_nvidia_runtime.py`로 준비한 각 런타임 폴더를 사용합니다. app-image에는 현재 자산 목록의 기본 모델과 한 개의 jpackage 실행 파일·설정 쌍이 있어야 합니다. JAR은 이 두 엔진을 등록하는 최신 GUI 빌드여야 하며 `--release-tag`는 해당 GUI 버전을 표시합니다.

```powershell
python scripts/assemble_windows_dual_cuda.py `
  --app-image 'C:/packages/KG-next NVIDIA' `
  --jar target/lizzie-yzy2.5.3-shaded.jar `
  --cuda12-engine 'C:/prepared/engines/windows-x64-nvidia' `
  --cuda13-engine 'C:/prepared/engines/windows-x64-nvidia-cuda13' `
  --cuda12-runtime 'C:/prepared/cuda12.8-cudnn9' `
  --cuda13-runtime 'C:/prepared/cuda13.2-cudnn9.24' `
  --release-tag next-YYYY-MM-DD.N `
  --output 'C:/Games/KG-next'
```

출력 폴더는 새 폴더 또는 빈 폴더여야 하며 입력 폴더와 겹칠 수 없습니다. 스크립트는 자산 목록으로 엔진 원본·출처·모델을 검증하고, 엔진 빌드에서 감사한 NVIDIA DLL 목록·크기·해시와 런타임을 대조한 뒤 완성된 설치를 배치합니다. 기존 설치의 `user-data`, 기보, 계정 정보는 복사하지 않습니다. 네이티브 실행 설정은 이 설치의 `user-data`를 명시하여 인접 설치의 자동 마이그레이션을 방지합니다.

`KG-next.exe`를 실행한 뒤 엔진 메뉴에서 기본 `KataGo Bundled` (CUDA12)와 `KataGo CUDA13 (13.2 / cuDNN 9.24)`를 선택합니다. CUDA13은 추가 항목이며 기존 기본 엔진·직접 수정한 명령을 바꾸지 않습니다. 각 엔진 폴더가 자식 프로세스의 DLL 검색 경로 앞에 오므로 같은 이름의 cuDNN DLL도 서로 섞이지 않습니다. GUI·JRE·기본 모델·GTP/analysis 설정은 한 벌만 보관합니다.

설치 루트의 `README-CUDA.md`에 사용법이 있으며, `pwsh -NoProfile -File .\Test-CUDA.ps1`로 두 버전을 순차 검사합니다. `-Profile CUDA12` 또는 `-Profile CUDA13`으로 한 버전만 검사할 수 있습니다. 각 검사는 1개 국면·32방문·탐색 스레드 4개로 실행하고 자동 튜닝·추가 배치 검사를 생략하며, 90초 제한·종료 코드·완료된 탐색 출력을 확인합니다. 결과는 `user-data/test-results`에 남기며 GUI 설정은 수정하지 않습니다. 짧은 실행 검사는 성능 비교를 대신하지 않습니다. 네이티브 실행 파일·JRE는 app-image를 만들 때의 버전이며, 조립 시 JAR과 표시 버전·설치 위치 설정만 갱신합니다.

KataGo는 MIT, KG-next Java 앱은 기존 GPL v3 라이선스를 유지합니다. 엔진과 각 런타임의 고지는 해당 저장소 및 배포 라이브러리에서 보존합니다.
