#!/bin/bash
set -e

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_FILE="$APP_DIR/target/attendance-audit-0.0.1-SNAPSHOT.jar"
LOG_DIR="$APP_DIR/logs"

mkdir -p "$LOG_DIR"

cd "$APP_DIR"

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR 文件不存在，开始打包..."
    mvn clean package -DskipTests -q
fi

nohup java -jar "$JAR_FILE" \
    --spring.profiles.active=prod \
    > "$LOG_DIR/attendance.out" 2>&1 &

echo "服务已启动，PID: $!"
echo "日志: $LOG_DIR/attendance.out"
