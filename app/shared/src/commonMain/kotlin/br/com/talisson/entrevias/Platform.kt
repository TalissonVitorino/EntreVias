package br.com.talisson.entrevias

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform