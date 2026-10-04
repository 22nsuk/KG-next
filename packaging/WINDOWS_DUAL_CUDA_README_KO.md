# CUDA 12·13 함께 사용하기

`KG-next.exe`를 실행하고 엔진 메뉴에서 버전을 선택하세요. 기본 엔진은 `KataGo Bundled` (CUDA 12.8 / cuDNN 9.8)이며, 추가 엔진은 `KataGo CUDA13 (13.2 / cuDNN 9.24)`입니다. 표시 이름은 엔진 설정에서 바꿀 수 있습니다.

GUI와 Java 런타임, `app/weights/default.bin.gz` 모델, `app/engines/katago/configs` 설정을 함께 사용합니다. CUDA12 엔진과 DLL은 `app/engines/katago/windows-x64`, CUDA13 엔진과 DLL은 `app/engines/katago/windows-x64-nvidia-cuda13`에 있습니다.

사용자 설정·기보·계정 정보는 실행 시 만들어지는 이 폴더의 `user-data`에 저장됩니다. 폴더를 옮길 때 `user-data`도 함께 옮기면 설정을 유지합니다. 다른 설치에서 사용자 데이터를 자동 가져오지 않습니다.

PowerShell 7에서 다음 명령으로 두 엔진의 짧은 실행 검사를 할 수 있습니다. GUI에서 분석 중이라면 먼저 엔진을 종료해 GPU 메모리를 비워 주세요.

```powershell
pwsh -NoProfile -File .\Test-CUDA.ps1
pwsh -NoProfile -File .\Test-CUDA.ps1 -Profile CUDA12
pwsh -NoProfile -File .\Test-CUDA.ps1 -Profile CUDA13
```

기본값은 두 버전의 순차 검사입니다. 같은 모델·설정으로 각 엔진에서 1개 국면, 32방문, 탐색 스레드 4개만 검사하고 스레드 자동 튜닝과 추가 배치 검사를 생략합니다. 모델 초기화 시간이 추가로 필요하며, 각 프로세스는 최대 90초 뒤 종료됩니다. 이 검사는 실행과 GPU 추론을 확인하며 두 버전의 성능 우열을 판정하기 위한 측정이 아닙니다.

결과와 출력은 `user-data/test-results/<시각>`에 저장합니다. `summary.json`에서 각 버전의 성공·실패를 확인할 수 있으며, 하나라도 실패하면 명령은 종료 코드 1을 반환합니다. 테스트 캐시는 `user-data/runtime/katago-test`에 버전별로 저장합니다. Python이나 CUDA Toolkit 설치는 필요하지 않으며 NVIDIA 드라이버는 사용하려는 엔진의 CUDA 버전을 지원해야 합니다.
