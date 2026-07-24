import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

object ReleasePublisherValidation {
    private val placeholderValues = setOf(
        "",
        "placeholder",
        "changeme",
        "dummy",
        "company",
        "mycompany",
        "yourdomain",
        "yourcompany",
        "yourorganization",
        "yourapp",
        "myapp",
        "example",
        "fake",
        "foo",
        "foobar",
        "sample",
        "test",
    )
    private val dnsLabel = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?""")
    private val placeholderEmailLocals = setOf("email", "name", "user", "yourname")
    private val rejectedPlayKeyFingerprints = setOf(
        // Repository purchase-signature fixture; never a publisher-owned Play key.
        "25dfafc0eb6ec5ad582c5962dd579f43a0c3aa9e89a056ebc27748c93de44413",
    )

    fun isPlaceholder(value: String): Boolean =
        value.lowercase().filter(Char::isLetterOrDigit) in placeholderValues

    fun isReservedDomain(value: String): Boolean {
        val domain = value.lowercase().trimEnd('.')
        return domain.isBlank() || '.' !in domain ||
            domain.split('.').any(::isPlaceholder) ||
            setOf("example", "invalid", "local", "localhost", "onion", "test").any {
                domain == it || domain.endsWith(".$it")
            } ||
            listOf("example.com", "example.net", "example.org", "home.arpa").any {
                domain == it || domain.endsWith(".$it")
            }
    }

    fun isValidApplicationId(value: String): Boolean =
        value.matches(Regex("""[A-Za-z]\w*(\.[A-Za-z]\w*)+""")) &&
            value != "dev.arsignallocator" &&
            value.split('.').none(::isPlaceholder)

    fun adMobPublisher(value: String, separator: Char): String? {
        val match = Regex("""ca-app-pub-(\d{16})\Q$separator\E(\d{10})""").matchEntire(value)
            ?: return null
        val publisher = match.groupValues[1]
        val placement = match.groupValues[2]
        return publisher.takeUnless {
            it == "3940256099942544" ||
                isObviousNumericId(it) ||
                isObviousNumericId(placement)
        }
    }

    fun isValidPrivacyUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host.orEmpty()
        val privateIpLiteral = isPrivateIpLiteral(host)
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            host.isNotBlank() &&
            privateIpLiteral != true &&
            (privateIpLiteral != null || !isReservedDomain(host))
    }

    fun isValidSupportEmail(value: String): Boolean {
        if (!value.matches(Regex("""[^@\s]+@[^@\s]+"""))) return false
        val (local, domain) = value.split('@', limit = 2)
        return local.length <= 64 &&
            value.length <= 254 &&
            !local.startsWith('.') &&
            !local.endsWith('.') &&
            ".." !in local &&
            domain.length <= 253 &&
            domain.split('.').all(dnsLabel::matches) &&
            !isPlaceholder(local) &&
            local.lowercase().filter(Char::isLetterOrDigit) !in placeholderEmailLocals &&
            !isReservedDomain(domain)
    }

    fun isValidPlayLicenseKey(value: String): Boolean {
        val encoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            ?: return false
        val key = runCatching {
            KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(encoded),
            ) as RSAPublicKey
        }.getOrNull()
        return key != null &&
            key.modulus.bitLength() >= 2048 &&
            key.publicExponent == BigInteger.valueOf(65_537L) &&
            sha256(encoded) !in rejectedPlayKeyFingerprints
    }

    private fun isObviousNumericId(value: String): Boolean {
        if (value.toSet().size == 1 || value.trimStart('0').length <= 3) return true
        return listOf("0123456789", "1234567890", "9876543210", "0987654321").any {
            value in it.repeat(3)
        }
    }

    private fun isPrivateIpLiteral(host: String): Boolean? {
        val literal = host.removePrefix("[").removeSuffix("]")
        val looksLikeIpv4 = literal.all { it.isDigit() || it == '.' }
        val looksLikeIpv6 = ':' in literal
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull()
            ?: return true
        val uniqueLocalIpv6 =
            address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            uniqueLocalIpv6
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {
            "%02x".format(it)
        }
}

val releaseApplicationId = providers.gradleProperty("RELEASE_APPLICATION_ID").orElse("")
val releaseAdMobAppId = providers.gradleProperty("RELEASE_ADMOB_APP_ID").orElse("")
val releaseAdMobBannerId = providers.gradleProperty("RELEASE_ADMOB_BANNER_ID").orElse("")
val releasePrivacyUrl = providers.gradleProperty("RELEASE_PRIVACY_URL").orElse("")
val releaseSupportEmail = providers.gradleProperty("RELEASE_SUPPORT_EMAIL").orElse("")
val releasePlayLicenseKey = providers.gradleProperty("RELEASE_PLAY_LICENSE_KEY").orElse("")
val debugPlayLicenseKey = providers.gradleProperty("DEBUG_PLAY_LICENSE_KEY").orElse("")
val debugTestAdsEnabled = providers.gradleProperty("DEBUG_TEST_ADS_ENABLED")
    .map { it.toBooleanStrictOrNull() == true }
    .orElse(false)

val publisherInputs = mapOf(
    "RELEASE_APPLICATION_ID" to releaseApplicationId,
    "RELEASE_ADMOB_APP_ID" to releaseAdMobAppId,
    "RELEASE_ADMOB_BANNER_ID" to releaseAdMobBannerId,
    "RELEASE_PRIVACY_URL" to releasePrivacyUrl,
    "RELEASE_SUPPORT_EMAIL" to releaseSupportEmail,
    "RELEASE_PLAY_LICENSE_KEY" to releasePlayLicenseKey,
)

val validateReleasePublisherInputs = tasks.register("validateReleasePublisherInputs") {
    group = "verification"
    description = "Validates private publisher values before building a release."
    publisherInputs.forEach(inputs::property)
    doLast {
        val values = inputs.properties.mapValues { it.value.toString() }
        val appPublisher = ReleasePublisherValidation.adMobPublisher(
            values.getValue("RELEASE_ADMOB_APP_ID"),
            '~',
        )
        val bannerPublisher = ReleasePublisherValidation.adMobPublisher(
            values.getValue("RELEASE_ADMOB_BANNER_ID"),
            '/',
        )
        val invalid = buildList {
            if (!ReleasePublisherValidation.isValidApplicationId(
                    values.getValue("RELEASE_APPLICATION_ID"),
                )
            ) {
                add("RELEASE_APPLICATION_ID")
            }
            if (appPublisher == null) {
                add("RELEASE_ADMOB_APP_ID")
            }
            if (bannerPublisher == null) {
                add("RELEASE_ADMOB_BANNER_ID")
            }
            if (!ReleasePublisherValidation.isValidPrivacyUrl(
                    values.getValue("RELEASE_PRIVACY_URL"),
                )
            ) {
                add("RELEASE_PRIVACY_URL")
            }
            if (!ReleasePublisherValidation.isValidSupportEmail(
                    values.getValue("RELEASE_SUPPORT_EMAIL"),
                )
            ) {
                add("RELEASE_SUPPORT_EMAIL")
            }
            if (!ReleasePublisherValidation.isValidPlayLicenseKey(
                    values.getValue("RELEASE_PLAY_LICENSE_KEY"),
                )
            ) {
                add("RELEASE_PLAY_LICENSE_KEY")
            }
        }
        val publisherMismatch =
            appPublisher != null && bannerPublisher != null && appPublisher != bannerPublisher
        if (invalid.isNotEmpty() || publisherMismatch) {
            throw GradleException(
                buildString {
                    if (invalid.isNotEmpty()) {
                        append(
                            "Release builds require valid " +
                                invalid.joinToString { "-P$it" } +
                                " values. ",
                        )
                    }
                    if (publisherMismatch) {
                        append("The AdMob app and banner publisher IDs must match. ")
                    }
                    append(
                        "These checks are structural only: SPEC.md leaves the permanent " +
                            "application ID and Play key fingerprint publisher-owned, so " +
                            "they must be verified in Play Console and an internal-track " +
                            "license-purchase smoke test.",
                    )
                },
            )
        }
        // Structural validation cannot pin publisher-owned values that SPEC.md marks unknown.
        logger.lifecycle(
            "Release publisher inputs are structurally valid; verify the application ID " +
                "and Play key through the Play Console/internal-track purchase smoke test.",
        )
    }
}

tasks.register("testReleasePublisherValidation") {
    group = "verification"
    description = "Tests positive and obvious-placeholder release publisher inputs."
    doLast {
        listOf(
            "com.northstar.devicefinder",
            "io.borealis.finder",
        ).forEach {
            check(ReleasePublisherValidation.isValidApplicationId(it)) {
                "Expected application ID to be accepted: $it"
            }
        }
        listOf(
            "dev.arsignallocator",
            "com.example.app",
            "com.company.app",
            "com.mycompany.finder",
            "com.your-company.finder",
            "test.fake.app",
        ).forEach {
            check(!ReleasePublisherValidation.isValidApplicationId(it)) {
                "Expected application ID to be rejected: $it"
            }
        }

        listOf(
            "https://privacy.northstar.dev/policy",
            "https://legal.borealis.io/privacy",
        ).forEach {
            check(ReleasePublisherValidation.isValidPrivacyUrl(it)) {
                "Expected privacy URL to be accepted: $it"
            }
        }
        listOf(
            "https://example.com/privacy",
            "https://privacy.test/policy",
            "https://your-domain.com/privacy",
            "https://127.0.0.1/privacy",
        ).forEach {
            check(!ReleasePublisherValidation.isValidPrivacyUrl(it)) {
                "Expected privacy URL to be rejected: $it"
            }
        }

        listOf(
            "support@northstar.dev",
            "first.last+tag@support.borealis.co.uk",
            "a@sub-domain.publisher.io",
        ).forEach {
            check(ReleasePublisherValidation.isValidSupportEmail(it)) {
                "Expected support email to be accepted: $it"
            }
        }
        listOf(
            ".support@company.com",
            "support.@company.com",
            "support..team@company.com",
            "support@-company.com",
            "support@company-.com",
            "support@company_name.com",
            "support@company..com",
            "support@company",
            "support@${"a".repeat(64)}.com",
            "dummy@publisher.com",
            "user@northstar.dev",
            "support@company.com",
            "support@example.com",
            "support@your-domain.com",
        ).forEach {
            check(!ReleasePublisherValidation.isValidSupportEmail(it)) {
                "Expected support email to be rejected: $it"
            }
        }

        listOf(
            "ca-app-pub-7319465280417385~5820471936" to '~',
            "ca-app-pub-7319465280417385/5820471936" to '/',
        ).forEach { (value, separator) ->
            check(ReleasePublisherValidation.adMobPublisher(value, separator) != null) {
                "Expected AdMob ID to be accepted: $value"
            }
        }
        listOf(
            "ca-app-pub-3940256099942544~3347511713" to '~',
            "ca-app-pub-1234567890123456~1234567890" to '~',
            "ca-app-pub-1111111111111111/0000000000" to '/',
            "ca-app-pub-0000000000000001/0000000123" to '/',
        ).forEach { (value, separator) ->
            check(ReleasePublisherValidation.adMobPublisher(value, separator) == null) {
                "Expected AdMob ID to be rejected: $value"
            }
        }

        val generatedKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair().public.encoded
        check(
            ReleasePublisherValidation.isValidPlayLicenseKey(
                Base64.getEncoder().encodeToString(generatedKey),
            ),
        ) {
            "Expected a structurally valid generated RSA public key to be accepted"
        }
        val repositoryFixtureKey =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAom6TEH5QNQoqP9JknIr9" +
                "UFgiLEmS7yq/M/ULr6a7DETerRqZcpKBm7Ep4Smp4Mfybh8gD3v5IT9mRZ4syfs" +
                "N7YF4b8iL6bTmFtH+6VQ+cgdGUF1zFNyr8o4SPNBA40je78niJCS11FlvOtRJhu" +
                "j2B15hsxknQMQUSV9ZVwdg/7EmroFlzxHW1e105TSvgezvoQgBfcT9J1hgeWLnX" +
                "q/pMB9a/lAIZDt7qAj2IdbzTsATUq6w4q7xoIIsdwr2jyMMc0ZlbgTSJ6R8Gt3I" +
                "DBP/okHFDfPHgPxpcBEGz7b4s62ERa8Mu0oONn3g8pBtyTXTTsGOxf39d0T9eKC" +
                "tv3BXAwIDAQAB"
        listOf("", "not-base64", repositoryFixtureKey).forEach {
            check(!ReleasePublisherValidation.isValidPlayLicenseKey(it)) {
                "Expected fake or malformed Play key to be rejected"
            }
        }
    }
}

validateReleasePublisherInputs.configure {
    dependsOn("testReleasePublisherValidation")
}

android {
    namespace = "dev.arsignallocator"
    compileSdk = 37

    defaultConfig {
        applicationId = releaseApplicationId.get().ifBlank { "dev.arsignallocator" }
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["adMobApplicationId"] =
                "ca-app-pub-3940256099942544~3347511713"
            buildConfigField(
                "String",
                "ADMOB_APP_ID",
                "ca-app-pub-3940256099942544~3347511713".asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "ADMOB_BANNER_ID",
                "ca-app-pub-3940256099942544/9214589741".asBuildConfigString(),
            )
            buildConfigField("String", "PRIVACY_URL", "\"\"")
            buildConfigField("String", "SUPPORT_EMAIL", "\"\"")
            buildConfigField(
                "String",
                "PLAY_LICENSE_KEY",
                debugPlayLicenseKey.get().asBuildConfigString(),
            )
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField(
                "boolean",
                "DEBUG_TEST_ADS_ENABLED",
                debugTestAdsEnabled.get().toString(),
            )
        }
        release {
            manifestPlaceholders["adMobApplicationId"] = releaseAdMobAppId.get()
            buildConfigField(
                "String",
                "ADMOB_APP_ID",
                releaseAdMobAppId.get().asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "ADMOB_BANNER_ID",
                releaseAdMobBannerId.get().asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "PRIVACY_URL",
                releasePrivacyUrl.get().asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "SUPPORT_EMAIL",
                releaseSupportEmail.get().asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "PLAY_LICENSE_KEY",
                releasePlayLicenseKey.get().asBuildConfigString(),
            )
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("boolean", "DEBUG_TEST_ADS_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleasePublisherInputs)
}

dependencies {
    implementation(project(":localization"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.fragment)
    implementation(libs.coroutines.android)
    implementation(libs.arcore)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.billing)
    implementation(libs.gma.next.gen)
    implementation(libs.ump)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("sharedFixturesDir", rootProject.file("test-data/fixtures").absolutePath)
}
