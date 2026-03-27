#!/bin/sh
exec java -Dline.separator=$'\n' $MOUSEGEN_OPTS -jar "$0" "$@"

