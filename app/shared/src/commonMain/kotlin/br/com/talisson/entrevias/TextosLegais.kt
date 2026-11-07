package br.com.talisson.entrevias

enum class Documento(val titulo: String) { TERMOS("Termos de uso"), PRIVACIDADE("Política de privacidade") }

class SecaoLegal(val titulo: String, val paragrafos: List<String>)

/**
 * Textos do cadastro (RQ11). É um RASCUNHO escrito pelo grupo, sem revisão jurídica: o que estiver entre
 * colchetes ainda precisa ser preenchido antes de qualquer uso com pessoas reais.
 */
object TextosLegais {
    const val VERSAO = "Rascunho de 06/11/2026"
    const val AVISO = "Rascunho do grupo, sem revisão jurídica. Os trechos entre [colchetes] precisam ser preenchidos antes de qualquer uso real."

    val termos = listOf(
        SecaoLegal("1. O que é o EntreVias", listOf(
            "O EntreVias é um trabalho acadêmico da disciplina de Engenharia de Requisitos Ágil (PUC Minas) que simula a busca de rotas de ônibus com baldeação.",
            "Empresas, horários e preços são demonstrativos. Não existe compra real, reembolso real nem proteção contra perda de conexão.",
        )),
        SecaoLegal("2. Sua conta", listOf(
            "Informe dados verdadeiros e guarde sua senha: o EntreVias não consegue ver nem recuperar a senha atual.",
            "Você pode corrigir seu nome e celular a qualquer momento na aba Conta. O e-mail é o seu acesso.",
        )),
        SecaoLegal("3. Uso permitido", listOf(
            "Não tente acessar contas de outras pessoas, burlar limites de tentativas nem sobrecarregar o serviço.",
            "Podemos bloquear temporariamente o acesso que pareça abuso.",
        )),
        SecaoLegal("4. Sem garantia", listOf(
            "O serviço pode ficar fora do ar e os dados de demonstração podem ser apagados a qualquer momento.",
        )),
        SecaoLegal("5. Mudanças e contato", listOf(
            "Estes termos podem mudar; a versão em vigor é a que aparece nesta tela.",
            "Dúvidas: [contato do grupo a definir].",
        )),
    )

    val privacidade = listOf(
        SecaoLegal("1. Quem cuida dos seus dados", listOf(
            "Os integrantes do grupo do trabalho: [nomes e contato a definir].",
        )),
        SecaoLegal("2. Quais dados guardamos", listOf(
            "Nome, e-mail e, se você informar, celular.",
            "A senha nunca é guardada: só um código irreversível gerado a partir dela (hash).",
            "Dados técnicos mínimos: a data em que você aceitou estes textos e um código do e-mail usado para limitar tentativas de acesso.",
        )),
        SecaoLegal("3. Para que usamos", listOf(
            "Criar e manter a sua conta, confirmar que é você e evitar abuso. Não usamos para publicidade e não vendemos dados.",
        )),
        SecaoLegal("4. Base legal", listOf(
            "O seu consentimento, dado ao criar a conta (LGPD, art. 7º, I). Você pode retirá-lo a qualquer momento.",
        )),
        SecaoLegal("5. Compartilhamento", listOf(
            "Hoje não compartilhamos seus dados com ninguém. Quando houver pagamento ou empresas parceiras reais, este texto será atualizado antes.",
        )),
        SecaoLegal("6. Quanto tempo guardamos", listOf(
            "Enquanto a sua conta existir. A exclusão da conta pelo próprio app ainda não está disponível: até lá, peça pelo contato [a definir].",
        )),
        SecaoLegal("7. Seus direitos (LGPD, art. 18)", listOf(
            "Confirmar que tratamos seus dados, acessá-los, corrigi-los, pedir a eliminação, a portabilidade e a revogação do consentimento.",
            "Nome e celular você corrige sozinho na aba Conta; os demais pedidos, pelo contato [a definir].",
        )),
        SecaoLegal("8. Segurança e rastreamento", listOf(
            "Senhas com hash e sal, sessão por código aleatório e bloqueio após tentativas erradas.",
            "O EntreVias não usa cookies de publicidade nem rastreia você em outros sites.",
        )),
    )

    fun secoes(d: Documento) = if (d == Documento.TERMOS) termos else privacidade
}
