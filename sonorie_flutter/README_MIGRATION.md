# Sonorie Flutter Migration

## v0.4.4-r1 — Duplicate fix + Shuffle/Repeat persistentes + Root scan dinâmico

Correções e melhorias sobre a v0.4.3-r2:

- corrigida a duplicação dos métodos `_mediaItemFor`/`_audioSourceFor` no controller
  (a v0.4.3-r2 não compilava por conflito de nomes — bug de merge);
- `shuffle` e `repeat` (off / all / one) foram reativados, agora persistentes no
  SharedPreferences e integrados ao `ConcatenatingAudioSource`/`LoopMode` do
  just_audio;
- controles visuais de Aleatório / Repetir na tela de Player (FilterChips);
- auto-advance correto no fim da faixa: respeita repeat-one, repeat-all (wrap)
  e repeat-off (para na última faixa);
- `playNext`/`playPrevious` com wrap-around em repeat-all e threshold de 3s
  para voltar ao início da faixa atual;
- removidos caminhos hardcoded de SD card (`/storage/3130-6234/...`); o scan
  agora descobre automaticamente qualquer volume sob `/storage`;
- adição das raízes `/Audio`, `/Podcasts`, `/Ringtones`;
- botão do hero da Home mostra "Atualizar biblioteca" quando a permissão já
  foi concedida;
- tela de bootstrap (loading/erro) respeita o tema do sistema (não mais
  forçada em dark);
- logs de debug para diagnóstico de varredura (`Sonorie: varrendo ... pastas`);
- mensagem de biblioteca vazia mais informativa;
- ícone oficial SW, notificação e dock preservados da v0.4.3.

## Histórico

### v0.4.3-r2 — Background Media + Official Icon

- reprodução local real;
- fila real carregada no `just_audio`;
- notificação de mídia com título, artista e controles;
- controles na tela de bloqueio;
- reprodução persistente fora da interface;
- anterior/próxima integrados à fila do sistema;
- pedido real de permissão de notificações;
- ícone oficial SW no APK, instalação, gaveta, recentes e ícone adaptativo;
- dock arredondado e transparente preservado.

## Próxima evolução

v0.4.5: seletor SAF, capas locais reais via MediaStore e recuperação refinada
da fila entre reinícios do app.
