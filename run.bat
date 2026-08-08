@echo off
chcp 949 >nul
rem ============================================================
rem  PlanB Market - 앱 실행 (Windows CMD 전용)
rem
rem  cmd 에서는 ./gradlew 가 안 먹는다. '.' 을 명령어로 읽어서
rem  "'.'은(는) 내부 또는 외부 명령... 이 아닙니다" 가 뜬다.
rem  이 파일이 그 차이를 흡수한다 - 더블클릭하거나 run 만 쳐도 된다.
rem
rem  파일명이 start.bat 이 아닌 이유: start 는 cmd 내장 명령이라
rem  같은 이름을 쓰면 내장 쪽이 먼저 잡혀서 이 파일이 안 불린다.
rem
rem  이 파일은 CP949 로 저장되어 있다. UTF-8 로 저장하면 cmd 가
rem  한글 주석의 바이트를 명령어로 잘못 읽어서 실행 자체가 깨진다.
rem ============================================================
setlocal

rem %~dp0 = 이 파일이 있는 폴더. 어느 경로에서 실행해도 프로젝트로 이동한다
cd /d "%~dp0"

echo.
echo   PlanB Market - 소멸 모니터링
echo   ----------------------------------------------------------
echo   대시보드   http://localhost:8080/
echo   Swagger    http://localhost:8080/swagger-ui.html
echo   H2 콘솔    http://localhost:8080/h2-console
echo              (JDBC URL 을 jdbc:h2:mem:planb 로 바꿀 것, 사용자 sa, 비번 없음)
echo   Health     http://localhost:8080/actuator/health
echo.
echo   계정 user01 ~ user05 / 비밀번호 pass1234
echo   종료하려면 이 창에서 Ctrl+C
echo   ----------------------------------------------------------
echo.

rem 앱이 응답하기 시작하면 브라우저를 연다.
rem 고정 시간 대기 대신 실제로 떴는지 확인하고 여는 것 - 빌드가 길어져도 안 어긋난다
start "" /min powershell -NoProfile -Command "for($i=0;$i -lt 120;$i++){try{$null=Invoke-WebRequest 'http://localhost:8080/api/admin/integrity-check' -UseBasicParsing -TimeoutSec 2;Start-Process 'http://localhost:8080/';break}catch{Start-Sleep 2}}"

rem 전체 경로로 부른다. 이름만 쓰면 PATH 해석을 타는데, cmd 를 다른 셸에서
rem 띄운 경우(Git Bash 등) PATH 가 변환돼 넘어와서 cwd 에 있는 파일조차 못 찾는다.
rem 전체 경로면 PATH 상태와 무관하게 항상 걸린다
call "%~dp0gradlew.bat" bootRun

endlocal
