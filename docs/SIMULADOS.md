# O que é simulado no EntreVias

Tudo que o app mostra como se fosse real, mas não é, está aqui, com o ponto exato de troca por produção.
Simulado novo entra neste arquivo **no mesmo commit** que o cria.

## Envio de e-mail (RQ9 recuperar senha, RQ10 confirmar e-mail)

**Estado atual: SIMULADO.** Nenhum e-mail de verdade sai. O servidor monta a mensagem (com o código de 6 dígitos)
e a entrega a um `EnvioSimulado`, que só a guarda na memória (últimas 50, perdidas ao reiniciar).

| Pergunta | Resposta |
|---|---|
| Onde está | `server/.../Verificacao.kt`: interface `EnvioDeEmail`, classe `EnvioSimulado`, objeto `Email` |
| Ponto exato de troca | atribuir um `EnvioDeEmail` real a `Email.envio` (em `Application.kt`, antes de subir o servidor). Nada mais muda: códigos, validade, limites e telas já são de verdade |
| O que já é real | geração do código, hash no banco, validade de 15 min, teto de 5 erros, limites por cliente/e-mail/global, troca de senha, encerramento de sessões, selo "e-mail confirmado" |
| O que é falso | só a entrega: ninguém recebe a mensagem |
| Em produção (`ENV=production`) com o envio simulado | os fluxos ficam **desligados**: 503 em recuperar senha e confirmar e-mail, e o app mostra "Indisponível no momento". Não fingimos que funciona |
| Como ler o código em desenvolvimento | subir o servidor com `CAIXA_DE_SAIDA_DEV=true` e abrir `GET /api/dev/caixa-de-saida`. Fechada por padrão e **nunca** abre em produção, mesmo com a variável |
| Aviso para quem usa | as telas de código mostram "E-mail simulado" enquanto `GET /api/email/estado` disser `simulado: true` |

Cuidado: com `CAIXA_DE_SAIDA_DEV=true` qualquer pessoa que alcance o servidor lê os códigos de todos.
Use só na sua máquina.

## Confirmação de celular (RQ10, parte do celular)

**Não implementada**, nem simulada: exige SMS. A tela de conta confirma só o e-mail.

## Dados de viagens, preços e atrasos

Horários, preços e o atraso típico das conexões vêm de uma base fictícia (`BaseFicticia`, `AtrasoFicticio`) e o app
mostra o selo "Dados demonstrativos". A troca por dados reais depende de acordo com as empresas (fora do código).
