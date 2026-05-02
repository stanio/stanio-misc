#!/bin/sh
exec java -Dline.separator='
' $MOUSEGEN_OPTS -jar "$0" "$@"

