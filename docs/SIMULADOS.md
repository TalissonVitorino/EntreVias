# O que é simulado no EntreVias

Tudo que o app mostra como se fosse real, mas não é, está aqui, com o ponto exato de troca por produção.
Simulado novo entra neste arquivo **no mesmo commit** que o cria.

Em produção (`ENV=production`) os dois fluxos simulados que movem dinheiro ou mensagem (**pagamento** e **e-mail**) ficam
**desligados** (503), em vez de fingir que funcionam.

## Pagamento (C3 a C6)

**Estado atual: SIMULADO.** Nenhuma cobrança de verdade.

| Pergunta | Resposta |
|---|---|
| Onde está | `server/.../Compras.kt`: objeto `Psp` e as funções `pagarComPix`, `pagarComCartao`, `simularPixPago` |
| Cartão | aprovado, **exceto o terminado em `0002`**, que é recusado (para testar a recusa). Máximo de 5 tentativas por pedido |
| Pix | gera um código que começa com `EVSIM` (não é um Pix de verdade) e expira em 10 min. A tela mostra o QR desse código (gerador próprio em `core/.../domain/Qr.kt`, conferido contra o `qrcode` do Python e lido pelo OpenCV), mas nenhum banco o reconhece. Quem "paga" é o botão **Simular pagamento do Pix (demonstração)**, que o servidor só aceita fora de produção |
| Número do cartão e CVV | **nunca saem do aparelho**: o app confere (Luhn, validade) e manda só bandeira, 4 últimos dígitos e parcelas. Numa integração real isso vira o token do processador |
| Ponto exato de troca | trocar o corpo de `pagarComPix`/`pagarComCartao` por chamadas ao processador e fazer o aviso do banco (webhook) chamar `aprovar(...)`, no lugar do botão de demonstração. Preço, reserva de poltrona, bilhetes e notificações já são de verdade |
| Em produção com `Psp.simulado = true` | criar pedido e pagar dão 503; o botão de demonstração não existe (404) |
| Estado informado ao app | `GET /api/pagamento/estado` → `{"simulado":true,"disponivel":...}`; as telas de pagamento mostram o aviso "Pagamento simulado" |

## Reembolso (D3)

**SIMULADO.** O cancelamento registra o valor a devolver (total menos a taxa de 5%, ou 100% se a empresa cancelou a viagem) e diz
"em até 72 horas", mas **nenhum dinheiro é devolvido**. A taxa vem do parâmetro `taxa_cancelamento_pct`. Troca por produção: ligar
o estorno do processador em `Compras.cancelar` e `Compras.cancelarPelaEmpresa`.

## Poltronas já ocupadas (C2)

**SIMULADO.** O mapa mostra algumas poltronas como ocupadas por outras pessoas, geradas pelo número da viagem
(`Compras.ocupadasSimuladas`), para o mapa não vir vazio. As reservas **das pessoas que usam o app** são de verdade: a chave
primária `(viagem, poltrona)` impede vender a mesma poltrona duas vezes, e a reserva vale 15 minutos. Troca por produção:
ler a ocupação real do sistema da empresa no lugar de `ocupadasSimuladas`.

## Envio de e-mail (RQ9 recuperar senha, RQ10 confirmar e-mail)

**Estado atual: SIMULADO.** Nenhum e-mail de verdade sai. O servidor monta a mensagem (com o código de 6 dígitos)
e a entrega a um `EnvioSimulado`, que só a guarda na memória (últimas 50, perdidas ao reiniciar).

| Pergunta | Resposta |
|---|---|
| Onde está | `server/.../Verificacao.kt`: interface `EnvioDeEmail`, classe `EnvioSimulado`, objeto `Email` |
| Ponto exato de troca | atribuir um `EnvioDeEmail` real a `Email.envio` (em `Application.kt`, antes de subir o servidor). Nada mais muda: códigos, validade, limites e telas já são de verdade |
| O que já é real | geração do código, hash no banco, validade de 15 min, teto de 5 erros, limites por cliente/e-mail/global, troca de senha, encerramento de sessões, selo "e-mail confirmado" |
| Como ler o código em desenvolvimento | subir o servidor com `CAIXA_DE_SAIDA_DEV=true` e abrir `GET /api/dev/caixa-de-saida`. Fechada por padrão e **nunca** abre em produção |

Cuidado: com `CAIXA_DE_SAIDA_DEV=true` qualquer pessoa que alcance o servidor lê os códigos de todos. Use só na sua máquina.

## Notificações (D4)

**Só dentro do app.** Os avisos (compra, cancelamento, atraso, horário alterado, viagem cancelada, conexão em risco, resposta do
suporte) são gravados e aparecem no sino e na tela Avisos. **Push, e-mail e WhatsApp não estão ligados.**
Quem gera atraso, mudança de horário e cancelamento é o **gestor, no app (Conta → Viagens, ajuda e reclamações) ou pela API** (`POST /api/gestor/viagens/{id}/ocorrencia`):
ainda não há tela para isso.

## Suporte (F1)

**Não é um atendimento ao vivo.** O pedido vira um chamado com protocolo (`AJ-xxxxx`); o gestor lê (`GET /api/gestor/chamados`) e
responde (`POST /api/gestor/chamados/{id}/resposta`), também no app ou pela API, e quem pediu vê a resposta no app e recebe um aviso.
O suporte de verdade é de uma empresa terceira. Troca por produção: integrar a fila de atendimento dela.

## Avaliação e reclamação (F2)

**De verdade** (gravadas, uma por trecho, só depois de chegar). O gestor lê as reclamações por `GET /api/gestor/reclamacoes`.

## Confirmação de celular (RQ10, parte do celular)

**Não implementada**, nem simulada: exige SMS. A tela de conta confirma só o e-mail.

## O que o design pede e **não** foi feito

- **Envio do comprovante por e-mail ou WhatsApp.** O comprovante existe na tela, como texto copiável e como PDF (link temporário de 5 min, aberto no navegador do aparelho; o link só vive na memória do servidor).
- **Remarcação** de viagem (aparece no design do carrinho); só existe o cancelamento.
- **Conexão entre terminais diferentes** (ex.: a baldeação que exige táxi entre terminais): a base fictícia tem um terminal por cidade,
  então toda conexão é no mesmo terminal. A tela mostra a infraestrutura desse terminal e a caminhada até o embarque.
- **Filtro "assento PcD livre"**: só existe o filtro de terminais acessíveis.
- **Alerta de preço** e nomear trajetos salvos (E1).

## Dados de viagens, preços, atrasos, terminais

Horários, preços, o atraso típico das conexões e a infraestrutura dos terminais vêm de uma base fictícia (`BaseFicticia`,
`AtrasoFicticio`, tabela `terminal_infra`) e o app mostra o selo "Dados demonstrativos". A troca por dados reais depende de
acordo com as empresas (fora do código).
