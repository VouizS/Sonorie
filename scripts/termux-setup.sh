#!/data/data/com.termux/files/usr/bin/bash
#
# scripts/termux-setup.sh
# Recuperação completa do ambiente Termux para trabalhar com o Sonorie.
# Uso: baixe este script no Termux e rode: bash termux-setup.sh
#
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[sonorie]${NC} $*"; }
warn()  { echo -e "${YELLOW}[aviso]${NC} $*"; }
err()   { echo -e "${RED}[erro]${NC} $*" >&2; }

if [[ ! -d /data/data/com.termux ]]; then
    err "Este script deve ser rodado DENTRO do Termux."
    exit 1
fi

info "Atualizando pacotes base..."
pkg update -y
pkg upgrade -y
pkg install -y git openssh curl wget python nano gh

info "Configurando git (se não configurado)..."
if ! git config --global user.name >/dev/null 2>&1; then
    read -rp "Nome para commits git: " gname
    git config --global user.name "$gname"
fi
if ! git config --global user.email >/dev/null 2>&1; then
    read -rp "Email para commits git: " gemail
    git config --global user.email "$gemail"
fi

SSH_KEY="$HOME/.ssh/id_ed25519"
if [[ ! -f "$SSH_KEY" ]]; then
    info "Gerando chave SSH ed25519..."
    ssh-keygen -t ed25519 -C "termux-sonorie-$(date +%Y%m%d)" -f "$SSH_KEY" -N ""
fi

REPO_DIR="$HOME/Sonorie"
if [[ -d "$REPO_DIR/.git" ]]; then
    info "Repositório Sonorie já existe em $REPO_DIR. Atualizando..."
    cd "$REPO_DIR"
    git pull --rebase || warn "'git pull' falhou. Verifique o remote/autenticação."
else
    info "Clonando Sonorie..."
    cd "$HOME"
    echo
    echo "Escolha o método de autenticação:"
    echo "  1) SSH  (recomendado - push sem senha)"
    echo "  2) HTTPS (precisa de token/password no push)"
    read -rp "Opção [1]: " authopt
    authopt="${authopt:-1}"
    if [[ "$authopt" == "2" ]]; then
        url="https://github.com/VouizS/Sonorie.git"
    else
        url="git@github.com:VouizS/Sonorie.git"
    fi
    git clone "$url" Sonorie
    cd "$REPO_DIR"
fi

info "Instalando aliases do Sonorie no .bashrc..."
if ! grep -q "# Sonorie aliases" "$HOME/.bashrc" 2>/dev/null; then
    cat >> "$HOME/.bashrc" <<'EOF'

# Sonorie aliases
alias sonorie='cd ~/Sonorie'
alias sgs='cd ~/Sonorie && git status'
alias sgps='cd ~/Sonorie && git push'
alias sblog='cd ~/Sonorie && ls -lt ~/storage/downloads/Sonorie/build-error-*.txt 2>/dev/null | head -1'
sgpl() { cd ~/Sonorie && git pull --rebase; }
sgc()  { cd ~/Sonorie && git add -A && git commit -m "$*"; }
sgd()  { cd ~/Sonorie && git diff --stat; }
sb()   { cd ~/Sonorie && bash scripts/sonorie-build.sh "$*"; }
sblog() {
  local f;
  f=$(ls -t ~/storage/downloads/Sonorie/build-error-*.txt 2>/dev/null | head -1);
  if [[ -n "$f" ]]; then echo "=== $f ==="; tail -80 "$f"; else echo "Sem logs de erro ainda."; fi;
}
EOF
fi

info "Configurando git aliases..."
git config --global alias.st "status -sb"
git config --global alias.lg "log --oneline --graph -20"

cat <<EOF

$(echo -e "${GREEN}===== Sonorie pronto! =====${NC}")

Próximos passos:

1) Se ainda não autenticou a chave SSH no GitHub:
   - Copie a chave abaixo
   - Abra https://github.com/settings/keys
   - New SSH key -> cole -> salve

$(echo -e "${YELLOW}")
cat "$SSH_KEY.pub"
$(echo -e "${NC}")

2) Autenticar GitHub CLI (opcional, para baixar artefatos):
     gh auth login

3) Para começar a trabalhar:
     sonorie
     sgs                 # ver status
     # edite arquivos em sonorie_flutter/lib/
     sgc "minha mudança" # commit
     sgps                # push dispara o build no Actions

4) Acompanhe o build em:
     https://github.com/VouizS/Sonorie/actions

Dica: se quiser buildar no celular, NÃO precisa instalar Flutter.
Deixe o GitHub Actions fazer o build APK e baixe o artefato.
EOF
