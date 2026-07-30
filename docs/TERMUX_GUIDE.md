# Guia de recuperação do Sonorie no Termux

Como você apagou os dados do Termux, este guia reinstala tudo do zero e recupera
o repositório Sonorie para você voltar a editar e enviar builds ao GitHub Actions.

---

## 1. Instalar o Termux

Baixe a versão mais atual do APK do Termux no F-Droid (o da Play Store está
obsoleto e quebra com frequência):

- https://f-droid.org/packages/com.termux/

Instale também o **Termux API** e o **Termux Widget** se você os usava antes
(ambos no F-Droid).

## 2. Atualizar pacotes base

Abra o Termux e rode:

```bash
pkg update -y && pkg upgrade -y
pkg install -y git openssh gh nano curl wget python gh
```

> Se `gh` (GitHub CLI) não estiver disponível na sua fonte do pkg, instale com
> `pkg install -y gh`; se falhar, use `pkg install -y tur-repo && pkg install gh`.

## 3. (Opcional, mas recomendado) Acesso SSH ao Termux

Se você gosta de editar do PC:

```bash
pkg install -y openssh
passwd             # defina uma senha curta
sshd               # sobe o servidor na porta 8022
```

Descubra o IP do celular com `ifconfig wlan0` e conecte de fora:
`ssh -p 8022 <ip-do-celular` (usuário é `u0_aXXX`, veja com `whoami`).

## 4. Autenticar no GitHub

A. **Com SSH (recomendado para push):**

```bash
# Gerar uma nova chave
ssh-keygen -t ed25519 -C "termux-sonorie" -f ~/.ssh/id_ed25519 -N ""
echo "--- COLE ESSA CHAVE NO GITHUB -> Settings -> SSH and GPG keys ---"
cat ~/.ssh/id_ed25519.pub
```

Copie a linha que começa com `ssh-ed25519 ...`, entre em
https://github.com/settings/keys , clique em **New SSH key**, cole e salve.

B. **Com GitHub CLI (para baixar artefatos e gerenciar issues/PRs):**

```bash
gh auth login
```

Siga os prompts: GitHub.com → HTTPS → Login with a web browser → copie o código
e abra no navegador do celular/PC.

## 5. Clonar o repositório

Se você usa SSH:

```bash
cd ~
git clone git@github.com:VouizS/Sonorie.git
cd Sonorie
git config user.name "Seu nome"
git config user.email "seu@email.com"
```

Se usar HTTPS (sem chave SSH):

```bash
cd ~
git clone https://github.com/VouizS/Sonorie.git
cd Sonorie
```

Você já vai cair na `main`. Para continuar trabalhando na branch da sessão
Arena, puxe o remote com `git fetch origin` depois que os patches desta sessão
forem commitados e fazer push para `main`.

## 6. Atalhos úteis (adicione no `~/.bashrc`)

```bash
cat >> ~/.bashrc <<'EOF'

# Sonorie aliases
alias sonorie='cd ~/Sonorie'
alias sgs='cd ~/Sonorie && git status'
alias sgps='cd ~/Sonorie && git push'
sgpl() { cd ~/Sonorie && git pull --rebase; }
sgc()  { cd ~/Sonorie && git add -A && git commit -m "$*"; }
sgd()  { cd ~/Sonorie && git diff --stat; }
sonorie-build() {
  cd ~/Sonorie
  echo "Push dispara build automaticamente no GitHub Actions."
  echo "Abra: https://github.com/VouizS/Sonorie/actions"
}
EOF
source ~/.bashrc
```

## 6.5 Ajuste pós-clone (bump de versão no workflow)

Por causa de uma limitação de permissão no push automatizado, o workflow
`.github/workflows/flutter-build.yml` voltou com o nome/versão antigos depois do
clone. Depois do primeiro `git clone` você pode rodar:

```bash
cd ~/Sonorie
sed -i 's/--build-name=0.4.3 --build-number=49/--build-name=0.4.4 --build-number=50/' .github/workflows/flutter-build.yml
sed -i 's/name: Sonorie-v0.4.3-r2-startup-guard-audioservice-flutter-debug-apk/name: Sonorie-v0.4.4-r1-flutter-debug-apk/' .github/workflows/flutter-build.yml
sgc "workflow: bump to 0.4.4-r1 build 50"
sgps
```

Isso alinha o artefato da Actions com o código 0.4.4-r1.

## 7. Fluxo diário (editar → commitar → build no Actions)

```bash
sonorie                     # entra na pasta do repo
# ... edite arquivos em sonorie_flutter/lib/ ...
sgc "Descreva a mudança"    # git add + commit
sgps                        # git push
# Abra o Actions: https://github.com/VouizS/Sonorie/actions
# Quando o build terminar, baixe o APK no GitHub (artefato) ou via gh:
gh run list --limit 5
# Baixar o APK do último run concluído:
gh run download latest -D ~/download-apk || echo "Use a aba Actions no navegador"
```

## 8. (Opcional) Flutter local no Termux

Você **não precisa** instalar Flutter no Termux porque o workflow do GitHub
Actions já faz o build do APK. Mas se quiser buildar direto no celular:

```bash
# AVISO: Flutter no Termux é pesado (vários GB) e lento.
# Não é recomendado a menos que você tenha bastante armazenamento.
# Se for, segue os passos do flutter-community/termux-packages.
```

Use o GitHub Actions como seu "servidor de build" — é o mesmo que já estava
fazendo e funciona muito bem.

## 9. Permissões no Android 13+

Depois de instalar o APK, o Sonorie precisa de permissão de Mídia/Áudio e de
Notificações. Se o app escanear e mostrar 0 músicas:

1. Segure o ícone do app → Informações do app → Permissões.
2. Ative **Música e áudio** e **Notificações**.
3. Volte e toque em **Atualizar biblioteca**.
4. Suas músicas em `/Music`, `/Download`, `/Audio` e cartão SD serão
   encontradas.

## 10. Problemas comuns

- **Push pede senha:** é porque você clonou por HTTPS. Rode
  `git remote set-url origin git@github.com:VouizS/Sonorie.git` depois de
  configurar a chave SSH.
- **Build do Actions falha em rede:** é normal — o workflow já faz até 4
  tentativas com espelhos chineses para contornar. Se falhar as 4, rode o
  workflow novamente (Actions → Sonorie Flutter Build → Re-run jobs).
- **APK instala mas não aparece músicas:** confirme se as músicas estão em
  formato mp3/m4a/flac/ogg/wav/opus/aac/3gp dentro de `/Music`, `/Download`,
  `/Audio`, pastas do Snaptube ou do cartão SD.
