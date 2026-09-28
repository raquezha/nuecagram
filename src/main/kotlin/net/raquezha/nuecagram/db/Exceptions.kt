package net.raquezha.nuecagram.db

class DuplicateInstallationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
