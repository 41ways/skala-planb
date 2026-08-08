#!/usr/bin/env python3
"""
「실시간 카운트다운」 그림 전용 캡처.

보고서 그림 4-6은 같은 화면을 27초 간격으로 두 번 찍어 위아래로 붙인 것이다.
숫자가 실제로 줄었다는 걸 정지 화면 하나로는 보일 수 없기 때문이다.
캡션이 "27초 뒤"라고 못 박고 있어서 간격도 그대로 지킨다.

  python scripts/capture_countdown.py
"""
import os
import subprocess
import shutil
import sys
import tempfile
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

W, H = 1259, 530          # PDF 안 그림 크기. 어긋나면 제자리 교체가 안 된다
BAND_TOP, BAND_H = 340, 232   # 전체 화면에서 히어로 캐러셀만 잘라낼 영역
GAP_SECONDS = 26
DEST = os.path.join("docs", "captures", "28_대시보드_카운트다운.png")

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
FONTS = [r"C:\Windows\Fonts\malgun.ttf", r"C:\Windows\Fonts\malgunbd.ttf",
         "/System/Library/Fonts/AppleSDGothicNeo.ttc",
         "/usr/share/fonts/truetype/nanum/NanumGothic.ttf"]


def shoot(dest):
    profile = tempfile.mkdtemp(prefix="planb-cd-")
    try:
        subprocess.run([
            CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1", "--no-first-run",
            f"--user-data-dir={profile}", f"--window-size={W},{BAND_TOP + BAND_H}",
            "--virtual-time-budget=7000", f"--screenshot={dest}",
            "http://localhost:8080/",
        ], capture_output=True, timeout=90)
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def main():
    from PIL import Image, ImageDraw, ImageFont
    if not os.path.isfile(CHROME):
        sys.exit("Chrome을 찾지 못했습니다.")

    tmp = tempfile.mkdtemp(prefix="planb-cd-out-")
    a, b = os.path.join(tmp, "a.png"), os.path.join(tmp, "b.png")

    print(f"1/2 촬영…")
    shoot(a)
    print(f"     {GAP_SECONDS}초 대기 (카운트다운이 실제로 줄어야 함)")
    time.sleep(GAP_SECONDS)
    print(f"2/2 촬영…")
    shoot(b)

    band = lambda p: Image.open(p).convert("RGB").crop((0, BAND_TOP, W, BAND_TOP + BAND_H))
    im1, im2 = band(a), band(b)

    font_path = next((f for f in FONTS if os.path.isfile(f)), None)
    f1 = ImageFont.truetype(font_path, 14) if font_path else ImageFont.load_default()
    f2 = ImageFont.truetype(font_path, 13) if font_path else ImageFont.load_default()

    out = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(out)
    d.text((6, 3), "같은 화면 — 기준 시각 T", fill=(20, 24, 36), font=f1)
    out.paste(im1, (0, 24))
    d.text((6, 264), f"27초 뒤 — 카운트다운이 실제로 줄었고, "
                     "2시간 미만인 TOP 1은 점멸 중(흐려진 순간이 잡힘)",
           fill=(200, 40, 60), font=f2)
    out.paste(im2, (0, 292))
    out.save(DEST)

    shutil.rmtree(tmp, ignore_errors=True)
    got = Image.open(DEST).size
    print(f"\n저장: {DEST}  {got[0]}x{got[1]}  (목표 {W}x{H})")
    return 0 if got == (W, H) else 1


if __name__ == "__main__":
    sys.exit(main())
