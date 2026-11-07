-- Compra (C1 a C7, D1 a D3). Idempotente: roda em todo início, tanto em banco novo quanto em banco antigo.

CREATE TABLE IF NOT EXISTS cupom (
  codigo      TEXT PRIMARY KEY,
  percentual  INTEGER NOT NULL CHECK (percentual BETWEEN 1 AND 50),
  ativo       INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0,1)),
  valido_ate  INTEGER
);
INSERT OR IGNORE INTO cupom (codigo, percentual) VALUES ('VIAJA10', 10);

CREATE TABLE IF NOT EXISTS pedido (
  id                 INTEGER PRIMARY KEY,
  usuario_id         INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  codigo             TEXT UNIQUE,
  status             TEXT NOT NULL CHECK (status IN ('carrinho','pago','cancelado','expirado')),
  cupom              TEXT,
  subtotal           INTEGER NOT NULL,
  desconto_categoria INTEGER NOT NULL,
  cupom_centavos     INTEGER NOT NULL,
  total              INTEGER NOT NULL,
  criado_em          INTEGER NOT NULL,
  pago_em            INTEGER,
  metodo             TEXT,
  reserva_expira_em  INTEGER,
  cancelado_em       INTEGER,
  reembolso          INTEGER,
  motivo_cancelamento TEXT
);
CREATE INDEX IF NOT EXISTS idx_pedido_usuario ON pedido(usuario_id, criado_em);

CREATE TABLE IF NOT EXISTS pedido_trecho (
  pedido_id  INTEGER NOT NULL REFERENCES pedido(id) ON DELETE CASCADE,
  ordem      INTEGER NOT NULL,
  viagem_id  INTEGER NOT NULL REFERENCES viagem(id),
  preco_unit INTEGER NOT NULL,
  PRIMARY KEY (pedido_id, ordem)
);

CREATE TABLE IF NOT EXISTS pedido_passageiro (
  pedido_id       INTEGER NOT NULL REFERENCES pedido(id) ON DELETE CASCADE,
  ordem           INTEGER NOT NULL,
  nome            TEXT NOT NULL,
  categoria       TEXT NOT NULL,
  idade           INTEGER NOT NULL,
  documento_final TEXT,
  acessibilidade  TEXT,
  PRIMARY KEY (pedido_id, ordem)
);

-- A chave primária (viagem, poltrona) é o que impede vender a mesma poltrona duas vezes, mesmo com dois pedidos ao mesmo tempo.
CREATE TABLE IF NOT EXISTS reserva_assento (
  viagem_id        INTEGER NOT NULL,
  numero           INTEGER NOT NULL CHECK (numero BETWEEN 1 AND 40),
  pedido_id        INTEGER NOT NULL REFERENCES pedido(id) ON DELETE CASCADE,
  passageiro_ordem INTEGER NOT NULL,
  confirmada       INTEGER NOT NULL DEFAULT 0 CHECK (confirmada IN (0,1)),
  expira_em        INTEGER,
  PRIMARY KEY (viagem_id, numero)
);

CREATE TABLE IF NOT EXISTS pagamento (
  id          INTEGER PRIMARY KEY,
  pedido_id   INTEGER NOT NULL REFERENCES pedido(id) ON DELETE CASCADE,
  metodo      TEXT NOT NULL CHECK (metodo IN ('pix','cartao')),
  status      TEXT NOT NULL CHECK (status IN ('pendente','aprovado','recusado','expirado')),
  codigo_pix  TEXT,
  expira_em   INTEGER,
  motivo      TEXT,
  parcelas    INTEGER NOT NULL DEFAULT 1,
  criado_em   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS bilhete (
  codigo     TEXT PRIMARY KEY,
  pedido_id  INTEGER NOT NULL REFERENCES pedido(id) ON DELETE CASCADE,
  trecho     INTEGER NOT NULL,
  passageiro TEXT NOT NULL,
  poltrona   INTEGER,
  plataforma INTEGER NOT NULL
);

INSERT OR IGNORE INTO parametro (chave, valor) VALUES ('taxa_cancelamento_pct', 5);

-- (a tabela 'notificacao' do esquema antigo é de outro desenho e não é usada; os avisos de hoje ficam em 'aviso')
CREATE TABLE IF NOT EXISTS aviso (
  id          INTEGER PRIMARY KEY,
  usuario_id  INTEGER NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
  tipo        TEXT NOT NULL,
  titulo      TEXT NOT NULL,
  texto       TEXT NOT NULL,
  pedido_id   INTEGER,
  criada_em   INTEGER NOT NULL,
  lida        INTEGER NOT NULL DEFAULT 0 CHECK (lida IN (0,1))
);
CREATE INDEX IF NOT EXISTS idx_aviso_usuario ON aviso(usuario_id, criada_em);

-- D4: o que o gestor registrou sobre uma viagem (atraso/horário/cancelamento). Uma linha por viagem: vale o último registro.
CREATE TABLE IF NOT EXISTS viagem_estado (
  viagem_id     INTEGER PRIMARY KEY REFERENCES viagem(id) ON DELETE CASCADE,
  atraso_min    INTEGER NOT NULL DEFAULT 0,
  cancelada     INTEGER NOT NULL DEFAULT 0 CHECK (cancelada IN (0,1)),
  atualizada_em INTEGER NOT NULL
);
