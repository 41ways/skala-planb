@echo off
chcp 949 >nul
rem ============================================================
rem  PlanB Market - 전체 시나리오 검증 (Windows CMD 전용)
rem
rem  scripts/verify.sh 는 bash 스크립트라 cmd 에서 직접 못 돌린다.
rem  Git Bash 를 찾아서 대신 실행해 준다.
rem
rem  주의: 데이터를 실제로 바꾼다. 갓 띄운 앱에 한 번만 돌릴 것.
rem       (인메모리 H2 라 앱을 다시 띄우면 시드가 처음부터 다시 깔린다)
rem
rem  이 파일은 CP949 로 저장되어 있다. UTF-8 로 저장하면 cmd 가
rem  한글 주석의 바이트를 명령어로 잘못 읽어서 실행 자체가 깨진다.
rem ============================================================
setlocal

cd /d "%~dp0"

set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe"        set "BASH=C:\Program Files\Git\bin\bash.exe"
if exist "C:\Program Files (x86)\Git\bin\bash.exe"  set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"

if not defined BASH (
  echo.
  echo   [!] Git Bash 를 찾지 못했습니다.
  echo       verify.sh 는 bash 스크립트라 Git Bash 가 필요합니다.
  echo       https://git-scm.com/download/win 에서 설치한 뒤 다시 실행하세요.
  echo.
  pause
  exit /b 1
)

rem 앱이 떠 있는지 먼저 확인. 안 떠 있으면 전부 실패로 뜨는데
rem 그 화면만 보면 "코드가 깨졌나" 로 오해하기 쉽다
powershell -NoProfile -Command "try{$null=Invoke-WebRequest 'http://localhost:8080/api/admin/integrity-check' -UseBasicParsing -TimeoutSec 3;exit 0}catch{exit 1}"
if errorlevel 1 (
  echo.
  echo   [!] http://localhost:8080 에 앱이 응답하지 않습니다.
  echo       다른 창에서 run.bat 을 먼저 실행하고, 앱이 뜬 뒤에 이걸 돌리세요.
  echo.
  pause
  exit /b 1
)

echo.
echo   검증 시작 - 스케줄러를 기다리는 구간이 있어 2~3분 걸립니다.
echo   카메라 표시가 뜨는 지점이 캡처할 자리입니다.
echo.

rem 파이썬이 한글을 출력할 때 코드페이지와 안 맞아 깨지는 걸 막는다
set PYTHONIOENCODING=utf-8

rem 위에서 이미 프로젝트 폴더로 cd 했으므로 bash 도 여기서 시작한다.
rem -l(로그인 셸)을 안 쓰는 이유: 로그인 셸은 홈으로 이동해 버려서 상대 경로가 깨진다.
rem 윈도우 경로를 bash 인자로 넘기면 역슬래시 때문에 또 꼬이므로 상대 경로로 부른다
"%BASH%" -c "./scripts/verify.sh"

echo.
pause
endlocal
