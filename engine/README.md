# KG-next 엔진 연동

탐색 트리·GTP 확장·엔진 빌드·네이티브 회귀 검사는 [22nsuk/KataGo](https://github.com/22nsuk/KataGo)에서 관리합니다. 이 GUI 저장소는 엔진 소스 패치를 중복 보관하지 않습니다.

검증한 엔진 소스: [`95041b74751270dd090b9fae1fea5da3fdbf8f48`](https://github.com/22nsuk/KataGo/commit/95041b74751270dd090b9fae1fea5da3fdbf8f48), [엔진 PR #1](https://github.com/22nsuk/KataGo/pull/1). 이 커밋에는 네이티브 테스트와 실제 프로세스 회귀 검사가 포함됩니다.

## 탐색 재사용

KataGo 저장소의 `docs/KG_ROOT_REUSE.md`와 `python/probe_kg_root_reuse.py`가 동작 및 검증의 기준입니다. 해당 기능을 포함한 엔진을 빌드하고 KG-next의 사용자 지정 엔진으로 선택하세요. Windows CUDA 13.2 빌드는 엔진 저장소의 `docs/KG_CUDA13_WINDOWS.md`를 따릅니다.

GUI는 실제 엔진이 GTP `list_commands`에 `kg-reuse-root-tree`를 광고할 때만 `kata-analyze ... reuseRootTree true`를 보냅니다. 동일 국면의 첫 수 허용·제외를 바꿀 때 하위 노드를 보존하고, 제외 후보는 선택·출력·집계에서 제외합니다. 다시 허용한 후보는 이전 탐색을 사용할 수 있습니다. 이후 수순까지 적용하는 제한의 변경은 하위 평가 조건이 달라지므로 초기화합니다.

국면·규칙·덤·모델 변경 및 명시적 캐시 초기화는 엔진의 무효화 규칙을 따릅니다. 누적 루트 방문 수와 현재 허용된 후보 방문 수의 합은 다를 수 있습니다. GUI가 과거 수치를 새 분석에 더하는 방식이 아닙니다.

## NVIDIA 런타임

- 기존 `windows-nvidia`: CUDA 12.8 / cuDNN 9.8, 기존 TensorRT 보조 엔진과 호환 유지.
- 추가 `windows-nvidia-cuda13`: CUDA 13.2.2 / cuDNN 9.24.0.43 CUDA13, 런타임 프로필 `cuda13.2-cudnn9.24`.

CUDA13 선택 패키지의 현재 카탈로그는 해시를 검증한 공식 KataGo v1.18.2 실행 파일을 사용합니다. 공식 파일은 탐색 재사용 확장을 포함하지 않습니다. 두 기능을 함께 쓰려면 위 KataGo 포크를 CUDA13으로 빌드해 사용자 지정 엔진으로 사용하세요. 포크 엔진을 공식 바이너리의 출처·해시로 표시하지 않습니다.

CUDA13 런타임의 `cudart`, cuBLAS, NVRTC, nvJitLink는 CUDA13 세트를 사용하고 cuDNN은 CUDA13용 9.24.0.43의 전체 DLL 세트를 함께 준비합니다. CUDA12 DLL을 이름만 바꾸거나 CUDA12의 manifest를 재사용하지 않습니다. 공식 Windows 엔진에는 동적 zlib `z.dll`도 필요합니다.

다운로드 자산·실행 파일 해시 검증과 실제 GPU 추론 검증은 서로 다른 검사입니다. GUI는 프로필별 필수 파일·manifest를 검사한 뒤 드라이버 및 추론 호환성을 검사합니다. 기존 CUDA12의 성공 표시는 CUDA13 검사 결과로 재사용하지 않습니다.

## 선택 CUDA13 패키지 준비

```sh
PREPARE_WINDOWS_CUDA13=true ./scripts/prepare_bundled_katago.sh
WINDOWS_BUILD_CUDA13=true ./scripts/package_windows_exe.sh YYYY-MM-DD 1.0.0 target/lizzie-yzy2.5.3-shaded.jar next-YYYY-MM-DD.1
```

일반 Windows 패키징 도구와 JDK가 필요합니다. 준비와 패키징 모두 옵션을 켜야 합니다. 생성되는 `windows64.nvidia.cuda13.portable.zip`은 별도 제품 폴더 `KG-next NVIDIA CUDA13`을 사용합니다. 기존 사용자의 엔진 폴더를 자동 교체하는 절차가 아닙니다.

KataGo는 MIT, KG-next Java 앱은 기존 GPL v3 라이선스를 유지합니다. 엔진과 각 런타임의 고지는 해당 저장소 및 배포 라이브러리에서 보존합니다.
