package com.novum.tv.ui.screen.login

/** The three editable parts of a login server, including a reverse-proxy path if present. */
data class LoginServerInput(
    val protocol: String = "https",
    val address: String = "",
    val port: String = "443",
) {
    /** Keep the selected port explicit so the repository cannot choose a different default. */
    val url: String
        get() {
            val target = address.trim()
            if (target.isEmpty()) return ""
            val parts = splitAddress(target)
            val host = if (parts.port == null) parts.host else "${parts.host}:${parts.port}"
            val separator = if (parts.suffix.startsWith('?') || parts.suffix.startsWith('#')) "/" else ""
            return "${protocol.lowercase()}://$host:${port.trim()}$separator${parts.suffix}"
        }

    /** Accept a pasted URL or host:port while keeping ordinary address edits independent of port. */
    fun withAddress(value: String): LoginServerInput {
        val input = value.trim()
        val scheme = SCHEME.find(input)
        // Unsupported schemes remain invalid address input instead of becoming another HTTP host.
        if (scheme == null && input.contains("://")) return copy(address = input)
        val nextProtocol = scheme?.groupValues?.get(1)?.lowercase() ?: protocol
        val target = if (scheme != null) input.substring(scheme.value.length) else input
        val parts = splitAddress(target)
        return copy(
            protocol = nextProtocol,
            address = parts.host + parts.suffix,
            port = parts.port ?: if (scheme != null) defaultPort(nextProtocol) else port,
        )
    }

    companion object {
        fun defaultPort(protocol: String): String = if (protocol.equals("http", ignoreCase = true)) "8080" else "443"

        private val SCHEME = Regex("^(https?)://", RegexOption.IGNORE_CASE)

        private data class AddressParts(val host: String, val suffix: String, val port: String? = null)

        private fun splitAddress(input: String): AddressParts {
            val boundary = input.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .takeIf { it >= 0 } ?: input.length
            val authority = input.substring(0, boundary)
            val suffix = input.substring(boundary)
            // User-info is not a server field; keep it intact so submit can reject it locally.
            if ('@' in authority) return AddressParts(authority, suffix)
            if (authority.startsWith('[')) {
                val close = authority.indexOf(']')
                if (close < 0) return AddressParts(authority, suffix)
                val tail = authority.substring(close + 1)
                return when {
                    tail.isEmpty() -> AddressParts(authority, suffix)
                    tail.startsWith(':') -> AddressParts(authority.substring(0, close + 1), suffix, tail.substring(1))
                    else -> AddressParts(authority, suffix)
                }
            }
            return when (authority.count { it == ':' }) {
                0 -> AddressParts(authority, suffix)
                1 -> AddressParts(authority.substringBefore(':'), suffix, authority.substringAfter(':'))
                // A bare IPv6 literal has no unambiguous port; brackets make the composed URL valid.
                else -> AddressParts("[$authority]", suffix)
            }
        }
    }
}
