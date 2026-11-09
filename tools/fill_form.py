#!/usr/bin/env python3
"""
submission/기획서.md 의 1~8번 섹션을 공식 접수양식 DOCX의 입력란(콘텐츠 컨트롤 8개, 문서 순서)에 넣는다.
01 참가신청서(개인정보)·04 서명란은 건드리지 않는다.
  python3 tools/fill_form.py submission/template/contest-application-consent.docx submission/피코드라이브_접수양식_기획서.docx
"""
import re
import sys
import zipfile
from xml.sax.saxutils import escape

SRC, DST = sys.argv[1], sys.argv[2]
import os

md = open("submission/기획서.md", encoding="utf-8").read()
# 제출 직전에 정해지는 값. 비어 있으면 눈에 띄는 표시를 남겨 빠뜨리지 않게 한다
md = md.replace("{{GITHUB_URL}}", os.environ.get("GITHUB_URL") or "【제출 전 기재: GitHub 저장소 링크】")
md = md.replace("{{SHORTFORM_URL}}", os.environ.get("SHORTFORM_URL") or "해당 없음")
sections = {}
for m in re.finditer(r"^## (\d)\. [^\n]*\n(.*?)(?=^## \d\. |\Z)", md, re.S | re.M):
    sections[int(m.group(1))] = m.group(2).strip()
assert sorted(sections) == list(range(1, 9)), sections.keys()


def runs(text):
    out = []
    for i, line in enumerate(text.split("\n")):
        if i:
            out.append("<w:r><w:br/></w:r>")
        out.append(f'<w:r><w:t xml:space="preserve">{escape(line)}</w:t></w:r>')
    return "".join(out)


zin = zipfile.ZipFile(SRC)
doc = zin.read("word/document.xml").decode("utf-8")
idx = 0


def fill(m):
    global idx
    idx += 1
    pr, content = m.group(1), sections[idx]
    pr = pr.replace("<w:text/>", '<w:text w:multiLine="1"/>')
    # 안내용 플레이스홀더 표시 해제
    pr = pr.replace("<w:showingPlcHdr/>", "")
    return f"<w:sdt>{pr}<w:sdtContent>{runs(content)}</w:sdtContent></w:sdt>"


doc = re.sub(r"<w:sdt>(<w:sdtPr>.*?</w:sdtPr>)<w:sdtContent>.*?</w:sdtContent></w:sdt>", fill, doc, flags=re.S)
assert idx == 8, f"filled {idx} controls"

with zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = doc.encode("utf-8") if item.filename == "word/document.xml" else zin.read(item.filename)
        zout.writestr(item, data)
print(f"filled 8 sections → {DST}")
