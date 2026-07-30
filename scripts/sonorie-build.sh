#!/data/data/com.termux/files/usr/bin/bash
#
# scripts/sonorie-build.sh
# Fluxo completo: commit -> push -> acompanha build -> baixa APK ou log de erro.
# Uso:
#   bash scripts/sonorie-build.sh "mensagem do commit"
#   bash scripts/sonorie-build.sh           # usa mensagem padrão
#
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

REPO_DIR="$HOME/Sonorie"
OUT_DIR="$HOME/storage/downloads/Sonorie"
WORKFLOW_FILE=".github/workflows/flutter-build.yml"
# Cópia íntegra do workflow (evita YAML quebrado por quebra de linha no Termux)
WORKFLOW_CORRECT="scripts/flutter-build.yml.correct"

COMMIT_MSG="${1:-}"
if [[ -z "$COMMIT_MSG" ]]; then
    COMMIT_MSG="sonorie-build: $(date '+%Y-%m-%d %H:%M')"
fi

log()   { echo -e "${GREEN}[sonorie-build]${NC} $*"; }
warn()  { echo -e "${YELLOW}[aviso]${NC} $*"; }
err()   { echo -e "${RED}[erro]${NC} $*" >&2; }

cd "$REPO_DIR"

# ---------- pré-checks ----------
if ! command -v gh >/dev/null 2>&1; then
    err "gh (GitHub CLI) não está instalado. Rode: pkg install -y gh && gh auth login"
    exit 1
fi

# Verifica se está autenticado
if ! gh auth status >/dev/null 2>&1; then
    err "gh não está autenticado. Rode: gh auth login"
    exit 1
fi

# Garante que a cópia íntegra do workflow existe
if [[ -f "$WORKFLOW_CORRECT" ]]; then
    if ! cmp -s "$WORKFLOW_CORRECT" "$WORKFLOW_FILE"; then
        warn "Workflow está diferente da cópia íntegra. Restaurando para evitar YAML quebrado..."
        cp "$WORKFLOW_CORRECT" "$WORKFLOW_FILE"
    fi
fi

# ---------- etapa 1: status / commit ----------
log "Verificando estado do git..."
CHANGES=$(git status --porcelain | wc -l)
if [[ "$CHANGES" -gt 0 ]]; then
    log "Encontradas $CHANGES alterações. Fazendo commit: \"$COMMIT_MSG\""
    git add -A
    git commit -m "$COMMIT_MSG"
else
    log "Working tree clean. Nada a commitar."
fi

# ---------- etapa 2: push ----------
log "Enviando para o GitHub (git push)..."
git push

# ---------- etapa 3: esperar o run ser criado ----------
log "Aguardando o workflow ser disparado..."
RUN_ID=""
for i in $(seq 1 15); do
    sleep 3
    # Pegar o run mais recente desta branch
    RID=$(gh run list --branch "$(git rev-parse --abbrev-ref HEAD)" \
        --workflow "flutter-build.yml" --limit 1 \
        --json databaseId,status --jq '.[0].databaseId // empty' 2>/dev/null || true)
    if [[ -n "$RID" ]]; then
        # Verificar se é um run novo (do push que acabamos de fazer)
        RSTATUS=$(gh run view "$RID" --json status --jq '.status' 2>/dev/null || echo "")
        if [[ "$RSTATUS" == "in_progress" || "$RSTATUS" == "queued" || "$RSTATUS" == "requested" || "$RSTATUS" == "waiting" || "$RSTATUS" == "pending" ]]; then
            RUN_ID="$RID"
            break
        fi
        # Se já estiver concluído, pode ser o run que já estava lá; tentar o próximo
        if [[ $i -eq 15 ]]; then
            RUN_ID="$RID"
        fi
    fi
done

if [[ -z "$RUN_ID" ]]; then
    err "Não consegui encontrar o run após o push. Verifique em https://github.com/VouizS/Sonorie/actions"
    exit 1
fi

log "Run encontrado: $RUN_ID"
log "URL: https://github.com/VouizS/Sonorie/actions/runs/$RUN_ID"

# ---------- etapa 4: assistir o build ----------
echo ""
log "Acompanhando o build em tempo real (gh run watch)..."
log "Pressione Ctrl+C para sair (o build continua rodando no GitHub)."
echo ""

# gh run watch retorna !=0 se o run falhar; não queremos que o script morra ali
set +e
gh run watch "$RUN_ID"
WATCH_EXIT=$?
set -e

# ---------- etapa 5: buscar conclusão ----------
echo ""
STATUS=$(gh run view "$RUN_ID" --json conclusion --jq '.conclusion' 2>/dev/null || echo "unknown")

mkdir -p "$OUT_DIR"

if [[ "$STATUS" == "success" ]]; then
    echo -e "${GREEN}╔══════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║   ✅ BUILD SUCESSO!                  ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════╝${NC}"

    log "Baixando APK para $OUT_DIR ..."
    cd "$OUT_DIR"
    # Remove APKs antigos do Sonorie
    rm -f Sonorie-*.apk app-debug.apk
    gh run download "$RUN_ID" --dir "apk-tmp"
    # Encontrar o APK baixado e mover com nome bonito
    APK_FOUND=$(find apk-tmp -name "*.apk" | head -1)
    if [[ -n "$APK_FOUND" ]]; then
        APK_NAME="Sonorie-v0.4.4-r1-debug.apk"
        cp "$APK_FOUND" "$APK_NAME"
        rm -rf apk-tmp
        log "APK salvo em: $OUT_DIR/$APK_NAME"
        echo ""
        log "Para instalar direto pelo Termux (opcional), use:"
        echo "  termux-open $OUT_DIR/$APK_NAME"
        echo ""
        # Tenta abrir a pasta no gerenciador de arquivos
        if command -v termux-open >/dev/null 2>&1; then
            log "Abrindo pasta Download..."
            termux-open "$OUT_DIR" 2>/dev/null || true
        fi
    else
        warn "Download concluído mas não encontrei APK. Verifique a pasta apk-tmp"
    fi
else
    echo -e "${RED}╔══════════════════════════════════════╗${NC}"
    echo -e "${RED}║   ❌ BUILD FALHOU (status=$STATUS)   ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════╝${NC}"

    LOG_FILE="$OUT_DIR/build-error-$(date '+%Y%m%d-%H%M%S').txt"
    log "Baixando logs de falha para $LOG_FILE ..."
    {
        echo "=== Sonorie build failure log ==="
        echo "Run ID: $RUN_ID"
        echo "URL: https://github.com/VouizS/Sonorie/actions/runs/$RUN_ID"
        echo "Data: $(date)"
        echo ""
        echo "=== gh run view ==="
        gh run view "$RUN_ID" 2>&1 || true
        echo ""
        echo "=== Failed step logs (tail) ==="
        gh run view "$RUN_ID" --log-failed 2>&1 || true
    } > "$LOG_FILE"
    log "Log de erro salvo em:"
    echo -e "${CYAN}  $LOG_FILE${NC}"
    echo ""
    log "Últimas 60 linhas do log:"
    echo "------------------------------------------------------------"
    tail -60 "$LOG_FILE"
    echo "------------------------------------------------------------"
    echo ""
    log "Mande este arquivo para debug. Para visualizar:"
    echo "  nano $LOG_FILE"
fi

echo ""
log "Concluído."
