const credentialPattern =
  /(?:\b(?:sk|sess)-[A-Za-z0-9_-]{12,}\b|\bBearer\s+[A-Za-z0-9._~-]{12,})/iu;
const posixAbsolutePathPattern =
  /(?:^|[\s("'=:])\/(?!\/)[^\s/]+(?:\/[^\s/]+)*/u;
const posixNetworkPathPattern =
  /(?:^|[\s("'=])\/\/[^\s/]+\/[^\s/]+/u;
const windowsDrivePathPattern =
  /(?:^|[\s("'=])(?:[A-Za-z]:\\)[^\s\\]+(?:\\[^\s\\]+)*/u;
const windowsUncPathPattern =
  /(?:^|[\s("'=])\\\\[^\\\s]+\\[^\\\s]+/u;
const homeRelativePathPattern =
  /(?:^|[\s("'=])~[/\\][^\s/\\]+/u;
const fileUriPattern = /\bfile:\/\/+/iu;

/**
 * Device-facing labels and diagnostics must not carry credentials or local
 * filesystem locations. This detects arbitrary absolute roots rather than
 * maintaining an incomplete list of private directories.
 */
export function containsUnsafeClientText(value: string): boolean {
  return (
    credentialPattern.test(value) ||
    posixAbsolutePathPattern.test(value) ||
    posixNetworkPathPattern.test(value) ||
    windowsDrivePathPattern.test(value) ||
    windowsUncPathPattern.test(value) ||
    homeRelativePathPattern.test(value) ||
    fileUriPattern.test(value)
  );
}
