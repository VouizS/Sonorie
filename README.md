# Sonorie

Sonorie é um reprodutor de música offline moderno para Android, com visual Material 3/Material You, reprodução em segundo plano e notificação de mídia.

## Base atual: Flutter (a partir de v0.4.x)

A aplicação foi migrada de Kotlin/Jetpack Compose para **Flutter**. O código vivo está em **`sonorie_flutter/`**. A pasta `app/` contém a base Kotlin legada (v0.3.x), mantida apenas como referência histórica — ela não recebe mais atualizações.

## Versão atual

**Sonorie v0.4.4-r1** — Duplicate fix + Shuffle/Repeat persistentes + Root scan dinâmico

## Recursos

- Biblioteca offline via varredura real do sistema de arquivos (Music, Download, Audio, Podcasts, SD cards dinâmicos, Snaptube)
- Reprodução com `just_audio` + `just_audio_background`
- Notificação de mídia e lockscreen com MediaSession
- Controles anterior / play-pause / próximo na notificação e lockscreen
- Fila real (toda a lista atual carregada no player)
- Aleatório (shuffle) persistente
- Repetir: desligado / todas / uma
- Favoritos offline (por caminho do arquivo)
- Onboarding de gosto musical (artistas e gêneros)
- Mini-player arredondado no dock inferior com toggle por gesto
- Tema do sistema / claro / escuro roxo oficial
- Build APK via GitHub Actions com Network Rescue para espelhos (funciona mesmo em rede instável do Termux)
- Ícone oficial SW (adaptativo)

## Pacote

`com.swlab.sonorie`

## Build

### GitHub Actions (recomendado)

Push para `main` ou acione **Sonorie Flutter Build** manualmente na aba Actions. O workflow:

1. gera o host Android com `flutter create`;
2. copia ícones oficiais de `sonorie_flutter/tooling/android_icons/`;
3. ajusta `AndroidManifest.xml` (permissões, AudioService, MainActivity);
4. roda `flutter build apk --debug` com até 4 tentativas e espelhos Aliyun/Huawei;
5. publica o APK como artefato.

### Termux (checkout + push)

Fluxo típico: edita no Termux, commita, envia para o GitHub, baixa o APK do Actions. Ver `scripts/termux-setup.sh` (se existir) ou siga o guia em `TERMUX_GUIDE.md` após recuperar o repositório.

## Estrutura

```
Sonorie/
├── app/                            # Kotlin/Compose legado (v0.3.x) — referência
├── sonorie_flutter/
│   ├── lib/main.dart               # App Flutter atual
│   ├── pubspec.yaml
│   ├── README_MIGRATION.md
│   └── tooling/android_icons/      # Ícones oficiais copiados durante o CI
└── .github/workflows/
    ├── flutter-build.yml           # Build atual (Flutter)
    └── build.yml                   # Build legado (Kotlin)
```
