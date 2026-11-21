#!/data/data/com.termux/files/usr/bin/bash
# Streamify Android autostart via Termux:Boot (~/.termux/boot/start-streamify.sh)
termux-wake-lock
cd "$HOME/streamify-apk/server" || exit 1
exec node src/boot/streamify.mjs
