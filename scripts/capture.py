#!/usr/bin/env python3
"""
보고서용 화면 캡처.

헤드리스 Chrome으로 프런트 화면을 **원본 캡처와 똑같은 픽셀 크기**로 다시 찍는다.
크기가 같아야 PDF의 그림을 자리·비율 손상 없이 제자리 교체할 수 있다.

  python scripts/capture.py            # docs/captures 에 덮어쓰기
  python scripts/capture.py --out tmp  # 다른 곳에 찍어서 먼저 확인

앱이 http://localhost:8080 에 떠 있어야 한다.
"""
import argparse
import os
import shutil
import subprocess
import sys

# 콘솔이 CP949면 한글·대시가 깨진다. 출력만 UTF-8로 돌린다
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass
import tempfile
import time
import urllib.request

BASE = "http://localhost:8080"
DEMO_USER, DEMO_PW = "user04", "pass1234"

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]

# (파일명, 폭, 높이, 쿼리스트링, 위에서 잘라낼 픽셀)
#   크기는 PDF에 박혀 있는 원본 그림의 픽셀 크기와 정확히 같아야 한다.
#
#   스크롤 대신 "크게 찍고 잘라내기"를 쓴다. 헤드리스에서 window.scrollTo를 쓰면
#   sticky 헤더가 문서 좌표에 그대로 남아 위쪽이 빈 띠로 찍힌다.
SHOTS = [
    ("27_대시보드_전체.png",        1415, 1502, "",                                 0),
    ("28_대시보드_카운트다운.png",  1259,  530, "",                               330),
    ("22_23_동시성_대시보드.png",   1415,  500, "",                               114),
    ("20_21_마켓리포트.png",        1415,  530, "?v=report",                      240),
    ("34_운영검증_정합성.png",      1415,  470, "?v=ops&run=integrity",           114),
    ("29_최종정합성검증.png",       1415,  430, "?v=ops&run=integrity",           200),
    ("35_마이페이지_거래요약.png",  1415,  600, f"?v=my&sub=purchase&u={DEMO_USER}&p={DEMO_PW}", 114),
]


def find_chrome():
    for p in CHROME_CANDIDATES:
        if os.path.isfile(p):
            return p
    for n in ("google-chrome", "chromium", "chrome", "msedge"):
        p = shutil.which(n)
        if p:
            return p
    sys.exit("Chrome/Edge를 찾지 못했습니다. CHROME_CANDIDATES에 경로를 추가하세요.")


def wait_for_app(timeout=90):
    for _ in range(timeout):
        try:
            urllib.request.urlopen(BASE + "/api/admin/dashboard-summary", timeout=2).read()
            return
        except Exception:
            time.sleep(1)
    sys.exit(f"{BASE} 에 앱이 떠 있지 않습니다. 먼저 실행하세요: gradlew bootRun")


def shoot(chrome, url, w, h, dest):
    # 프로필을 매번 새로 판다. 세션 쿠키가 남으면 로그인 상태가 캡처마다 달라진다
    profile = tempfile.mkdtemp(prefix="planb-cap-")
    cmd = [
        chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars",
        "--force-device-scale-factor=1", "--no-first-run", "--no-default-browser-check",
        f"--user-data-dir={profile}",
        f"--window-size={w},{h}",
        "--virtual-time-budget=7000",     # JS가 API를 불러 화면을 채울 시간
        f"--screenshot={dest}",
        url,
    ]
    try:
        subprocess.run(cmd, capture_output=True, timeout=90)
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs/captures")
    ap.add_argument("--only", help="파일명 일부로 걸러서 일부만 다시 찍기")
    args = ap.parse_args()

    chrome = find_chrome()
    wait_for_app()
    os.makedirs(args.out, exist_ok=True)
    print(f"chrome: {chrome}\nout   : {args.out}\n")

    from PIL import Image
    ok = True
    for name, w, h, qs, top in SHOTS:
        if args.only and args.only not in name:
            continue
        dest = os.path.abspath(os.path.join(args.out, name))
        shoot(chrome, BASE + "/" + qs, w, h + top, dest)
        if not os.path.exists(dest):
            print(f"  FAIL {name} — 파일이 생기지 않았습니다")
            ok = False
            continue
        im = Image.open(dest).convert("RGB")
        im.crop((0, top, w, top + h)).save(dest)
        im = Image.open(dest)
        flag = "OK " if im.size == (w, h) else "!! "
        print(f"  {flag}{name:<32} {im.size[0]}x{im.size[1]}  (목표 {w}x{h}, 위 {top}px 잘라냄)")
        if im.size != (w, h):
            ok = False

    print("\n완료" if ok else "\n일부 실패 — 위 !! 표시를 확인하세요")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
