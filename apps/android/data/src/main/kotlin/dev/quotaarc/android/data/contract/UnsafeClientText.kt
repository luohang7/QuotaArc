package dev.quotaarc.android.data.contract

private val credentialPattern =
    Regex(
        """(?:\b(?:sk|sess)-[A-Za-z0-9_-]{12,}\b|\bBearer\s+[A-Za-z0-9._~-]{12,})""",
        RegexOption.IGNORE_CASE,
    )
private val posixAbsolutePathPattern =
    Regex("""(?:^|[\s("'=:])/(?!/)[^\s/]+(?:/[^\s/]+)*""")
private val posixNetworkPathPattern =
    Regex("""(?:^|[\s("'=])//[^\s/]+/[^\s/]+""")
private val windowsDrivePathPattern =
    Regex("""(?:^|[\s("'=])(?:[A-Za-z]:\\)[^\s\\]+(?:\\[^\s\\]+)*""")
private val windowsUncPathPattern =
    Regex("""(?:^|[\s("'=])\\\\[^\\\s]+\\[^\\\s]+""")
private val homeRelativePathPattern =
    Regex("""(?:^|[\s("'=])~[/\\][^\s/\\]+""")
private val fileUriPattern = Regex("""\bfile://+""", RegexOption.IGNORE_CASE)

/**
 * Defense in depth for the device boundary. The Collector performs the same
 * check, but a malformed or compromised transport must not turn the phone into
 * a path or credential display surface.
 */
fun containsUnsafeClientText(value: String): Boolean =
    credentialPattern.containsMatchIn(value) ||
        posixAbsolutePathPattern.containsMatchIn(value) ||
        posixNetworkPathPattern.containsMatchIn(value) ||
        windowsDrivePathPattern.containsMatchIn(value) ||
        windowsUncPathPattern.containsMatchIn(value) ||
        homeRelativePathPattern.containsMatchIn(value) ||
        fileUriPattern.containsMatchIn(value)
