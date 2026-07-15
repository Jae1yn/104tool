#!/usr/bin/env bash
# 打包 Windows 10/11 x64 绿色便携版：dist/104tool-win-x64.zip
# 解压后双击 104tool.bat 即用，无需目标机器安装 Java。
set -euo pipefail
cd "$(dirname "$0")"

DIST_NAME="104tool-win-x64"
DIST_DIR="dist/${DIST_NAME}"
JRE_CACHE="${HOME}/.cache/tool104-build"
# JavaFX 24 的类文件版本为 66，要求 JRE 22+，这里用当前 LTS 25
JRE_ZIP="${JRE_CACHE}/temurin-jre25-win-x64.zip"
JRE_URL="https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jre/hotspot/normal/eclipse"

echo "==> 构建 fat jar（JavaFX Windows natives）"
./mvnw -q -DskipTests -Djavafx.platform=win clean package

JAR="$(ls target/tool104-*.jar | grep -v original | head -1)"
echo "==> fat jar: ${JAR}"

if [[ ! -f "${JRE_ZIP}" ]]; then
  echo "==> 下载 Temurin JRE 25 (Windows x64)"
  mkdir -p "${JRE_CACHE}"
  curl -fL --retry 3 -o "${JRE_ZIP}.tmp" "${JRE_URL}"
  mv "${JRE_ZIP}.tmp" "${JRE_ZIP}"
else
  echo "==> 使用缓存 JRE: ${JRE_ZIP}"
fi

echo "==> 组装 ${DIST_DIR}"
rm -rf dist
mkdir -p "${DIST_DIR}"

unzip -q "${JRE_ZIP}" -d "${DIST_DIR}/jre-tmp"
JRE_TOP="$(ls -d "${DIST_DIR}"/jre-tmp/*/)"
mv "${JRE_TOP}" "${DIST_DIR}/runtime"
rmdir "${DIST_DIR}/jre-tmp"
[[ -f "${DIST_DIR}/runtime/bin/javaw.exe" ]] || { echo "错误：runtime/bin/javaw.exe 不存在"; exit 1; }

cp "${JAR}" "${DIST_DIR}/tool104.jar"

cat > "${DIST_DIR}/104tool.bat" <<'EOF'
@echo off
start "" "%~dp0runtime\bin\javaw.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0tool104.jar"
EOF

cat > "${DIST_DIR}/104tool-debug.bat" <<'EOF'
@echo off
"%~dp0runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0tool104.jar"
pause
EOF

cat > "${DIST_DIR}/README.txt" <<'EOF'
104 Tool (IEC 60870-5-104 master simulator)
===========================================

Start:   double-click 104tool.bat
Debug:   double-click 104tool-debug.bat (keeps console output)

- No Java installation required; a private runtime is bundled in .\runtime
- Settings and point table are stored in %USERPROFILE%\.tool104\
- The tool listens as a TCP server; allow it in the Windows Firewall
  prompt on first launch.
EOF

# Windows 文本文件统一 CRLF
for f in "${DIST_DIR}"/*.bat "${DIST_DIR}/README.txt"; do
  perl -pi -e 's/\n/\r\n/' "$f"
done

echo "==> 打 zip"
(cd dist && rm -f "${DIST_NAME}.zip" && zip -qr "${DIST_NAME}.zip" "${DIST_NAME}")

echo "==> 完成: dist/${DIST_NAME}.zip ($(du -h "dist/${DIST_NAME}.zip" | cut -f1))"
