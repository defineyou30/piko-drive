#!/bin/bash
# 공개 GitHub용 저장소 만들기: 현재 커밋된 파일만, 기록 없이 익명 작성자 커밋 1개로.
#   tools/make_public_repo.sh ../piko-drive-public
# 결과 폴더에서 직접: git remote add origin <URL> && git push -u origin main
# (작업 저장소의 커밋 작성자는 실명·이메일이라 그대로 올리지 않는다)
set -eu
DEST=${1:?"출력 폴더 경로"}
[ -e "$DEST" ] && { echo "이미 있음: $DEST"; exit 1; }
mkdir -p "$DEST"
git archive HEAD | tar -x -C "$DEST"
# 제출 APK는 apk/ 폴더에 (tools/package_submission.sh 결과가 있으면 그것을)
if ls apk/*_release.apk >/dev/null 2>&1; then mkdir -p "$DEST/apk" && cp apk/*_release.apk "$DEST/apk/"; fi
cd "$DEST"
# 실명·로컬 경로·이메일이 남았는지 마지막 확인 (검사어는 이 파일 자체에 걸리지 않게 조각으로)
PAT="koh""yun|define""you30|@gm""ail\.com|/Us""ers/"
if grep -rIl -i -E "$PAT" . --exclude-dir=.git >/dev/null; then
  echo "⚠️  개인정보 흔적:"; grep -rIn -i -E "$PAT" . --exclude-dir=.git | head; exit 1
fi
git init -q -b main
git add -A
[ -d apk ] && git add -f apk  # 작업 저장소에선 apk/를 무시하지만 공개 저장소엔 넣는다
git -c user.name="Piko Drive Team" -c user.email="piko-drive@users.noreply.github.com" commit -q -m "피코 드라이브 — 현대자동차그룹 차량용 앱 개발 공모전 출품작"
git log --format='%an <%ae> %s' -1
du -sh .
