-- EntreVias: modelo de dados (SQLite / SQLDelight). Dinheiro em centavos; datas em segundos desde 1970 (UTC).
-- Todos os dados de empresas, preços e horários são FICTÍCIOS.

CREATE TABLE cidade (
  id    INTEGER PRIMARY KEY,
  nome  TEXT NOT NULL,
  uf    TEXT NOT NULL CHECK (length(uf) = 2),
  UNIQUE (nome, uf)
);

CREATE TABLE terminal (
  id         INTEGER PRIMARY KEY,
  cidade_id  INTEGER NOT NULL REFERENCES cidade(id),
  nome       TEXT NOT NULL,
  codigo     TEXT NOT NULL UNIQUE            -- cadastro padronizado (premissa da etapa 4)
);

CREATE TABLE empresa (
  id        INTEGER PRIMARY KEY,
  nome      TEXT NOT NULL UNIQUE,
  ativa     INTEGER NOT NULL DEFAULT 1 CHECK (ativa IN (0,1)),
  ficticia  INTEGER NOT NULL DEFAULT 1 CHECK (ficticia IN (0,1)),
  sigla     TEXT,                          -- US7
  cnpj      TEXT                           -- US7: só dígitos
);
CREATE UNIQUE INDEX empresa_cnpj ON empresa(cnpj) WHERE cnpj IS NOT NULL;

CREATE TABLE linha (
  id                  INTEGER PRIMARY KEY,
  empresa_id          INTEGER NOT NULL REFERENCES empresa(id),
  origem_terminal_id  INTEGER NOT NULL REFERENCES terminal(id),
  destino_terminal_id INTEGER NOT NULL REFERENCES terminal(id),
  categoria           TEXT NOT NULL CHECK (categoria IN ('Convencional','Executivo','Semi-leito','Leito')),
  CHECK (origem_terminal_id <> destino_terminal_id),
  UNIQUE (empresa_id, origem_terminal_id, destino_terminal_id, categoria)
);

CREATE TABLE viagem (
  id                   INTEGER PRIMARY KEY,
  linha_id             INTEGER NOT NULL REFERENCES linha(id),
  saida_em             INTEGER NOT NULL,
  chegada_em           INTEGER NOT NULL,
  preco_centavos       INTEGER NOT NULL CHECK (preco_centavos >= 0),
  preco_atualizado_em  INTEGER NOT NULL,       -- RN02 / RNF02
  CHECK (chegada_em > saida_em)
);
CREATE INDEX idx_viagem_busca ON viagem (linha_id, saida_em);

CREATE TABLE preco_historico (                 -- R2 (RF11)
  id              INTEGER PRIMARY KEY,
  viagem_id       INTEGER NOT NULL REFERENCES viagem(id) ON DELETE CASCADE,
  preco_centavos  INTEGER NOT NULL CHECK (preco_centavos >= 0),
  coletado_em     INTEGER NOT NULL
);

CREATE TABLE parametro (                       -- regras ajustáveis sem mudar código (RN01)
  chave  TEXT PRIMARY KEY,
  valor  INTEGER NOT NULL
);

CREATE TABLE usuario (
  id                  INTEGER PRIMARY KEY,
  email               TEXT NOT NULL UNIQUE,
  senha_hash          TEXT NOT NULL,           -- RNF05: nunca a senha em texto
  nome                TEXT,
  plano               TEXT NOT NULL DEFAULT 'gratuito' CHECK (plano IN ('gratuito','premium')),
  aceita_notificacoes INTEGER NOT NULL DEFAULT 0 CHECK (aceita_notificacoes IN (0,1)),   -- RNF06: consentimentos
  aceita_marketing    INTEGER NOT NULL DEFAULT 0 CHECK (aceita_marketing IN (0,1)),      -- separados
  criado_em           INTEGER NOT NULL,
  excluido_em         INTEGER,
  celular             TEXT,                    -- US8/US9
  papel               TEXT NOT NULL DEFAULT 'viajante' CHECK (papel IN ('viajante','gestor')),
  aceitou_termos_em   INTEGER,                 -- RQ11
  email_verificado_em INTEGER                  -- RQ10
);

CREATE TABLE codigo_verificacao (              -- RQ9/RQ10: um código ativo por pessoa e finalidade; só o hash fica
  usuario_id   INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  finalidade   TEXT NOT NULL CHECK (finalidade IN ('email','senha')),
  codigo_hash  TEXT NOT NULL,
  expira_em    INTEGER NOT NULL,
  tentativas   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (usuario_id, finalidade)
);

CREATE TABLE forma_pagamento (                 -- A5: só bandeira, 4 últimos dígitos e validade (LGPD)
  id          INTEGER PRIMARY KEY,
  usuario_id  INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  tipo        TEXT NOT NULL CHECK (tipo IN ('cartao','pix')),
  rotulo      TEXT NOT NULL,
  bandeira    TEXT,
  ultimos4    TEXT,
  validade    TEXT,
  padrao      INTEGER NOT NULL DEFAULT 0 CHECK (padrao IN (0,1)),
  criado_em   INTEGER NOT NULL
);
CREATE INDEX idx_forma_usuario ON forma_pagamento(usuario_id);

CREATE TABLE passageiro (                      -- A6: quem viaja com a pessoa
  id              INTEGER PRIMARY KEY,
  usuario_id      INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  nome            TEXT NOT NULL,
  relacao         TEXT NOT NULL,
  idade           INTEGER NOT NULL,
  documento_final TEXT,
  acessibilidade  TEXT,
  criado_em       INTEGER NOT NULL
);
CREATE INDEX idx_passageiro_usuario ON passageiro(usuario_id);

CREATE TABLE tentativa_login (                 -- RQ12: por hash do e-mail, valendo também para e-mail que não existe
  chave          TEXT PRIMARY KEY,
  falhas         INTEGER NOT NULL DEFAULT 0,
  bloqueado_ate  INTEGER
);

CREATE TABLE sessao (                          -- guardamos só o hash do token, nunca o token
  token_hash  TEXT PRIMARY KEY,
  usuario_id  INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  criada_em   INTEGER NOT NULL,
  expira_em   INTEGER NOT NULL
);

CREATE TABLE assinatura (                      -- R3 (RF12)
  id                INTEGER PRIMARY KEY,
  usuario_id        INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  ciclo             TEXT NOT NULL CHECK (ciclo IN ('mensal','anual')),
  inicio_em         INTEGER NOT NULL,
  fim_periodo_em    INTEGER NOT NULL,          -- RN10: acesso até o fim do período pago
  cancelada_em      INTEGER
);

CREATE TABLE roteiro (
  id                    INTEGER PRIMARY KEY,
  usuario_id            INTEGER REFERENCES usuario(id) ON DELETE CASCADE,   -- NULL = visitante (só no aparelho)
  nome                  TEXT NOT NULL,
  tipo                  TEXT NOT NULL DEFAULT 'ida' CHECK (tipo IN ('ida','ida_volta')),
  orcamento_centavos    INTEGER CHECK (orcamento_centavos IS NULL OR orcamento_centavos >= 0),  -- RF15
  token_compartilhamento TEXT UNIQUE,                                           -- RF16
  criado_em             INTEGER NOT NULL,
  atualizado_em         INTEGER NOT NULL
);

CREATE TABLE parada (
  id          INTEGER PRIMARY KEY,
  roteiro_id  INTEGER NOT NULL REFERENCES roteiro(id) ON DELETE CASCADE,
  ordem       INTEGER NOT NULL CHECK (ordem >= 1),
  cidade_id   INTEGER NOT NULL REFERENCES cidade(id),
  UNIQUE (roteiro_id, ordem)
);

CREATE TABLE roteiro_trecho (
  id          INTEGER PRIMARY KEY,
  roteiro_id  INTEGER NOT NULL REFERENCES roteiro(id) ON DELETE CASCADE,
  sentido     TEXT NOT NULL DEFAULT 'ida' CHECK (sentido IN ('ida','volta')),
  ordem       INTEGER NOT NULL CHECK (ordem >= 1),
  viagem_id   INTEGER NOT NULL REFERENCES viagem(id),
  UNIQUE (roteiro_id, sentido, ordem)
);

CREATE TABLE alerta (                          -- R2 (RF10, RN06)
  id                   INTEGER PRIMARY KEY,
  usuario_id           INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  linha_id             INTEGER NOT NULL REFERENCES linha(id),
  data_inicio          TEXT NOT NULL,
  data_fim             TEXT NOT NULL,
  preco_alvo_centavos  INTEGER NOT NULL CHECK (preco_alvo_centavos > 0),
  ativo                INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0,1)),
  disparado_em         INTEGER,
  criado_em            INTEGER NOT NULL,
  CHECK (data_fim >= data_inicio)
);

CREATE TABLE notificacao (                     -- R2 (RF13, RN11)
  id          INTEGER PRIMARY KEY,
  usuario_id  INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  tipo        TEXT NOT NULL CHECK (tipo IN ('alerta_preco','conexao_apertada','mudanca_trecho','promocional')),
  conteudo    TEXT NOT NULL,
  criada_em   INTEGER NOT NULL,
  lida_em     INTEGER
);

CREATE TABLE clique_compra (                   -- R1 (RF09, RN04): base da comissão
  id                      INTEGER PRIMARY KEY,
  usuario_id              INTEGER NOT NULL REFERENCES usuario(id),
  viagem_id               INTEGER NOT NULL REFERENCES viagem(id),
  preco_exibido_centavos  INTEGER NOT NULL CHECK (preco_exibido_centavos >= 0),
  clicado_em              INTEGER NOT NULL,
  confirmado_empresa_em   INTEGER,
  comissao_centavos       INTEGER CHECK (comissao_centavos IS NULL OR comissao_centavos >= 0)
);

CREATE TABLE anuncio (                         -- R4 (RF14, RNF08)
  id              INTEGER PRIMARY KEY,
  empresa_id      INTEGER NOT NULL REFERENCES empresa(id),
  linha_id        INTEGER REFERENCES linha(id),
  titulo          TEXT NOT NULL,
  vigencia_inicio INTEGER NOT NULL,
  vigencia_fim    INTEGER NOT NULL,
  rotulo          TEXT NOT NULL DEFAULT 'Patrocinado' CHECK (rotulo = 'Patrocinado'),  -- RNF08 no banco
  CHECK (vigencia_fim > vigencia_inicio)
);

-- As tabelas da compra (cupom, pedido, pedido_trecho, pedido_passageiro, reserva_assento, pagamento, bilhete)
-- ficam em migracao-compra.sql, que roda em todo início (idempotente) e serve a banco novo e antigo.
