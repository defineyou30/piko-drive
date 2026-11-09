#!/bin/bash
# 제출 패키징: 팀명·링크가 정해진 뒤 한 번 실행
#   TEAM=팀명 GITHUB_URL=https://... [SHORTFORM_URL=https://...] tools/package_submission.sh
# 결과: apk/<팀명>_피코드라이브_release.apk, submission/피코드라이브_접수양식_기획서.docx(링크 반영)
set -eu
: "${TEAM:?TEAM(팀명 또는 개인 성명)을 지정하세요}"
mkdir -p apk
rm -f apk/*_release.apk
cp dist/PikoDrive_release.apk "apk/${TEAM}_피코드라이브_release.apk"
shasum -a 256 "apk/${TEAM}_피코드라이브_release.apk"
python3 tools/fill_form.py submission/template/contest-application-consent.docx "submission/피코드라이브_접수양식_기획서.docx"
grep -q "제출 전 기재" <(textutil -convert txt -stdout "submission/피코드라이브_접수양식_기획서.docx" 2>/dev/null || true) \
  && echo "⚠️  기획서에 아직 채우지 않은 링크가 있습니다" || echo "기획서 링크 반영 완료"
