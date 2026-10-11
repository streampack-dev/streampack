/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.model

/**
 * The OSV.dev ecosystems `cve` names, by the prefixes they're asked for with (`npm:lodash`); Maven
 * needs none. [scheme] is how the ecosystem's versions are ordered, which decides whether the
 * lowest version fixing every CVE can be worked out.
 */
enum class Ecosystem(val osvName: String, val prefixes: List<String>, val scheme: VersionScheme) {
    MAVEN("Maven", emptyList(), VersionScheme.MAVEN),
    NPM("npm", listOf("npm"), VersionScheme.SEMVER),
    GO("Go", listOf("go", "golang"), VersionScheme.SEMVER),
    CRATES("crates.io", listOf("crates", "cargo"), VersionScheme.SEMVER),
    PYPI("PyPI", listOf("pypi", "pip"), VersionScheme.NUMERIC),
    RUBYGEMS("RubyGems", listOf("gem", "rubygems"), VersionScheme.NUMERIC),
    NUGET("NuGet", listOf("nuget"), VersionScheme.NUMERIC),
    PACKAGIST("Packagist", listOf("composer", "packagist"), VersionScheme.NUMERIC),
    HEX("Hex", listOf("hex"), VersionScheme.SEMVER),
    PUB("Pub", listOf("pub"), VersionScheme.SEMVER);

    companion object {
        /** The ecosystem asked for with [prefix], ignoring case, or null for none. */
        fun forPrefix(prefix: String): Ecosystem? = entries.firstOrNull {
            prefix.lowercase() in it.prefixes
        }
    }
}

/**
 * How an ecosystem's versions are ordered: Maven's own rules, semantic versioning, or (for
 * ecosystems whose rules aren't modeled here: PEP 440, RubyGems, NuGet, Composer) only versions
 * that are plain dotted numbers.
 */
enum class VersionScheme {
    MAVEN,
    SEMVER,
    NUMERIC,
}

/** A package in an ecosystem: `group:artifact` for Maven, the package's own name elsewhere. */
data class PackageRef(val ecosystem: Ecosystem, val name: String) {
    /** How the package is named in a reply: as it would be asked for. */
    val display: String
        get() = if (ecosystem == Ecosystem.MAVEN) name else "${ecosystem.prefixes.first()}:$name"

    companion object {
        /**
         * Coordinates as asked for: an ecosystem prefix and a name, or Maven's `group:artifact`.
         * Null for anything else, a bare name included.
         */
        fun parse(coordinates: String): PackageRef? {
            val colon = coordinates.indexOf(':')
            if (colon <= 0 || colon == coordinates.lastIndex) return null
            val head = coordinates.substring(0, colon)
            val rest = coordinates.substring(colon + 1)
            Ecosystem.forPrefix(head)?.let {
                return PackageRef(it, rest)
            }
            // Maven: exactly group:artifact.
            if (':' in rest || !MAVEN_PART.matches(head) || !MAVEN_PART.matches(rest)) return null
            return PackageRef(Ecosystem.MAVEN, coordinates)
        }

        private val MAVEN_PART = Regex("[A-Za-z0-9_.\\-]+")
    }
}
