#!/usr/bin/env bash
# 앱 내장 읍면동 경계(app/src/main/assets/regions.geojson) 생성 스크립트
#
# 원본: vuski/admdongkor (통계청 SGIS 행정동 경계 가공, CC BY 4.0 / 공공누리 제1유형)
# 사용: ./build_regions.sh [시도명 필터]    예) ./build_regions.sh 인천광역시
#       인자를 비우면 전국
set -euo pipefail
cd "$(dirname "$0")"

VER=ver20260701
SRC="HangJeongDong_${VER}.geojson"
OUT=../app/src/main/assets/regions.geojson

if [ ! -f "$SRC" ]; then
  curl -fL -o "$SRC" "https://raw.githubusercontent.com/vuski/admdongkor/master/${VER}/${SRC}"
fi
[ -d node_modules ] || npm install --silent

FILTER=()
if [ -n "${1:-}" ]; then
  FILTER=(-filter "sidonm==\"$1\"")
fi

# interval=10: 10m 이내 굴곡 제거(판정 오차 ~10m). keep-shapes: 작은 섬도 유지
npx mapshaper "$SRC" "${FILTER[@]}" \
  -simplify interval=10 keep-shapes \
  -each 'code=adm_cd2, name=adm_nm, sido=sidonm, sgg=sggnm, delete adm_cd2, delete adm_nm, delete sidonm, delete sggnm, delete adm_cd' \
  -o format=geojson precision=0.00001 "$OUT"

ls -l "$OUT"
