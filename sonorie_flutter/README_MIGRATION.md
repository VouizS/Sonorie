# Sonorie Flutter Migration

Versão visual: 0.4.0-r2
Android build-name: 0.4.0
versionCode: 41

Esta pasta inicia a migração verdadeira do Sonorie para Flutter.

## O que já é real nesta etapa

- APK Flutter real gerado pelo GitHub Actions.
- Shell com Home, Biblioteca, Player e Ajustes.
- Tema claro/escuro/sistema persistido localmente.
- Onboarding local de artistas e gêneros usando SharedPreferences.
- Bottom dock refeito em Flutter com toggle por clique na barrinha.

## O que ainda não foi migrado

- Leitura real das músicas locais.
- Player offline real.
- Notificação de mídia.
- Capa/arte de álbum.
- Imagem real de artista por fonte segura.

Regra do projeto: não usar imagem falsa, desenho ou avatar inventado para artista.
Se não houver imagem real e segura, mostrar apenas nome/metadados.
