#!/bin/bash
set -e

DB_NAME="attendance"
DB_USER="postgres"
BACKUP_DIR="/opt/attendance/backups"
DATE=$(date +%Y%m%d_%H%M%S)
KEEP_DAYS=30

mkdir -p "$BACKUP_DIR"

# 备份数据库
pg_dump -U "$DB_USER" -d "$DB_NAME" -F c -f "$BACKUP_DIR/db_${DATE}.dump"

# 备份上传文件
tar czf "$BACKUP_DIR/uploads_${DATE}.tar.gz" -C /opt/attendance uploads

# 清理旧备份
find "$BACKUP_DIR" -name "*.dump" -mtime +$KEEP_DAYS -delete
find "$BACKUP_DIR" -name "*.tar.gz" -mtime +$KEEP_DAYS -delete

echo "备份完成: $BACKUP_DIR/db_${DATE}.dump"
