#!/usr/bin/env python3
"""
보고서 PDF의 프런트 화면 그림만 제자리 교체.

PDF는 헤드리스 Chrome이 HTML을 인쇄해 만든 것이고 그 HTML 원본은 남아 있지 않다.
그래서 문서를 다시 만드는 대신 **박혀 있는 그림만 갈아끼운다** — 본문·쪽수·목차·
레이아웃은 한 글자도 건드리지 않는다.

식별은 xref 번호가 아니라 **픽셀 크기**로 한다. 보고서가 캡처 PNG를 원본 크기 그대로
품고 있고, 교체 대상 7장의 크기가 서로 겹치지 않아서 크기만으로 정확히 짚힌다.
그래서 보고서를 다시 뽑아 xref가 바뀌어도 이 스크립트는 계속 동작한다.

  python scripts/patch_pdf.py --dry     # 무엇을 바꿀지만 보기
  python scripts/patch_pdf.py           # 실제 교체 (원본은 .bak 으로 보존)
"""
import argparse
import os
import shutil
import sys

# 콘솔이 CP949면 한글·대시가 깨진다. 출력만 UTF-8로 돌린다
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

PDF = "docs/PlanB_Market_보고서.pdf"
CAPS = "docs/captures"

# 크기 → 갈아끼울 캡처 파일.
#
# 프런트가 바뀐 화면만 담되, **캡션이 특정 숫자를 인용하는 그림은 뺐다.**
# 그림을 새로 찍으면 숫자가 달라져서 본문이 거짓말이 되기 때문이다. 지금 화면이
# 더 예쁘다는 이유로 캡션과 어긋나게 만드는 건 보고서를 나쁘게 만드는 일이다.
#
#   제외 · 그림 4-2 (1415x500) — "성공 9건 → 1건". 동시성 실행마다 값이 달라짐
#   제외 · 그림 4-3 (1415x430) — "차대 총액 4,783,400 / platformBalance 66,945"
#   제외 · 그림 5-1 (1415x600) — "판매 정산 233,510원 · 구매 확정 311,800원"
#                                (verify.sh 완주 뒤 상태여야 나오는 값)
#
# 이 셋까지 갱신하려면 앱을 새로 띄우고 scripts/verify.sh 를 완주한 상태에서
# 찍어야 한다. 그때는 아래 dict 에 도로 넣고 돌리면 된다.
#
# H2·시드·Swagger·actuator·AOP·예외처리 캡처는 백엔드라 애초에 대상이 아니다.
TARGETS = {
    (1415, 1502): "27_대시보드_전체.png",        # 그림 4-5 마켓 탭 전체
    (1259,  530): "28_대시보드_카운트다운.png",  # 그림 4-6 27초 간격 2장 합성
    (1415,  530): "20_21_마켓리포트.png",        # 그림 4-7 마켓 리포트 탭
    (1415,  470): "34_운영검증_정합성.png",      # 그림 4-8 운영 검증 탭
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true", help="바꾸지 않고 대상만 출력")
    ap.add_argument("--pdf", default=PDF)
    ap.add_argument("--caps", default=CAPS)
    args = ap.parse_args()

    import pymupdf
    from PIL import Image

    if not os.path.exists(args.pdf):
        sys.exit(f"PDF를 찾을 수 없습니다: {args.pdf}")

    doc = pymupdf.open(args.pdf)
    plan, seen = [], set()
    for pno in range(doc.page_count):
        for img in doc.get_page_images(pno):
            xref = img[0]
            if xref in seen:
                continue
            seen.add(xref)
            d = doc.extract_image(xref)
            key = (d["width"], d["height"])
            if key in TARGETS:
                plan.append((pno, xref, key, TARGETS[key]))

    if not plan:
        sys.exit("교체 대상을 찾지 못했습니다. 캡처 크기가 PDF의 그림과 다를 수 있습니다.")

    print(f"{args.pdf} — {doc.page_count}쪽\n교체 대상 {len(plan)}장:\n")
    ok = True
    for pno, xref, (w, h), name in plan:
        src = os.path.join(args.caps, name)
        if not os.path.exists(src):
            print(f"  X  p{pno+1:<3} {name} — 캡처 파일이 없습니다")
            ok = False
            continue
        sw, sh = Image.open(src).size
        mark = "OK " if (sw, sh) == (w, h) else "!! "
        if (sw, sh) != (w, h):
            ok = False
        print(f"  {mark}p{pno+1:<3} {name:<30} {sw}x{sh}  (PDF 안 {w}x{h})")

    if not ok:
        sys.exit("\n크기가 어긋난 항목이 있습니다. 먼저 scripts/capture.py를 돌리세요.")
    if args.dry:
        print("\n--dry 라 실제로는 바꾸지 않았습니다.")
        return 0

    bak = args.pdf + ".bak"
    if not os.path.exists(bak):
        shutil.copy2(args.pdf, bak)
        print(f"\n원본 보존: {bak}")

    for pno, xref, key, name in plan:
        doc[pno].replace_image(xref, filename=os.path.join(args.caps, name))

    tmp = args.pdf + ".tmp"
    # garbage=4 + clean: 교체되고 남은 옛 그림 객체까지 걷어낸다.
    # 안 하면 안 그려지는 그림이 파일 안에 그대로 쌓인다
    doc.save(tmp, garbage=4, clean=True, deflate=True)
    doc.close()
    os.replace(tmp, args.pdf)

    after = pymupdf.open(args.pdf)
    print(f"\n완료 — {after.page_count}쪽 (교체 전과 같아야 정상), "
          f"{os.path.getsize(args.pdf)/1024/1024:.1f}MB")
    after.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
