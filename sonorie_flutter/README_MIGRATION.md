# Sonorie Flutter Migration

## v0.4.2-r2

Correção visual isolada do rodapé:

- `Scaffold.extendBody` ativado;
- dock exterior transparente;
- navegação recortada dentro de um `Material` arredondado;
- remoção do retângulo escuro que ligava mini player e navegação;
- remoção do `AnimatedCrossFade` que mantinha espaço invisível;
- mini player real preservado;
- toggle por clique preservado;
- biblioteca, busca, fila, favoritos e player não foram reescritos.

Próxima evolução:

- reprodução persistente em segundo plano;
- notificação de mídia;
- seletor de pasta SAF.

## Build Rescue

- espelhos Maven configurados antes dos repositórios oficiais;
- quatro tentativas reais de compilação;
- limpeza seletiva do cache Kotlin/Gradle após falha;
- espera progressiva entre tentativas;
- artifact exclusivo ligado ao commit correto.
