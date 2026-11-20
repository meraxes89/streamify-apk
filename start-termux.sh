#!/usr/bin/env bash
# ==============================================================================
# Streamify Termux Edition — Professional Runner
# ==============================================================================
# Starts and manages the local Streamify relay & web player server on Termux.
#
# Usage:
#   ./start-termux.sh          # Start in foreground (Ctrl+C to stop)
#   ./start-termux.sh start    # Start as background daemon
#   ./start-termux.sh stop     # Stop running server
#   ./start-termux.sh restart  # Restart server
#   ./start-termux.sh status   # Show status & health
# ==============================================================================

set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-3000}"
PID_FILE="${DIR}/.streamify.pid"
LOG_FILE="${DIR}/streamify-termux.log"

# Colors
C_RESET='\033[0m'
C_BOLD='\033[1m'
C_RED='\033[31m'
C_GREEN='\033[32m'
C_YELLOW='\033[33m'
C_BLUE='\033[34m'
C_CYAN='\033[36m'

find_running_pid() {
    local pid=""
    if command -v lsof >/dev/null 2>&1; then
        pid="$(lsof -ti :"${PORT}" 2>/dev/null || true)"
        pid="$(echo "${pid}" | head -n 1 | tr -d ' ')"
    fi
    if [ -z "${pid}" ] && command -v fuser >/dev/null 2>&1; then
        pid="$(fuser "${PORT}/tcp" 2>/dev/null || true)"
        pid="$(echo "${pid}" | awk '{print $1}' | tr -d ' ')"
    fi
    if [ -z "${pid}" ] && [ -f "${PID_FILE}" ]; then
        local file_pid
        file_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [ -n "${file_pid}" ] && kill -0 "${file_pid}" 2>/dev/null; then
            pid="${file_pid}"
        fi
    fi
    echo "${pid}"
}

get_lan_ip() {
    local ip
    ip="$(ifconfig 2>/dev/null | awk '/wlan/ {flag=1} flag && /inet / {print $2; exit}' || true)"
    if [ -z "${ip}" ]; then
        ip="$(ifconfig 2>/dev/null | awk '/inet / && !/127.0.0.1/ {print $2; exit}' || true)"
    fi
    echo "${ip}"
}

check_prerequisites() {
    if ! command -v node >/dev/null 2>&1; then
        echo -e "${C_RED}[!] Node.js is not installed.${C_RESET}"
        echo "    Install it with: pkg install nodejs"
        exit 1
    fi

    if [ ! -d "${DIR}/server/node_modules/express" ]; then
        echo -e "${C_YELLOW}[*] Installing relay dependencies (npm install)...${C_RESET}"
        (cd "${DIR}/server" && npm install --silent)
        echo -e "${C_GREEN}[✓] Dependencies installed.${C_RESET}"
    fi

    if [ ! -f "${DIR}/server/public/index.html" ]; then
        echo -e "${C_YELLOW}[!] Warning: server/public/index.html not found.${C_RESET}"
        echo "    The API relay will run, but the Web UI may not be served."
    fi

    # Streamify Skin (mobile YTM shell) is injected into the GENERATED
    # server/public/settings.js, which is gitignored. On a fresh checkout that
    # file has no loader, so wire it here. Non-fatal: the relay itself must
    # still start if the skin wiring fails. Live edits to front-end/skin/*.css
    # need no restart — server/index.js serves that source dir at /skin.
    if [ -f "${DIR}/server/public/settings.js" ] && [ -f "${DIR}/scripts/inject-skin.mjs" ]; then
        if ! grep -q 'STREAMIFY_SKIN_LOADER' "${DIR}/server/public/settings.js"; then
            echo -e "${C_YELLOW}[*] Wiring Streamify Skin into local bundle...${C_RESET}"
            if (cd "${DIR}" && node scripts/inject-skin.mjs server/public); then
                echo -e "${C_GREEN}[✓] Skin wired into settings.js.${C_RESET}"
            else
                echo -e "${C_YELLOW}[!] Skin wiring failed; continuing without it.${C_RESET}"
            fi
        fi
    fi
}

print_banner() {
    local lan_ip
    lan_ip="$(get_lan_ip)"

    echo -e "${C_CYAN}${C_BOLD}====================================================${C_RESET}"
    echo -e "${C_CYAN}${C_BOLD}           🎵 Streamify Termux Edition              ${C_RESET}"
    echo -e "${C_CYAN}${C_BOLD}====================================================${C_RESET}"
    echo -e "  ${C_BOLD}Port:${C_RESET}         ${PORT}"
    echo -e "  ${C_BOLD}Local Player:${C_RESET} ${C_GREEN}http://localhost:${PORT}/${C_RESET}"
    if [ -n "${lan_ip}" ]; then
        echo -e "  ${C_BOLD}WiFi / LAN:${C_RESET}   ${C_GREEN}http://${lan_ip}:${PORT}/${C_RESET}"
    fi
    echo -e "  ${C_BOLD}API Health:${C_RESET}   ${C_BLUE}http://localhost:${PORT}/api/health${C_RESET}"
    echo -e "${C_CYAN}----------------------------------------------------${C_RESET}"
}

stop_server() {
    local pid
    pid="$(find_running_pid)"
    if [ -n "${pid}" ]; then
        echo -e "${C_YELLOW}[*] Stopping Streamify server (PID ${pid})...${C_RESET}"
        kill "${pid}" 2>/dev/null || true
        for _ in {1..10}; do
            if kill -0 "${pid}" 2>/dev/null; then
                sleep 0.5
            else
                break
            fi
        done
        if kill -0 "${pid}" 2>/dev/null; then
            kill -9 "${pid}" 2>/dev/null || true
        fi
        rm -f "${PID_FILE}"
        echo -e "${C_GREEN}[✓] Server stopped.${C_RESET}"
    else
        rm -f "${PID_FILE}"
        echo -e "${C_YELLOW}[i] No server running on port ${PORT}.${C_RESET}"
    fi
}

show_status() {
    local pid
    pid="$(find_running_pid)"
    if [ -n "${pid}" ]; then
        echo -e "${C_GREEN}[✓] Streamify server is RUNNING (PID: ${pid}, Port: ${PORT})${C_RESET}"
        echo ""
        if command -v curl >/dev/null 2>&1; then
            echo -e "${C_BOLD}Health Check:${C_RESET}"
            curl -s "http://localhost:${PORT}/api/health" || echo -e "${C_RED}Failed to reach health endpoint${C_RESET}"
            echo ""
        fi
    else
        echo -e "${C_RED}[✗] Streamify server is NOT running on port ${PORT}.${C_RESET}"
    fi
}

start_daemon() {
    check_prerequisites
    local existing_pid
    existing_pid="$(find_running_pid)"
    if [ -n "${existing_pid}" ]; then
        echo -e "${C_YELLOW}[i] Server already running (PID ${existing_pid}) on port ${PORT}.${C_RESET}"
        return 0
    fi

    print_banner
    echo -e "${C_GREEN}[*] Starting Streamify in background...${C_RESET}"
    local server_script="${DIR}/server/bin/streamify-server.mjs"
    if [ "${USE_LEGACY:-0}" = "1" ]; then
        server_script="${DIR}/server/index.js"
        echo -e "${C_YELLOW}[i] Running legacy Express server architecture.${C_RESET}"
    fi

    export PORT="${PORT}"
    nohup node "${server_script}" > "${LOG_FILE}" 2>&1 &
    local new_pid=$!
    echo "${new_pid}" > "${PID_FILE}"
    sleep 1

    if kill -0 "${new_pid}" 2>/dev/null; then
        echo -e "${C_GREEN}[✓] Streamify started successfully! (PID ${new_pid})${C_RESET}"
        echo -e "    Logs: ${LOG_FILE}"
        echo -e "    Stop with: ./start-termux.sh stop"
    else
        echo -e "${C_RED}[✗] Failed to start server. Check logs:${C_RESET}"
        tail -n 20 "${LOG_FILE}"
        exit 1
    fi
}

start_foreground() {
    check_prerequisites
    local existing_pid
    existing_pid="$(find_running_pid)"
    if [ -n "${existing_pid}" ]; then
        echo -e "${C_YELLOW}[*] Found existing server on port ${PORT} (PID ${existing_pid}). Stopping it first...${C_RESET}"
        kill "${existing_pid}" 2>/dev/null || true
        sleep 1
    fi

    print_banner
    echo -e "Press ${C_BOLD}Ctrl+C${C_RESET} to stop the server."
    echo -e "${C_CYAN}====================================================${C_RESET}"

    cleanup() {
        echo ""
        echo -e "${C_YELLOW}[*] Shutting down Streamify...${C_RESET}"
        rm -f "${PID_FILE}"
        exit 0
    }
    trap cleanup SIGINT SIGTERM

    local server_script="${DIR}/server/bin/streamify-server.mjs"
    if [ "${USE_LEGACY:-0}" = "1" ]; then
        server_script="${DIR}/server/index.js"
        echo -e "${C_YELLOW}[i] Running legacy Express server architecture.${C_RESET}"
    fi

    export PORT="${PORT}"
    echo $$ > "${PID_FILE}"
    exec node "${server_script}"
}

# Command dispatch
ACTION="${1:-run}"
case "${ACTION}" in
    start|daemon|--daemon|-d)
        start_daemon
        ;;
    stop)
        stop_server
        ;;
    restart)
        stop_server
        start_daemon
        ;;
    status)
        show_status
        ;;
    run|foreground|"")
        start_foreground
        ;;
    help|--help|-h)
        echo "Streamify Termux Runner"
        echo "Usage: $0 [run|start|stop|restart|status]"
        ;;
    *)
        echo -e "${C_RED}Unknown command: ${ACTION}${C_RESET}"
        echo "Usage: $0 [run|start|stop|restart|status]"
        exit 1
        ;;
esac
