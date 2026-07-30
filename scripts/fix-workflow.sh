#!/data/data/com.termux/files/usr/bin/bash
#
# scripts/fix-workflow.sh
# Substitui o workflow quebrado por uma cópia íntegra já ajustada
# (bump 0.4.4 build 50 + trigger arena/** + pull_request).
#
set -euo pipefail

cd "$HOME/Sonorie"

SRC="scripts/flutter-build.yml.correct"
DST=".github/workflows/flutter-build.yml"

if [[ ! -f "$SRC" ]]; then
    echo "ERRO: $SRC não encontrado. Faça 'git pull' primeiro."
    exit 1
fi

echo "[fix-workflow] Copiando workflow íntegro para $DST ..."
cp "$SRC" "$DST"

echo "[fix-workflow] Verificando:"
grep -n "\-\-build-name=0.4" "$DST" || true
grep -n "Sonorie-v0.4" "$DST" || true
grep -n "arena/\*\*" "$DST" || true
echo ""
echo "[fix-workflow] Status do git:"
git status
echo ""
echo "[fix-workflow] Próximos passos:"
echo "  sgc 'workflow: corrigir YAML, bump 0.4.4-r1 build 50'"
echo "  sgps"
echo "  gh run watch"
