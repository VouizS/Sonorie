#!/data/data/com.termux/files/usr/bin/bash
#
# scripts/fix-workflow.sh
# Corrige o workflow flutter-build.yml no Termux de forma segura
# (reseta para o commit que está no main/arena, re-aplica o bump,
# e adiciona trigger para branches arena/**).
#
set -euo pipefail

YML=".github/workflows/flutter-build.yml"

cd "$HOME/Sonorie"

if [[ ! -f "$YML" ]]; then
    echo "Arquivo $YML não encontrado. Rode este script de dentro de ~/Sonorie"
    exit 1
fi

echo "[fix-workflow] Resetando o workflow para o estado íntegro do commit remoto..."
git checkout "$YML"

echo "[fix-workflow] Aplicando build-name=0.4.4 build-number=50..."
sed -i 's|--build-name=0.4.3 --build-number=49|--build-name=0.4.4 --build-number=50|' "$YML"

echo "[fix-workflow] Renomeando artefato para Sonorie-v0.4.4-r1..."
sed -i 's|Sonorie-v0.4.3-r2-startup-guard-audioservice-flutter-debug-apk|Sonorie-v0.4.4-r1-flutter-debug-apk|' "$YML"

echo "[fix-workflow] Adicionando trigger para branches arena/**..."
# Adiciona "- arena/**" logo abaixo de "- main" dentro de push.branches
python3 - <<'PY'
from pathlib import Path
p = Path(".github/workflows/flutter-build.yml")
s = p.read_text(encoding="utf-8")

old = """  push:
    branches:
      - main
    paths:"""

new = """  push:
    branches:
      - main
      - "arena/**"
    paths:"""

if "- \"arena/**\"" in s:
    print("[fix-workflow] Trigger arena/** já existe, pulando.")
else:
    if old not in s:
        print("[fix-workflow] ERRO: não encontrei a seção push.branches no workflow.")
        raise SystemExit(1)
    s = s.replace(old, new, 1)
    p.write_text(s, encoding="utf-8")
    print("[fix-workflow] Trigger arena/** adicionado.")

# Adiciona bloco pull_request depois de push.paths
pr_block = """  pull_request:
    branches:
      - main
    paths:
      - "sonorie_flutter/**"
      - ".github/workflows/flutter-build.yml"
"""

if "pull_request:" in s:
    print("[fix-workflow] Bloco pull_request já existe, pulando.")
else:
    marker = """    paths:
      - "sonorie_flutter/**"
      - ".github/workflows/flutter-build.yml"
  workflow_dispatch:"""
    if marker not in s:
        print("[fix-workflow] ERRO: não encontrei o marker de workflow_dispatch.")
        raise SystemExit(1)
    s = p.read_text(encoding="utf-8")
    s = s.replace(marker, """    paths:
      - "sonorie_flutter/**"
      - ".github/workflows/flutter-build.yml"
""" + pr_block + "  workflow_dispatch:", 1)
    p.write_text(s, encoding="utf-8")
    print("[fix-workflow] Bloco pull_request adicionado.")
PY

echo ""
echo "[fix-workflow] Verificando resultado..."
grep -n "\-\-build-name=0.4" "$YML" || true
grep -n "Sonorie-v0.4" "$YML" || true
grep -n "arena/\*\*" "$YML" || true
echo ""
echo "[fix-workflow] Status do git:"
git status
echo ""
echo "[fix-workflow] Próximos passos:"
echo "  sgc 'workflow: fix YAML, bump 0.4.4-r1 build 50, arena/** trigger'"
echo "  sgps"
echo "  gh run watch"
